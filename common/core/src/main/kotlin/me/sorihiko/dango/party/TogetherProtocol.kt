package me.sorihiko.dango.party

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.music.NeteaseTrack

/**
 * 一起听歌的网络协议：客户端和服务端之间只传一个 JSON 字符串，由各加载器的网络通道
 * （Fabric 的插件消息 / Forge 的 SimpleChannel）原样转发。
 *
 * 服务端是唯一权威：房间、共享播放列表、当前放到哪、权限，都由服务端记着。
 * 房主只上报"自己播放器的真实进度"，服务端盖上时间戳后广播给成员；成员的操作先到服务端，
 * 服务端检查权限后再转给房主执行。
 *
 * 方向与职责：
 *   客户端 → 服务端
 *     [LIST]        要一份服务器里正在一起听歌的房间列表
 *     [CREATE]      创建房间（创建者是房主）
 *     [JOIN]        用房间码加入
 *     [LEAVE]       离开房间（房主离开 = 房间结束）
 *     [ADD]         把歌按顺序加进共享播放列表（权限不够时被拒绝）
 *     [PLAY_NOW]    重复选到列表里已有的歌：提到队首并立刻从头播放
 *     [JUMP]        跳到播放列表里的某一首
 *     [NEXT]/[PREV] 切歌
 *     [REMOVE]      从播放列表里移除一首（房主）
 *     [INTENT]      暂停 / 继续 / 拖进度
 *     [SET_PERMS]   房主设置成员权限
 *     [HOST_STATE]  房主上报播放状态（歌曲、进度、是否在放）
 *     [ENDED]       房主报告一首歌自然放完了，服务端推进到下一首
 *     [RESYNC]      请求服务端重发一次完整状态
 *   服务端 → 客户端
 *     [ROOMS]       房间列表
 *     [ROOM]        进入房间时的完整快照（房间码、房主、成员、播放列表、进度）
 *     [QUEUE]       共享播放列表变了
 *     [STATE]       当前歌曲 / 进度 / 播放状态变了
 *     [MEMBERS]     成员或权限变了
 *     [COMMAND]     服务端转给房主的成员操作
 *     [CLOSED]      房间结束（房主离开 / 服务端重置）
 *     [ERROR]       服务端拒绝了请求
 */
object TogetherProtocol {

    /** 协议版本：服务端和客户端不一致时拒绝请求，免得字段对不上 */
    const val VERSION = 4

    /**
     * 一条自定义通道包的字符串上限。Fabric/Forge 的自定义 payload 上限是 32767 字节，
     * 超过会直接把玩家踢出服务器（Payload may not be larger than 32767 bytes），
     * 所以这里留在 30 KB 以内，长队列一律拆包发。
     */
    const val MAX_BYTES = 30 * 1024

    /** 拆包时单条消息的目标字节数，给 UUID 之类的包头留一点余量 */
    const val WIRE_BYTES = 26 * 1024

    /** 共享播放列表最多多少首（服务端和客户端都用这个数，超了要明确告诉玩家） */
    const val MAX_QUEUE = 500

    // ---------------------------------------------------------------- 客户端 → 服务端

    const val LIST = "list"
    const val CREATE = "create"
    const val JOIN = "join"
    const val LEAVE = "leave"
    const val ADD = "add"
    const val PLAY_NOW = "play_now"
    const val JUMP = "jump"
    const val NEXT = "next"
    const val PREV = "prev"
    const val REMOVE = "remove"
    const val INTENT = "intent"
    const val SET_PERMS = "set_perms"
    const val HOST_STATE = "host_state"
    const val ENDED = "ended"
    const val RESYNC = "resync"

    // ---------------------------------------------------------------- 服务端 → 客户端

    const val ROOMS = "rooms"
    const val ROOM = "room"
    const val QUEUE = "queue"
    const val STATE = "state"
    const val MEMBERS = "members"
    const val COMMAND = "command"
    const val CLOSED = "closed"
    const val ERROR = "error"

    // ---------------------------------------------------------------- 成员操作（转发给房主）

    const val OP_PAUSE = "pause"
    const val OP_RESUME = "resume"
    const val OP_SEEK = "seek"

