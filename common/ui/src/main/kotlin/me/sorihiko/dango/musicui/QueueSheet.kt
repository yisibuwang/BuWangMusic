package me.sorihiko.dango.musicui

import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 播放栏右侧的播放列表按钮弹出的下层面板：从窗口底部往上滑出，
 * 显示当前播放列表 / 歌单；在一起听歌房间里则显示房间的共享播放列表。
 *
 * 面板的点击、滚轮由 [MusicScreen] 优先转给这里，避免点穿到下面的页面。
 */
internal class QueueSheet(private val screen: MusicScreen) {
    private val ui = screen.ui
    private val slide = Spring(300f, 34f)

    var open = false
        private set

    private var scroll = 0
    private var panel = LayoutBounds()
    private var closeRect = LayoutBounds()
    private var listRect = LayoutBounds()
    private var rowH = 34f
    private var rows = 0
    private var drawn = 0

    fun toggle() {
        open = !open
        if (open) scroll = 0
    }

    fun close() {
        open = false
    }

    private fun tracks(): List<NeteaseTrack> = if (ListenParty.inRoom) ListenParty.queue else MusicPlayer.queue

    private fun currentIndex(): Int = if (ListenParty.inRoom) ListenParty.current else MusicPlayer.index

    fun draw(win: LayoutBounds, bar: LayoutBounds) {
        val t = slide.tick(if (open) 1f else 0f).coerceIn(0f, 1f)
        if (t < 0.01f) {
            panel = LayoutBounds()
            closeRect = LayoutBounds()
            listRect = LayoutBounds()
            rows = 0
            drawn = 0
            return
        }
        val ctx = ui.ctx
        val items = tracks()
        val cur = currentIndex()
        // 靠右的小面板：像从播放列表按钮弹出来的子窗口，不遮挡播放栏
        val w = min(412f, max(320f, win.width * 0.44f))
        val h = min(392f, win.height * 0.64f)
        val x = bar.right - w
        val y = bar.y - 10f - h * t
        val box = LayoutBounds(x, y, w, h)
        panel = box

        ctx.pushClip(win)
        ctx.pushOpacity((t * 1.3f).coerceIn(0f, 1f))
        // 不透明的深色底：下面页面的文字不会透过亚克力叠在列表上
        ctx.drawShape(box, ShapeStyle(Color(0.075f, 0.080f, 0.098f, 0.985f), 14f))
        ctx.drawShape(box, ShapeStyle(Color(0f, 0f, 0f, 0f), 14f, 1f, Color(1f, 1f, 1f, 0.075f)))
        ui.card(LayoutBounds(box.x + 16f, box.y + 42f, box.width - 32f, 1f), Theme.SEPARATOR, 0f)

        val title = if (ListenParty.inRoom) "一起听歌 · ${ListenParty.roomCode}" else "播放列表"
        ui.text(title, box.x + 16f, box.y + 14f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val sub = if (ListenParty.inRoom) "共享 · ${items.size} 首 · 房主 ${ListenParty.hostName}"
        else "本地 · ${items.size} 首"
        ui.text(ui.trim(sub, box.width - 90f, 10f, Theme.FONT_REGULAR), box.x + 16f, box.y + 31f, 10f, Theme.TEXT_TERTIARY)

        closeRect = LayoutBounds(box.right - 36f, box.y + 9f, 26f, 24f)
        ui.icon("✕", closeRect.x + 13f, closeRect.y + 12f, 11f, Theme.TEXT_SECONDARY)
        ui.hits += closeRect to { close() }

        val top = box.y + 52f
        val bottom = box.bottom - 10f
        rowH = 32f
        listRect = LayoutBounds(box.x + 8f, top, box.width - 16f, bottom - top)
        rows = max(1, ((bottom - top) / rowH).toInt())
        val maxScroll = max(0, items.size - rows)
        if (scroll > maxScroll) scroll = maxScroll
        if (scroll < 0) scroll = 0

        if (items.isEmpty()) {
            ui.text(
                if (ListenParty.inRoom) "共享播放列表还是空的" else "播放列表还是空的",
                box.x + 16f, top + 6f, 11f, Theme.TEXT_TERTIARY,
            )
            drawn = 0
        } else {
            val end = min(items.size, scroll + rows)
            drawn = end - scroll
            for (i in scroll until end) {
                drawRow(items[i], i, LayoutBounds(listRect.x, top + (i - scroll) * rowH, listRect.width, rowH), i == cur)
            }
        }

        ctx.popOpacity()
        ctx.popClip()
    }

    private fun drawRow(track: NeteaseTrack, index: Int, row: LayoutBounds, playing: Boolean) {
        val ctx = ui.ctx
        if (playing) ui.card(row.expand(2f, 1f), Theme.CARD_HOVER, 8f)
        val y = row.y + (row.height - 22f) / 2f
        if (playing) {
            ctx.drawShape(
                LayoutBounds(row.x + 3f, y + 3f, 3f, 16f),
                ShapeStyle(RED, 1.5f),
            )
        }
        ui.text("${index + 1}", row.x + 14f, row.y + 11f, 10f, Theme.TEXT_TERTIARY)
        ui.drawCover(LayoutBounds(row.x + 30f, y, 22f, 22f), CoverCache.track(track), 5f)
        val titleColor = if (playing) RED else Theme.TEXT_PRIMARY
        val textW = row.width - 116f
        ui.text(ui.trim(track.name, textW, 11.5f, Theme.FONT_MEDIUM), row.x + 58f, row.y + 7f, 11.5f, titleColor, Theme.FONT_MEDIUM)
        ui.text(
            ui.trim(track.artists.joinToString(" / "), textW, 9.5f),
            row.x + 58f, row.y + 20f, 9.5f, Theme.TEXT_SECONDARY,
        )
        ui.text(fmtTime(track.durationMs), row.right - 50f, row.y + 10f, 9.5f, Theme.TEXT_SECONDARY)
        if (ListenParty.inRoom && ListenParty.isHost) {
            ui.icon("✕", row.right - 14f, row.y + 16f, 9f, Theme.TEXT_TERTIARY)
        }
    }

    /** 点击：返回 true 表示这次点击属于面板，页面不再处理 */
    fun mouseDown(px: Int, py: Int): Boolean {
        if (!open) return false
        val x = px.toFloat()
        val y = py.toFloat()
        if (closeRect.contains(px, py) || !panel.contains(px, py)) {
            close()
            return true
        }
        val index = rowAt(x, y)
        if (index != null) {
            val removeZone = ListenParty.inRoom && ListenParty.isHost && x >= listRect.right - 26f
            if (removeZone) {
                ListenParty.removeFromQueue(index)
            } else {
                if (ListenParty.inRoom) ListenParty.jumpTo(index) else MusicPlayer.playIndex(index)
                close()
            }
        }
        return true
    }

    fun scroll(delta: Int): Boolean {
        if (!open) return false
        val items = tracks()
        val maxScroll = max(0, items.size - max(1, rows))
        scroll = (scroll + delta).coerceIn(0, maxScroll)
        return true
    }

    private fun rowAt(x: Float, y: Float): Int? {
        if (!listRect.contains(x.toInt(), y.toInt())) return null
        if (rows <= 0) return null
        val slot = ((y - listRect.y) / rowH).toInt()
        if (slot !in 0 until drawn) return null
        val index = scroll + slot
        return if (index in tracks().indices) index else null
    }
}
