package me.sorihiko.dango.party

import de.sciss.jump3r.lowlevel.LameEncoder
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.music.LocalLibrary
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.notice.Notices
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.sound.sampled.AudioFormat

/**
 * 本地音乐的"声音中转"：房主把自己正在放的本地文件按实时速度切成小片，
 * 经服务端转给房间里的人，客户端把分片拼成一个可阻塞的流喂给解码器。
 *
 * 设计上以服务器体验为先：
 *  - 只在服务端允许、房间里正在放本地音乐时才发；
 *  - 按文件平均码率连续切 8 KB 一片，始终比房主进度多囤约 1.2 秒；
 *  - 房主选了码率挡位（128/96/64 kbps）时，先在自己这边用 LAME 转成该码率的 CBR MP3 再发，
 *    这样带宽按挡位走，客户端仍然只是普通 MP3 解码，不需要额外的解码器；
 *  - 服务端只转发不解码。
 */
object TogetherRelay {

    private const val MAX_CHUNK = 8192
    /** 提前把这么多毫秒的声音推给成员，网络抖一下也不会断 */
    private const val LEAD_MS = 1_200
    /** 每次 tick 最多补几片，避免一次性把通道打满 */
    private const val BURST = 4
    /** 至少缓冲这么多字节再开始放（128 kbps 大约 0.5 秒） */
    private const val PREBUFFER = 8_000
    /** 转码最多领先播放位置这么多字节，避免把整首歌都编进内存 */
    private const val TRANSCODE_AHEAD = 1 shl 21

    /** 服务端是否允许共享本地音乐（房间里正在放本地歌时才真的走中转） */
    @Volatile
    var enabled = false
        private set

    /** 房主选的中转码率（kbps），0 = 原始码率不转码 */
    @Volatile
    var quality = 0
        private set

    private var hostKey = ""
    private var hostBytes: ByteArray? = null
    /** 已经发到文件里的哪个字节；按这个游标连续发，不用每 tick 重新对齐 */
    private var sentOffset = 0
    /** 上一次 tick 看到的播放位置，用来发现房主拖了进度 */
    private var lastPositionMs = 0L
    /** 需要转码时，房主这边的编码器把整首歌慢慢编成 CBR MP3 */
    private var encoder: Transcoder? = null

    private val streams = HashMap<String, RelayStream>()
    private val requested = HashSet<String>()
    private var playingKey = ""

    @Synchronized
    fun setEnabled(on: Boolean, kbps: Int = quality) {
        if (kbps != quality) {
            quality = kbps
            encoder?.close()
            encoder = null
            sentOffset = 0
            // 换挡位后旧分片不能再用：成员重新请求，房主从当前进度重新发
            clearStreams()
        }
        if (enabled == on) return
        enabled = on
        if (!on) clearStreams()
    }

    @Synchronized
    fun reset() {
        enabled = false
        quality = 0
        hostKey = ""
        hostBytes = null
        sentOffset = 0
        encoder?.close()
        encoder = null
        clearStreams()
    }

    private fun clearStreams() {
        streams.values.forEach { it.close() }
        streams.clear()
        requested.clear()
        playingKey = ""
    }

    // ------------------------------------------------------------ 房主：切流发片

    fun hostTick(
        track: NeteaseTrack,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        kbps: Int,
        send: (String) -> Boolean,
    ) {
        if (!enabled) return
        if (track.source != MusicSource.LOCAL) return
        val duration = durationMs.coerceAtLeast(1000L)
        val position = positionMs.coerceIn(0L, duration)
        if (track.key != hostKey) {
            hostKey = track.key
            hostBytes = null
            sentOffset = 0
            lastPositionMs = 0L
            encoder?.close()
            encoder = null
            loadHost(track)
            return
        }
        val source = hostBytes ?: return
        // 暂停时不发；恢复后从原游标继续，成员那边的缓冲还能顶一会儿
        if (!playing) return
        if (kbps > 0) {
            if (encoder == null) {
                encoder = Transcoder(track.key, kbps, source) { sentOffset + TRANSCODE_AHEAD }.also { it.start() }
            }
            val enc = encoder ?: return
            if (enc.key != track.key || enc.kbps != kbps) return
            val bytesPerSecond = kbps * 125.0
            val base = (position * bytesPerSecond / 1000.0).toInt()
            alignCursor(base, position)
            lastPositionMs = position
            val lead = (LEAD_MS * bytesPerSecond / 1000.0).toInt()
            sendSlices({ enc.available() }, { from, len -> enc.copyFrom(from, len) }, base + lead, track.key, send)
            return
        }
        if (source.size < 10_000) return
        val rate = source.size.toDouble() / duration          // 每毫秒多少字节
        val base = ((position * source.size) / duration).toInt()
        alignCursor(base, position)
        lastPositionMs = position
        sendSlices({ source.size }, { from, len -> source.copyOfRange(from, from + len) }, base + (rate * LEAD_MS).toInt(), track.key, send)
    }

