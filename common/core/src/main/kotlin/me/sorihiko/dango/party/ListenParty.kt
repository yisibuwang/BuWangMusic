package me.sorihiko.dango.party

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.notice.Notices
import kotlin.math.abs

/**
 * 一起听歌（服务器内同步，协议见 [TogetherProtocol]）。
 *
 *   服务端是唯一权威：房间、共享播放列表、当前歌曲、进度、成员权限都在服务端，
 *   客户端只负责把自己的播放器对齐到服务端的状态。
 *
 *   房主：创建房间；本地播放器的真实进度（歌曲 / 进度 / 是否在放）每 300ms 上报一次，
 *     服务端加上时间戳后广播给成员。房主点的歌也进入共享播放列表，由服务端推进。
 *   成员：加入房间后收到完整快照（播放列表 + 当前歌曲 + 已经播到哪），直接跳到对应位置；
 *     之后靠本地时间推算进度，只在事件发生时（切歌 / 暂停 / 跳进度）纠正。
 *   权限：房主可以分别开关"切歌 / 暂停和拖进度 / 点歌"，服务端检查后才会执行。
 *
 * 没有 Firebase、没有外部服务，所有网络包通过各加载器的通道收发；
 * 这个类本身不碰 Minecraft 的类，方便单测和复用。
 */
object ListenParty {

    /** 房主没有变化时多久重发一次状态（兜底，正常的切歌 / 暂停 / 拖进度会立刻发） */
    private const val STATE_REFRESH_MS = 30_000L
    /** 客户端创建 / 加入房间后等服务端回应的最长时间 */
    private const val JOIN_TIMEOUT_MS = 4_000L
    /** 加载失败后多久自动重试一次 */
    private const val RETRY_MS = 5_000L
    /** 房间列表自动刷新间隔 */
    private const val ROOM_LIST_MS = 3_000L
    /** 点歌发出去之后，这么久没被服务端确认就不再算"已点"，允许重发 */
    private const val PENDING_ADD_MS = 5_000L

    enum class Role { NONE, HOST, MEMBER }

    class Member(val uid: String, val name: String)

    private class RemoteState(
        val track: NeteaseTrack,
        val pos: Long, val playing: Boolean, val at: Long,
    )

    @Volatile var role = Role.NONE
        private set
    /** 房间码；没进房间时为空 */
    @Volatile var roomCode = ""
        private set
    @Volatile var hostName = ""
        private set
    @Volatile var hostUid = ""
        private set
    @Volatile var members: List<Member> = emptyList()
        private set
    /** 服务端下发的成员权限位掩码 */
    @Volatile var perms = 0
        private set
    /** 共享播放列表（服务端权威） */
    @Volatile var queue: List<NeteaseTrack> = emptyList()
        private set
    /** 当前放到第几首 */
    @Volatile var current = -1
        private set
    /** 服务器里正在一起听歌的房间（房间列表页） */
    @Volatile var rooms: List<TogetherProtocol.RoomInfo> = emptyList()
        private set
    /** 给界面看的状态 / 错误信息 */
    @Volatile var status = ""
        private set
    @Volatile var busy = false
        private set
    /** 服务端没有装 DangoMusic（或者版本不同）时为 true，界面据此提示 */
    @Volatile var unsupported = false
        private set

    val uid: String get() = myUid
    val inRoom: Boolean get() = role != Role.NONE
    val isHost: Boolean get() = role == Role.HOST
    val canSkip: Boolean get() = inRoom && (isHost || (perms and TogetherProtocol.PERM_SKIP) != 0)
    val canPause: Boolean get() = inRoom && (isHost || (perms and TogetherProtocol.PERM_PAUSE) != 0)
    val canAdd: Boolean get() = inRoom && (isHost || (perms and TogetherProtocol.PERM_ADD) != 0)
    /** 兼容旧调用：成员能切歌就算"能控制" */
    val canControl: Boolean get() = canSkip

    /** 服务端认定的自己的 uid；由加载器在登录时给出 */
    @Volatile private var myUid = ""

    @Volatile private var remote: RemoteState? = null
    @Volatile private var send: ((String) -> Unit)? = null

