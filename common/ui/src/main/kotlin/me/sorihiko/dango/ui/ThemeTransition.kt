package me.sorihiko.dango.ui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Texture

/**
 * 外观预设切换的过渡状态：先按旧配色画完一帧并抓成纹理（[capture]），
 * 之后每帧把 [Theme.palette] 往新配色插值，由 me.sorihiko.dango.musicui.ThemeSwitch
 * 把旧画面按圆形遮罩叠回去。
 *
 * 平台层拿不到帧缓冲（旧版本没有实现 [me.sorihiko.dango.gfx.Gfx.captureScreen]）时
 * snapshot 为 null，退化成纯配色渐变。
 */
object ThemeTransition {

    /** 过渡时长（毫秒）；[request] 传 0 表示直接到位、不播动画 */
    const val DURATION_MS = 520L

    var active = false
        private set

    /** 这一帧画完之后要抓帧、换配色 */
    var capturePending = false
        private set

    /** 旧画面（圆外显示它） */
    var snapshot: Texture? = null
        private set

    /** 圆形扩散的圆心与最大半径，都是逻辑坐标 */
    var centerX = 0f
        private set
    var centerY = 0f
        private set
    var radius = 0f
        private set

    /** 线性进度 0..1，缓动在绘制时算 */
    var progress = 0f
        private set

    /** 过渡进行中：这期间锁住点击和滚轮，免得点到旧位置上的新控件 */
    val busy: Boolean get() = active || capturePending

    private var pendingMode: ThemeMode? = null
    private var durationMs = DURATION_MS
    private var from: Palette = Palette.CLEAR
    private var to: Palette = Palette.CLEAR
    private var startNanos = 0L
    private var capturedWidth = 0f
    private var capturedHeight = 0f

    /** 请求切换；cx / cy 是逻辑坐标的圆心（一般是点击位置），radiusPx 是铺满窗口需要的半径 */
    fun request(mode: ThemeMode, cx: Float, cy: Float, radiusPx: Float, immediate: Boolean = false) {
        if (mode == Theme.mode && !active) return
        centerX = cx
        centerY = cy
        radius = radiusPx.coerceAtLeast(1f)
        durationMs = if (immediate) 0L else DURATION_MS
        pendingMode = mode
        capturePending = !immediate
        active = false
        progress = 0f
        if (immediate) {
            Theme.mode = mode
            Theme.palette = Palette.of(mode)
            pendingMode = null
        }
    }

    /** 打开界面时把配色直接设成用户选的模式（不播动画） */
    fun applyImmediately(mode: ThemeMode) {
        if (mode == Theme.mode && !active) return
        releaseSnapshot()
        capturePending = false
        pendingMode = null
        active = false
        progress = 0f
        Theme.mode = mode
        Theme.palette = Palette.of(mode)
    }

    /**
     * 播放器关闭时的纯配色渐变：不抓帧、不做圆形扩散，只把 [Theme.palette] 平滑过渡到目标配色。
     * 由 HUD 每帧调用 [beginFrame] 推进，Song card / 灵动岛就能跟着一起淡过去。
     */
    fun requestFade(mode: ThemeMode) {
        if (mode == Theme.mode && !active) return
        releaseSnapshot()
        capturePending = false
        pendingMode = null
        centerX = 0f
        centerY = 0f
        radius = 1f
        from = Theme.palette
        to = Palette.of(mode)
        Theme.mode = mode
        startNanos = System.nanoTime()
        durationMs = DURATION_MS
        progress = 0f
        active = true
    }

    /**
     * 每帧画完之后调用：需要换配色时抓下这一帧、开始过渡。
     * [width] / [height] 是逻辑尺寸，窗口大小一变就作废这张图。
     */
    fun capture(width: Float, height: Float) {
        if (!capturePending) return
        val mode = pendingMode ?: run {
            capturePending = false
            return
        }
        releaseSnapshot()
        // 抓帧失败（驱动不支持、帧缓冲被切走）不该把界面卡在过渡里：退化成纯配色渐变
        snapshot = runCatching { DangoClient.gfx.captureScreen() }.getOrNull()
        capturedWidth = width
        capturedHeight = height
        capturePending = false
        from = Theme.palette
        to = Palette.of(mode)
        Theme.mode = mode
        startNanos = System.nanoTime()
        progress = 0f
        active = true
    }

    /** 每帧开始：推进配色插值 */
    fun beginFrame(width: Float, height: Float) {
        if (!active) return
        if (snapshot != null && (width != capturedWidth || height != capturedHeight)) {
            finish()
            return
        }
        if (durationMs <= 0L) {
            finish()
            return
        }
        val t = ((System.nanoTime() - startNanos) / 1_000_000.0).toFloat() / durationMs
        progress = t.coerceIn(0f, 1f)
        if (progress >= 1f) finish() else Theme.palette = mixPalette(from, to, easeOut(progress))
    }

    /** 过渡结束：停在目标配色上 */
    fun finish() {
        if (active && durationMs > 0L) Theme.palette = to
        active = false
        progress = 1f
        releaseSnapshot()
    }

    /** 关闭界面时收尾 */
    fun cancel() {
        capturePending = false
        pendingMode = null
        finish()
    }

    /** 缓动：开头快、结尾慢（和切换菜单那种手感一致） */
    private fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun releaseSnapshot() {
        val texture = snapshot ?: return
        snapshot = null
        runCatching { DangoClient.gfx.textures.delete(texture) }
    }
}
