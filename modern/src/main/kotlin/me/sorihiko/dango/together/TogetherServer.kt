package me.sorihiko.dango.together

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.party.PartyTrackCodec
import me.sorihiko.dango.party.TogetherProtocol
import me.sorihiko.dango.party.TogetherProtocol.RoomSnapshot
import me.sorihiko.dango.party.flag
import me.sorihiko.dango.party.num
import me.sorihiko.dango.party.str
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.random.Random

/**
 * 服务端的一起听歌房间管理。
 *
 * 服务端是唯一权威：房间、成员、共享播放列表、当前歌曲、进度、成员权限都记在这里。
 * 服务端只维护"元数据"：谁在放哪首歌、放到哪、是不是暂停；音频永远在各自客户端解码播放。
 *
 * 权威性：
 *   - 播放列表和当前歌曲由服务端推进（成员点歌 / 切歌 / 一首歌自然放完）；
 *   - 房主只上报自己播放器的真实进度（[TogetherProtocol.HOST_STATE]），服务端加上时间戳后广播；
 *   - 成员的操作（暂停 / 继续 / 拖进度）先到服务端，检查权限后转给房主执行；
 *   - 客户端任何情况下都不直接改全服状态。
 *
 * 这个类不依赖具体加载器：Fabric / Forge 的胶水层各注册一条通道，
 * 把原始 JSON 字符串交给 [handle]；[install] 注入"把消息发给某个玩家"的实现。
 */
object TogetherServer {

    /** 一个房间最多多少人（防刷） */
    private const val MAX_MEMBERS = 32
    /** 一个房间的播放列表最多多少首 */
    private const val MAX_QUEUE = TogetherProtocol.MAX_QUEUE
    /** 中转缓冲：最近几片，晚进来的人靠它追上当前进度 */
    private const val RELAY_BUFFER = 24
    private const val CODE_LEN = 5
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private class Room(val code: String, val host: UUID, var hostName: String) {
        var perms: Int = TogetherProtocol.PERM_ALL
        /** 加入顺序：房主排第一 */
        val members = LinkedHashMap<UUID, String>()
        /** 共享播放列表 */
        val queue = ArrayList<NeteaseTrack>()
        /** 当前放到第几首；-1 表示还没开始 */
        var current: Int = -1
        /** 上一次上报时的进度 */
        var pos: Long = 0L
        var playing: Boolean = false
        /** 上报进度的服务端时间 */
        var stamp: Long = 0L
        /** 房主选的中转码率（kbps），0 = 原始码率不转码 */
        var quality: Int = 0
        /** 正在听中转的人 */
        val relayMembers = LinkedHashSet<UUID>()
        /** 最近的中转分片（序号 + 歌曲标识 + base64） */
        val relayBuffer = ArrayDeque<Pair<String, String>>()

        val track: NeteaseTrack? get() = queue.getOrNull(current)

        /** 按服务端时间推算现在的进度 */
        fun position(now: Long): Long =
            if (!playing) pos.coerceAtLeast(0L) else (pos + (now - stamp).coerceAtLeast(0L)).coerceAtLeast(0L)

        fun snapshot(now: Long): RoomSnapshot = RoomSnapshot(
            code = code,
            hostUid = host.toString(),
            hostName = hostName,
            perms = perms,
            members = members.map { TogetherProtocol.Member(it.key.toString(), it.value) },
            queue = queue.toList(),
            current = current,
            positionMs = position(now),
            playing = playing,
            track = track,
            quality = quality,
            maxKbps = TogetherConfig.maxBitrate,
            allowLocal = TogetherConfig.allowLocalMusic,
        )
    }

    private val rooms = LinkedHashMap<String, Room>()
    /** 玩家 → 房间码 */
    private val roomOf = HashMap<UUID, String>()
    /** 正在分片上传的点歌：玩家 → 前面几片已经加进去的歌曲数 */
    private val addBatches = HashMap<UUID, Int>()
    /** 已经提醒过版本不一致的玩家，避免每条包都弹一次提示 */
    private val versionWarned = HashSet<UUID>()
    private val random = Random(System.nanoTime())