    // 房主：上一次发出去的状态
    private var sentKey = ""
    private var sentPlaying = false
    private var sentPos = 0L
    private var sentAt = 0L
    // 已经请求加载的歌、上次纠正 / 重试的时间
    @Volatile private var requestedKey = ""
    @Volatile private var lastCorrection = 0L
    @Volatile private var lastRetry = 0L
    // 房间列表
    @Volatile private var lastRoomList = 0L
    @Volatile private var browseUntil = 0L
    // 已经发出去、还没看到服务端带回的"点歌"：防止双击 / 手快连点把同一首加两遍
    private val pendingAdds = HashMap<String, Long>()

    /** 待确认点歌的歌名（服务端把队列带回来以后再用它提示"已添加"） */
    private val pendingNames = HashMap<String, String>()

    /** 分片的共享队列：key 是分片起点下标，拼齐了再一次性换掉本地队列 */
    private val queueChunks = HashMap<Int, List<NeteaseTrack>>()

    /** 最近一次 QUEUE 分片里的 current，拼包完成时用 */
    private var queueChunkCurrent = -1
    // 已经提示过"这首放不了"的歌，避免每帧刷提示
    @Volatile private var failureNoticeKey = ""
    // 房间内一首歌自然放完
    @Volatile private var endedFlag = false
    // 创建 / 加入的超时起点
    private var startedAt = 0L

