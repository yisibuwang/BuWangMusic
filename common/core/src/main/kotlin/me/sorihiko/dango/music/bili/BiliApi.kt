package me.sorihiko.dango.music.bili

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.music.SourceAccessException
import me.sorihiko.dango.music.number
import me.sorihiko.dango.music.text
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** One page of a video; a video with several parts is several pages. */
class BiliPage(val cid: Long, val title: String, val durationMs: Long)

class BiliVideo(
    val bvid: String,
    val title: String,
    val owner: String,
    val cover: String,
    val pages: List<BiliPage>,
    /** Set when the video belongs to a collection (合集); importing it pulls the whole collection. */
    val seasonId: Long = 0L,
    val seasonTitle: String = "",
    val seasonMid: Long = 0L,
)

/** A DASH audio track taken from the player endpoint. */
class BiliAudio(val id: Int, val url: String, val backup: List<String>, val codec: String, val bandwidth: Long)

class BiliFav(val id: Long, val title: String, val count: Int, val cover: String)

/** A row taken from a collection or a favorite folder; no extra detail request needed. */
class BiliEntry(
    val bvid: String,
    val title: String,
    val author: String,
    val cover: String,
    val durationMs: Long,
    /** Non-zero for one part of a multi-part video; 0 means the first part. */
    val cid: Long = 0L,
)

/** Everything a pasted link produced. */
class BiliImport(val title: String, val entries: List<BiliEntry>)

/** One search result; enough to show a row before the video detail is fetched. */
class BiliHit(val bvid: String, val title: String, val author: String, val cover: String, val durationMs: Long)

class BiliSeason(val id: Long, val title: String, val cover: String, val count: Int)

/**
 * The public endpoints the player needs. Everything here is metadata and a short-lived
 * media URL; no request carries a login token unless the player is logged in.
 */
object BiliApi {
    const val REFERER = "https://www.bilibili.com/"
    private const val API = "https://api.bilibili.com"

    private const val MAX_IMPORT_PAGES = 20
    private const val SEASON_LIMIT = 600