    @Volatile
    private var sender: ((ServerPlayer, String) -> Unit)? = null
    /** 服务端配置只在第一次用到时读一次（读取需要 MinecraftServer） */
    private var configured = false

    /** 加载器注入"把一条 JSON 发给某个玩家"的实现 */
    /** 服务器启动时就把配置读出来（没进房间时也会生成配置文件） */
    fun ensureConfigured(server: MinecraftServer) {
        if (configured) return
        configured = true
        TogetherConfig.load(server)
    }

    fun install(send: (ServerPlayer, String) -> Unit) {
        sender = send
    }

    /** 服务器停止时清空 */
    fun reset() {
        rooms.clear()
        roomOf.clear()
        addBatches.clear()
        versionWarned.clear()
        configured = false
        TogetherConfig.reset()
    }

    /** 玩家断线：从房间里移除（房主断线 = 房间结束） */
    fun playerLeft(player: ServerPlayer) {
        leave(player, announce = true)
    }

    // ---------------------------------------------------------------- 消息分发

    fun handle(player: ServerPlayer, raw: String) {
        if (raw.length > TogetherProtocol.MAX_BYTES) return
        if (!configured) {
            ensureConfigured(player.level().getServer()!!)
        }
        val message = TogetherProtocol.parse(raw) ?: return
        if (message.num("v").toInt() != TogetherProtocol.VERSION) {
            if (versionWarned.add(player.uuid)) error(player, "版本不一致：服务器和客户端需要同一个版本的 DangoMusic")
            return
        }
        when (TogetherProtocol.type(message)) {
            TogetherProtocol.LIST -> sendRooms(player)
            TogetherProtocol.CREATE -> create(player)
            TogetherProtocol.JOIN -> join(player, message.str("code").trim().uppercase())
            TogetherProtocol.LEAVE -> leave(player, announce = true)
            TogetherProtocol.ADD -> add(player, message)
            TogetherProtocol.PLAY_NOW -> playNow(player, message)
            TogetherProtocol.REMOVE -> remove(player, message)
            TogetherProtocol.JUMP -> jump(player, message)
            TogetherProtocol.NEXT -> step(player, 1)
            TogetherProtocol.PREV -> step(player, -1)
            TogetherProtocol.INTENT -> intent(player, message)
            TogetherProtocol.SET_PERMS -> setPerms(player, message)
            TogetherProtocol.SET_QUALITY -> setQuality(player, message)
            TogetherProtocol.RELAY_REQ -> relayRequest(player, message)
            TogetherProtocol.RELAY_DATA -> relayData(player, message)
            TogetherProtocol.HOST_STATE -> hostState(player, message)
            TogetherProtocol.ENDED -> ended(player)
            TogetherProtocol.RESYNC -> room(player)?.let { sendRoom(player, it) }
        }
    }

    // ---------------------------------------------------------------- 房间进出

    private fun create(player: ServerPlayer) {
        if (room(player) != null) {
            error(player, "你已经在房间里了")
            return
        }
        val code = newCode()
        val name = player.gameProfile.name.take(64)
        val room = Room(code, player.uuid, name)
        room.members[player.uuid] = name
        rooms[code] = room
        roomOf[player.uuid] = code
        DangoLogger.info("Listen Together: {} created room {}", name, code)
        sendRoom(player, room)
    }

    private fun join(player: ServerPlayer, code: String) {
        if (code.isEmpty()) {
            error(player, "请先输入房间码")
            return
        }
        val room = rooms[code]
        if (room == null) {
            error(player, "房间不存在，可能已经结束了")
            return
        }
        if (room.members.containsKey(player.uuid)) {
            sendRoom(player, room)
            return
        }
        if (room.members.size >= MAX_MEMBERS) {
            error(player, "房间满了（最多 $MAX_MEMBERS 人）")
            return
        }
        // 先离开原来的房间，再加进新的
        leave(player, announce = false)
        val name = player.gameProfile.name.take(64)
        room.members[player.uuid] = name
        roomOf[player.uuid] = room.code
        DangoLogger.info("Listen Together: {} joined room {}", name, room.code)
        sendRoom(player, room)
        broadcastMembers(player.level().getServer()!!, room)
    }

