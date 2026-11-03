package me.sorihiko.dango.music

import me.sorihiko.dango.utils.Color

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 当前封面主色：强调色（播放栏进度、选中块、HUD 点缀）跟着封面走。
 *
 * 只取"鲜艳又有亮度"的那一簇，再压低饱和度上限、抬高亮度下限，
 * 这样深色和浅色主题下都能当高亮色用，不会被一块灰白封面带得看不见。
 */
object AccentTint {

    private const val BUCKETS = 24
    private const val WORKERS = 2
    /** 最多记多少首歌的主色，滚回去重听时不用再算 */
    private const val MAX = 48

    private val worker = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread(r, "Dango-AccentTint").apply { isDaemon = true }
    }

    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cache = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>) = size > MAX
    }

    @Volatile
    private var lastKey = ""

    @Volatile
    private var latest: Color? = null

    /** 正在播的这首歌的主色；还没算出来时返回 null（调用方继续用预设强调色） */
    fun track(track: NeteaseTrack?): Color? {
        if (track == null || track.coverUrl.isEmpty()) return null
        val key = track.key
        val hit = synchronized(cache) { cache[key] }
        if (hit != null) {
            lastKey = key
            latest = hit
            return hit
        }
        if (key != lastKey) {
            lastKey = key
            latest = null
        }
        if (!requested.add(key)) return latest
        val url = track.coverUrl
        worker.execute {
            try {
                val color = runCatching { measure(url) }.getOrNull()
                if (color != null) {
                    synchronized(cache) { cache[key] = color }
                    if (lastKey == key) latest = color
                }
            } finally {
                // 先落缓存再摘标记，否则这中间再来一次请求会重复下载同一张封面
                requested.remove(key)
            }
        }
        return latest
    }

    private fun measure(url: String): Color? {
        val bytes = NeteaseApi.request(url, "image/jpeg,image/png,image/*;q=0.8") ?: return null
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null

        val weight = FloatArray(BUCKETS)
        val red = FloatArray(BUCKETS)
        val green = FloatArray(BUCKETS)
        val blue = FloatArray(BUCKETS)
        val hsb = FloatArray(3)
        val step = max(1, min(w, h) / 48)

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24) < 128) {
                    x += step
                    continue
                }
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                java.awt.Color.RGBtoHSB(r, g, b, hsb)
                val saturation = hsb[1]
                val brightness = hsb[2]
                // 灰白和黑块不当主色：封面留白很多时，它们会把整首歌的颜色拉没了
                if (saturation >= 0.16f && brightness >= 0.12f && !(brightness > 0.94f && saturation < 0.12f)) {
                    val bucket = (hsb[0] * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                    val gain = saturation * (0.35f + brightness)
                    weight[bucket] += gain
                    red[bucket] += r / 255f * gain
                    green[bucket] += g / 255f * gain
                    blue[bucket] += b / 255f * gain
                }
                x += step
            }
            y += step
        }

        var best = -1
        var bestWeight = 0f
        for (i in 0 until BUCKETS) {
            if (weight[i] > bestWeight) {
                bestWeight = weight[i]
                best = i
            }
        }
        if (best < 0 || bestWeight < 2f) return null

        // 相邻色相一起平均，取到的色不会卡在两个桶的边界上
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var sum = 0f
        for (d in -1..1) {
            val i = Math.floorMod(best + d, BUCKETS)
            val factor = if (d == 0) 1f else 0.5f
            val gain = weight[i] * factor
            if (gain <= 0f) continue
            // 累加量按同样的系数缩，除下来才是真正的加权平均
            sr += red[i] * factor
            sg += green[i] * factor
            sb += blue[i] * factor
            sum += gain
        }
        if (sum <= 0f) return null
        return refine(Color(sr / sum, sg / sum, sb / sum))
    }

    /** 压到"能当高亮色"的范围：够鲜艳，又不会亮到发白或暗到看不见 */
    private fun refine(base: Color): Color {
        val hsb = FloatArray(3)
        java.awt.Color.RGBtoHSB(
            (base.r * 255f).toInt().coerceIn(0, 255),
            (base.g * 255f).toInt().coerceIn(0, 255),
            (base.b * 255f).toInt().coerceIn(0, 255),
            hsb,
        )
        val saturation = min(0.95f, max(0.58f, hsb[1] * 1.2f))
        val brightness = min(0.98f, max(0.74f, hsb[2]))
        val rgb = java.awt.Color.HSBtoRGB(hsb[0], saturation, brightness)
        return Color(
            ((rgb shr 16) and 0xFF) / 255f,
            ((rgb shr 8) and 0xFF) / 255f,
            (rgb and 0xFF) / 255f,
            base.a,
        )
    }

    /** 主题切换、窗口关掉时清掉，别让旧封面的颜色留在新界面上 */
    fun clear() {
        latest = null
        lastKey = ""
    }

    /** 两张颜色差多少（0 = 一样）；调用方用来判断是否已经收敛 */
    fun distance(a: Color, b: Color): Float =
        abs(a.r - b.r) + abs(a.g - b.g) + abs(a.b - b.b)
}
