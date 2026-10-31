package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.party.TogetherProtocol
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 一起听歌：房间列表 / 创建 / 用房间码加入，进房间后是成员、共享播放列表和房主权限。
 * 状态全在服务器上，这里只是入口和显示：点击只会发请求，真正切歌、暂停由服务端确认后再广播。
 */
internal class PartyPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** 房间码输入框正在输入（由 [MusicScreen] 汇总进"有没有输入框在打字"） */
    var codeFocused = false
        private set

    /** 房间码输入框区域（点击其它地方时用来取消焦点） */
    var codeRect: LayoutBounds? = null
        private set

    private var code = ""

    /** 点开输入框以外的区域时取消焦点 */
    fun blurCode() {
        codeFocused = false
    }

    /** 房间码输入框拿走按键；没在输入 / 已经在房间里时返回 false */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!codeFocused || ListenParty.inRoom) return false
        when {
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> submitCode()
            keyCode == Keys.ESCAPE -> codeFocused = false
            keyCode == Keys.BACK -> if (code.isNotEmpty()) code = code.dropLast(1)
            typedChar.isLetterOrDigit() -> if (code.length < 5) code += typedChar.uppercaseChar()
        }
        return true
    }

    private fun submitCode() {
        val typed = code.trim()
        codeFocused = false
        if (typed.isEmpty()) return
        ListenParty.join(typed)
        code = ""
    }

    // ---------------------------------------------------------------- 绘制

    fun draw(area: LayoutBounds) {
        val x = area.x
        var y = area.y
        ui.text(I18n.text("Listen Together"), x, y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        ui.text(subtitle(), x, y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        y += 64f
        codeRect = null
        if (ListenParty.inRoom) drawRoom(area, x, y) else drawLobby(area, x, y)
    }

    private fun subtitle(): String = when (ListenParty.role) {
        ListenParty.Role.NONE -> I18n.text("Play the same song in sync with everyone on this server")
        ListenParty.Role.HOST -> I18n.text("You are the host · share the room code so others can join")
        ListenParty.Role.MEMBER -> I18n.text("Following ") + ListenParty.hostName + when {
            ListenParty.canSkip && ListenParty.canPause -> I18n.text(" · you can control playback too")
            ListenParty.canSkip -> I18n.text(" · you can change songs and add songs")
            ListenParty.canAdd -> I18n.text(" · you can add songs to the shared list")
            else -> I18n.text(" · only the host controls playback")
        }
    }

    private fun drawLobby(area: LayoutBounds, x: Float, y0: Float) {
        var y = y0
        val width = area.right - x
        ListenParty.browse()

        if (ListenParty.unsupported) {
            val lines = ui.wrap2(
                I18n.text("This server does not have DangoMusic installed, or the version is different, so Listen Together is unavailable."),
                min(width, 560f), 12f, Theme.FONT_MEDIUM,
            )
            lines.forEachIndexed { i, line -> ui.text(line, x, y + i * 16f, 12f, RED, Theme.FONT_MEDIUM) }
            y += lines.size * 16f + 14f
        }

        // 创建房间 / 输入房间码加入
        val createW = 168f
        ui.pill(
            LayoutBounds(x, y, createW, 34f), RED,
            if (ListenParty.busy) I18n.text("Working...") else I18n.text("Create room"), "♬",
        ) { ListenParty.createRoom() }

        val box = LayoutBounds(x + createW + 12f, y, 118f, 34f)
        codeRect = box
        ui.card(box, if (codeFocused) Theme.TRACK else Theme.CARD, 9f)
        val shown = code.ifEmpty { I18n.text("Room code") }
        val shownWidth = ui.width(shown, 14f, Theme.FONT_SEMIBOLD)
        val textX = box.x + (box.width - shownWidth) / 2f
        val textY = box.y + box.height / 2f - 14f * 0.39f
        ui.text(shown, textX, textY, 14f, if (codeFocused) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        if (codeFocused) {
            // 闪烁光标
            val caretX = if (code.isEmpty()) box.x + 18f else textX + shownWidth + 2f
            ui.ctx.drawShape(
                LayoutBounds(caretX, box.y + 9f, 1.5f, 16f),
                ShapeStyle(Color(1f, 1f, 1f, 0.9f), 0.75f),
            )
        }
        ui.hits += box to { codeFocused = true }

        ui.pill(LayoutBounds(box.right + 10f, y, 92f, 34f), Theme.TRACK, I18n.text("Join")) { submitCode() }
        y += 42f
        ui.text(I18n.text("Ask the host for the 5-character room code."), x, y, 10f, Theme.TEXT_TERTIARY)
        y += 22f

        // 房间列表
        ui.text(
            I18n.text("Rooms on this server") + " · " + ListenParty.rooms.size,
            x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD,
        )
        y += 18f
        val rooms = ListenParty.rooms
        if (rooms.isEmpty()) {
            ui.text(
                I18n.text("No rooms yet — start one and friends can join."),
                x, y + 6f, 12f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM,
            )
            y += 30f
        }
        for (room in rooms.take(5)) {
            val row = LayoutBounds(x, y, width, 42f)
            ui.card(row, if (ui.hover(row)) Theme.CARD_HOVER else Theme.CARD, 10f)
            ui.text(room.code, row.x + 12f, row.y + 13f, 13f, RED, Theme.FONT_SEMIBOLD)
            ui.text(
                ui.trim(room.hostName, 150f, 11f), row.x + 82f, row.y + 14f,
                11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM,
            )
            ui.text(
                room.count.toString() + " " + I18n.text("people"),
                row.x + 236f, row.y + 14f, 11f, Theme.TEXT_TERTIARY,
            )
            val trackLabel = if (room.playing) "▶ " else ""
            ui.text(
                trackLabel + ui.trim(room.trackName.ifEmpty { I18n.text("Not playing yet") }, 210f, 11f),
                row.x + 302f, row.y + 14f, 11f, Theme.TEXT_SECONDARY,
            )
            ui.pill(LayoutBounds(row.right - 82f, row.y + 8f, 68f, 26f), Theme.TRACK, I18n.text("Join")) {
                ListenParty.join(room.code)
            }
            y += 46f
        }
        y += 4f

        drawStatus(x, y, area)
        ui.text(
            I18n.text("Everyone streams from their own music account. VIP and region permissions apply to each member."),
            x, area.bottom - 12f, 10f, Theme.TEXT_TERTIARY,
        )
    }

    private fun drawRoom(area: LayoutBounds, x: Float, y0: Float) {
        var y = y0
        val width = area.right - x

        // 房间码 / 房主
        ui.card(LayoutBounds(x, y, width, 52f), Theme.CARD, 12f)
        ui.text(I18n.text("ROOM"), x + 14f, y + 8f, 9f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ui.text(ListenParty.roomCode, x + 14f, y + 22f, 19f, RED, Theme.FONT_DISPLAY)
        ui.text(
            I18n.text("Host") + " · " + ui.trim(ListenParty.hostName, 200f, 12f),
            x + 130f, y + 24f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD,
        )
        ui.text(
            ListenParty.members.size.toString() + " " + I18n.text("people"),
            x + 130f, y + 8f, 10f, Theme.TEXT_TERTIARY,
        )
        y += 60f

        // 成员
        ui.text(
            I18n.text("Members") + " · " + ListenParty.members.size,
            x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD,
        )
        y += 15f
        val names = ListenParty.members.joinToString(" · ") { m ->
            if (m.uid == ListenParty.uid) m.name + " (" + I18n.text("you") + ")" else m.name
        }
        ui.text(
            ui.trim(names.ifEmpty { I18n.text("Nobody here yet") }, width, 12f),
            x, y, 12f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM,
        )
        y += 22f

        // 共享播放列表
        ui.text(
            I18n.text("Shared playlist") + " · " + ListenParty.queue.size,
            x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD,
        )
        y += 17f
        val queue = ListenParty.queue
        val current = ListenParty.current
        if (queue.isEmpty()) {
            ui.text(
                I18n.text("The list is empty — the first song the host plays shows up here."),
                x, y + 2f, 12f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM,
            )
            y += 26f
        } else {
            val rows = min(5, max(3, ((area.bottom - y - 150f) / 30f).toInt()))
            val from = if (current + 1 > rows) current + 1 - rows else 0
            val last = min(queue.size, from + rows)
            for (i in from until last) {
                val track = queue[i]
                val row = LayoutBounds(x, y, width, 28f)
                val playing = i == current
                if (playing) ui.card(row, Theme.CARD_HOVER, 8f) else if (ui.hover(row)) ui.card(row, Theme.CARD, 8f)
                if (playing) ui.ctx.drawShape(LayoutBounds(row.x + 3f, row.y + 6f, 3f, 16f), ShapeStyle(RED, 1.5f))
                ui.text((i + 1).toString(), row.x + 12f, row.y + 9f, 10f, Theme.TEXT_TERTIARY)
                ui.text(
                    ui.trim(track.name, width - 180f, 12f), row.x + 34f, row.y + 7f, 12f,
                    if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM,
                )
                ui.text(fmtTime(track.durationMs), row.right - 92f, row.y + 8f, 11f, Theme.TEXT_TERTIARY)
                if (ListenParty.isHost) {
                    val remove = LayoutBounds(row.right - 60f, row.y + 4f, 22f, 20f)
                    ui.text("×", remove.x + 6f, remove.y + 2f, 14f, Theme.TEXT_TERTIARY)
                    ui.hits += remove to { ListenParty.removeFromQueue(i) }
                }
                if (ListenParty.canSkip) {
                    ui.hits += LayoutBounds(row.x, row.y, width - (if (ListenParty.isHost) 76f else 100f), row.height) to {
                        ListenParty.jumpTo(i)
                    }
                }
                y += 30f
            }
            if (queue.size > last) {
                ui.text(
                    "+ " + (queue.size - last) + " " + I18n.text("more songs"),
                    x + 34f, y + 4f, 10f, Theme.TEXT_TERTIARY,
                )
                y += 12f
            }
        }
        y += 6f

        // 房主：成员权限
        if (ListenParty.isHost) {
            ui.text(I18n.text("Allow members to"), x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 15f
            val perms = ListenParty.perms
            permission(
                x, y, I18n.text("Change song"), (perms and TogetherProtocol.PERM_SKIP) != 0, "skip",
            ) { ListenParty.setPerms(perms xor TogetherProtocol.PERM_SKIP) }
            permission(
                x + 180f, y, I18n.text("Pause & seek"), (perms and TogetherProtocol.PERM_PAUSE) != 0, "pause",
            ) { ListenParty.setPerms(perms xor TogetherProtocol.PERM_PAUSE) }
            permission(
                x + 380f, y, I18n.text("Add songs"), (perms and TogetherProtocol.PERM_ADD) != 0, "add",
            ) { ListenParty.setPerms(perms xor TogetherProtocol.PERM_ADD) }
            y += 26f
        }

        // 按钮
        ui.pill(LayoutBounds(x, y, 104f, 32f), Theme.TRACK, I18n.text("Sync now"), "↻") { ListenParty.resync() }
        ui.pill(
            LayoutBounds(x + 114f, y, 124f, 32f), RED,
            if (ListenParty.isHost) I18n.text("End room") else I18n.text("Leave room"),
        ) { ListenParty.leave() }
        y += 40f

        drawStatus(x, y, area)
    }

    private fun permission(x: Float, y: Float, label: String, on: Boolean, key: String, action: () -> Unit) {
        ui.toggleSwitch(LayoutBounds(x, y, 40f, 22f), on, LayoutBounds(0f, 0f, 100000f, 100000f), key, action)
        ui.text(label, x + 50f, y + 3f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
    }

    private fun drawStatus(x: Float, y0: Float, area: LayoutBounds) {
        val text = ListenParty.status
        if (text.isEmpty()) return
        val lines = ui.wrap2(I18n.message(text), min(area.right - x, 560f), 12f, Theme.FONT_MEDIUM)
        lines.forEachIndexed { i, line -> ui.text(line, x, y0 + i * 16f, 12f, RED, Theme.FONT_MEDIUM) }
    }
}
