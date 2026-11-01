package me.sorihiko.dango.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新提醒：看本仓库（yisibuwang/BuWangMusic）的 GitHub Release 里有没有比这个 jar 新的正式版。
 * 配了中转就先用中转的 /update（不受 GitHub 每个 IP 每小时 60 次的限制），不行再直接问 GitHub。
 * 每次启动最多查一次，结果缓存 12 小时（config/dangomusic/update_check.json）；查不到就不提醒，也不报错。
 * 只提醒，不下载、不替换任何文件。
 */
object UpdateChecker {

    /** 一个发布版本：版本号、发布页、各个 jar 的文件名和下载地址 */
    class Release(val version: String, val page: String, val assets: List<Pair<String, String>>)

    /** 有更新时给界面用：新版本号、发布页、适合当前游戏版本的 jar 的下载地址（找不到就是发布页） */
    class Info(val version: String, val page: String, val download: String)

    const val REPO = "yisibuwang/BuWangMusic"
    const val GITHUB_LATEST = "https://api.github.com/repos/yisibuwang/BuWangMusic/releases/latest"
    /** 发布页都长这样；缓存里如果是别的仓库（比如改名前存的上游结果）就直接丢掉 */
    private const val PAGE_PREFIX = "https://github.com/yisibuwang/BuWangMusic"
    private const val CACHE_MS = 12 * 60 * 60_000L
    /** 进游戏后等多久再查（客户端 tick，20 个一秒），别和启动时的加载抢网络 */
    private const val START_DELAY_TICKS = 200
    private const val MAX_BYTES = 1 shl 20

    @Volatile var available: Info? = null
        private set

    private val started = AtomicBoolean(false)
    private var ticks = 0

    /** 这个 jar 的版本号和游戏版本（构建时写进 dangomusic-build.properties），读不到时为空 */
    val currentVersion: String get() = build.first
    val target: String get() = build.second

    /**
     * 可选的中转地址：`config/dangomusic/relay.properties` 里配了 RELAY_URL 就多一条更快的更新检查路径，
     * 没配就直接问 GitHub。BuWang Music 不再需要 Firebase，老配置文件不再读取。
     */
    private val relayUrl: String by lazy {
        try {
            val p = Properties()
            val file = DangoMusic.instance.platform.configDir.resolve("dangomusic").resolve("relay.properties").toFile()
            if (file.isFile) {
                file.inputStream().use { p.load(it) }
            } else {
                UpdateChecker::class.java.getResourceAsStream("/relay.properties")?.use { p.load(it) }
            }
            val raw = p.getProperty("RELAY_URL", "").trim().trimEnd('/')
            // 只接受 https，或者本机调试用的 http
            if (raw.startsWith("https://") || raw.startsWith("http://localhost") || raw.startsWith("http://127.0.0.1")) raw else ""
        } catch (_: Exception) {
            ""
        }
    }

    private val build: Pair<String, String> by lazy {
        try {
            val p = Properties()
            UpdateChecker::class.java.getResourceAsStream("/dangomusic-build.properties")?.use { p.load(it) }
            val v = p.getProperty("version", "").trim()
            val t = p.getProperty("target", "").trim()
            // 没经过构建替换的占位符也当作读不到
            (if (v.startsWith("$")) "" else v) to (if (t.startsWith("$")) "" else t)
        } catch (_: Exception) {
            "" to ""
        }
    }

    private val cacheFile: File? get() = try {
        DangoMusic.instance.clientDir.resolve("update_check.json").toFile()
    } catch (_: Throwable) {
        null
    }