    private val mixinTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52,
    )

    @Volatile private var cookie: String = ""
    @Volatile private var sessionLoaded = false
    @Volatile private var wbiKeys: Pair<String, String>? = null
    @Volatile private var wbiAt = 0L
    @Volatile var mid: Long = 0L
        private set

    fun login(cookieText: String) {
        cookie = cookieText.trim()
        wbiKeys = null
        mid = 0L
        saveSession(cookie)
    }

    fun loggedIn(): Boolean {
        ensureSession()
        return cookie.isNotEmpty()
    }

    /** 登录状态存在 config/dangomusic/bilibili_cookie.txt，重启后自动恢复。 */
    private fun ensureSession() {
        if (sessionLoaded) return
        sessionLoaded = true
        val file = sessionFile() ?: return
        try {
            if (file.isFile) cookie = file.readText().trim()
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili session read failed: {}", e.toString())
        }
    }

    private fun saveSession(text: String) {
        val file = sessionFile() ?: return
        try {
            if (text.isEmpty()) file.delete() else {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili session save failed: {}", e.toString())
        }
    }

    fun logout() {
        cookie = ""
        wbiKeys = null
        mid = 0L
        saveSession("")
    }

    private fun sessionFile(): java.io.File? = try {
        me.sorihiko.dango.DangoMusic.instance.clientDir.resolve("bilibili_cookie.txt").toFile()
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- 视频

    fun view(bvid: String): BiliVideo? {
        val json = get("$API/x/web-interface/view?bvid=" + enc(bvid)) ?: return null
        val data = json.obj("data") ?: return null
        val pages = ArrayList<BiliPage>()
        data.arr("pages")?.forEach { element ->
            val page = element.asJsonObject
            pages += BiliPage(page.number("cid"), page.text("part"), page.number("duration") * 1000)
        }
        val title = data.text("title")
        if (title.isEmpty()) return null
        val owner = data.obj("owner")?.text("name").orEmpty()
        val cover = data.text("pic")
        val season = data.obj("ugc_season")
        return BiliVideo(
            bvid, title, owner, cover, pages.ifEmpty {
                listOf(BiliPage(data.number("cid"), title, data.number("duration") * 1000))
            },
            season?.number("id") ?: 0L,
            season?.text("title").orEmpty(),
            season?.number("mid") ?: 0L,
        )
    }

    fun audio(bvid: String, cid: Long): List<BiliAudio> {
        val json = get("$API/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16&fnver=0&fourk=0&platform=pc") ?: return emptyList()
        val dash = json.obj("data")?.obj("dash") ?: return emptyList()
        val list = ArrayList<BiliAudio>()
        dash.arr("audio")?.forEach { element ->
            val item = element.asJsonObject
            val url = item.text("baseUrl").ifEmpty { item.text("base_url") }
            if (url.isEmpty()) return@forEach
            val backups = ArrayList<String>()
            item.arr("backupUrl")?.forEach { backup -> backups += backup.asString }
            item.arr("backup_url")?.forEach { backup -> backups += backup.asString }
            list += BiliAudio(item.number("id").toInt(), url, backups, item.text("codecs"), item.number("bandwidth"))
        }
        return list
    }

    /** Prefer the highest lossless-ish AAC-LC track; HE-AAC is skipped because the decoder cannot follow SBR. */
    fun best(audio: List<BiliAudio>): BiliAudio? =
        audio.filter { it.codec == "mp4a.40.2" }.maxByOrNull { it.bandwidth }
            ?: audio.filter { it.codec != "mp4a.40.5" && it.codec.isNotEmpty() }.maxByOrNull { it.bandwidth }

    /** Accepts a bare BV id, an av number, or any link that contains one (b23.tv short links are followed). */
    fun resolve(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        bvOf(text)?.let { return it }
        avOf(text)?.let { return viewOfAv(it) }
        if (!text.contains("http")) return null
        val target = follow(text) ?: return null
        bvOf(target)?.let { return it }
        avOf(target)?.let { return viewOfAv(it) }
        return null
    }

    private fun viewOfAv(aid: Long): String? {
        val json = get("$API/x/web-interface/view?aid=$aid") ?: return null
        return json.obj("data")?.text("bvid")?.takeIf { it.isNotEmpty() }
    }

    private fun bvOf(text: String): String? = Regex("BV[0-9A-Za-z]{10}").find(text)?.value

    private fun avOf(text: String): Long? = Regex("av(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun follow(url: String): String? {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                if (code in 300..399) current = URL(URL(current), conn.getHeaderField("Location") ?: return null).toString() else return current
            } finally { conn.disconnect() }
        }
        return current
    }

    // ---------------------------------------------------------------- 合集 / 收藏夹

    fun season(id: Long, owner: Long = mid): BiliSeason? {
        val json = get("$API/x/polymer/web-space/seasons_archives_list?mid=$owner&season_id=$id&page_num=1&page_size=1") ?: return null
        val meta = json.obj("data")?.obj("meta") ?: return null
        val title = meta.text("name")
        if (title.isEmpty()) return null
        return BiliSeason(id, title, meta.text("cover"), meta.number("total").toInt())
    }

    fun seasonEntries(id: Long, page: Int, size: Int, owner: Long = mid): List<BiliEntry> {
        val json = get("$API/x/polymer/web-space/seasons_archives_list?mid=$owner&season_id=$id&page_num=$page&page_size=$size") ?: return emptyList()
        return json.obj("data")?.arr("archives")?.mapNotNull { element ->
            val item = element.asJsonObject
            val bvid = item.text("bvid")
            if (bvid.isEmpty()) return@mapNotNull null
            BiliEntry(bvid, clean(item.text("title")), clean(item.text("name")), normalizeCover(item.text("pic")), item.number("duration") * 1000)
        }.orEmpty()
    }

    fun favFolders(): List<BiliFav> {
        val owner = midOrFetch() ?: return emptyList()
        val json = get("$API/x/v3/fav/folder/created/list-all?up_mid=$owner") ?: return emptyList()
        val list = json.obj("data")?.arr("list") ?: return emptyList()
        return list.mapNotNull {
            val item = it.asJsonObject
            val id = item.number("id")
            val title = item.text("title")
            if (id == 0L || title.isEmpty()) null else BiliFav(id, title, item.number("media_count").toInt(), item.text("cover"))
        }
    }

    fun favEntries(id: Long, page: Int, size: Int): List<BiliEntry> {
        val json = get("$API/x/v3/fav/resource/list?media_id=$id&pn=$page&ps=$size&platform=web") ?: return emptyList()
        return json.obj("data")?.arr("medias")?.mapNotNull { element ->
            val item = element.asJsonObject
            val bvid = item.text("bvid")
            // Deleted videos keep a placeholder entry with no bvid.
            if (bvid.isEmpty()) return@mapNotNull null
            BiliEntry(bvid, clean(item.text("title")), clean(item.obj("upper")?.text("name").orEmpty()), normalizeCover(item.text("cover")), item.number("duration") * 1000)
        }.orEmpty()
    }

    /** Reads a whole collection page by page; [owner] is the uploader mid the endpoint needs. */
    private fun collectSeason(id: Long, owner: Long): BiliImport? {
        if (owner <= 0L) return null
        val entries = ArrayList<BiliEntry>()
        var page = 1
        var more = true
        while (more && page <= MAX_IMPORT_PAGES && entries.size < SEASON_LIMIT) {
            val batch = seasonEntries(id, page, 30, owner)
            if (batch.isEmpty()) break
            entries += batch
            more = batch.size >= 30
            page++
        }
        if (entries.isEmpty()) return null
        val meta = season(id, owner)
        // The season endpoint has no uploader field, so take the name from the profile card once.
        val name = cardName(owner)
        val filled = if (name.isEmpty()) entries else entries.map { BiliEntry(it.bvid, it.title, name, it.cover, it.durationMs, it.cid) }
        return BiliImport(meta?.title?.ifEmpty { "Bilibili collection" } ?: "Bilibili collection", filled)
    }

    private fun midOrFetch(): Long? {
        if (mid > 0L) return mid
        val json = get("$API/x/web-interface/nav") ?: return null
        val data = json.obj("data")
        mid = data?.number("mid") ?: 0L
        return mid.takeIf { it > 0L }
    }

    // ---------------------------------------------------------------- 粘贴导入

    /**
     * Turns a pasted link into rows. Handles a single video (BV/av/b23), a collection
     * (collectiondetail?sid=) and a favorite folder (favlist?fid=). Collections and favorite
     * folders are read page by page, capped so one paste cannot pull thousands of videos.
     */
    fun importLink(text: String): BiliImport? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val seasonId = param(raw, "sid")
        if (seasonId != null) {
            val owner = Regex("""space\.bilibili\.com/(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: mid
            collectSeason(seasonId, owner)?.let { return it }
        }
        val favId = param(raw, "fid") ?: param(raw, "media_id")
        if (favId != null) {
            val entries = ArrayList<BiliEntry>()
            var page = 1
            while (page <= MAX_IMPORT_PAGES) {
                val batch = favEntries(favId, page, 20)
                if (batch.isEmpty()) break
                entries += batch
                if (batch.size < 20) break
                page++
            }
            if (entries.isEmpty()) return null
            val owner = Regex("""space\.bilibili\.com/(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val name = if (owner != null) favName(owner, favId) ?: "Bilibili favorites" else "Bilibili favorites"
            return BiliImport(name, entries)
        }
        val bvid = resolve(raw) ?: return null
        val video = view(bvid) ?: return null
        if (video.pages.isEmpty()) return null
        // A multi-part video is imported part by part; a single part inside a collection
        // expands to the whole collection instead, which is what pasting that link means.
        if (video.pages.size == 1 && video.seasonId > 0L) {
            collectSeason(video.seasonId, video.seasonMid)?.let { return it }
        }
        val cover = normalizeCover(video.cover)
        // Multi-part videos (分P) become one row per part, each with its own cid.
        val entries = video.pages.mapIndexed { index, page ->
            val title = if (video.pages.size == 1) video.title
            else video.title + " · P" + (index + 1) + " " + page.title
            BiliEntry(bvid, title, video.owner, cover, page.durationMs, if (video.pages.size == 1) 0L else page.cid)
        }
        return BiliImport(video.title, entries)
    }

    /** "Uploader - folder title" for a favourite folder link; null when the folder is not public. */
    private fun favName(mid: Long, id: Long): String? {
        val owner = cardName(mid)
        val folders = get("$API/x/v3/fav/folder/created/list-all?up_mid=$mid")?.obj("data")?.arr("list") ?: return null
        val title = folders.firstOrNull { it.asJsonObject.number("id") == id }?.asJsonObject?.text("title").orEmpty()
        if (owner.isEmpty() || title.isEmpty()) return null
        return "$owner - $title"
    }

    /** Display name of an uploader, empty when the profile cannot be read. */
    private fun cardName(mid: Long): String =
        get("$API/x/web-interface/card?mid=$mid")?.obj("data")?.obj("card")?.text("name").orEmpty()

    private fun param(url: String, name: String): Long? =
        Regex("""[?&]$name=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

    // ---------------------------------------------------------------- 搜索（WBI 签名）

    /** Why the last search came back empty, so the UI can say something useful instead of nothing. */
    @Volatile var lastSearchError: String = ""

    fun search(keyword: String, page: Int = 1): List<BiliHit> {
        lastSearchError = ""
        val json = searchJson(keyword, page)
        if (json == null) {
            lastSearchError = "搜索请求没有发出去（检查网络）"
            return emptyList()
        }
        var code = json.number("code")
        var answer = json
        if (code != 0L) {
            // 签名被拒（限流 -412 / 风控 -352）时退回未签名接口再试一次
            val plain = plainSearch(keyword, page)
            if (plain != null && plain.number("code") == 0L) {
                answer = plain
                code = 0L
            }
        }
        if (code != 0L) {
            DangoLogger.warn("Bilibili search code {} message {}", code, answer.text("message"))
            lastSearchError = "B 站搜索被拒绝：" + answer.text("message").ifEmpty { "code " + code }
            return emptyList()
        }
        val list = answer.obj("data")?.arr("result") ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element.asJsonObject
            val bvid = item.text("bvid")
            if (bvid.isEmpty()) return@mapNotNull null
            BiliHit(
                bvid = bvid,
                title = clean(item.text("title")),
                author = clean(item.text("author")),
                cover = normalizeCover(item.text("pic")),
                durationMs = clock(item.text("duration")) ?: 0L,
            )
        }
    }

    private fun searchJson(keyword: String, page: Int): JsonObject? {
        val keys = keys()
        if (keys != null) {
            val params = linkedMapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to page.toString(),
                "page_size" to "20",
            )
            get("$API/x/web-interface/wbi/search/type?" + sign(params, keys))?.let { return it }
        }
        return plainSearch(keyword, page)
    }

    /** Unsigned search endpoint, kept as a fallback when the signed one is throttled. */
    private fun plainSearch(keyword: String, page: Int): JsonObject? =
        get("$API/x/web-interface/search/type?search_type=video&keyword=" + enc(keyword) + "&page=" + page)

    /** Search titles carry <em> markers and escaped entities; collection titles come back raw. */
    fun clean(value: String): String = value
        .replace(Regex("<[^>]*>"), "")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    /** Covers come back as "//i0.hdslb.com/…" from search and as full URLs from the list endpoints. */
    fun normalizeCover(value: String): String = when {
        value.isEmpty() -> ""
        value.startsWith("//") -> "https:$value"
        else -> value
    }

    /** Search results give "12:34"; the view endpoint gives seconds. */
    fun clock(value: String): Long? {
        if (value.isEmpty()) return null
        val parts = value.split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toLongOrNull()
            2 -> parts[0].toLongOrNull()?.times(60)?.plus(parts[1].toLongOrNull() ?: 0L)
            3 -> parts[0].toLongOrNull()?.times(3600)?.plus((parts[1].toLongOrNull() ?: 0L) * 60)?.plus(parts[2].toLongOrNull() ?: 0L)
            else -> null
        }
        return seconds?.times(1000)
    }

    private fun keys(): Pair<String, String>? {
        val cached = wbiKeys
        if (cached != null && System.currentTimeMillis() - wbiAt < 6 * 60 * 60_000L) return cached
        val json = get("$API/x/web-interface/nav") ?: return cached
        val data = json.obj("data") ?: return cached
        mid = data.number("mid")
        val image = data.obj("wbi_img")
        val img = image?.text("img_url").orEmpty().substringAfterLast('/')
        val sub = image?.text("sub_url").orEmpty().substringAfterLast('/')
        if (img.isEmpty() || sub.isEmpty()) return cached
        val raw = img.substringBefore('.') + sub.substringBefore('.')
        if (raw.length < 64) return cached
        val key = buildString { for (index in mixinTab) append(raw[index]) }.take(32)
        val pair = key to key
        wbiKeys = pair
        wbiAt = System.currentTimeMillis()
        return pair
    }

    private fun sign(params: Map<String, String>, keys: Pair<String, String>): String {
        val stamp = System.currentTimeMillis() / 1000
        val merged = params + ("wts" to stamp.toString())
        val query = merged.entries.sortedBy { it.key }
            .joinToString("&") { enc(it.key) + "=" + enc(it.value).replace("+", "%20") }
        val rid = md5(query + keys.second)
        return "$query&w_rid=$rid"
    }

    // ---------------------------------------------------------------- HTTP

    fun download(url: String, referer: String = REFERER): ByteArray? = bytes(url, referer)

    fun prepare(audio: BiliAudio): String = audio.url

    private fun get(url: String): JsonObject? {
        val bytes = bytes(url, REFERER) ?: return null
        val text = String(bytes, Charsets.UTF_8).trim()
        if (text.startsWith("<")) throw SourceAccessException("Bilibili needs a login for this item")
        return try {
            JsonParser().parse(text).asJsonObject
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili answer was not JSON: {}", e.toString())
            null
        }
    }

    private fun bytes(url: String, referer: String): ByteArray? {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("Accept", "application/json, */*")
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Origin", "https://www.bilibili.com")
                ensureSession()
                if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
                val code = conn.responseCode
                if (code in 300..399) {
                    current = URL(current, conn.getHeaderField("Location") ?: return null)
                } else {
                    if (code !in 200..299) return null
                    return conn.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            // 音频分片正常 4~10 MB；上限只是防一个畸形响应把堆吃满
                            if (output.size() > MAX_BYTES) {
                                DangoLogger.warn("Bilibili response over {} bytes, dropped", MAX_BYTES)
                                return null
                            }
                        }
                        output.toByteArray()
                    }
                }
            } finally { conn.disconnect() }
        }
        return null
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private const val MAX_BYTES = 64 * 1024 * 1024
}