    val OPS: Set<String> = setOf(OP_PAUSE, OP_RESUME, OP_SEEK)

    // ---------------------------------------------------------------- 成员权限（位掩码）

    /** 允许成员切歌（下一首 / 上一首 / 点列表里的某一首） */
    const val PERM_SKIP = 1
    /** 允许成员暂停、继续、拖动进度 */
    const val PERM_PAUSE = 2
    /** 允许成员往共享播放列表里点歌 */
    const val PERM_ADD = 4
    const val PERM_ALL = PERM_SKIP or PERM_PAUSE or PERM_ADD

    /** 一起听歌里的一个人：服务端用 UUID 当 uid */
    class Member(val uid: String, val name: String)

    /** 房间列表里的一行 */
    class RoomInfo(
        val code: String,
        val hostName: String,
        val count: Int,
        val trackName: String,
        val playing: Boolean,
    )

    /**
     * 房间的完整快照。[positionMs] 是服务端发出这份快照那一刻的进度，
     * 客户端从收到它的时间开始往后推算。
     */
    class RoomSnapshot(
        val code: String,
        val hostUid: String,
        val hostName: String,
        val perms: Int,
        val members: List<Member>,
        val queue: List<NeteaseTrack>,
        val current: Int,
        val positionMs: Long,
        val playing: Boolean,
        /** 当前这首：单独带一份，队列太长时可以只发队列分片、不重复发整队 */
        val track: NeteaseTrack? = queue.getOrNull(current),
    )

