package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.BiliSource
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.notice.Notices
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.utils.Color
import java.util.concurrent.Executors

/**
 * Bilibili page: paste a link to import a video, a collection or a favorite folder.
 * Typing in game is painful, so the link always comes from the clipboard; the favorite
 * folders already listed in the sidebar open through the normal playlist page.
 */
internal class BiliPage(private val screen: MusicScreen) {
    private val ui = screen.ui
    private val worker = Executors.newFixedThreadPool(1) { r -> Thread(r, "Dango-BiliUI").apply { isDaemon = true } }

    var area = LayoutBounds(0f, 0f, 0f, 0f)
    @Volatile private var importedTitle = ""
    @Volatile private var imported: List<NeteaseTrack> = emptyList()
    @Volatile private var busy = false
    @Volatile private var pending: Pair<String, List<NeteaseTrack>>? = null
    @Volatile private var failed: String? = null
    private var scroll = 0f
    private var scrollPos = 0f

    fun scrollBy(delta: Float) {
        scroll = (scroll + delta).coerceAtLeast(0f)
    }

    fun draw(body: LayoutBounds) {
        area = body
        consume()

        ui.text(I18n.text("Bilibili"), body.x, body.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val sub = when {
            imported.isNotEmpty() -> I18n.format("%d songs · imported", imported.size)
            busy -> I18n.text("Reading the link…")
            else -> I18n.text("Copy a video, collection or favorite folder link, then press Paste link.")
        }
        ui.text(sub, body.x, body.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(importedTitle, body.width - 40f, 11f, Theme.FONT_REGULAR), body.x, body.y + 48f, 11f, Theme.TEXT_TERTIARY)

        var x = body.x
        val y = body.y + 70f
        x = pill(x, y, if (busy) I18n.text("Working…") else I18n.text("Paste link"), RED, "⧉", busy) { paste() }
        x = pill(x, y, I18n.text("Refresh"), Theme.CARD, "↻", false) { Library.reloadPlaylists(); Notices.post(I18n.text("Refreshing favorites"), Notices.INFO) }
        if (imported.isNotEmpty()) {
            pill(x, y, I18n.text("Play all"), RED, "▶", false) { MusicPlayer.playWholeQueue(imported, null) }
        }

        val listTop = y + 44f
        val list = LayoutBounds(body.x, listTop, body.width - 14f, body.bottom - listTop)
        val label = if (imported.isEmpty()) I18n.text("My favorites") else I18n.text("Imported songs")
        ui.text(label, list.x, list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        val content = LayoutBounds(list.x, list.y + 18f, list.width, list.height - 18f)

        if (imported.isNotEmpty()) drawImported(content) else drawFavorites(content)

        if (failed != null) {
            ui.text(ui.trim(I18n.message(failed!!), body.width - 20f, 11f, Theme.FONT_REGULAR), body.x, body.bottom - 14f, 11f, RED)
        }
    }

    /** Pills are laid out left to right; returns the x after the pill. */
    private fun pill(x: Float, y: Float, label: String, fill: Color, glyph: String, disabled: Boolean, action: () -> Unit): Float {
        val w = ui.width(label, 11f, Theme.FONT_MEDIUM) + 44f
        val b = LayoutBounds(x, y, w, 26f)
        ui.card(b, if (!disabled && ui.hover(b)) Theme.CARD_HOVER else fill, 13f)
        ui.icon(glyph, b.x + 15f, b.y + 13f, 11f, if (fill == RED) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
        ui.text(label, b.x + 27f, b.y + 8f, 11f, if (fill == RED) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        if (!disabled) ui.hits += b to action
        return b.right + 8f
    }

    private fun drawImported(list: LayoutBounds) {
        val rowH = 44f
        val rows = maxOf(1, (list.height / rowH).toInt())
        scroll = scroll.coerceIn(0f, maxOf(0f, (imported.size - rows).toFloat()))
        scrollPos = ui.easeScroll(scrollPos, scroll.toInt(), rows * 3)
        val first = kotlin.math.floor(scrollPos).toInt()
        val frac = scrollPos - first
        val clip = LayoutBounds(list.x - 4f, list.y - 2f, list.width + 8f, rows * rowH)
        ui.beginClip(clip)
        for (r in 0..rows) {
            val i = first + r
            if (i < 0) continue
            if (i >= imported.size) break
            val track = imported[i]
            val row = LayoutBounds(list.x, list.y + (r - frac) * rowH, list.width, rowH - 4f)
            val playing = MusicPlayer.current?.key == track.key
            if (playing) ui.card(row, Theme.CARD_HOVER, 10f) else if (ui.hover(row)) ui.card(row, Theme.CARD, 10f)
            ui.drawCover(LayoutBounds(row.x + 6f, row.y + 6f, 32f, 32f), CoverCache.track(track), 8f)
            ui.text(ui.trim(track.name, row.right - 96f - (row.x + 48f), 12f), row.x + 48f, row.y + 8f, 12f,
                if (playing) RED else Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            ui.text(ui.trim(track.artists.joinToString(" / "), row.right - 96f - (row.x + 48f), 10f), row.x + 48f, row.y + 25f, 10f, Theme.TEXT_SECONDARY)
            ui.text(fmtTime(track.durationMs), row.right - 46f, row.y + 15f, 10f, Theme.TEXT_TERTIARY)
            val del = LayoutBounds(row.right - 26f, row.y + 12f, 18f, 18f)
            if (ui.hover(del)) ui.card(del, Theme.CARD_HOVER, 9f)
            ui.text("×", del.x + 6f, del.y + 3f, 12f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            ui.hits += del to { removeAt(i) }
            ui.hits += row to {
                val all = imported
                if (i in all.indices) MusicPlayer.playQueue(all, i, null)
            }
        }
        ui.endClip()
    }

    private fun drawFavorites(list: LayoutBounds) {
        val playlists = Library.playlists
        if (Library.playlistsLoading && playlists.isEmpty()) {
            ui.text(I18n.text("Loading..."), list.x, list.y + 4f, 11f, Theme.TEXT_TERTIARY)
            return
        }
        if (playlists.isEmpty()) {
            val hint = if (BiliSource.libraryWarning.isNotEmpty()) I18n.text("Log in to see your favorites and private collections.") else I18n.text("No favorites yet")
            ui.text(hint, list.x, list.y + 4f, 11f, Theme.TEXT_TERTIARY)
            return
        }
        val rowH = 40f
        val rows = maxOf(1, (list.height / rowH).toInt())
        scroll = scroll.coerceIn(0f, maxOf(0f, (playlists.size - rows).toFloat()))
        scrollPos = ui.easeScroll(scrollPos, scroll.toInt(), rows * 3)
        val first = kotlin.math.floor(scrollPos).toInt()
        val frac = scrollPos - first
        ui.beginClip(LayoutBounds(list.x - 4f, list.y - 2f, list.width + 8f, rows * rowH))
        for (r in 0..rows) {
            val i = first + r
            if (i < 0) continue
            if (i >= playlists.size) break
            val p = playlists[i]
            val row = LayoutBounds(list.x, list.y + (r - frac) * rowH, list.width, rowH - 4f)
            if (ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 10f)
            ui.drawCover(LayoutBounds(row.x + 6f, row.y + 4f, 30f, 30f), CoverCache.playlist(p), 8f)
            ui.text(ui.trim(p.name, row.right - 90f - (row.x + 46f), 12f), row.x + 46f, row.y + 5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            ui.text(I18n.format("%d songs", p.trackCount), row.x + 46f, row.y + 21f, 10f, Theme.TEXT_SECONDARY)
            ui.hits += row to { screen.openPlaylist(p, Page.BILI) }
        }
        ui.endClip()
    }

    /** Drops one row; the imported list is a snapshot, so this never touches the real folder. */
    private fun removeAt(index: Int) {
        val all = imported
        if (index !in all.indices) return
        imported = all.filterIndexed { i, _ -> i != index }
        if (imported.isEmpty()) importedTitle = ""
        scroll = scroll.coerceAtMost(maxOf(0f, (imported.size - 1).toFloat()))
        scrollPos = scroll
    }

    private fun consume() {
        val done = pending
        if (done != null) {
            pending = null
            busy = false
            importedTitle = done.first
            val known = imported.mapTo(HashSet()) { it.key }
            val fresh = done.second.filter { known.add(it.key) }
            val total = imported.size + fresh.size
            imported = imported + fresh
            scroll = maxOf(0f, (total - 1).toFloat())
            scrollPos = scroll
            // The import is also a sidebar playlist now; ask the library to pick it up.
            Library.reloadPlaylists()
            Notices.post(I18n.format("Imported %d songs", done.second.size), Notices.OK)
        }
        val error = failed
        if (error != null) {
            busy = false
        }
    }

    private fun paste() {
        if (busy) return
        val text = runCatching { DangoClient.platform.clipboard }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) {
            failed = null
            Notices.post(I18n.text("The clipboard is empty"), Notices.WARN)
            return
        }
        busy = true
        failed = null
        worker.execute {
            try {
                val result = BiliSource.importLink(text)
                if (result == null) {
                    failed = "Not a Bilibili video, collection or favorite link"
                } else {
                    pending = result
                }
            } catch (e: Exception) {
                DangoLogger.warn("Bilibili import failed: {}", e.toString())
                failed = e.message ?: "Import failed"
            }
        }
    }
}