    /** 每个客户端 tick 调一次；设置开着时，启动约 10 秒后在后台查一次 */
    fun tick(enabled: Boolean) {
        if (!enabled || started.get()) return
        if (++ticks < START_DELAY_TICKS) return
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                check()
            } catch (e: Exception) {
                DangoLogger.warn("Update check failed: {}", e.toString())
            }
        }, "Dango-UpdateCheck").apply { isDaemon = true }.start()
    }

    private fun check() {
        val current = currentVersion
        if (current.isEmpty()) return
        val cache = readCache()?.takeIf { it.second.page.startsWith(PAGE_PREFIX) }
        val now = System.currentTimeMillis()
        val release = if (cache != null && now - cache.first < CACHE_MS) {
            cache.second
        } else {
            fetchLatest(relayUrl, GITHUB_LATEST)?.also { writeCache(now, it) } ?: cache?.second
        } ?: return
        available = infoFor(release, current, target)
        available?.let { DangoLogger.info("BuWang Music {} is available (this is {})", it.version, current) }
    }

    /** 有比 [current] 新的正式版就返回提醒信息 */
    @JvmStatic
    fun infoFor(release: Release, current: String, target: String): Info? {
        if (release.version.isEmpty() || compareVersions(release.version, current) <= 0) return null
        // 发布页上的文件名是 BuWangMusic-<版本>+<游戏版本>-<加载器>.jar
        val jar = "BuWangMusic-" + release.version + "+" + target + ".jar"
        val download = release.assets.firstOrNull { it.first == jar }?.second
            ?: release.assets.firstOrNull { it.first.endsWith("+$target.jar") }?.second
            ?: release.page
        return Info(release.version, release.page, download)
    }

    // ---------------------------------------------------------------- 提醒过没有

    /** 灵动岛只提醒一次：这个版本还没提醒过才返回 true */
    fun shouldNotify(): Boolean {
        val info = available ?: return false
        return readNotified() != info.version
    }

    fun markNotified() {
        val info = available ?: return
        try {
            val f = cacheFile ?: return
            val o = readJson(f) ?: JsonObject()
            o.addProperty("notified", info.version)
            f.parentFile.mkdirs()
            f.writeText(o.toString(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun readNotified(): String = try {
        cacheFile?.let { readJson(it) }?.get("notified")?.asString ?: ""
    } catch (_: Exception) {
        ""
    }

    // ---------------------------------------------------------------- 缓存

    private fun readJson(f: File): JsonObject? = if (!f.isFile) null else
        JsonParser().parse(f.readText(StandardCharsets.UTF_8)) as? JsonObject

    private fun readCache(): Pair<Long, Release>? = try {
        val o = cacheFile?.let { readJson(it) }
        val at = o?.get("checkedAt")?.asLong
        val r = (o?.get("release") as? JsonObject)?.let { parse(it) }
        if (at == null || r == null) null else at to r
    } catch (_: Exception) {
        null
    }

    private fun writeCache(at: Long, r: Release) {
        try {
            val f = cacheFile ?: return
            val o = readJson(f) ?: JsonObject()
            o.addProperty("checkedAt", at)
            o.add("release", JsonObject().apply {
                addProperty("version", r.version)
                addProperty("page", r.page)
                add("assets", JsonArray().apply {
                    for ((name, url) in r.assets) add(JsonObject().apply { addProperty("name", name); addProperty("url", url) })
                })
            })
            f.parentFile.mkdirs()
            f.writeText(o.toString(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 先问中转（[relayBase] 为空就跳过），不行再问 GitHub；都不行返回 null */
    @JvmStatic
    fun fetchLatest(relayBase: String, githubUrl: String): Release? {
        if (relayBase.isNotEmpty()) {
            try {
                get("$relayBase/update")?.let { parse(it) }?.let { return it }
            } catch (e: Exception) {
                DangoLogger.warn("Update check via relay failed: {}", e.toString())
            }
        }
        return try {
            get(githubUrl)?.let { parse(it) }
        } catch (e: Exception) {
            DangoLogger.warn("Update check via GitHub failed: {}", e.toString())
            null
        }
    }

    private fun get(url: String): JsonObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "BuWangMusic/" + currentVersion.ifEmpty { "dev" })
            if (c.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("update answer too large")
                }
            }
            return JsonParser().parse(String(out.toByteArray(), StandardCharsets.UTF_8)) as? JsonObject
        } finally {
            c.disconnect()
        }
    }

    /**
     * 认两种格式：中转的精简格式 {version, page, assets: [{name, url}]}，
     * 和 GitHub 原始格式 {tag_name, html_url, assets: [{name, browser_download_url}]}。
     * 链接只认 https://github.com/ 开头的，别的一律丢掉。
     */
    @JvmStatic
    fun parse(o: JsonObject): Release? {
        val github = o.has("tag_name")
        val version = text(o, if (github) "tag_name" else "version").removePrefix("v").trim()
        if (!version.matches(Regex("[0-9]+(\\.[0-9]+)*(-[0-9A-Za-z.]+)?"))) return null
        val page = text(o, if (github) "html_url" else "page").takeIf { safe(it) } ?: return null
        val assets = (o.get("assets") as? JsonArray)?.mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val name = text(a, "name")
            val url = text(a, if (github) "browser_download_url" else "url")
            if (name.endsWith(".jar") && safe(url)) name to url else null
        } ?: emptyList()
        return Release(version, page, assets)
    }

    private fun text(o: JsonObject, key: String): String =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun safe(url: String) = url.startsWith("https://github.com/") && url.none { it.isWhitespace() }

    /**
     * 比较版本号：1.6.0 > 1.6.0-beta.21 > 1.6.0-beta.3 > 1.5.0。
     * 数字部分逐段比；相同时没有后缀的正式版更新；两个都有后缀时按段比较，数字段按数值比。
     */
    @JvmStatic
    fun compareVersions(a: String, b: String): Int {
        val (am, ap) = split(a)
        val (bm, bp) = split(b)
        for (i in 0 until maxOf(am.size, bm.size)) {
            val d = (am.getOrNull(i) ?: 0).compareTo(bm.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        if (ap.isEmpty() || bp.isEmpty()) return bp.size.compareTo(0) - ap.size.compareTo(0)
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val x = ap.getOrNull(i) ?: return -1
            val y = bp.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val d = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (d != 0) return d
        }
        return 0
    }

    private fun split(v: String): Pair<List<Int>, List<String>> {
        val main = v.substringBefore('-')
        val pre = if (v.contains('-')) v.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        return main.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }
}
