package me.sorihiko.dango.music.bili

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.music.NeteaseLogin
import me.sorihiko.dango.music.number
import me.sorihiko.dango.music.text
import java.awt.image.BufferedImage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 二维码登录（passport.bilibili.com）：生成二维码 → 轮询状态 → 成功时从回调链接里取
 * SESSDATA / bili_jct / DedeUserID 三个 cookie。cookie 交给 [BiliApi.login] 保存。
 */
object BiliLogin {
    @Volatile var state = NeteaseLogin.State.IDLE
        private set
    @Volatile var qrImage: BufferedImage? = null
        private set
    @Volatile var qrVersion = 0
        private set
    @Volatile var message = ""
        private set

    private val lock = Any()
    private var session = 0

    fun start() {
        val current = synchronized(lock) {
            ++session
            state = NeteaseLogin.State.LOADING
            qrImage = null
            message = ""
            session
        }
        Thread({ run(current) }, "Dango-BiliLogin").apply { isDaemon = true }.start()
    }

    fun cancel() = synchronized(lock) {
        ++session
        state = NeteaseLogin.State.IDLE
        qrImage = null
    }

    private fun update(current: Int, apply: () -> Unit) = synchronized(lock) {
        if (current == session && state != NeteaseLogin.State.SUCCESS) apply()
    }

    private fun run(current: Int) {
        try {
            val generated = request("https://passport.bilibili.com/x/passport-login/web/qrcode/generate") ?: run {
                fail(current, "无法获取二维码")
                return
            }
            val url = generated.text("url")
            val key = generated.text("qrcode_key")
            if (url.isEmpty() || key.isEmpty()) {
                fail(current, "无法获取二维码")
                return
            }
            val image = NeteaseLogin.makeQr(url, 200)
            update(current) {
                qrImage = image
                qrVersion++
                state = NeteaseLogin.State.WAITING
            }
            val deadline = System.currentTimeMillis() + 180_000L
            while (System.currentTimeMillis() < deadline && synchronized(lock) { current == session }) {
                Thread.sleep(2_000L)
                val reply = poll(key)
                val data = reply.data ?: continue
                when (data.number("code").toInt()) {
                    0 -> {
                        // 成功：优先从回调链接里取，取不到再用这一次响应下发的 Set-Cookie
                        var cookie = cookieOf(data.text("url"))
                        if (cookie.isEmpty()) cookie = cookieOf(reply.cookies.joinToString("&"))
                        if (cookie.isEmpty()) {
                            DangoLogger.warn("Bilibili login: no cookie in answer {}", reply.raw.take(400))
                            fail(current, "登录成功但没拿到 cookie")
                        } else {
                            BiliApi.login(cookie)
                            update(current) { state = NeteaseLogin.State.SUCCESS }
                        }
                        return
                    }
                    86090 -> update(current) { state = NeteaseLogin.State.SCANNED }
                    86038 -> {
                        update(current) { state = NeteaseLogin.State.EXPIRED }
                        return
                    }
                    86101 -> Unit   // 还没扫码，继续等
                    else -> DangoLogger.warn("Bilibili login poll code {} message {}", data.number("code"), data.text("message"))
                }
            }
            update(current) { if (state == NeteaseLogin.State.WAITING) state = NeteaseLogin.State.EXPIRED }
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili login failed: {}", e.toString())
            fail(current, e.message ?: "登录失败")
        }
    }

    private fun fail(current: Int, why: String) = update(current) {
        message = why
        state = NeteaseLogin.State.ERROR
    }

    /** 回调链接形如 https://passport.bilibili.com/…?DedeUserID=…&SESSDATA=…&bili_jct=… */
    private fun cookieOf(target: String): String {
        val query = if (target.contains('?')) target.substringAfter('?', "") else target
        if (query.isEmpty()) return ""
        val wanted = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
        val parts = ArrayList<String>()
        for (pair in query.split('&')) {
            val name = pair.substringBefore('=')
            if (name !in wanted) continue
            val value = java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            if (value.isEmpty()) continue
            parts += "$name=$value"
        }
        return parts.joinToString("; ")
    }

    private class Reply(val data: JsonObject?, val cookies: List<String>, val raw: String)

    /** 轮询二维码状态；除了 data 还带回 Set-Cookie，某些账号只在响应头里给 cookie。 */
    private fun poll(key: String): Reply {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + enc(key) + "&source=main-fe-header"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DangoMusic")
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            val cookies = ArrayList<String>()
            conn.headerFields?.forEach { (name, values) ->
                if (name.equals("Set-Cookie", ignoreCase = true)) {
                    for (value in values) cookies += value.substringBefore(';')
                }
            }
            if (conn.responseCode != 200) return Reply(null, cookies, "")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = com.google.gson.JsonParser().parse(text).asJsonObject
            if (root.get("code")?.asInt != 0) Reply(null, cookies, text) else Reply(root.getAsJsonObject("data"), cookies, text)
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili login poll failed: {}", e.toString())
            Reply(null, emptyList(), "")
        } finally {
            conn.disconnect()
        }
    }

    /** 调一次 passport 接口，取出 data 对象；失败返回 null。 */
    private fun request(url: String): JsonObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DangoMusic")
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = com.google.gson.JsonParser().parse(text).asJsonObject
            if (root.get("code")?.asInt != 0) return null
            root.getAsJsonObject("data")
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(text: String): String = try {
        URLEncoder.encode(text, "UTF-8")
    } catch (e: Exception) {
        text
    }
}
