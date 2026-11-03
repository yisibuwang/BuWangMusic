package me.sorihiko.dango.ui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.utils.Color

/**
 * 液态玻璃主题：颜色、字体、玻璃材质预设，以及常用绘制辅助。
 * 所有尺寸都是"逻辑像素"，整个菜单会按屏幕大小统一缩放。
 *
 * 颜色不写死在这里，而是来自当前 [palette]（深色 / 浅色两套，见 [Palette]）；
 * 换主题就是换 palette，过渡时由 [mixPalette] 逐帧插值。
 */
object Theme {
    // ---- 布局 ----
    const val MENU_WIDTH = 880f
    const val MENU_HEIGHT = 560f

    // ---- 字体 ----
    const val FONT_REGULAR = "Inter-Regular-cut.ttf"
    const val FONT_MEDIUM = "Inter-Medium-cut.ttf"
    const val FONT_SEMIBOLD = "Inter-SemiBold-cut.ttf"
    const val FONT_DISPLAY = "InterDisplay-Bold-cut.ttf"

    /** Inter 的大写字高 / 字号，用于文字垂直居中 */
    private const val CAP_HEIGHT = 0.727f

    // ---- 当前配色 ----

    /** 现在生效的配色；过渡期间每帧都会被替换成插值结果 */
    @Volatile
    var palette: Palette = Palette.CLEAR

    /** 用户选的模式（切换请求时立刻更新，配色随后追上） */
    @Volatile
    var mode: ThemeMode = ThemeMode.CLEAR

    // ---- 颜色 ----
    /** 跟随封面时的强调色；null = 用预设配色 */
    @Volatile
    var coverTint: Color? = null

    val TEXT_PRIMARY: Color get() = palette.textPrimary
    val TEXT_SECONDARY: Color get() = palette.textSecondary
    val TEXT_TERTIARY: Color get() = palette.textTertiary

    val ACCENT: Color get() = coverTint ?: palette.accent
    /** 高亮色：进度条、喜欢按钮、选中块描边；跟随封面时一起换 */
    val HIGHLIGHT: Color get() = coverTint ?: palette.highlight

    /** HUD 歌词是否改用强调色（由设置里的开关写入） */
    @Volatile
    var lyricAccent: Boolean = false

    val LYRIC: Color get() = if (lyricAccent) HIGHLIGHT else palette.textPrimary
    val LYRIC_SUB: Color get() = if (lyricAccent) HIGHLIGHT.copy(a = 0.72f) else palette.textTertiary
    val GREEN: Color get() = palette.green
    val SWITCH_OFF: Color get() = palette.switchOff
    val TRACK: Color get() = palette.track

    val CARD: Color get() = palette.card
    val CARD_HOVER: Color get() = palette.cardHover
    val HAIRLINE: Color get() = palette.hairline
    val SEPARATOR: Color get() = palette.separator

    val ICON_DISABLED: Color get() = palette.iconDisabled

    /** 不透明面板底色（抽屉、提示条、取色器） */
    val PANEL: Color get() = palette.panel

    /** 菜单没有世界时的兜底底色 */
    val BACKDROP: Color get() = palette.backdrop

    /** 窗口后面那一层压暗/提亮的遮罩 */
    val SCRIM: Color get() = palette.scrim

    /** 叠在背景上的"提亮"（深色）或"压暗"（浅色）层 */
    fun overlay(alpha: Float): Color {
        val base = palette.overlayBase
        return Color(base.r, base.g, base.b, alpha)
    }

    // ---- 玻璃材质：越靠前的层越亮 ----
    val GLASS_WINDOW: GlassStyle get() = palette.window
    val GLASS_SIDEBAR: GlassStyle get() = palette.sidebar
    val GLASS_MENU: GlassStyle get() = palette.menu

    /** 选中块 / 当前播放行的底色 */
    val SELECTION: Color get() = palette.selection

    // ---- 绘制辅助 ----

    fun textWidth(text: String, size: Float, fontPath: String): Float =
        DangoClient.gfx.uiTextWidth(text, size, fontPath)

    /** 以 centerY 为视觉中线绘制文字（按大写字高居中） */
    fun drawText(
        context: RenderContext,
        text: String,
        x: Float,
        centerY: Float,
        size: Float,
        fontPath: String,
        color: Color,
    ) {
        context.drawText(
            text,
            x,
            centerY + size * CAP_HEIGHT / 2f,
            FontStyle(fontPath, color, size)
        )
    }

    /** 细 chevron（">"），angle 为顺时针旋转角度 */
    fun drawChevron(context: RenderContext, cx: Float, cy: Float, size: Float, angle: Float, color: Color) {
        val thickness = size * 0.22f
        val length = size * 0.62f
        context.pushTranslate(cx, cy, 0f)
        context.pushRotate(angle, 0f, 0f, 1f)
        // 尖端在原点、两臂向左展开；右移让图形视觉居中
        context.pushTranslate(length * 0.35f, 0f, 0f)
        for (dir in intArrayOf(-1, 1)) {
            context.pushRotate(45f * dir, 0f, 0f, 1f)
            context.drawShape(
                LayoutBounds(-length + thickness / 2f, -thickness / 2f, length, thickness),
                ShapeStyle(color, thickness / 2f)
            )
            context.popTransform()
        }
        context.popTransform()
        context.popTransform()
        context.popTransform()
    }
}