    /**
     * 跳进度后游标要对齐到新位置：差得太多就整段跳过，免得成员听到旧位置的音频。
     */
    private fun alignCursor(base: Int, positionMs: Long) {
        if (kotlin.math.abs(base - sentOffset) > MAX_CHUNK * 2) sentOffset = base
        if (positionMs - lastPositionMs > 800 || lastPositionMs - positionMs > 800) sentOffset = base
    }

    /** 整首读进内存比较慢，放到后台线程，别把上报进度的线程卡住 */
    private fun loadHost(track: NeteaseTrack) {
        val key = track.key
        Thread({
            val bytes = LocalLibrary.read(track)
            if (bytes == null) {
                Notices.post("《${track.name}》读不出来了，中转停一下", Notices.WARN)
                return@Thread
            }
            synchronized(this) {
                if (hostKey == key) {
                    hostBytes = bytes
                    sentOffset = 0
                }
            }
        }, "Dango-RelayLoad").apply { isDaemon = true }.start()
    }

    /** 从游标往 target 推片；原始字节和转码后的字节共用这段逻辑 */
    private fun sendSlices(
        available: () -> Int,
        slice: (Int, Int) -> ByteArray,
        target: Int,
        key: String,
        send: (String) -> Boolean,
    ) {
        if (target <= sentOffset) return
        val total = available()
        var budget = BURST
        while (budget > 0 && sentOffset < target) {
            val want = minOf(target - sentOffset, MAX_CHUNK, total - sentOffset)
            if (want < 256) return
            val data = Base64.getEncoder().encodeToString(slice(sentOffset, want))
            val message = TogetherProtocol.message(TogetherProtocol.RELAY_DATA) {
                addProperty("k", key)
                addProperty("d", data)
            }
            val sent = try {
                send(message)
            } catch (e: Exception) {
                DangoLogger.warn("Relay send failed: {}", e.toString())
                false
            }
            if (!sent) return
            sentOffset += want
            budget--
        }
    }

    // ------------------------------------------------------------ 成员：收片拼流

    @Synchronized
    fun onData(key: String, base64: String) {
        if (!enabled || key.isEmpty() || base64.isEmpty()) return
        val bytes = try { Base64.getDecoder().decode(base64) } catch (e: Exception) { return }
        val stream = streams.getOrPut(key) { RelayStream(key) }
        stream.offer(bytes)
    }

    @Synchronized
    fun onStop() {
        clearStreams()
    }

    /** 第一次需要某首歌时向服务端要；每个 key 只请求一次 */
    fun requestOnce(key: String): Boolean = synchronized(this) {
        if (key in requested) return false
        requested += key
        true
    }

    fun ready(key: String): Boolean = synchronized(this) {
        (streams[key]?.available() ?: 0) >= PREBUFFER
    }

    /**
     * 拿某一首歌的流。换歌时把上一首的流关掉，解码线程读到 -1 就会退出，
     * 不然它会一直阻塞在这个流里。
     */
    fun stream(key: String): RelayStream = synchronized(this) {
        if (streams.keys.any { it != key }) {
            streams.entries.removeAll { (k, stream) ->
                if (k == key) false else { stream.close(); true }
            }
        }
        streams.getOrPut(key) { RelayStream(key) }
    }

    fun playing(key: String) {
        synchronized(this) { playingKey = key }
    }

    fun playingKey(): String = synchronized(this) { playingKey }
}

/**
 * 房主这边的转码器：把整首 MP3 一边解码成 PCM（顺手混成单声道），一边用 LAME 编成 CBR MP3。
 * LAME 的 Java 版大约能跑到 30~40 倍速，放歌时边放边编完全跟得上。
 */