    private fun leave(player: ServerPlayer, announce: Boolean) {
        val code = roomOf.remove(player.uuid) ?: return
        val room = rooms[code] ?: return
        val wasHost = room.host == player.uuid
        addBatches.remove(player.uuid)
        room.members.remove(player.uuid)
        room.relayMembers.remove(player.uuid)
        if (wasHost) {
            closeRoom(player.level().getServer()!!, room, "房主离开了一起听歌")
        } else {
            if (room.members.isEmpty()) {
                rooms.remove(code)
            } else if (announce) {
                broadcastMembers(player.level().getServer()!!, room)
            }
        }
    }

    private fun closeRoom(server: MinecraftServer, room: Room, reason: String) {
        rooms.remove(room.code)
        val message = TogetherProtocol.message(TogetherProtocol.CLOSED) { addProperty("message", reason) }
        for (uid in room.members.keys.toList()) {
            roomOf.remove(uid)
            addBatches.remove(uid)
            versionWarned.remove(uid)
            server.playerList.getPlayer(uid)?.let { send(it, message) }
        }
        room.members.clear()
        DangoLogger.info("Listen Together: room {} closed", room.code)
    }

    // ---------------------------------------------------------------- 共享播放列表

    private fun add(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (!can(room, player, TogetherProtocol.PERM_ADD)) {
            addBatches.remove(player.uuid)
            error(player, "房主没有开放点歌权限")
            return
        }
        val tracks = readTracks(message)
        if (tracks.isEmpty()) return
        // 同一首歌只保留一份（客户端双击 / 多个成员同时点同一首）
        val know = room.queue.mapTo(HashSet()) { it.key }
        var added = 0
        var full = false
        for (track in tracks) {
            if (room.queue.size >= MAX_QUEUE) {
                full = true
                break
            }
            if (!know.add(track.key)) continue
            room.queue.add(track)
            added++
        }
        // 客户端把长歌单拆成多片发；等最后一片到了再广播一次，免得每片都推全服。
        // 注意"这一片全被去重掉"不等于"整批没加进去"：前面几片加的要记着。
        if (message.flag("more")) {
            if (added > 0) addBatches[player.uuid] = (addBatches[player.uuid] ?: 0) + added
            return
        }
        val batched = addBatches.remove(player.uuid) ?: 0
        val total = batched + added
        if (total == 0) {
            error(
                player,
                if (full) "共享播放列表最多 $MAX_QUEUE 首，先删掉几首再加吧"
                else "这些歌已经在共享播放列表里了",
            )
            return
        }
        val needsStart = room.current < 0 || room.current >= room.queue.size
        if (needsStart && room.queue.isNotEmpty()) {
            room.current = 0
            room.pos = 0L
            room.playing = true
            room.stamp = System.currentTimeMillis()
        }
        broadcastQueue(player.level().getServer()!!, room)
        if (needsStart) broadcastState(player.level().getServer()!!, room, except = null)
        if (full) {
            // 加了一部分但没加完：队列已经广播，再单独提示一句
            error(player, "共享播放列表最多 $MAX_QUEUE 首，后面的歌没有再放进去")
        }
    }

    /**
     * 又在歌单里点了一首已经在共享列表里的歌：把这首挪到队首立刻播放
     * （如果放的正是这首，就从头重放），而不是提示"已经在列表里了"。
     */
    private fun playNow(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (!can(room, player, TogetherProtocol.PERM_SKIP)) {
            error(player, "房主没有开放切歌权限")
            return
        }
        val track = TogetherProtocol.readTrack(message) ?: return
        val existing = room.queue.indexOfFirst { it.key == track.key }
        if (existing >= 0) room.queue.removeAt(existing)
        room.queue.add(0, track)
        while (room.queue.size > MAX_QUEUE) room.queue.removeAt(room.queue.lastIndex)
        room.current = 0
        room.pos = 0L
        room.playing = true
        room.stamp = System.currentTimeMillis()
        broadcastQueue(player.level().getServer()!!, room)
        broadcastState(player.level().getServer()!!, room, except = null, restart = true)
    }

