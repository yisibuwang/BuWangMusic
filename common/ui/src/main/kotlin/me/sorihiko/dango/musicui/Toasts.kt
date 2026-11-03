package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.notice.Notices
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 浮在播放器窗口底部、播放栏上方的提示条。
 *
 * 一起听歌和其它功能把消息 post 到 [Notices]，这里每帧取最多三条画出来：
 * 深色胶囊底 + 左边一个小的圆形状态点（✓ 成功 / ！ 失败 / i 信息），
 * 200ms 淡入并轻轻往上浮，最后 400ms 淡出，4 秒后自动消失。
 */
internal class Toasts {

    private class Show(val text: String, val level: Int, val start: Long)

    private val show = ArrayDeque<Show>()

    fun draw(ui: Ui, area: LayoutBounds) {
        val now = System.currentTimeMillis()
        while (show.size < 3) {
            val next = Notices.poll() ?: break
            // 播放器关着的时候攒下的旧提示不再弹出来
            if (now - next.at > STALE_MS) continue
            show.addLast(Show(I18n.message(next.text), next.level, now))
        }
        if (show.isEmpty()) return
        show.removeAll { now - it.start > LIFE_MS }

        var y = area.bottom - 12f
        val maxW = min(460f, max(180f, area.width - 120f))
        for (item in show.reversed()) {
            val lines = ui.wrap2(item.text, maxW - 58f, 11.5f, Theme.FONT_MEDIUM)
            var textW = 0f
            for (line in lines) textW = max(textW, ui.width(line, 11.5f, Theme.FONT_MEDIUM))
            val h = max(34f, lines.size * 15f + 18f)
            val w = textW + 58f
            val age = now - item.start
            val rise = (1f - min(1f, age / 200f)) * 6f
            y -= h
            val box = LayoutBounds(area.x + (area.width - w) / 2f, y + rise, w, h)
            y -= 7f

            val alpha = min(1f, age / 200f.coerceAtLeast(1f)) * min(1f, (LIFE_MS - age) / 400f.coerceAtLeast(1f))
            if (alpha <= 0.01f) continue
            val accent = when (item.level) {
                Notices.WARN -> RED
                Notices.OK -> Theme.GREEN
                else -> Theme.TEXT_SECONDARY
            }
            val glyph = when (item.level) {
                Notices.WARN -> "!"
                Notices.OK -> "✓"
                else -> "i"
            }
            ui.ctx.pushOpacity(alpha.coerceIn(0f, 1f))
            ui.ctx.drawShape(box, ShapeStyle(Theme.PANEL, h / 2f))
            ui.ctx.drawShape(box, ShapeStyle(Color(0f, 0f, 0f, 0f), h / 2f, 1f, Theme.HAIRLINE))
            val dot = 17f
            val dy = box.y + (h - dot) / 2f
            ui.ctx.drawShape(LayoutBounds(box.x + 11f, dy, dot, dot), ShapeStyle(Color(accent.r, accent.g, accent.b, 0.18f), dot / 2f))
            val glyphW = ui.width(glyph, 10f, Theme.FONT_SEMIBOLD)
            ui.text(glyph, box.x + 11f + (dot - glyphW) / 2f, dy + 4f, 10f, accent, Theme.FONT_SEMIBOLD)
            lines.forEachIndexed { i, line ->
                ui.text(line, box.x + 38f, box.y + (h - lines.size * 15f) / 2f + i * 15f, 11.5f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            }
            ui.ctx.popOpacity()
        }
    }

    private companion object {
        /** 一条提示停留多久 */
        const val LIFE_MS = 4_200L

        /** 超过这么久还没被取走的提示直接丢掉（界面关着时的积压） */
        const val STALE_MS = 8_000L
    }
}
