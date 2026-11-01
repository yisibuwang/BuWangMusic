package me.sorihiko.dango.music.bili

import me.sorihiko.dango.DangoLogger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parts of a multi-part video, cached per bvid.
 *
 * The list endpoints used for collections and favourites do not carry part information, so the
 * detail endpoint is read lazily: rows that become visible ask for their parts in the background,
 * which keeps the cost at a couple of tiny requests instead of one per imported song.
 */
object BiliParts {
    /** One part of a video; [cid] is what the audio endpoint needs. */
    class Part(val bvid: String, val title: String, val cid: Long, val durationMs: Long)

    private const val PARALLEL = 3

    private val cache = HashMap<String, List<Part>>()
    private val pending = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private const val RETRY_MS = 60_000L
    private val worker = Executors.newFixedThreadPool(PARALLEL) { runnable ->
        Thread(runnable, "Dango-BiliParts").apply { isDaemon = true }
    }
    private val waiting = AtomicInteger()

    /** Cached parts, or null when this video was never looked at. A single-part video maps to an empty list. */
    @Synchronized
    fun peek(bvid: String): List<Part>? = cache[bvid]

    /** Asks for the parts of a video once; safe to call every frame. */
    fun request(bvid: String) {
        if (bvid.isEmpty()) return
        synchronized(this) {
            if (cache.containsKey(bvid) || !pending.add(bvid)) return
            val last = failed[bvid]
            if (last != null && System.currentTimeMillis() - last < RETRY_MS) {
                pending.remove(bvid)
                return
            }
        }
        if (waiting.incrementAndGet() > PARALLEL * 2) {
            waiting.decrementAndGet()
            synchronized(this) { pending.remove(bvid) }
            return
        }
        worker.execute {
            val parts = try { read(bvid) } catch (e: Exception) {
                DangoLogger.warn("Bilibili parts {} failed: {}", bvid, e.toString())
                null
            }
            synchronized(this) {
                pending.remove(bvid)
                if (parts != null) {
                    cache[bvid] = parts
                    failed.remove(bvid)
                } else {
                    failed[bvid] = System.currentTimeMillis()
                }
            }
            waiting.decrementAndGet()
        }
    }

    private fun read(bvid: String): List<Part>? {
        val video = BiliApi.view(bvid) ?: return null
        if (video.pages.size <= 1) return emptyList()
        return video.pages.map { page ->
            Part(bvid, page.title, page.cid, page.durationMs)
        }
    }
}