    private fun remove(player: ServerPlayer, message: JsonObject) {
        val server = player.level().getServer()!!
        val room = room(player) ?: return
        if (!isHost(room, player)) {
            error(player, "只有房主能删除播放列表里的歌")
            return
        }
        val index = message.num("index").toInt()
        if (index !in room.queue.indices) return
        room.queue.removeAt(index)
        val now = System.currentTimeMillis()
        if (room.queue.isEmpty()) {
            room.current = -1
            room.pos = 0L
            room.playing = false
            room.stamp = now
            broadcastQueue(server, room)
            broadcastState(server, room, except = null)
            return
        }
        if (index < room.current) {
            room.current--
        } else if (index == room.current) {
            if (room.current > room.queue.lastIndex) room.current = room.queue.lastIndex
            room.pos = 0L
            room.stamp = now
            broadcastQueue(server, room)
            broadcastState(server, room, except = null)
            return
        }
        broadcastQueue(server, room)
    }

    private fun jump(player: ServerPlayer, message: JsonObject) {
        val server = player.level().getServer()!!
        val room = room(player) ?: return
        if (!can(room, player, TogetherProtocol.PERM_SKIP)) {
            error(player, "房主没有开放切歌权限")
            return
        }
        val index = message.num("index").toInt()
        if (index !in room.queue.indices) return
        room.current = index
        room.pos = 0L
        room.playing = true
        room.stamp = System.currentTimeMillis()
        broadcastQueue(server, room)
        broadcastState(server, room, except = null)
    }

    /** 切歌：delta = 1 下一首，-1 上一首 */
    private fun step(player: ServerPlayer, delta: Int) {
        val server = player.level().getServer()!!
        val room = room(player) ?: return
        if (!can(room, player, TogetherProtocol.PERM_SKIP)) {
            error(player, "房主没有开放切歌权限")
            return
        }
        if (room.queue.isEmpty()) return
        val target = room.current + delta
        if (target !in room.queue.indices) {
            if (delta > 0) {
                // 已经是最后一首：停在结尾
                room.playing = false
                room.pos = room.track?.durationMs ?: 0L
                room.stamp = System.currentTimeMillis()
                broadcastState(server, room, except = null)
            }
            return
        }
        room.current = target
        room.pos = 0L
        room.playing = true
        room.stamp = System.currentTimeMillis()
        broadcastQueue(server, room)
        broadcastState(server, room, except = null)
    }

    /** 房主报告一首歌自然放完了，服务端推进到下一首 */
    private fun ended(player: ServerPlayer) {
        val server = player.level().getServer()!!
        val room = room(player) ?: return
        if (!isHost(room, player)) return
        if (room.queue.isEmpty()) return
        val now = System.currentTimeMillis()
        if (room.current + 1 < room.queue.size) {
            room.current++
            room.pos = 0L
            room.playing = true
            room.stamp = now
            broadcastQueue(server, room)
            broadcastState(server, room, except = null)
        } else {
            room.playing = false
            room.pos = room.track?.durationMs ?: 0L
            room.stamp = now
            broadcastState(server, room, except = null)
        }
    }

    // ---------------------------------------------------------------- 房主上报进度

    private fun hostState(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (!isHost(room, player)) return
        val track = (message.get("track") as? JsonObject)?.let { PartyTrackCodec.read(it) } ?: return
        if (room.track?.key != track.key) {
            // 房主放的歌和服务端记录的不一样：以服务端播放列表为准，找到就纠正
            val index = room.queue.indexOfFirst { it.key == track.key }
            if (index < 0) return
            room.current = index
        }
        val pos = message.num("pos").coerceAtLeast(0L).let { if (track.durationMs > 0) it.coerceAtMost(track.durationMs) else it }
        room.pos = pos
        room.playing = message.flag("playing")
        room.stamp = System.currentTimeMillis()
        broadcastState(player.level().getServer()!!, room, except = player.uuid)
    }

