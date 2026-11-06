package me.sorihiko.dango.ui

import me.sorihiko.dango.utils.Color

/** 界面配色：默认的透明玻璃、更实的深色、亮底浅色 */
enum class ThemeMode {
    CLEAR,
    DARK,
    LIGHT;

    companion object {
        /** 设置项里 0 / 1 / 2 依次对应这三套 */
        fun of(index: Int): ThemeMode = when (index) {
            1 -> DARK
            2 -> LIGHT
            else -> CLEAR
        }
    }
}

/**
 * 一套界面配色。所有颜色都挂在这里，[Theme] 只是把当前的这一套暴露出去，
 * 所以换主题（以及过渡时逐帧插值）不需要改任何绘制代码。
 *
 * [overlayBase] 是"叠在背景上提亮/压暗"的基色：深色主题叠白、浅色主题叠黑，
 * 卡片、分隔线、悬停底色都用它按 alpha 算出来。
 */
class Palette(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val green: Color,
    val switchOff: Color,
    /** 占位块 / 进度条槽 */
    val track: Color,
    val card: Color,
    val cardHover: Color,
    val hairline: Color,
    val separator: Color,
    val iconDisabled: Color,
    /** 不透明面板（队列抽屉、分P 抽屉、提示条、取色器） */
    val panel: Color,
    val overlayBase: Color,
    /** 没有世界可采样时的兜底底色（主菜单打开播放器） */
    val backdrop: Color,
    /** 窗口后面压暗的遮罩，让玻璃立起来 */
    val scrim: Color,
    val window: GlassStyle,
    val sidebar: GlassStyle,
    /** 选中块：一层半透明填充，不用玻璃，避免在亮背景上被压成黑条 */
    val selection: Color,
    /** 音源菜单这类小浮层：比侧栏更实，保证字看得清 */
    val menu: GlassStyle,
    /** 高亮色（进度条、喜欢按钮、选中块描边）；跟随封面时会换成封面主色 */
    val highlight: Color = Color.fromHex("#FF375F"),
) {
    companion object {
        /**
         * 默认的透明预设：原来的液态玻璃观感，背景透得最多。
         */
        val CLEAR = Palette(
            textPrimary = Color(1f, 1f, 1f, 0.96f),
            textSecondary = Color(0.92f, 0.92f, 0.96f, 0.62f),
            textTertiary = Color(0.92f, 0.92f, 0.96f, 0.38f),
            accent = Color.fromHex("#0A84FF"),
            green = Color.fromHex("#30D158"),
            switchOff = Color(0.47f, 0.47f, 0.50f, 0.36f),
            track = Color(0.47f, 0.47f, 0.50f, 0.30f),
            card = Color(1f, 1f, 1f, 0.055f),
            cardHover = Color(1f, 1f, 1f, 0.085f),
            hairline = Color(1f, 1f, 1f, 0.08f),
            separator = Color(1f, 1f, 1f, 0.07f),
            iconDisabled = Color(0.38f, 0.38f, 0.42f, 1f),
            panel = Color(0.075f, 0.080f, 0.098f, 0.985f),
            overlayBase = Color(1f, 1f, 1f, 1f),
            backdrop = Color(0.075f, 0.085f, 0.11f, 1f),
            scrim = Color(0f, 0f, 0f, 0f),
            window = GlassStyle(
                radius = 30f,
                tint = Color(0.10f, 0.10f, 0.13f, 0.18f),
                maxLuma = 0.30f,
                saturation = 1.45f,
                frost = 0.85f,
                refraction = 22f,
                bevel = 26f,
                dispersion = 0.30f,
                highlight = 1f,
                shadow = ShadowStyle(Color(0f, 0f, 0f, 0.30f), 36f, Vec2(0f, -10f)),
            ),
            sidebar = GlassStyle(
                radius = 22f,
                tint = Color(1f, 1f, 1f, 0.05f),
                maxLuma = 0.36f,
                saturation = 1.4f,
                frost = 0.55f,
                refraction = 16f,
                bevel = 18f,
                dispersion = 0.25f,
                highlight = 0.9f,
                shadow = ShadowStyle(Color(0f, 0f, 0f, 0.18f), 18f, Vec2(0f, -4f)),
            ),
            selection = Color(1f, 1f, 1f, 0.10f),
            menu = GlassStyle(
                radius = 14f,
                tint = Color(0.05f, 0.05f, 0.07f, 0.55f),
                maxLuma = 0.22f,
                saturation = 1.4f,
                frost = 0.9f,
                refraction = 12f,
                bevel = 12f,
                dispersion = 0.3f,
                highlight = 1.1f,
                shadow = ShadowStyle(Color(0f, 0f, 0f, 0.30f), 18f, Vec2(0f, -4f)),
            ),
        )

        /**
         * 深色：在透明预设上再压一档，玻璃更实、底色更暗，白天/雪地这类亮场景里字也不糊。
         */
        val DARK = Palette(
            textPrimary = Color(1f, 1f, 1f, 0.97f),
            textSecondary = Color(0.92f, 0.92f, 0.96f, 0.70f),
            textTertiary = Color(0.92f, 0.92f, 0.96f, 0.44f),
            accent = Color.fromHex("#0A84FF"),
            green = Color.fromHex("#30D158"),
            switchOff = Color(0.47f, 0.47f, 0.50f, 0.40f),
            track = Color(0.47f, 0.47f, 0.50f, 0.34f),
            card = Color(1f, 1f, 1f, 0.07f),
            cardHover = Color(1f, 1f, 1f, 0.10f),
            hairline = Color(1f, 1f, 1f, 0.10f),
            separator = Color(1f, 1f, 1f, 0.09f),
            iconDisabled = Color(0.34f, 0.34f, 0.38f, 1f),
            panel = Color(0.045f, 0.048f, 0.060f, 0.99f),
            overlayBase = Color(1f, 1f, 1f, 1f),
            backdrop = Color(0.035f, 0.040f, 0.055f, 1f),
            scrim = Color(0f, 0f, 0f, 0.42f),
            window = GlassStyle(
                radius = 30f,
                tint = Color(0.02f, 0.022f, 0.030f, 0.46f),
                maxLuma = 0.16f,
                saturation = 1.30f,
                frost = 1f,
                refraction = 22f,
                bevel = 26f,
                dispersion = 0.28f,
                highlight = 1.05f,
                shadow = ShadowStyle(Color(0f, 0f, 0f, 0.40f), 36f, Vec2(0f, -10f)),
                fallback = Color(0.055f, 0.058f, 0.070f, 0.95f),
            ),
            sidebar = GlassStyle(
                radius = 22f,
                tint = Color(0.02f, 0.02f, 0.03f, 0.34f),
                maxLuma = 0.20f,
                saturation = 1.30f,
                frost = 0.92f,
                refraction = 16f,
                bevel = 18f,
                dispersion = 0.26f,
                highlight = 1f,
                shadow = ShadowStyle(Color(0f, 0f, 0f, 0.30f), 18f, Vec2(0f, -4f)),
                fallback = Color(0.05f, 0.055f, 0.068f, 0.94f),
            ),
            selection = Color(1f, 1f, 1f, 0.08f),
            menu = GlassStyle(
                radius = 14f,
                tint = Color(0.02f, 0.02f, 0.03f, 0.80f),
                maxLuma = 0.18f,
                saturation = 1.30f,
                frost = 1f,
                refraction = 12f,
                bevel = 12f,
                dispersion = 0.28f,
                highlight = 1.1f,
                shadow = ShadowStyle(Color(0f, 0f, 0f, 0.40f), 18f, Vec2(0f, -4f)),
                fallback = Color(0.045f, 0.048f, 0.060f, 0.97f),
            ),
        )

        /**
         * 浅色：玻璃用白 tint 把背景抬亮，maxLuma 放到 1 不再压暗（现在要靠深色字读）。
         * 卡片、分隔线换成叠黑，阴影也收一档，否则白底上的黑边会脏。
         */
        val LIGHT = Palette(
            textPrimary = Color(0.09f, 0.09f, 0.11f, 0.96f),
            textSecondary = Color(0.12f, 0.12f, 0.15f, 0.62f),
            textTertiary = Color(0.12f, 0.12f, 0.15f, 0.40f),
            accent = Color.fromHex("#007AFF"),
            green = Color.fromHex("#248A3D"),
            switchOff = Color(0.47f, 0.47f, 0.50f, 0.26f),
            track = Color(0f, 0f, 0f, 0.10f),
            card = Color(0f, 0f, 0f, 0.045f),
            cardHover = Color(0f, 0f, 0f, 0.075f),
            hairline = Color(0f, 0f, 0f, 0.09f),
            separator = Color(0f, 0f, 0f, 0.08f),
            iconDisabled = Color(0.62f, 0.62f, 0.66f, 1f),
            panel = Color(0.965f, 0.968f, 0.978f, 0.985f),
            overlayBase = Color(0f, 0f, 0f, 1f),
            backdrop = Color(0.90f, 0.92f, 0.95f, 1f),
            scrim = Color(1f, 1f, 1f, 0.20f),
            window = GlassStyle(
                radius = 30f,
                tint = Color(1f, 1f, 1f, 0.62f),
                maxLuma = 1f,
                saturation = 1.02f,
                frost = 0.92f,
                refraction = 20f,
                bevel = 26f,
                dispersion = 0.22f,
                highlight = 0.55f,
                shadow = ShadowStyle(Color(0.10f, 0.12f, 0.18f, 0.16f), 34f, Vec2(0f, -10f)),
                fallback = Color(0.94f, 0.95f, 0.97f, 0.94f),
            ),
            sidebar = GlassStyle(
                radius = 22f,
                tint = Color(1f, 1f, 1f, 0.56f),
                maxLuma = 1f,
                saturation = 1f,
                frost = 0.8f,
                refraction = 14f,
                bevel = 18f,
                dispersion = 0.18f,
                highlight = 0.5f,
                shadow = ShadowStyle(Color(0.10f, 0.12f, 0.18f, 0.12f), 18f, Vec2(0f, -4f)),
                fallback = Color(0.96f, 0.97f, 0.98f, 0.94f),
            ),
            selection = Color(0f, 0f, 0f, 0.055f),
            menu = GlassStyle(
                radius = 14f,
                tint = Color(1f, 1f, 1f, 0.66f),
                maxLuma = 1f,
                saturation = 1.02f,
                frost = 0.9f,
                refraction = 12f,
                bevel = 12f,
                dispersion = 0.22f,
                highlight = 0.55f,
                shadow = ShadowStyle(Color(0.10f, 0.12f, 0.18f, 0.16f), 18f, Vec2(0f, -4f)),
                fallback = Color(0.97f, 0.975f, 0.985f, 0.96f),
            ),
        )

        fun of(mode: ThemeMode): Palette = when (mode) {
            ThemeMode.DARK -> DARK
            ThemeMode.LIGHT -> LIGHT
            ThemeMode.CLEAR -> CLEAR
        }
    }
}

