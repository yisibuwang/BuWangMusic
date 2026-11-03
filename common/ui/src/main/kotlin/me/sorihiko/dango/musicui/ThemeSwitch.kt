package me.sorihiko.dango.musicui

import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ThemeTransition

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 外观预设切换的圆形扩散。
 *
 * 切换时把上一帧整屏存成贴图，然后只画「圆外面」那一块：圆里已经是新配色，圆外仍是旧画面，
 * 于是看起来就是从鼠标位置开始把新界面擦出来。逐条水平带 + scissor 实现，不依赖任何着色器，
 * 所有平台（含没有帧缓冲拷贝能力的）都能跑；拿不到快照时 [draw] 直接跳过，只剩配色渐变。
 */
internal object ThemeSwitch {

    /** 条带高度（逻辑像素）。越小圆边越顺，绘制次数越多；4 在 800 高下约 200 次。 */
    private const val BAND = 4f

    fun beginFrame(ui: Ui) {
        ThemeTransition.beginFrame(ui.lw, ui.lh)
    }

    /** 这一帧画完之后调用：需要换配色就抓帧并开始过渡 */
    fun endFrame(ui: Ui) {
        ThemeTransition.capture(ui.lw, ui.lh)
    }

    fun draw(ui: Ui) {
        val texture = ThemeTransition.snapshot
        if (texture == null || !ThemeTransition.active) return
        val k = ui.scale
        val lw = ui.lw
        val lh = ui.lh
        val cx = ThemeTransition.centerX
        val cy = ThemeTransition.centerY
        val radius = ThemeTransition.radius * easeOut(ThemeTransition.progress)
        if (radius <= 0f) return

        ui.ctx.pushScale(k, k, 1f)
        try {
            var y = 0f
            while (y < lh) {
                val y2 = min(lh, y + BAND)
                // 这一条带离圆心的垂直距离（为 0 时整条都在圆的横轴上）
                val dy = when {
                    cy < y -> y - cy
                    cy > y2 -> cy - y2
                    else -> 0f
                }
                if (dy >= radius) {
                    band(ui, texture, 0f, y, lw, y2, k, lh)
                } else {
                    val half = sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
                    val left = cx - half
                    val right = cx + half
                    if (left > 0f) band(ui, texture, 0f, y, min(left, lw), y2, k, lh)
                    if (right < lw) band(ui, texture, max(right, 0f), y, lw, y2, k, lh)
                }
                y = y2
            }
        } finally {
            ui.ctx.noScissor()
            ui.ctx.popTransform()
        }
    }

    private fun band(
        ui: Ui,
        texture: Texture,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        k: Float,
        lh: Float,
    ) {
        if (x1 - x0 <= 0.01f || y1 - y0 <= 0.01f) return
        // scissor 是窗口像素、原点左下角；这里传进来的是逻辑坐标。两条边都取整后
        // 用差值当宽高，相邻条带才不会留下 1px 的缝。
        val sx0 = (x0 * k).roundToInt()
        val sx1 = (x1 * k).roundToInt()
        val sy0 = ((lh - y1) * k).roundToInt()
        val sy1 = ((lh - y0) * k).roundToInt()
        val sw = (sx1 - sx0).coerceAtLeast(1)
        val sh = (sy1 - sy0).coerceAtLeast(1)
        ui.ctx.scissor(sx0, sy0, sw, sh)
        ui.ctx.drawImage(LayoutBounds(0f, 0f, ui.lw, lh), texture)
    }

    private fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }
}