    // ---------------------------------------------------------------- 成员操作 / 权限

    private fun intent(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (isHost(room, player)) return
        val op = message.str("op")
        if (op !in TogetherProtocol.OPS) return
        if ((room.perms and TogetherProtocol.PERM_PAUSE) == 0) {
            error(player, "房主没有开放暂停/拖进度权限")
            return
        }
        val host = player.level().getServer()!!.playerList.getPlayer(room.host)
        if (host == null) {
            error(player, "房主暂时不在，稍后再试")
            return
        }
        val forward = TogetherProtocol.message(TogetherProtocol.COMMAND) {
            addProperty("op", op)
            addProperty("pos", message.num("pos").coerceAtLeast(0L))
        }
        send(host, forward)
    }

    private fun setPerms(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (!isHost(room, player)) return
        room.perms = message.num("perms").toInt() and TogetherProtocol.PERM_ALL
        broadcastMembers(player.level().getServer()!!, room)
    }

    // ---------------------------------------------------------------- 发快照 / 广播

    private fun sendRoom(player: ServerPlayer, room: Room) {
        val now = System.currentTimeMillis()
        val out = TogetherProtocol.message(TogetherProtocol.ROOM) {
            TogetherProtocol.writeRoom(this, room.snapshot(now))
        }
        send(player, out)
        // 快照里不放整队，紧跟一条（或多条）分片的队列
        for (chunk in TogetherProtocol.queueMessages(room.queue.toList(), room.current)) send(player, chunk)
    }

    private fun sendRooms(player: ServerPlayer) {
        val infos = rooms.values.map { room ->
            TogetherProtocol.RoomInfo(
                code = room.code,
                hostName = room.hostName,
                count = room.members.size,
                trackName = room.track?.name ?: "",
                playing = room.playing,
            )
        }
        val out = TogetherProtocol.message(TogetherProtocol.ROOMS) {
            TogetherProtocol.writeRooms(this, infos)
        }
        send(player, out)
    }

    private fun broadcastQueue(server: MinecraftServer, room: Room) {
        for (out in TogetherProtocol.queueMessages(room.queue.toList(), room.current)) {
            broadcast(server, room, out, except = null)
        }
    }

    private fun broadcastState(server: MinecraftServer, room: Room, except: UUID?, restart: Boolean = false) {
        val now = System.currentTimeMillis()
        val track = room.track
        val out = TogetherProtocol.message(TogetherProtocol.STATE) {
            track?.let { TogetherProtocol.writeTrack(this, "track", it) }
            addProperty("pos", room.position(now))
            addProperty("playing", room.playing)
            addProperty("current", room.current)
            if (restart) addProperty("restart", true)
        }
        broadcast(server, room, out, except)
    }

    private fun broadcastMembers(server: MinecraftServer, room: Room) {
        val out = TogetherProtocol.message(TogetherProtocol.MEMBERS) {
            TogetherProtocol.writeMembers(this, room.host.toString(), room.hostName, room.perms,
                room.members.map { TogetherProtocol.Member(it.key.toString(), it.value) })
        }
        broadcast(server, room, out, except = null)
    }

    // ---------------------------------------------------------------- 小工具

    private fun room(player: ServerPlayer): Room? = roomOf[player.uuid]?.let { rooms[it] }

    private fun isHost(room: Room, player: ServerPlayer): Boolean = room.host == player.uuid

    private fun can(room: Room, player: ServerPlayer, perm: Int): Boolean =
        isHost(room, player) || (room.perms and perm) != 0

