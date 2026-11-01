package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.LocalLibrary
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.notice.Notices
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import kotlin.math.max
import kotlin.math.min

/**
 * 本地音乐页：文件夹管理 + 刷新 + 筛选 + 列表。
 * 和在线音源用同一套排版（标题、行高 44、播放中的行红色高亮），只是数据来自 [LocalLibrary]。
 */
internal class LocalPage(private val screen: MusicScreen) {

    private val ui = screen.ui

    /** 滚轮 / 点击区域，由 MusicScreen 每帧设置 */
    var area = LayoutBounds()

    /** 0 全部 / 1 歌手 / 2 专辑 / 3 文件夹 */
    private var mode = 0
    private var value = ""

    private var picking = false

    /** 选择器在后台线程跑，结果先放这里，回到渲染线程再改状态 */
    @Volatile private var picked: String? = null
    @Volatile private var pickFailed: String? = null
    @Volatile private var pickDone = false

    /** 当前按文件夹筛选的值（侧栏用它高亮），没在按文件夹筛选时为空 */
    val folderFilter: String get() = if (mode == 3) value else ""

    /** 侧栏点某个文件夹：按这个文件夹筛选并回到本地音乐页 */
    fun filterByFolder(folder: String) {
        mode = 3
        value = folder
        scroll = 0
    }

    /** 添加/移除文件夹后回到「全部」，否则旧的筛选值会让新歌看起来没被识别 */
    fun resetFilter() {
        mode = 0
        value = ""
        scroll = 0
    }

    private var scroll = 0
    private var scrollPos = 0f
    private var rows = 10

    /** 筛选取值的缓存：大歌单每帧重算 artist 列表太浪费 */
    private var valuesMode = -1
    private var valuesRef: List<LocalLibrary.Entry>? = null
    private var valuesFoldersRef: List<String>? = null
    private var values: List<String> = emptyList()