    fun parse(raw: String): JsonObject? = try {
        JsonParser().parse(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }

    fun type(message: JsonObject): String = message.str("t")

    /** 组一条消息：自动带上类型和协议版本 */
    fun message(type: String, body: JsonObject.() -> Unit = {}): String = JsonObject().apply {
        addProperty("t", type)
        addProperty("v", VERSION)
        body()
    }.toString()

    // ---------------------------------------------------------------- 歌曲 / 队列

    fun writeTrack(target: JsonObject, key: String, track: NeteaseTrack) {
        target.add(key, PartyTrackCodec.write(track))
    }

    fun readTrack(message: JsonObject, key: String = "track"): NeteaseTrack? =
        (message.get(key) as? JsonObject)?.let { PartyTrackCodec.read(it) }

    fun writeQueue(target: JsonObject, queue: List<NeteaseTrack>, current: Int) {
        target.add("queue", JsonArray().apply {
            for (track in queue) add(PartyTrackCodec.write(track))
        })
        target.addProperty("current", current)
    }

    fun readQueue(message: JsonObject): List<NeteaseTrack> =
        (message.get("queue") as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            PartyTrackCodec.read(obj)
        } ?: emptyList()

    fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * 把整条共享队列拆成若干条 [QUEUE] 消息：每片都带 [from] / [total] / [current]，
     * 客户端按 [from] 拼回去。500 首的歌单一条包发出去会超过 32 KB 然后把玩家踢下线。
     */
    fun queueMessages(queue: List<NeteaseTrack>, current: Int): List<String> {
        val total = queue.size
        val messages = ArrayList<String>(1)
        if (total == 0) {
            messages.add(message(QUEUE) {
                add("queue", JsonArray())
                addProperty("from", 0)
                addProperty("total", 0)
                addProperty("current", current)
            })
            return messages
        }
        var from = 0
        while (from < total) {
            val chunk = ArrayList<JsonObject>()
            var index = from
            while (index < total) {
                chunk.add(PartyTrackCodec.write(queue[index]))
                if (utf8Bytes(queueMessage(chunk, from, total, current)) > WIRE_BYTES && chunk.size > 1) {
                    chunk.removeAt(chunk.size - 1)
                    break
                }
                index++
            }
            if (chunk.isEmpty()) break
            messages.add(queueMessage(chunk, from, total, current))
            from += chunk.size
        }
        return messages
    }

    private fun queueMessage(chunk: List<JsonObject>, from: Int, total: Int, current: Int): String =
        message(QUEUE) {
            add("queue", JsonArray().apply { chunk.forEach { add(it) } })
            addProperty("from", from)
            addProperty("total", total)
            addProperty("current", current)
        }

    /**
     * 客户端点歌时同样要拆包；除最后一片外都带 [MORE]=true，
     * 服务端等收全了再广播一次队列，避免每片都推一遍全服。
     */
    fun addMessages(tracks: List<NeteaseTrack>): List<String> {
        val batches = ArrayList<List<JsonObject>>()
        var chunk = ArrayList<JsonObject>()
        for (track in tracks) {
            chunk.add(PartyTrackCodec.write(track))
            val probe = message(ADD) {
                add("tracks", JsonArray().apply { chunk.forEach { add(it) } })
                addProperty("more", true)
            }
            if (utf8Bytes(probe) > WIRE_BYTES && chunk.size > 1) {
                chunk.removeAt(chunk.size - 1)
                batches.add(chunk)
                chunk = arrayListOf(PartyTrackCodec.write(track))
            }
        }
        if (chunk.isNotEmpty()) batches.add(chunk)
        return batches.mapIndexed { i, batch ->
            message(ADD) {
                add("tracks", JsonArray().apply { batch.forEach { add(it) } })
                addProperty("more", i < batches.lastIndex)
            }
        }
    }

    // ---------------------------------------------------------------- 成员 / 权限

    fun writeMembers(target: JsonObject, hostUid: String, hostName: String, perms: Int, members: List<Member>) {
        target.addProperty("host", hostUid)
        target.addProperty("hostName", hostName)
        target.addProperty("perms", perms)
        target.add("members", JsonArray().apply {
            for (member in members) {
                add(JsonObject().apply {
                    addProperty("uid", member.uid)
                    addProperty("name", member.name)
                })
            }
        })
    }

    fun readMembers(message: JsonObject): List<Member> =
        (message.get("members") as? JsonArray)?.mapNotNull { element ->
            val member = element as? JsonObject ?: return@mapNotNull null
            val uid = member.str("uid")
            if (uid.isEmpty()) null else Member(uid, member.str("name"))
        } ?: emptyList()

    // ---------------------------------------------------------------- 房间快照

    fun writeRoom(target: JsonObject, snapshot: RoomSnapshot) {
        target.addProperty("code", snapshot.code)
        writeMembers(target, snapshot.hostUid, snapshot.hostName, snapshot.perms, snapshot.members)
        // 队列不进快照：500 首的队列能撑爆单包上限，服务端紧接着会发分片的 [QUEUE]
        snapshot.track?.let { writeTrack(target, "track", it) }
        target.addProperty("current", snapshot.current)
        target.addProperty("pos", snapshot.positionMs)
        target.addProperty("playing", snapshot.playing)
    }

    fun readRoom(message: JsonObject): RoomSnapshot? {
        val code = message.str("code")
        val host = message.str("host")
        if (code.isEmpty() || host.isEmpty()) return null
        val queue = readQueue(message)
        val current = message.num("current").toInt()
        return RoomSnapshot(
            code = code,
            hostUid = host,
            hostName = message.str("hostName"),
            perms = message.num("perms").toInt(),
            members = readMembers(message),
            queue = queue,
            current = current,
            positionMs = message.num("pos").coerceAtLeast(0L),
            playing = message.flag("playing"),
            track = readTrack(message) ?: queue.getOrNull(current),
        )
    }

    // ---------------------------------------------------------------- 房间列表

    fun writeRooms(target: JsonObject, rooms: List<RoomInfo>) {
        target.add("rooms", JsonArray().apply {
            for (room in rooms) {
                add(JsonObject().apply {
                    addProperty("code", room.code)
                    addProperty("hostName", room.hostName)
                    addProperty("count", room.count)
                    addProperty("track", room.trackName)
                    addProperty("playing", room.playing)
                })
            }
        })
    }

    fun readRooms(message: JsonObject): List<RoomInfo> =
        (message.get("rooms") as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val code = obj.str("code")
            if (code.isEmpty()) return@mapNotNull null
            RoomInfo(code, obj.str("hostName"), obj.num("count").toInt(), obj.str("track"), obj.flag("playing"))
        } ?: emptyList()
}

/** 读字符串字段；不是字符串时返回空串 */
fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

/** 读数字字段；读不到（或不是数字）时返回 0 */
fun JsonObject.num(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

/** 读布尔字段；读不到时返回 false */
fun JsonObject.flag(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
