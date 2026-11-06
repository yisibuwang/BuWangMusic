package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.ListeningStats
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme

import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * 听歌周报页：本地统计的本周总时长、曲数、歌手数、每日柱状图，
 * 以及最常听歌手 / 最常播歌曲排行。数据来自 [ListeningStats]，只存在本地。
 */
internal class WeeklyReportPage(private val screen: MusicScreen) {

    private val ui = screen.ui
    private val dateFmt = DateTimeFormatter.ofPattern("MM/dd")
    private val weekdays = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun draw(body: LayoutBounds) {
        val s = ListeningStats.snapshot()
        val bottom = (body.bottom - ui.bottomInset - 8f).coerceAtLeast(body.y + 120f)
        var y = body.y

        ui.text(I18n.text("Weekly report"), body.x, y, 20f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val range = s.weekStart.format(dateFmt) + " – " + s.weekStart.plusDays(6).format(dateFmt)
        ui.text(range, body.right - ui.width(range, 11f), y + 6f, 11f, Theme.TEXT_TERTIARY)
        y += 36f

        val gap = 12f
        val tileW = (body.width - gap * 3f) / 4f
        val tileH = 66f
        statTile(body.x, y, tileW, tileH, I18n.text("Total time"), fmtDuration(s.totalMs))
        statTile(body.x + (tileW + gap), y, tileW, tileH, I18n.text("Songs played"), s.plays.toString())
        statTile(body.x + (tileW + gap) * 2f, y, tileW, tileH, I18n.text("Artists"), s.artistCount.toString())
        val compare = if (s.prevWeekMs > 0L) {
            val pct = ((s.totalMs - s.prevWeekMs) * 100.0 / s.prevWeekMs).toInt()
            I18n.format("%+d%%", pct)
        } else "—"
        statTile(body.x + (tileW + gap) * 3f, y, tileW, tileH, I18n.text("vs last week"), compare)
        y += tileH + 18f

        ui.text(I18n.text("Daily listening"), body.x, y, 12f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        y += 18f
        val chartH = 82f
        val barGap = 10f
        val barW = (body.width - barGap * 6f) / 7f
        val maxDay = max(1L, s.dailyMs.maxOrNull() ?: 0L)
        for (i in 0 until 7) {
            val v = s.dailyMs.getOrElse(i) { 0L }
            val h = (v.toDouble() / maxDay * chartH).toFloat().coerceAtLeast(2f)
            val bx = body.x + i * (barW + barGap)
            ui.card(LayoutBounds(bx, y + chartH - h, barW, h), if (v > 0L) Theme.SELECTION else Theme.CARD, 6f)
            val label = I18n.text(weekdays[i])
            ui.text(label, bx + (barW - ui.width(label, 9f)) / 2f, y + chartH + 4f, 9f, Theme.TEXT_TERTIARY)
        }
        y += chartH + 26f

        if (y + 34f > bottom) return
        val colGap = 24f
        val colW = (body.width - colGap) / 2f
        drawRank(body.x, y, colW, bottom, I18n.text("Top artists"),
            s.topArtists.map { it.first to fmtDuration(it.second) })
        drawRank(body.x + colW + colGap, y, colW, bottom, I18n.text("Top songs"),
            s.topSongs.map {
                (it.first + if (it.second.isNotEmpty()) " · " + it.second else "") to fmtDuration(it.third)
            })
    }

    private fun statTile(x: Float, y: Float, w: Float, h: Float, label: String, value: String) {
        ui.card(LayoutBounds(x, y, w, h), Theme.CARD, 12f)
        ui.text(ui.trim(label, w - 24f, 10f), x + 12f, y + 11f, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(value, w - 24f, 17f, Theme.FONT_SEMIBOLD), x + 12f, y + 30f, 17f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
    }

    private fun drawRank(
        x: Float,
        y: Float,
        w: Float,
        bottom: Float,
        title: String,
        rows: List<Pair<String, String>>,
    ) {
        ui.text(title, x, y, 12f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        var ry = y + 22f
        if (rows.isEmpty()) {
            ui.text(I18n.text("No listening data this week yet"), x, ry, 11f, Theme.TEXT_TERTIARY)
            return
        }
        for ((i, row) in rows.withIndex()) {
            if (ry + 32f > bottom) break
            ui.card(LayoutBounds(x, ry, w, 30f), if (i == 0) Theme.CARD_HOVER else Theme.CARD, 9f)
            ui.text((i + 1).toString(), x + 12f, ry + 9f, 11f, if (i == 0) RED else Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            ui.text(ui.trim(row.first, w - 118f, 11f), x + 34f, ry + 8f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            ui.text(row.second, x + w - 12f - ui.width(row.second, 10f), ry + 9f, 10f, Theme.TEXT_TERTIARY)
            ry += 34f
        }
    }

    private fun fmtDuration(ms: Long): String {
        val totalMin = ms / 60_000L
        val h = totalMin / 60L
        val m = totalMin % 60L
        return if (h > 0L) I18n.format("%d h %d min", h, m) else I18n.format("%d min", m)
    }
}