    init {
        Thread({
            while (true) {
                try {
                    Thread.sleep(300)
                    when (role) {
                        Role.HOST -> {
                            followTick()
                            hostTick()
                        }
                        Role.MEMBER -> followTick()
                        Role.NONE -> {}
                    }
                    val now = System.currentTimeMillis()
                    if (endedFlag) {
                        endedFlag = false
                        if (role == Role.HOST) emit(TogetherProtocol.message(TogetherProtocol.ENDED))
                    }
                    if (busy && startedAt != 0L && now - startedAt > JOIN_TIMEOUT_MS) {
                        busy = false
                        startedAt = 0L
                        unsupported = true
                        status = "服务器没有开启一起听歌：需要服务端也安装这个模组"
                    }
                    if (now < browseUntil && now - lastRoomList > ROOM_LIST_MS) requestRooms()
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    DangoLogger.warn("Listen party tick failed: {}", e.toString())
                }
            }
        }, "Dango-PartyTick").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { leaveQuietly() })
    }

    private fun myName(): String = try { DangoMusic.instance.platform.playerName } catch (_: Exception) { "Player" }

    /** 所有音源都能在服务器内同步：歌还是每个人用自己的账号去取，只同步歌曲信息 */
    fun supportsSource(source: MusicSource): Boolean = true

    // ---------------------------------------------------------------- 加载器接上 / 断开通道

    /**
     * 登录进服务器时调用：[uid] 是本地玩家的 UUID，[sender] 把一条 JSON 消息发给服务端。
     */
    fun attach(uid: String, sender: (String) -> Unit) {
        if (myUid == uid && send != null) return
        abandonLocally()
        myUid = uid
        send = sender
        unsupported = false
        startedAt = 0L
        status = ""
    }

    /** 退出服务器 / 断开时调用 */
    fun detach() {
        send = null
        abandonLocally()
        myUid = ""
        unsupported = false
        startedAt = 0L
        status = ""
    }

    private fun emit(message: String): Boolean {
        val s = send ?: return false
        return try {
            s.invoke(message)
            true
        } catch (e: Exception) {
            DangoLogger.warn("Listen party send failed: {}", e.toString())
            false
        }
    }

    /** 发一条操作消息；发不出去就明确告诉玩家，而不是静默失败 */
    private fun emitOrWarn(message: String): Boolean {
        if (emit(message)) return true
        status = "操作没发出去：和服务器的连接已断开"
        Notices.post(status, Notices.WARN)
        return false
    }

    // ---------------------------------------------------------------- 房间列表 / 创建 / 加入

    /** 房间列表页每帧调用：保持自动刷新（离开页面约 2 秒后停止） */
    fun browse() {
        browseUntil = System.currentTimeMillis() + 2000
        val now = System.currentTimeMillis()
        if (now - lastRoomList > ROOM_LIST_MS) requestRooms()
    }

    private fun requestRooms() {
        if (send == null) return
        lastRoomList = System.currentTimeMillis()
        emit(TogetherProtocol.message(TogetherProtocol.LIST))
    }

    /** 创建房间，自己是房主 */
    fun createRoom() {
        if (busy || inRoom) return
        if (send == null) {
            unsupported = true
            status = "还没连进服务器，没法一起听歌"
            return
        }
        busy = true
        unsupported = false
        status = "正在创建房间…"
        startedAt = System.currentTimeMillis()
        if (!emit(TogetherProtocol.message(TogetherProtocol.CREATE))) {
            busy = false
            startedAt = 0L
            status = "发不出去：服务器没有开启一起听歌"
        }
    }

    /** 用房间码加入 */
    fun join(code: String) {
        val room = code.trim().uppercase()
        if (room.isEmpty()) {
            status = "请先输入房间码"
            return
        }
        if (busy || inRoom) return
        if (send == null) {
            unsupported = true
            status = "还没连进服务器，没法一起听歌"
            return
        }
        busy = true
        unsupported = false
        status = "正在加入房间 $room…"
        startedAt = System.currentTimeMillis()
        if (!emit(TogetherProtocol.message(TogetherProtocol.JOIN) { addProperty("code", room) })) {
            busy = false
            startedAt = 0L
            status = "发不出去：服务器没有开启一起听歌"
        }
    }

    fun leave() {
        if (!inRoom) return
        emit(TogetherProtocol.message(TogetherProtocol.LEAVE))
        abandonLocally()
        status = ""
    }

    private fun leaveQuietly() {
        if (!inRoom) return
        emit(TogetherProtocol.message(TogetherProtocol.LEAVE))
        abandonLocally()
    }

    /** 只清本地状态，不碰网络 */
    private fun abandonLocally() {
        val wasInRoom = inRoom
        role = Role.NONE
        roomCode = ""
        hostUid = ""
        hostName = ""
        members = emptyList()
        perms = 0
        queue = emptyList()
        current = -1
        remote = null
        requestedKey = ""
        sentKey = ""
        sentPlaying = false
        sentPos = 0L
        sentAt = 0L
        busy = false
        startedAt = 0L
        lastCorrection = 0L
        lastRetry = 0L
        endedFlag = false
        queueChunks.clear()
        synchronized(pendingAdds) { pendingAdds.clear() }
        synchronized(pendingNames) { pendingNames.clear() }
        MusicPlayer.followMode = false
        if (wasInRoom && status.contains("自动重试")) status = ""
    }

    /** 重新和服务端对齐 */
    fun resync() {
        requestedKey = ""
        lastCorrection = 0L
        if (send != null) emit(TogetherProtocol.message(TogetherProtocol.RESYNC))
    }

    /** 界面提示一条错误（比如权限不够）；同时浮一条提示，任何页面都看得到 */
    fun deny(text: String) {
        status = text
        Notices.post(text, Notices.WARN)
    }

    /** 房主：设置成员权限（位掩码） */
    fun setPerms(mask: Int) {
        if (role != Role.HOST) return
        perms = mask and TogetherProtocol.PERM_ALL
        emit(TogetherProtocol.message(TogetherProtocol.SET_PERMS) { addProperty("perms", perms) })
    }

    // ---------------------------------------------------------------- 共享播放列表 / 切歌

    /** 点歌：按顺序加进房间的共享播放列表（权限不够时只提示） */
    fun enqueue(tracks: List<NeteaseTrack>, announce: Boolean = true) {
        if (!inRoom) return
        if (!canAdd) {
            deny("房主没有开放点歌权限")
            return
        }
        val supported = tracks.filter { supportsSource(it.source) }
        if (supported.isEmpty()) return

        val now = System.currentTimeMillis()
        // 在歌单里又点了一首已经在共享列表里的歌：不重复加，直接提到队首立刻播放
        if (supported.size == 1) {
            val one = supported[0]
            val pendingAt = synchronized(pendingAdds) { pendingAdds[one.key] }
            if (pendingAt != null && now - pendingAt <= PENDING_ADD_MS) return // 双击的第二下，忽略
            if (queue.any { it.key == one.key }) {
                if (!canSkip) {
                    deny("房主没有开放切歌权限")
                    return
                }
                val message = TogetherProtocol.message(TogetherProtocol.PLAY_NOW) {
                    TogetherProtocol.writeTrack(this, "track", one)
                }
                if (!emit(message)) {
                    status = "操作没发出去：和服务器的连接已断开"
                    Notices.post(status, Notices.WARN)
                }
                return
            }
        }

        // 共享列表有上限：满了就直接说清楚，别再发请求让服务端拒绝一遍
        val capacity = TogetherProtocol.MAX_QUEUE - queue.size
        if (capacity <= 0) {
            Notices.post(
                "共享播放列表已经满了（最多 ${TogetherProtocol.MAX_QUEUE} 首），先删掉几首再加吧",
                Notices.WARN,
            )
            return
        }

        // 同一首歌在共享列表里只保留一份：双击 / 手快连点不会加两遍
        val know = queue.mapTo(HashSet()) { it.key }
        val fresh = ArrayList<NeteaseTrack>(supported.size)
        var duplicate: NeteaseTrack? = null
        synchronized(pendingAdds) {
            pendingAdds.entries.removeAll { now - it.value > PENDING_ADD_MS }
            for (track in supported) {
                val pendingAt = pendingAdds[track.key]
                if (track.key in know || (pendingAt != null && now - pendingAt <= PENDING_ADD_MS)) {
                    if (duplicate == null) duplicate = track
                    continue
                }
                pendingAdds[track.key] = now
                fresh += track
            }
        }

        if (fresh.isEmpty()) {
            val name = duplicate?.name.orEmpty()
            Notices.post(if (name.isNotEmpty()) "《$name》已经在共享播放列表里了" else "这些歌已经在共享播放列表里了", Notices.INFO)
            return
        }
        // 超过上限的后面几首不发了
        val sending = if (fresh.size > capacity) ArrayList(fresh.subList(0, capacity)) else fresh
        if (sending.size < fresh.size) {
            synchronized(pendingAdds) { fresh.drop(capacity).forEach { pendingAdds.remove(it.key) } }
            Notices.post(
                "共享播放列表最多 ${TogetherProtocol.MAX_QUEUE} 首，这次只加了前 ${capacity} 首",
                Notices.WARN,
            )
        }
        // 队列拆包发：500 首挤成一条包会超过 32 KB，直接把玩家踢下线
        for (message in TogetherProtocol.addMessages(sending)) {
            if (emit(message)) continue
            status = "操作没发出去：和服务器的连接已断开"
            Notices.post(status, Notices.WARN)
            synchronized(pendingAdds) { sending.forEach { pendingAdds.remove(it.key) } }
            // 可能有前几片已经到了服务端：重新对齐一次，别让本地和服务端的队列不一致
            emit(TogetherProtocol.message(TogetherProtocol.RESYNC))
            return
        }
        if (announce) {
            synchronized(pendingNames) { sending.forEach { pendingNames[it.key] = it.name } }
        }
        // 先把新歌接到本地列表，面板马上就有反馈；服务端广播的队列随后会再校准一次，
        // 真正的"已添加"提示等队列里确实出现这些歌了再弹（避免服务端拒绝时两头都提示）。
        queue = queue + sending
        MusicPlayer.mirrorRoomQueue(queue, current)
    }

    /** 跳到共享播放列表里的某一首 */
    fun jumpTo(index: Int) {
        if (!inRoom) return
        if (!canSkip) {
            deny("房主没有开放切歌权限")
            return
        }
        emitOrWarn(TogetherProtocol.message(TogetherProtocol.JUMP) { addProperty("index", index) })
    }

    fun requestNext() {
        if (!inRoom) return
        if (!canSkip) {
            deny("房主没有开放切歌权限")
            return
        }
        emitOrWarn(TogetherProtocol.message(TogetherProtocol.NEXT))
    }

    fun requestPrev() {
        if (!inRoom) return
        if (!canSkip) {
            deny("房主没有开放切歌权限")
            return
        }
        emitOrWarn(TogetherProtocol.message(TogetherProtocol.PREV))
    }

    /** 房主：从共享播放列表里删掉一首 */
    fun removeFromQueue(index: Int) {
        if (role != Role.HOST) return
        emitOrWarn(TogetherProtocol.message(TogetherProtocol.REMOVE) { addProperty("index", index) })
    }

    /** 暂停 / 继续 / 拖进度：成员发给服务端，由服务端转给房主 */
    fun requestState(op: String, pos: Long) {
        if (!inRoom || isHost || op !in TogetherProtocol.OPS) return
        if (!canPause) {
            deny("房主没有开放暂停/拖进度权限")
            return
        }
        lastCorrection = System.currentTimeMillis() + 2500
        val message = TogetherProtocol.message(TogetherProtocol.INTENT) {
            addProperty("op", op)
            addProperty("pos", pos)
        }
        if (!emit(message)) {
            status = "操作没发出去：和服务器的连接已断开"
            resync()
        }
    }

    // ---------------------------------------------------------------- 收到网络包

    /** 加载器收到一条来自服务端的消息；可以在网络线程直接调 */
    fun onMessage(raw: String) {
        val message = TogetherProtocol.parse(raw) ?: return
        when (TogetherProtocol.type(message)) {
            TogetherProtocol.ROOMS -> {
                rooms = TogetherProtocol.readRooms(message)
                lastRoomList = System.currentTimeMillis()
                unsupported = false
            }
            TogetherProtocol.ROOM -> {
                val snapshot = TogetherProtocol.readRoom(message) ?: return
                applyRoom(snapshot)
            }
            TogetherProtocol.QUEUE -> onQueueChunk(message)
            TogetherProtocol.STATE -> {
                val track = TogetherProtocol.readTrack(message)
                current = message.num("current").toInt()
                remote = if (track == null) null else RemoteState(
                    track = track,
                    pos = message.num("pos").coerceAtLeast(0L),
                    playing = message.flag("playing"),
                    at = System.currentTimeMillis(),
                )
                // 服务端把某首提到队首重新播放：正在放这首的客户端直接从 0 重来（房主也一样）
                if (message.flag("restart") && track != null && MusicPlayer.current?.key == track.key) {
                    lastCorrection = System.currentTimeMillis() + 2500
                    MusicPlayer.syncTo(message.num("pos").coerceAtLeast(0L), message.flag("playing"))
                }
                if (track != null) {
                    requestedKey = ""
                    MusicPlayer.mirrorRoomQueue(queue, current)
                }
            }
            TogetherProtocol.MEMBERS -> {
                val old = members
                hostUid = message.str("host")
                hostName = message.str("hostName")
                perms = message.num("perms").toInt() and TogetherProtocol.PERM_ALL
                members = TogetherProtocol.readMembers(message).map { Member(it.uid, it.name) }
                val oldUids = old.mapTo(HashSet()) { it.uid }
                val newUids = members.mapTo(HashSet()) { it.uid }
                members.firstOrNull { it.uid !in oldUids && it.uid != myUid }?.let {
                    Notices.post("${it.name} 加入了一起听歌", Notices.INFO)
                }
                old.firstOrNull { it.uid !in newUids && it.uid != myUid }?.let {
                    Notices.post("${it.name} 离开了一起听歌", Notices.INFO)
                }
            }
            TogetherProtocol.COMMAND -> if (role == Role.HOST) runCommand(message)
            TogetherProtocol.CLOSED -> {
                val text = message.str("message").ifEmpty { "一起听歌已结束" }
                abandonLocally()
                status = text
                Notices.post(text, Notices.WARN)
            }
            TogetherProtocol.ERROR -> {
                busy = false
                startedAt = 0L
                val hadPending = rollbackPending()
                status = message.str("message").ifEmpty { "服务端拒绝了请求" }
                Notices.post(status, Notices.WARN)
                // 刚撤销过乐观加入的歌：重新拉一次权威队列，别让本地列表和服务端长期不一致
                if (hadPending) emit(TogetherProtocol.message(TogetherProtocol.RESYNC))
            }
        }
    }

    /** 收到一段共享队列：拼齐 total 首之后才替换本地列表 */
    private fun onQueueChunk(message: JsonObject) {
        val total = message.num("total").toInt()
        val from = message.num("from").toInt()
        val at = message.num("current").toInt()
        val chunk = TogetherProtocol.readQueue(message)
        if (total <= 0) {
            queueChunks.clear()
            queue = emptyList()
            current = at
            MusicPlayer.mirrorRoomQueue(queue, current)
            settlePending()
            return
        }
        queueChunkCurrent = at
        if (from == 0) queueChunks.clear()
        queueChunks[from] = chunk
        val merged = ArrayList<NeteaseTrack>(total)
        var next = 0
        while (true) {
            val part = queueChunks[next] ?: break
            if (part.isEmpty()) break
            merged.addAll(part)
            next += part.size
        }
        if (merged.size < total) return
        queueChunks.clear()
        queue = merged
        current = queueChunkCurrent
        // 房间快照先到、队列后到：重新给 followTick 一次机会去加载当前这首
        requestedKey = ""
        MusicPlayer.mirrorRoomQueue(queue, current)
        settlePending()
    }

    private fun applyRoom(snapshot: TogetherProtocol.RoomSnapshot) {
        val wasInRoom = inRoom
        roomCode = snapshot.code
        hostUid = snapshot.hostUid
        hostName = snapshot.hostName
        perms = snapshot.perms
        members = snapshot.members.map { Member(it.uid, it.name) }
        queue = snapshot.queue
        current = snapshot.current
        queueChunks.clear()
        role = if (snapshot.hostUid.isNotEmpty() && snapshot.hostUid == myUid) Role.HOST else Role.MEMBER
        busy = false
        startedAt = 0L
        unsupported = false
        status = ""
        requestedKey = ""
        lastCorrection = 0L
        lastRetry = 0L
        MusicPlayer.followMode = true
        // 快照里不带整队（队列可能 500 首），队列由紧接着的 QUEUE 分片补齐
        settlePending()
        val track = snapshot.track
        remote = if (track == null) null else RemoteState(
            track = track,
            pos = snapshot.positionMs,
            playing = snapshot.playing,
            at = System.currentTimeMillis(),
        )
        // 建房间时把房主正在听的这首放进共享列表，别人一进来就有歌
        if (!wasInRoom && role == Role.HOST && queue.isEmpty()) {
            MusicPlayer.current?.let { enqueue(listOf(it), announce = false) }
        }
        // 队列一律以房间为准（空房间也镜像），免得队列页还显示着加入前的私人歌单
        MusicPlayer.mirrorRoomQueue(queue, current)
        if (!wasInRoom) {
            Notices.post(
                if (role == Role.HOST) "房间 ${roomCode}：你是房主，把房间码告诉朋友就能一起听"
                else "已加入 $hostName 的房间 ${roomCode}",
                Notices.OK,
            )
        }
    }

    /** 点歌被服务端带进共享列表后，把"待确认"的记录清掉，并在这时才提示"已添加" */
    private fun settlePending() {
        if (pendingAdds.isEmpty()) return
        val now = System.currentTimeMillis()
        val know = queue.mapTo(HashSet()) { it.key }
        val confirmed = ArrayList<String>(pendingAdds.size)
        synchronized(pendingAdds) {
            for (key in pendingAdds.keys.toList()) {
                val at = pendingAdds[key] ?: continue
                if (key in know) {
                    pendingAdds.remove(key)
                    confirmed += key
                } else if (now - at > PENDING_ADD_MS) {
                    pendingAdds.remove(key)
                    synchronized(pendingNames) { pendingNames.remove(key) }
                }
            }
        }
        if (confirmed.isEmpty()) return
        val names = synchronized(pendingNames) {
            confirmed.mapNotNull { pendingNames.remove(it) }
        }
        if (names.isEmpty()) return // 建房自动带歌这类静默添加不提示
        Notices.post(
            if (names.size == 1) "已添加《${names[0]}》到共享播放列表"
            else "已添加 ${names.size} 首歌到共享播放列表",
            Notices.OK,
        )
    }

    /** 服务端拒绝了请求：撤销本地乐观加入的歌，并把待确认记录清掉 */
    private fun rollbackPending(): Boolean {
        val keys = synchronized(pendingAdds) {
            val copy = pendingAdds.keys.toSet()
            pendingAdds.clear()
            copy
        }
        synchronized(pendingNames) { pendingNames.clear() }
        if (keys.isEmpty()) return false
        val kept = queue.filterNot { it.key in keys }
        if (kept.size != queue.size) {
            queue = kept
            MusicPlayer.mirrorRoomQueue(kept, current)
        }
        return true
    }

    // ---------------------------------------------------------------- 房主：把自己的播放状态发出去

    private fun hostTick() {
        val cur = MusicPlayer.current ?: return
        val state = remote ?: return
        if (cur.key != state.track.key) return
        val st = MusicPlayer.state
        val now = System.currentTimeMillis()
        if (st == MusicPlayer.State.LOADING || st == MusicPlayer.State.ERROR) {
            // 切歌了先告诉大家开始加载这首（暂停状态），真正开始放时再发一次
            if (cur.key != sentKey) send(cur, 0L, false)
            return
        }
        val playing = st == MusicPlayer.State.PLAYING
        val pos = MusicPlayer.positionMs
        val predicted = sentPos + if (sentPlaying) now - sentAt else 0L
        val changed = cur.key != sentKey || playing != sentPlaying || abs(pos - predicted) > 1500 || now - sentAt > STATE_REFRESH_MS
        if (changed) send(cur, pos, playing)
    }

    private fun send(track: NeteaseTrack, pos: Long, playing: Boolean) {
        val now = System.currentTimeMillis()
        sentKey = track.key
        sentPlaying = playing
        sentPos = pos
        sentAt = now
        val message = TogetherProtocol.message(TogetherProtocol.HOST_STATE) {
            TogetherProtocol.writeTrack(this, "track", track)
            addProperty("pos", pos)
            addProperty("playing", playing)
        }
        if (!emit(message)) status = "状态没发出去：和服务器的连接已断开"
    }

    // ---------------------------------------------------------------- 房主：执行成员的请求

    private fun runCommand(message: JsonObject) {
        val op = message.str("op")
        val pos = message.num("pos").coerceAtLeast(0L)
        DangoLogger.info("Listen Together: member request {}", op)
        when (op) {
            TogetherProtocol.OP_SEEK -> {
                val st = MusicPlayer.state
                if (st == MusicPlayer.State.PLAYING || st == MusicPlayer.State.PAUSED) {
                    MusicPlayer.syncTo(pos, st == MusicPlayer.State.PLAYING)
                }
            }
            TogetherProtocol.OP_PAUSE -> if (MusicPlayer.state == MusicPlayer.State.PLAYING) MusicPlayer.togglePause()
            TogetherProtocol.OP_RESUME -> {
                val st = MusicPlayer.state
                if (st == MusicPlayer.State.PAUSED) {
                    if (abs(MusicPlayer.positionMs - pos) > 1500) MusicPlayer.syncTo(pos, true) else MusicPlayer.togglePause()
                }
            }
            else -> return
        }
        // 马上把新状态发出去，不等进度差够大
        sentAt = 0L
        sentKey = ""
    }

    /** 房间内一首歌自然放完（由 [MusicPlayer] 在解码线程调用） */
    fun onTrackFinished() {
        endedFlag = true
    }

    // ---------------------------------------------------------------- 客户端：跟着服务端的状态放

    private fun expectedPos(s: RemoteState): Long =
        s.pos + if (s.playing) (System.currentTimeMillis() - s.at).coerceAtLeast(0L) else 0L

    private fun loadRemote(s: RemoteState) {
        lastRetry = System.currentTimeMillis()
        MusicPlayer.playRoomTrack(queue, current, { expectedPos(s) }, !s.playing)
    }

    private fun followTick() {
        val s = remote ?: return
        val cur = MusicPlayer.current
        if (cur?.key != s.track.key) {
            if (requestedKey != s.track.key) {
                requestedKey = s.track.key
                loadRemote(s)
            }
            return
        }
        val st = MusicPlayer.state
        if (st == MusicPlayer.State.ERROR) {
            // 加载 / 播放失败：房间内不自动跳歌，状态栏提示并每 5 秒重试一次
            if (!status.contains("自动重试")) {
                status = "这首歌在你这边放不了：可能需要登录或会员（每 5 秒自动重试）"
            }
            if (failureNoticeKey != s.track.key) {
                failureNoticeKey = s.track.key
                Notices.post("《${s.track.name}》在你这边放不了：可能需要登录或会员，正在自动重试", Notices.WARN)
            }
            val now = System.currentTimeMillis()
            if (now - lastRetry >= RETRY_MS) loadRemote(s)
            return
        }
        if (failureNoticeKey == s.track.key) failureNoticeKey = ""
        if (status.contains("自动重试")) status = ""
        if (role == Role.HOST) return
        if (st == MusicPlayer.State.LOADING) return
        val now = System.currentTimeMillis()
        if (now - lastCorrection < 2000) return

        val exp = expectedPos(s)
        val localPlaying = st == MusicPlayer.State.PLAYING
        val drift = abs(MusicPlayer.positionMs - exp)
        if (s.playing != localPlaying || (s.playing && drift > 700) || (!s.playing && drift > 1500)) {
            MusicPlayer.syncTo(exp, s.playing)
            lastCorrection = now
        }
    }
}