    private fun readTracks(message: JsonObject): List<NeteaseTrack> {
        val array = message.get("tracks") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let { PartyTrackCodec.read(it) }
        }
    }

    // ---------------------------------------------------------------- 本地音乐中转

    /** 房主选中转码率：0 = 原始码率，其它是 kbps；超过服务端上限时拒绝 */
    private fun setQuality(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (room.host != player.uuid) { error(player, "只有房主能选中转码率"); return }
        val wanted = message.num("q").toInt()
        if (wanted !in TogetherProtocol.QUALITY_TIERS) { error(player, "没有这个码率挡位"); return }
        if (wanted != 0 && TogetherConfig.maxBitrate > 0 && wanted > TogetherConfig.maxBitrate) {
            error(player, "服务器限制中转码率不超过 ${TogetherConfig.maxBitrate} kbps")
            return
        }
        val changed = room.quality != wanted
        room.quality = wanted
        val server = player.level().getServer()!!
        // 换挡位等于换了一条编码流，成员手里的旧分片作废，等房主按新挡位重新发
        if (changed) {
            room.relayBuffer.clear()
            broadcast(server, room, TogetherProtocol.message(TogetherProtocol.RELAY_STOP), null)
        }
        broadcast(server, room, TogetherProtocol.message(TogetherProtocol.RELAY_STATE) {
            addProperty("on", TogetherConfig.allowLocalMusic)
            addProperty("q", room.quality)
        }, null)
    }

    /** 成员请求听中转：把已经缓存的几片先给他，剩下的等房主继续发 */
    private fun relayRequest(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (!TogetherConfig.allowLocalMusic) { error(player, "服务器没有开启本地音乐共享"); return }
        if (room.host == player.uuid) return
        room.relayMembers += player.uuid
        // 只补发当前这首歌的缓冲，别的歌的分片对他没用
        val want = message.str("k")
        for ((key, data) in room.relayBuffer.toList()) {
            if (want.isNotEmpty() && key != want) continue
            send(player, TogetherProtocol.message(TogetherProtocol.RELAY_DATA) {
                addProperty("k", key)
                addProperty("d", data)
            })
        }
    }

    /** 房主发来一片音频：缓存并转给正在听的人 */
    private fun relayData(player: ServerPlayer, message: JsonObject) {
        val room = room(player) ?: return
        if (room.host != player.uuid) return
        if (!TogetherConfig.allowLocalMusic) return
        val key = message.str("k")
        val data = message.str("d")
        if (key.isEmpty() || data.isEmpty()) return
        if (data.length > 28_000) return
        // 换歌时旧缓冲作废，免得补发给新进来的成员的是上一首的声音
        if (room.relayBuffer.isNotEmpty() && room.relayBuffer.last().first != key) room.relayBuffer.clear()
        room.relayBuffer.addLast(key to data)
        while (room.relayBuffer.size > RELAY_BUFFER) room.relayBuffer.removeFirst()
        for (member in room.relayMembers) {
            if (member == player.uuid) continue
            val target = player.level().getServer()!!.playerList.getPlayer(member) ?: continue
            send(target, TogetherProtocol.message(TogetherProtocol.RELAY_DATA) {
                addProperty("k", key)
                addProperty("d", data)
            })
        }
    }

    private fun send(player: ServerPlayer, json: String) {
        try {
            sender?.invoke(player, json)
        } catch (e: Exception) {
            DangoLogger.warn("Listen Together: send to {} failed: {}", player.gameProfile.name, e.toString())
        }
    }

    private fun broadcast(server: MinecraftServer, room: Room, json: String, except: UUID?) {
        for (uid in room.members.keys.toList()) {
            if (uid == except) continue
            server.playerList.getPlayer(uid)?.let { send(it, json) }
        }
    }

    private fun error(player: ServerPlayer, text: String) {
        send(player, TogetherProtocol.message(TogetherProtocol.ERROR) { addProperty("message", text) })
    }

    private fun newCode(): String {
        while (true) {
            val code = buildString { repeat(CODE_LEN) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }
            if (!rooms.containsKey(code)) return code
        }
    }
}