/** 两个颜色按 [t]（0..1）混合 */
fun mixColor(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    if (k == 0f) return a
    if (k == 1f) return b
    return Color(
        a.r + (b.r - a.r) * k,
        a.g + (b.g - a.g) * k,
        a.b + (b.b - a.b) * k,
        a.a + (b.a - a.a) * k,
    )
}

private fun mixNumber(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun mixShadow(a: ShadowStyle, b: ShadowStyle, t: Float): ShadowStyle = ShadowStyle(
    mixColor(a.color, b.color, t),
    mixNumber(a.size, b.size, t),
    Vec2(mixNumber(a.offset.x, b.offset.x, t), mixNumber(a.offset.y, b.offset.y, t)),
)

private fun mixStyle(a: GlassStyle, b: GlassStyle, t: Float): GlassStyle = GlassStyle(
    radius = mixNumber(a.radius, b.radius, t),
    tint = mixColor(a.tint, b.tint, t),
    maxLuma = mixNumber(a.maxLuma, b.maxLuma, t),
    saturation = mixNumber(a.saturation, b.saturation, t),
    frost = mixNumber(a.frost, b.frost, t),
    refraction = mixNumber(a.refraction, b.refraction, t),
    bevel = mixNumber(a.bevel, b.bevel, t),
    dispersion = mixNumber(a.dispersion, b.dispersion, t),
    highlight = mixNumber(a.highlight, b.highlight, t),
    shadow = mixShadow(a.shadow, b.shadow, t),
    fallback = mixColor(a.fallback, b.fallback, t),
)

/** 逐项插值两套配色，圆扩散期间每一帧用它生成中间态 */
fun mixPalette(a: Palette, b: Palette, t: Float): Palette {
    if (t <= 0f) return a
    if (t >= 1f) return b
    return Palette(
        textPrimary = mixColor(a.textPrimary, b.textPrimary, t),
        textSecondary = mixColor(a.textSecondary, b.textSecondary, t),
        textTertiary = mixColor(a.textTertiary, b.textTertiary, t),
        accent = mixColor(a.accent, b.accent, t),
        green = mixColor(a.green, b.green, t),
        switchOff = mixColor(a.switchOff, b.switchOff, t),
        track = mixColor(a.track, b.track, t),
        card = mixColor(a.card, b.card, t),
        cardHover = mixColor(a.cardHover, b.cardHover, t),
        hairline = mixColor(a.hairline, b.hairline, t),
        separator = mixColor(a.separator, b.separator, t),
        iconDisabled = mixColor(a.iconDisabled, b.iconDisabled, t),
        panel = mixColor(a.panel, b.panel, t),
        overlayBase = mixColor(a.overlayBase, b.overlayBase, t),
        backdrop = mixColor(a.backdrop, b.backdrop, t),
        scrim = mixColor(a.scrim, b.scrim, t),
        window = mixStyle(a.window, b.window, t),
        sidebar = mixStyle(a.sidebar, b.sidebar, t),
        selection = mixColor(a.selection, b.selection, t),
        menu = mixStyle(a.menu, b.menu, t),
        highlight = mixColor(a.highlight, b.highlight, t),
    )
}