    fun draw(body: LayoutBounds) {
        applyPicked()
        LocalLibrary.watch()
        val ctx = ui.ctx
        area = body
        var y = body.y

        // 标题行
        ui.text(I18n.text("Local music"), body.x, y, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val count = LocalLibrary.entries.size
        ui.text(
            I18n.format("%d local files", count) + if (LocalLibrary.folders.isEmpty()) "" else " · ${LocalLibrary.folders.size} " + I18n.text("folders"),
            body.x + 120f, y + 6f, 10f, Theme.TEXT_TERTIARY,
        )
        ui.pill(
            LayoutBounds(body.right - 118f, y - 6f, 110f, 28f), RED,
            if (LocalLibrary.scanning) I18n.text("Scanning…") else I18n.text("Refresh"), "↻",
        ) {
            if (!LocalLibrary.scanning) LocalLibrary.refresh()
        }
        y += 28f

        // 第一行：系统选择器 + 打开模组音乐目录
        val pickPill = LayoutBounds(body.x, y, 140f, 28f)
        ui.pill(pickPill, RED, I18n.text("Choose folder…"), "📂") { chooseFolder() }
        val openLabel = I18n.text("Open music folder")
        val openW = ui.width(openLabel, 10f) + 26f
        val openPill = LayoutBounds(pickPill.right + 8f, y + 1f, openW, 26f)
        ui.card(openPill, if (ui.hover(openPill)) Theme.CARD_HOVER else Theme.CARD, 8f)
        ui.text(openLabel, openPill.x + 13f, openPill.y + 8f, 10f, Theme.TEXT_SECONDARY)
        ui.hits += openPill to {
            if (!LocalLibrary.reveal(LocalLibrary.musicDir.absolutePath)) {
                Notices.post(I18n.text("Cannot open the folder"), Notices.WARN)
            }
        }
        val hint = ui.trim(
            I18n.text("Pick a folder, or drop mp3 files into the music folder and they show up here automatically."),
            body.right - openPill.right - 12f, 11f,
        )
        ui.text(hint, openPill.right + 12f, y + 8f, 11f, Theme.TEXT_TERTIARY)
        y += 34f

        y += 6f

        // 筛选行
        val modes = listOf(I18n.text("All"), I18n.text("By artist"), I18n.text("By album"), I18n.text("By folder"))
        var mx = body.x
        for ((i, label) in modes.withIndex()) {
            val w = ui.width(label, 10f) + 22f
            val chip = LayoutBounds(mx, y, w, 24f)
            if (mode == i) ui.card(chip, RED.copy(a = 0.22f), 12f) else ui.card(chip, Theme.CARD, 12f)
            ui.text(label, chip.x + 11f, chip.y + 6f, 10f, if (mode == i) RED else Theme.TEXT_SECONDARY)
            ui.hits += chip to {
                mode = if (mode == i) 0 else i
                value = ""
                scroll = 0
            }
            mx += w + 6f
        }
        y += 32f

        // 选中某个筛选维度后，列出可选的取值
        if (mode != 0) {
            val current = LocalLibrary.entries
            val folders = LocalLibrary.folders
            if (valuesMode != mode || valuesRef !== current || valuesFoldersRef !== folders) {
                valuesMode = mode
                valuesRef = current
                valuesFoldersRef = folders
                values = when (mode) {
                    1 -> current.flatMap { it.track.artists }.distinct()
                    2 -> current.map { it.album }.filter { it.isNotEmpty() }.distinct()
                    else -> LocalLibrary.folders
                }.sorted()
            }
            var vx = body.x
            val show = if (value.isEmpty()) values.take(9) else (listOf(value) + values.take(8))
            for (v in show.distinct().take(10)) {
                val label = if (mode == 3) LocalLibrary.label(v).ifEmpty { v } else v.ifEmpty { I18n.text("Unknown") }
                val w = min(ui.width(label, 10f) + 22f, 180f)
                if (vx + w > body.right) break
                val chip = LayoutBounds(vx, y, w, 22f)
                if (value == v) ui.card(chip, RED.copy(a = 0.22f), 11f) else ui.card(chip, Theme.CARD, 11f)
                ui.text(ui.trim(label, w - 16f, 10f), chip.x + 8f, chip.y + 5f, 10f, if (value == v) RED else Theme.TEXT_SECONDARY)
                ui.hits += chip to {
                    value = if (value == v) "" else v
                    scroll = 0
                }
                vx += w + 6f
            }
            y += 30f
        }

        // 空状态
        val shown = filtered()
        if (shown.isEmpty()) {
            val tip = when {
                LocalLibrary.folders.isEmpty() -> I18n.text("Add a folder of mp3 files to start listening offline.")
                LocalLibrary.scanning -> I18n.text("Scanning folders…")
                LocalLibrary.entries.isEmpty() -> I18n.text("No mp3 in these folders yet. Drop files in, or pick another folder.")
                else -> I18n.text("No mp3 in this filter.")
            }
            ui.text(tip, body.x, y + 18f, 12f, Theme.TEXT_SECONDARY)
            // 有歌但被筛选挡住时，给一个一键回到「全部」的入口
            if (LocalLibrary.entries.isNotEmpty() && !LocalLibrary.scanning) {
                val w = ui.width(I18n.text("Show all"), 11f) + 28f
                val clear = LayoutBounds(body.x, y + 40f, w, 26f)
                ui.pill(clear, RED, I18n.text("Show all"), null) { resetFilter() }
            }
            return
        }

        // 表头
        y += 4f
        ui.text("#", body.x + 12f, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text("TITLE"), body.x + 84f, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        val timeLabel = I18n.text("TIME")
        ui.text(timeLabel, body.right - 12f - ui.width(timeLabel, 10f, Theme.FONT_SEMIBOLD), y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ctx.drawShape(LayoutBounds(body.x, y + 16f, body.width, 1f), ShapeStyle(Theme.SEPARATOR))
        val listTop = y + 22f

        val rowH = 44f
        rows = max(1, ((body.bottom - listTop) / rowH).toInt())
        scroll = scroll.coerceIn(0, max(0, shown.size - rows))
        scrollPos = ui.easeScroll(scrollPos, scroll, rows * 3)
        val first = kotlin.math.floor(scrollPos).toInt()
        val frac = scrollPos - first
        val playing = MusicPlayer.current
        ui.beginClip(LayoutBounds(body.x - 4f, listTop - 2f, body.width + 8f, rows * rowH))
        for (r in 0..rows) {
            val vi = first + r
            if (vi < 0) continue
            if (vi >= shown.size) break
            val entry = shown[vi]
            val track = entry.track
            ui.itemAnim(r) {
                val row = LayoutBounds(body.x, listTop + (r - frac) * rowH, body.width, rowH - 4f)
                val isCur = playing != null && playing.key == track.key
                when {
                    isCur -> ctx.drawGlass(row, Theme.GLASS_SELECTION)
                    ui.hover(row) -> ui.card(row, Theme.CARD_HOVER, 10f)
                }
                if (isCur && MusicPlayer.state == MusicPlayer.State.PLAYING) {
                    ui.icon("♫", row.x + 18f, row.y + 20f, 12f, RED)
                } else {
                    ui.text("${vi + 1}", row.x + 12f, row.y + 15f, 11f, Theme.TEXT_TERTIARY)
                }
                ui.card(LayoutBounds(row.x + 44f, row.y + 6f, 32f, 32f), Theme.CARD, 6f)
                ui.icon("♪", row.x + 60f, row.y + 22f, 14f, Theme.TEXT_TERTIARY)
                val tx = row.x + 86f
                val avail = row.right - 60f - tx
                ui.text(ui.trim(track.name, avail, 13f), tx, row.y + 6f, 13f, if (isCur) RED else Theme.TEXT_PRIMARY)
                ui.text(
                    ui.trim(entry.folder + " · " + I18n.text("Local file"), avail, 10f),
                    tx, row.y + 24f, 10f, Theme.TEXT_SECONDARY,
                )
                val d = fmtTime(track.durationMs)
                ui.text(d, row.right - 12f - ui.width(d, 11f), row.y + 15f, 11f, Theme.TEXT_TERTIARY)
                ui.hits += row to { play(shown, vi) }
            }
        }
        ui.endClip()
    }

    /** 点一行：把当前筛选后的列表整体交给播放器，从这一首开始 */
    private fun play(shown: List<LocalLibrary.Entry>, index: Int) {
        MusicPlayer.playQueue(shown.map { it.track }, index, null)
    }

    fun filtered(): List<LocalLibrary.Entry> {
        if (mode == 3 && value.isNotEmpty() && LocalLibrary.folders.none { it.equals(value, ignoreCase = true) }) {
            mode = 0; value = ""
        }
        val all = LocalLibrary.entries
        return when {
            mode == 0 || value.isEmpty() -> all
            mode == 1 -> all.filter { value in it.track.artists }
            mode == 2 -> all.filter { it.album == value }
            else -> all.filter { it.root.equals(value, ignoreCase = true) }
        }
    }

    fun scrollBy(delta: Int) {
        scroll = max(0, scroll + delta)
    }

    /** 把后台选择器的结果搬回渲染线程再改状态，避免和绘制线程抢 mode/value */
    private fun applyPicked() {
        if (!pickDone) return
        pickDone = false
        val path = picked
        val failure = pickFailed
        picked = null
        pickFailed = null
        picking = false
        if (path.isNullOrBlank() && failure == null) return
        val error = failure ?: LocalLibrary.addFolder(path.orEmpty())
        if (error == null) resetFilter()
        Notices.post(
            error ?: I18n.format("Added folder %s", path.orEmpty()),
            if (error == null) Notices.OK else Notices.WARN,
        )
    }

    /** 调系统文件夹选择器，结果在后台线程拿，渲染线程不阻塞 */
    private fun chooseFolder() {
        if (picking) return
        picking = true
        Thread {
            val result = LocalLibrary.pickFolder()
            picked = result.path
            pickFailed = if (result.supported) null
            else I18n.text("This system has no folder picker — use Open music folder.")
            pickDone = true
        }.apply {
            isDaemon = true
            name = "Dango-FolderPick"
        }.start()
    }
}