private class Transcoder(
    val key: String,
    val kbps: Int,
    private val source: ByteArray,
    /** 已经发到的位置 + 允许领先的量；超过就先歇一会儿 */
    private val limit: () -> Int,
) {

    private var buffer = ByteArray(1 shl 20)
    private var size = 0
    private val lock = Object()

    @Volatile
    private var closed = false

    fun start() {
        Thread({ run() }, "Dango-RelayEnc").apply { isDaemon = true }.start()
    }

    private fun run() {
        var bitstream: Bitstream? = null
        var encoder: LameEncoder? = null
        try {
            val stream = Bitstream(ByteArrayInputStream(source))
            bitstream = stream
            val decoder = Decoder()
            val out = ByteArray(1 shl 16)
            var rate = 0
            while (!closed) {
                val header = try { stream.readFrame() } catch (e: Exception) { null } ?: break
                val samples = try {
                    decoder.decodeFrame(header, stream) as SampleBuffer
                } catch (e: Exception) {
                    stream.closeFrame()
                    continue
                }
                val sampleRate = samples.sampleFrequency
                if (encoder == null || rate != sampleRate) {
                    encoder?.close()
                    rate = sampleRate
                    val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                    encoder = LameEncoder(format, kbps, LameEncoder.CHANNEL_MODE_MONO, 5, false)
                }
                val pcm = monoPcm(samples)
                if (pcm.isNotEmpty()) {
                    val written = encoder.encodeBuffer(pcm, 0, pcm.size, out)
                    if (written > 0) append(out, written)
                }
                stream.closeFrame()
                // 编码比实时快几十倍，不看着点会把整首歌堆在内存里
                while (!closed && synchronized(lock) { size } > limit()) {
                    Thread.sleep(20)
                }
            }
            encoder?.let {
                val written = it.encodeFinish(out)
                if (written > 0) append(out, written)
            }
        } catch (e: Exception) {
            DangoLogger.warn("Relay transcode failed: {}", e.toString())
        } finally {
            try { encoder?.close() } catch (_: Exception) {}
            try { bitstream?.close() } catch (_: Exception) {}
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** JLayer 给的是交错的 short，混成单声道再交给 LAME */
    private fun monoPcm(samples: SampleBuffer): ByteArray {
        val buffer = samples.buffer ?: return ByteArray(0)
        val channels = samples.channelCount.coerceAtLeast(1)
        val length = samples.bufferLength
        if (length <= 0) return ByteArray(0)
        val frames = if (channels > 1) length / channels else length
        if (frames <= 0) return ByteArray(0)
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var value = 0
            for (c in 0 until channels) {
                val index = i * channels + c
                if (index < length) value += buffer[index].toInt()
            }
            val mono = (value / channels).coerceIn(-32768, 32767)
            out[i * 2] = (mono and 0xff).toByte()
            out[i * 2 + 1] = ((mono shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun append(data: ByteArray, length: Int) = synchronized(lock) {
        if (size + length > buffer.size) {
            var capacity = buffer.size
            while (capacity < size + length) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(data, 0, buffer, size, length)
        size += length
        lock.notifyAll()
    }

    fun available(): Int = synchronized(lock) { size }

    fun copyFrom(from: Int, length: Int): ByteArray = synchronized(lock) { buffer.copyOfRange(from, from + length) }

    fun close() {
        closed = true
    }
}

/**
 * 一个越写越长的阻塞流：解码线程读空了就在这里等，网速慢时自然放慢，
 * 不需要自己算缓冲时长。
 */
class RelayStream(private val key: String) : java.io.InputStream() {

    private val chunks = ArrayDeque<ByteArray>()
    private var head: ByteArray? = null
    /** 缓冲上限：暂停太久时不无限堆内存，多出来的直接丢，等漂移修正重接 */
    private val maxBuffer = 48 * 1024 * 1024
    private var headPos = 0
    @Volatile private var closed = false
    @Volatile private var total = 0L

    @Synchronized
    fun offer(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        if (total + bytes.size > maxBuffer) return
        chunks.addLast(bytes)
        total += bytes.size
        (this as java.lang.Object).notifyAll()
    }

    fun availableBytes(): Long = synchronized(this) { total }

    @Synchronized
    override fun available(): Int = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    @Synchronized
    override fun close() {
        closed = true
        chunks.clear()
        head = null
        (this as java.lang.Object).notifyAll()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            synchronized(this) {
                var current = head
                if (current == null || headPos >= current.size) {
                    current = chunks.removeFirstOrNull()
                    head = current
                    headPos = 0
                }
                if (current != null) {
                    val n = minOf(len, current.size - headPos)
                    System.arraycopy(current, headPos, b, off, n)
                    headPos += n
                    total -= n
                    return n
                }
                if (closed) return -1
                try {
                    (this as java.lang.Object).wait(500)
                } catch (e: InterruptedException) {
                    return -1
                }
            }
        }
    }

    override fun toString(): String = "RelayStream($key)"
}
