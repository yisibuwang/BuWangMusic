package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.BiliSource
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.bili.BiliParts
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Drawer listing the parts of a multi-part Bilibili video.
 *
 * Collections import one row per video, so a video with several parts would otherwise only ever
 * play its first part. Clicking such a row expands this panel instead of starting playback;
 * picking a part plays it. Single videos keep the flat behaviour.
 */
internal class PartsSheet(private val screen: MusicScreen) {
    private val ui = screen.ui
    private val anim = Spring(260f, 30f)

    private var target: NeteaseTrack? = null
    private var parts: List<NeteaseTrack> = emptyList()
    private var pending: List<NeteaseTrack>? = null
    private var pendingIndex = 0
    private var loading = false
    private var scroll = 0
    private var panel = LayoutBounds(0f, 0f, 0f, 0f)
    private var listRect = LayoutBounds(0f, 0f, 0f, 0f)

    var open = false
        private set

    /** A row click: videos with parts open the drawer, everything else plays straight away. */
    fun pick(all: List<NeteaseTrack>, index: Int) {
        val track = all.getOrNull(index) ?: return
        // 已经是一P（带 cid）的行没有抽屉可开，直接播
        if (track.source != MusicSource.BILIBILI || track.nativeId.contains(':')) {
            play(all, index)
            return
        }
        screen.queueSheet.close()
        val bvid = track.nativeId.substringBefore(':')
        val known = BiliParts.peek(bvid)
        if (known == null) {
            screen.queueSheet.close()
            BiliParts.request(bvid)
            target = track
            pending = all
            pendingIndex = index
            parts = emptyList()
            loading = true
            return
        }
        expand(all, index, track, known)
    }

    /** Called every frame while a drawer is loading; finishes the click once parts arrive. */
    fun update() {
        val track = target ?: return
        if (!loading) return
        val known = BiliParts.peek(track.nativeId.substringBefore(':')) ?: return
        loading = false
        val all = pending
        pending = null
        if (all != null) expand(all, pendingIndex, track, known) else finish(track, known)
    }

    private fun expand(all: List<NeteaseTrack>, index: Int, track: NeteaseTrack, known: List<BiliParts.Part>) {
        if (known.isEmpty()) {
            play(all, index)
            close()
            return
        }
        target = track
        parts = BiliSource.tracksOf(known, track.artists, track.coverUrl)
        scroll = 0
        open = true
    }

    private fun finish(track: NeteaseTrack, known: List<BiliParts.Part>) {
        if (known.isEmpty()) {
            MusicPlayer.playQueue(listOf(track), 0, null)
            close()
        } else {
            target = track
            parts = BiliSource.tracksOf(known, track.artists, track.coverUrl)
            scroll = 0
            open = true
        }
    }

    private fun play(all: List<NeteaseTrack>, index: Int) {
        val t = all.getOrNull(index) ?: return
        MusicPlayer.playQueue(all, index, null)
        if (t.source == MusicSource.BILIBILI) BiliParts.request(t.nativeId.substringBefore(':'))
    }

    fun close() {
        open = false
        loading = false
        target = null
        parts = emptyList()
        pending = null
    }

    fun draw(win: LayoutBounds, bar: LayoutBounds) {
        update()
        if (!open && !loading) return
        val track = target ?: return
        val k = anim.tick(1f)
        if (k <= 0.01f) return

        val ctx = ui.ctx
        val w = min(430f, max(330f, win.width * 0.46f))
        val h = min(360f, win.height * 0.6f)
        val x = bar.right - w
        val y = bar.y - 12f - h * k
        panel = LayoutBounds(x, y, w, h)

        ctx.pushClip(win)
        ctx.pushOpacity((k * 1.35f).coerceIn(0f, 1f))
        // 不透明深色底，避免下面的列表文字透上来
        ctx.drawShape(panel, ShapeStyle(Theme.PANEL, 14f))
        ctx.drawShape(panel, ShapeStyle(Color(0f, 0f, 0f, 0f), 14f, 1f, Theme.HAIRLINE))

        ui.text(ui.trim(track.name, w - 72f, 13f), panel.x + 16f, panel.y + 14f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        val sub = if (loading) I18n.text("Reading parts…") else I18n.format("Parts · %d", parts.size)
        ui.text(sub, panel.x + 16f, panel.y + 33f, 10f, Theme.TEXT_TERTIARY)
        ui.card(LayoutBounds(panel.x + 16f, panel.y + 50f, w - 32f, 1f), Theme.SEPARATOR, 0f)

        val closeRect = LayoutBounds(panel.right - 36f, panel.y + 9f, 26f, 24f)
        ui.icon("✕", closeRect.x + 13f, closeRect.y + 12f, 11f, Theme.TEXT_SECONDARY)

        listRect = LayoutBounds(panel.x + 8f, panel.y + 56f, w - 16f, h - 64f)
        if (loading) {
            ui.text(I18n.text("Reading parts…"), listRect.x + 12f, listRect.y + 8f, 11f, Theme.TEXT_SECONDARY)
            ctx.popOpacity()
            ctx.popClip()
            return
        }
        val rowH = 30f
        val rows = max(1, (listRect.height / rowH).toInt())
        scroll = scroll.coerceIn(0, max(0, parts.size - rows))
        ui.beginClip(listRect)
        val playing = MusicPlayer.current
        for (r in 0 until rows) {
            val i = scroll + r
            val p = parts.getOrNull(i) ?: break
            val row = LayoutBounds(listRect.x, listRect.y + r * rowH, listRect.width, rowH - 3f)
            val current = playing != null && playing.key == p.key
            when {
                current -> ui.card(row, Theme.SELECTION, 8f)
                ui.hover(row) -> ui.card(row, Theme.CARD_HOVER, 8f)
            }
            ui.text(if (current) "♫" else (i + 1).toString(), row.x + 8f, row.y + 8f, 10f, if (current) RED else Theme.TEXT_TERTIARY)
            ui.text(ui.trim(p.name, row.width - 96f, 11f), row.x + 30f, row.y + 7f, 11f, if (current) RED else Theme.TEXT_PRIMARY)
            val d = fmtTime(p.durationMs)
            ui.text(d, row.right - 10f - ui.width(d, 10f), row.y + 8f, 10f, Theme.TEXT_TERTIARY)
        }
        ui.endClip()
        ctx.popOpacity()
        ctx.popClip()
    }

    private fun playPart(track: NeteaseTrack) {
        MusicPlayer.playQueue(listOf(track), 0, null)
        close()
    }

    /**
     * Handles clicks itself instead of going through the shared hit list: the drawer sits above
     * the track list, whose rows are registered earlier, so a shared hit would start playback
     * underneath the panel.
     */
    fun mouseDown(px: Int, py: Int): Boolean {
        if (!open && !loading) return false
        if (!panel.contains(px, py)) {
            close()
            return false
        }
        if (LayoutBounds(panel.right - 38f, panel.y + 6f, 30f, 30f).contains(px, py)) {
            close()
            return true
        }
        if (loading || !open) return true
        val rowH = 30f
        if (py >= listRect.y && py < listRect.y + listRect.height) {
            val index = scroll + ((py - listRect.y) / rowH).toInt()
            val part = parts.getOrNull(index)
            if (part != null) {
                playPart(part)
                return true
            }
        }
        return true
    }

    fun scroll(delta: Int): Boolean {
        if (!open) return false
        scroll = (scroll + delta).coerceAtLeast(0)
        return true
    }

    fun keyEsc(): Boolean {
        if (!open) return false
        close()
        return true
    }
}
