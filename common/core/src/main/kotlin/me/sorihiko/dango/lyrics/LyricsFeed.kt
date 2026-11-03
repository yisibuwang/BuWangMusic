package me.sorihiko.dango.lyrics

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.NeteaseTrack

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 歌词取用中心：一份缓存、一条下载队列。
 *
 * 3D 歌词和各个 HUD 都走这里，所以哪怕 3D 歌词模块没开，HUD 也能拿到歌词；
 * 同一首歌只请求一次，取不到（纯音乐、版权限制）也记下来，不会反复打接口。
 * 下载在后台线程，其余方法都可以在渲染线程直接调。
 */
object LyricsFeed {

    /** 最后一句没有下一句时，显示多久 */
    private const val TAIL_MS = 5_000L

    /** 两句之间隔得久（间奏）时，上一句最多再多挂这么久 */
    private const val HOLD_MS = 8_000L

    /** 最多留多少首歌的歌词，长队列连着放也不会一直涨 */
    private const val MAX_TRACKS = 48

    /** 一句歌词：正文 + 翻译（没有翻译时为空串） */
    data class Line(val text: String, val translation: String)

    private object None

    private val cache = ConcurrentHashMap<String, Any>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** 插入顺序，超上限时从最早的那首开始丢 */
    private val order = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val fetcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dango-Lyrics").apply { isDaemon = true }
    }

    /** 已经拿到的歌词；没请求过、或者这首歌确实没有，都返回 null */
    fun peek(key: String): Lyrics? = cache[key] as? Lyrics

    /** 拿歌词；没有就顺手请求一次，这次先返回 null */
    fun of(track: NeteaseTrack): Lyrics? {
        val key = track.key
        val hit = cache[key]
        if (hit == null) {
            request(key, track)
            return null
        }
        return hit as? Lyrics
    }

    /** 跟着当前播放预取，界面开不开都会跑（模块每 tick 调一次） */
    fun prefetch() {
        if (!MusicPlayer.active) return
        val track = MusicPlayer.current ?: return
        val key = track.key
        if (cache.containsKey(key) || pending.contains(key)) return
        request(key, track)
    }

    /**
     * 当前该显示的一句；null 表示没在放歌、没有歌词，或者这句已经该收起来了。
     *
     * 时间规则：下一句开始就换；最后一句按 [TAIL_MS] 算；间奏太长时最多挂到
     * 开始后 [HOLD_MS]，免得屏幕上一直停着上一句。暂停时保持原样（调用方自己决定要不要画）。
     */
    fun line(positionMs: Long = MusicPlayer.positionMs): String? = current(positionMs)?.text

    /** 当前这一句的正文和翻译；没有翻译时 [Line.translation] 是空串 */
    fun current(positionMs: Long = MusicPlayer.positionMs): Line? {
        if (!MusicPlayer.active) return null
        val track = MusicPlayer.current ?: return null
        val lyrics = of(track) ?: return null
        val index = lyrics.indexAt(positionMs)
        if (index < 0) return null
        val line = lyrics.lines[index]
        val next = lyrics.lines.getOrNull(index + 1)?.timeMs
        val until = next?.let { minOf(it, line.timeMs + HOLD_MS) } ?: (line.timeMs + TAIL_MS)
        if (positionMs >= until) return null
        val text = line.text.trim()
        if (text.isEmpty()) return null
        return Line(text, line.translation?.trim().orEmpty())
    }

    private fun request(key: String, track: NeteaseTrack) {
        if (!pending.add(key)) return
        fetcher.execute {
            try {
                cache[key] = MusicSources.api(track.source).lyrics(track) ?: None
            } catch (e: Exception) {
                DangoLogger.warn("Lyrics request failed for {}: {}", track.name, e.toString())
                cache[key] = None
            } finally {
                order.add(key)
                while (cache.size > MAX_TRACKS) {
                    val old = order.poll() ?: break
                    if (old != key) cache.remove(old)
                }
                pending.remove(key)
            }
        }
    }
}
