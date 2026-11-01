package me.sorihiko.dango.music

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.bili.BiliApi
import me.sorihiko.dango.music.bili.BiliEntry
import me.sorihiko.dango.music.bili.BiliImports
import me.sorihiko.dango.music.bili.BiliMedia
import me.sorihiko.dango.music.bili.BiliTrack

/**
 * Bilibili as a music source: every video (or video part) is one song, and the audio is
 * the DASH AAC track the player endpoint hands out. Playback goes through [BiliSource.open]
 * because the built-in mp3 decoder cannot read AAC; collections and favorites are playlists.
 */
object BiliSource : MusicSourceApi, PagedSearch {
    override val source = MusicSource.BILIBILI
    override val libraryWarning: String
        get() = if (BiliApi.loggedIn()) "" else "Log in to see your favorites and private collections."

    override fun search(query: String): List<NeteaseTrack> = searchPage(query, 1)

    override fun searchPage(query: String, page: Int): List<NeteaseTrack> =
        BiliApi.search(query, page).map { hit ->
            NeteaseTrack(
                id = MusicSources.stableId(source, hit.bvid),
                name = hit.title,
                artists = listOf(hit.author).filter { it.isNotEmpty() },
                durationMs = hit.durationMs,
                coverUrl = hit.cover,
                source = source,
                nativeId = hit.bvid,
            )
        }

    /** Bilibili has no mp3 URL; the player reads the AAC track itself. */
    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = null

    override fun lyrics(track: NeteaseTrack): Lyrics? = null

    /** Pasted links come first (they are what the player just added), then the account's favorites. */
    override fun playlists(): List<NeteasePlaylist> {
        val list = ArrayList<NeteasePlaylist>()
        for (item in BiliImports.all()) {
            list += NeteasePlaylist(importedId(item.key), item.name, item.entries.size, item.entries.firstOrNull()?.cover.orEmpty(), false)
        }
        try {
            for (fav in BiliApi.favFolders()) list += NeteasePlaylist(fav.id, fav.title, fav.count, fav.cover, true)
        } catch (e: Exception) {
            DangoLogger.warn("Bilibili favorites failed: {}", e.toString())
        }
        return list
    }

    override fun playlistTracks(id: Long): List<NeteaseTrack> {
        BiliImports.all().firstOrNull { importedId(it.key) == id }?.let { item ->
            return item.entries.map { rowOf(it) }
        }
        val rows = ArrayList<NeteaseTrack>()
        var page = 1
        while (page <= 25) {
            val batch = BiliApi.favEntries(id, page, 20)
            if (batch.isEmpty()) break
            batch.mapTo(rows) { rowOf(it) }
            if (batch.size < 20) break
            page++
        }
        return rows
    }

    /** Numeric playlist id of an import; stable across restarts because it hashes the key. */
    fun importedId(key: String): Long = MusicSources.stableId(source, "import:$key")

    fun importedKey(id: Long): String? = BiliImports.all().firstOrNull { importedId(it.key) == id }?.key

    /** Keeps a pasted link around as a sidebar playlist. */
    fun saveImport(name: String, entries: List<BiliEntry>): Boolean = BiliImports.save(name, entries) != null

    /** Builds a row straight from a list endpoint, so a 200 item import costs one request per page. */
    private fun rowOf(entry: me.sorihiko.dango.music.bili.BiliEntry): NeteaseTrack = NeteaseTrack(
        id = MusicSources.stableId(source, if (entry.cid != 0L) entry.bvid + ":" + entry.cid else entry.bvid),
        name = entry.title.ifEmpty { entry.bvid },
        artists = listOf(entry.author).filter { it.isNotEmpty() },
        durationMs = entry.durationMs,
        coverUrl = entry.cover,
        source = source,
        nativeId = if (entry.cid != 0L) entry.bvid + ":" + entry.cid else entry.bvid,
    )

    /**
     * Turns a pasted link into a titled list. Collections and favourite folders are read
     * page by page; a single video becomes one row.
     */
    fun importLink(text: String): Pair<String, List<NeteaseTrack>>? {
        val parsed = BiliApi.importLink(text) ?: return null
        val rows = parsed.entries.map { rowOf(it) }
        if (rows.isEmpty()) return null
        // Every successful paste becomes a sidebar playlist, so it can be replayed later.
        BiliImports.save(parsed.title, parsed.entries)
        return parsed.title to rows
    }

    /** Rows for the parts of one video, used by the part drawer in the playlist. */
    fun tracksOf(parts: List<me.sorihiko.dango.music.bili.BiliParts.Part>, author: List<String>, cover: String): List<NeteaseTrack> =
        parts.map { part ->
            NeteaseTrack(
                id = MusicSources.stableId(source, part.bvid + ":" + part.cid),
                name = part.title.ifEmpty { part.bvid },
                artists = author,
                durationMs = part.durationMs,
                coverUrl = cover,
                source = source,
                nativeId = part.bvid + ":" + part.cid,
            )
        }

    /** Builds a row from the video detail endpoint (the first part when a video has several). */
    internal fun trackOf(bvid: String): NeteaseTrack? {
        val video = BiliApi.view(bvid) ?: return null
        val page = video.pages.firstOrNull() ?: return null
        return NeteaseTrack(
            id = MusicSources.stableId(source, bvid),
            name = if (video.pages.size > 1) "${video.title} · ${page.title}" else video.title,
            artists = listOf(video.owner).filter { it.isNotEmpty() },
            durationMs = page.durationMs,
            coverUrl = BiliApi.normalizeCover(video.cover),
            source = source,
            nativeId = bvid,
        )
    }

    /**
     * Fetches the media URL, downloads the track and cuts it into AAC frames.
     * Runs on the loader thread; null means "cannot play this one" and the caller shows the reason.
     */
    internal fun open(track: NeteaseTrack): BiliTrack? {
        // A part of a multi-part video carries "bvid:cid"; a plain video leaves the choice to the first page.
        val bvid = track.nativeId.substringBefore(':')
        val wanted = track.nativeId.substringAfter(':', "").toLongOrNull()
        // 重复播放、成员端 5 秒重试都会重新 open：整段 m4s 有 4~10 MB，留最近两首就够
        synchronized(recent) { recent[track.nativeId] }?.let { return it }
        val video = BiliApi.view(bvid) ?: return null
        val page = video.pages.firstOrNull { wanted != null && it.cid == wanted } ?: video.pages.firstOrNull() ?: return null
        val audio = BiliApi.best(BiliApi.audio(bvid, page.cid)) ?: return null
        val urls = listOf(audio.url) + audio.backup
        for (url in urls) {
            val bytes = BiliApi.download(url) ?: continue
            val media = BiliMedia.parse(bytes) ?: continue
            synchronized(recent) {
                recent[track.nativeId] = media
                while (recent.size > RECENT_SIZE) recent.remove(recent.keys.first())
            }
            return media
        }
        return null
    }

    private const val RECENT_SIZE = 2
    private val recent = LinkedHashMap<String, BiliTrack>(4, 0.75f, true)
}
