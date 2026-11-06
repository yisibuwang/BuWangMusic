package me.sorihiko.dango.music

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 听歌周报的本地统计：只写 config/dangomusic/weekly_report.json，不联网、不上传。
 * 按自然周（周一 00:00 起）累计；跨周时保留上一周的时长/曲数用于对比。
 */
object ListeningStats {

    class TrackStat(
        var title: String = "",
        var artist: String = "",
        var source: String = "",
        var listenedMs: Long = 0L,
    )

    data class Snapshot(
        val weekStart: LocalDate,
        val totalMs: Long,
        val plays: Int,
        val artistCount: Int,
        val topArtists: List<Pair<String, Long>>,
        val topSongs: List<Triple<String, String, Long>>,
        val dailyMs: LongArray,
        val prevWeekMs: Long,
        val prevWeekPlays: Int,
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private val tracks = LinkedHashMap<String, TrackStat>()
    private val dailyMs = LongArray(7)
    private var weekStartEpochDay = -1L
    private var totalMs = 0L
    private var plays = 0
    private var prevWeekMs = 0L
    private var prevWeekPlays = 0
    private var lastNanos = 0L
    private var lastKey = ""
    private var loaded = false
    private var lastSaveNanos = 0L

    /** 每个 tick 调一次：只在真正播放时累计，单次最多算 250ms，避免卡顿/挂起后一次补一大段 */
    fun tick() {
        val now = System.nanoTime()
        synchronized(lock) {
            ensureLoaded()
            val dt = if (lastNanos == 0L) 0L else ((now - lastNanos) / 1_000_000L).coerceIn(0L, 250L)
            lastNanos = now
            if (MusicPlayer.state != MusicPlayer.State.PLAYING) {
                if (MusicPlayer.current == null) lastKey = ""
                return
            }
            val track = MusicPlayer.current ?: return
            val today = LocalDate.now()
            rollover(today)
            if (dt <= 0L) return
            totalMs += dt
            dailyMs[today.dayOfWeek.value - 1] += dt
            val stat = tracks.getOrPut(track.key) { TrackStat() }
            stat.title = track.name
            stat.artist = track.artists.joinToString(" / ")
            stat.source = track.source.key
            stat.listenedMs += dt
            if (track.key != lastKey) {
                lastKey = track.key
                plays++
            }
            if (now - lastSaveNanos >= 30_000_000_000L) saveLocked()
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        ensureLoaded()
        rollover(LocalDate.now())
        val topArtists = tracks.values.filter { it.artist.isNotEmpty() }
            .groupBy { it.artist }
            .mapValues { e -> e.value.sumOf { it.listenedMs } }
            .entries.sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }
        val topSongs = tracks.values.sortedByDescending { it.listenedMs }
            .take(5)
            .map { Triple(it.title, it.artist, it.listenedMs) }
        Snapshot(
            weekStart = LocalDate.ofEpochDay(weekStartEpochDay),
            totalMs = totalMs,
            plays = plays,
            artistCount = tracks.values.map { it.artist }.filter { it.isNotEmpty() }.toSet().size,
            topArtists = topArtists,
            topSongs = topSongs,
            dailyMs = dailyMs.copyOf(),
            prevWeekMs = prevWeekMs,
            prevWeekPlays = prevWeekPlays,
        )
    }

    private fun rollover(today: LocalDate) {
        val start = weekStart(today)
        if (weekStartEpochDay < 0L) {
            weekStartEpochDay = start.toEpochDay()
            return
        }
        if (start.toEpochDay() == weekStartEpochDay) return
        prevWeekMs = totalMs
        prevWeekPlays = plays
        weekStartEpochDay = start.toEpochDay()
        totalMs = 0L
        plays = 0
        dailyMs.fill(0L)
        tracks.clear()
        lastKey = ""
        saveLocked()
    }

    private fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = file() ?: return
        if (!Files.exists(file)) {
            weekStartEpochDay = weekStart(LocalDate.now()).toEpochDay()
            return
        }
        try {
            val root = JsonParser().parse(String(Files.readAllBytes(file), StandardCharsets.UTF_8)).asJsonObject
            weekStartEpochDay = root.get("weekStart")?.asLong ?: weekStart(LocalDate.now()).toEpochDay()
            totalMs = root.get("totalMs")?.asLong ?: 0L
            plays = root.get("plays")?.asInt ?: 0
            prevWeekMs = root.get("prevWeekMs")?.asLong ?: 0L
            prevWeekPlays = root.get("prevWeekPlays")?.asInt ?: 0
            root.getAsJsonArray("dailyMs")?.let { arr ->
                for (i in 0 until minOf(7, arr.size())) dailyMs[i] = arr[i].asLong
            }
            root.getAsJsonObject("tracks")?.entrySet()?.forEach { (key, value) ->
                val obj = value.asJsonObject
                tracks[key] = TrackStat(
                    title = obj.get("title")?.asString.orEmpty(),
                    artist = obj.get("artist")?.asString.orEmpty(),
                    source = obj.get("source")?.asString.orEmpty(),
                    listenedMs = obj.get("listenedMs")?.asLong ?: 0L,
                )
            }
            rollover(LocalDate.now())
        } catch (e: Exception) {
            DangoLogger.warn("Weekly report load failed: {}", e.toString())
            weekStartEpochDay = weekStart(LocalDate.now()).toEpochDay()
        }
    }

    private fun saveLocked() {
        lastSaveNanos = System.nanoTime()
        val file = file() ?: return
        try {
            val root = JsonObject()
            root.addProperty("weekStart", weekStartEpochDay)
            root.addProperty("totalMs", totalMs)
            root.addProperty("plays", plays)
            root.addProperty("prevWeekMs", prevWeekMs)
            root.addProperty("prevWeekPlays", prevWeekPlays)
            root.add("dailyMs", gson.toJsonTree(dailyMs))
            val list = JsonObject()
            for ((key, stat) in tracks) {
                val obj = JsonObject()
                obj.addProperty("title", stat.title)
                obj.addProperty("artist", stat.artist)
                obj.addProperty("source", stat.source)
                obj.addProperty("listenedMs", stat.listenedMs)
                list.add(key, obj)
            }
            root.add("tracks", list)
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(tmp, gson.toJson(root).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            DangoLogger.warn("Weekly report save failed: {}", e.toString())
        }
    }

    private fun file() = try {
        DangoMusic.instance.clientDir.resolve("weekly_report.json")
    } catch (_: Throwable) {
        null
    }
}
