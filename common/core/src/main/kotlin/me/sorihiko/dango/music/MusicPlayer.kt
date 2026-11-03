package me.sorihiko.dango.music

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import me.sorihiko.dango.DangoLogger

import java.io.ByteArrayInputStream
import java.util.Random
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.max

/**
 * 内置网易云播放器：下载 mp3 → JLayer 解码 → Java Sound 输出（安卓等不带声音库的 Java 改用游戏的 OpenAL，见 [AudioOutput]）。
 * 播放进度 = 起始偏移 + 声卡实际已经播放的时长，所以歌词同步是精确的，拖动进度条也没问题。
 */
object MusicPlayer {

    enum class State { STOPPED, LOADING, PLAYING, PAUSED, ERROR }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var current: NeteaseTrack? = null
        private set
    @Volatile var message: String = ""
        private set

    @Volatile var queue: List<NeteaseTrack> = emptyList()
        private set
    @Volatile var index = -1
        private set

    @Volatile var shuffle = false
    /** 歌曲自然播完时重播当前曲目；手动切歌仍按队列 / Shuffle 工作。 */
    @Volatile var repeatOne = false
    /** 0..1 */
    @Volatile var volume = 0.2f

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicLoader").apply { isDaemon = true } }
    private val radioWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicRadio").apply { isDaemon = true } }

    /** 电台式的队列（私人 FM）：快放完时用它再要几首接在后面；普通歌单是 null */
    @Volatile var radio: (() -> List<NeteaseTrack>)? = null
        private set
    private val random = Random()

    /** 每次换歌 / 拖动都 +1，旧的解码线程看到代数变了就自己退出 */
    @Volatile private var generation = 0
    @Volatile private var audio: ByteArray? = null
    @Volatile private var line: AudioOutput? = null
    @Volatile private var seekBaseMs = 0L
    @Volatile private var pausedAtMs = 0L
    /** 中转播放时流并不是从歌曲开头开始，界面进度要加上这一段偏移 */
    @Volatile private var positionBaseMs = 0L

    val durationMs: Long get() = current?.durationMs ?: 0L

    // ---------------------------------------------------------------- 鼓点检测（文闪用）

    /** (歌曲内时间 ms, 强度 0..1)，解码时根据低频能量突增检测出来 */
    private class Beat(val timeMs: Long, val strength: Float)
    private val beats = ArrayDeque<Beat>()

    /**
     * 当前的"鼓点冲击"强度：最近一个鼓点之后迅速衰减，0..1。
     * posMs 用歌曲内时间（和歌词同一条时间线）。
     */
    fun beatPulse(posMs: Long): Float {
        var best = 0f
        synchronized(beats) {
            for (b in beats) {
                val dt = posMs - b.timeMs
                if (dt < 0 || dt > 600) continue
                best = max(best, b.strength * kotlin.math.exp(-dt / 110f))
            }
        }
        return best
    }

    private fun clearBeats() = synchronized(beats) { beats.clear() }

    /** 最近检测到的鼓点：[0] 时间（歌曲内 ms），[1] 强度 0..1，从早到晚 */
    fun beatSnapshot(): Array<FloatArray> = synchronized(beats) {
        Array(beats.size) { val b = beats.elementAt(it); floatArrayOf(b.timeMs.toFloat(), b.strength) }
    }

    val positionMs: Long
        get() = when (state) {
            State.PLAYING -> positionBaseMs + seekBaseMs + (line?.positionMicros ?: 0L) / 1000
            State.PAUSED -> pausedAtMs
            else -> 0L
        }

    val active: Boolean get() = current != null && (state == State.PLAYING || state == State.PAUSED || state == State.LOADING)

    // ---------------------------------------------------------------- 队列控制

    private val party get() = me.sorihiko.dango.party.ListenParty

    @JvmOverloads fun playQueue(tracks: List<NeteaseTrack>, startIndex: Int, radio: (() -> List<NeteaseTrack>)? = null) {
        // 一起听：点一首歌 = 按顺序加进房间的共享播放列表
        if (party.inRoom) {
            if (tracks.isNotEmpty()) party.enqueue(listOf(tracks[Math.floorMod(startIndex, tracks.size)]))
            return
        }
        if (tracks.isNotEmpty() && !canPlay(tracks[Math.floorMod(startIndex, tracks.size)])) return
        queue = tracks
        this.radio = radio
        playIndex(startIndex)
    }

    /** "播放全部"：一起听时整张列表按顺序加进共享播放列表 */
    fun playWholeQueue(tracks: List<NeteaseTrack>, radio: (() -> List<NeteaseTrack>)? = null) {
        if (party.inRoom) {
            party.enqueue(tracks)
            return
        }
        playQueue(tracks, 0, radio)
    }

    fun playIndex(i: Int) {
        val q = queue
        if (q.isEmpty()) return
        val idx = ((i % q.size) + q.size) % q.size
        val track = q[idx]
        // 一起听：点播放列表里的某一首 = 请服务端跳到那一首
        if (party.inRoom) {
            party.jumpTo(idx)
            return
        }
        if (!canPlay(track)) return
        index = idx
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track) }
        if (radio != null && idx >= q.size - 2) extendRadio(null)
    }

    /** 电台：再要几首接到队列后面（去掉已经在队列里的），然后执行 [then] */
    private fun extendRadio(then: (() -> Unit)?) {
        val fetch = radio ?: return
        radioWorker.execute {
            if (radio !== fetch) return@execute
            // 已经有好几首没放的就不用再要了（连续调用时只要一次）
            if (queue.size - index <= 2) {
                val more = try { fetch() } catch (e: Exception) {
                    DangoLogger.warn("Radio fetch failed: {}", e.toString())
                    emptyList()
                }
                if (radio !== fetch) return@execute
                val have = queue.mapTo(HashSet()) { it.key }
                val fresh = more.filter { have.add(it.key) }
                if (fresh.isNotEmpty()) queue = queue + fresh
            }
            then?.invoke()
        }
    }

    /**
     * 歌单还在加载时就开始放了：加载出更多后把队列换成更长的版本。
     * 只在队列还是 [from] 本身、并且 [to] 以它为开头时才换，当前这首和播放位置都不变。
     */
    fun growQueue(from: List<NeteaseTrack>, to: List<NeteaseTrack>) {
        if (queue !== from || radio != null || to.size <= from.size) return
        for (i in from.indices) if (from[i].key != to[i].key) return
        queue = to
    }

    /** 一起听：正在跟随房主（歌放完不自动下一首，等房主切） */
    @Volatile var followMode = false

    /** 当前声音来自房间里的中转流（不是本地文件、也不是平台直链） */
    @Volatile private var audioRelay = false
    /** Bilibili 的 AAC 音频（整段放在内存里，seek 直接按帧下标找） */
    @Volatile private var biliMedia: me.sorihiko.dango.music.bili.BiliTrack? = null

    private fun canPlay(track: NeteaseTrack): Boolean {
        if (me.sorihiko.dango.party.ListenParty.role == me.sorihiko.dango.party.ListenParty.Role.NONE || me.sorihiko.dango.party.ListenParty.supportsSource(track.source)) return true
        message = "This source is unavailable in this room. Create a new room with the updated mod."
        return false
    }

    /** 一起听：跟随房主播放某首歌。起始位置在下载完成、真正开始播放前才计算（下载要好几秒，提前算会落后）。 */
    fun playTrackAt(track: NeteaseTrack, startAt: () -> Long, paused: Boolean) {
        if (!canPlay(track)) return
        queue = listOf(track)
        radio = null
        index = 0
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track, startAt, paused) }
    }

    /** 一起听（房间内）：队列以服务端同步的共享播放列表为准，不覆盖它 */
    fun playRoomTrack(tracks: List<NeteaseTrack>, at: Int, startAt: () -> Long, paused: Boolean) {
        val track = tracks.getOrNull(at) ?: return
        if (!canPlay(track)) return
        queue = tracks
        radio = null
        index = at
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track, startAt, paused) }
    }

    /** 一起听：把播放列表镜像到播放器，供队列页面显示 */
    fun mirrorRoomQueue(tracks: List<NeteaseTrack>, at: Int) {
        queue = tracks
        index = at.coerceIn(-1, tracks.lastIndex)
    }

    /** 一起听：房主放成员点的歌，插在当前这首后面，放完接着原来的队列 */
    fun playNext(track: NeteaseTrack) {
        val q = queue
        if (q.isEmpty() || index !in q.indices) {
            queue = listOf(track)
            playIndex(0)
            return
        }
        queue = q.subList(0, index + 1) + track + q.subList(index + 1, q.size)
        playIndex(index + 1)
    }

    /** 一起听：跳到 ms 并设成播放 / 暂停（当前这首已经下载好时用） */
    fun syncTo(ms: Long, play: Boolean) {
        // B 站曲目没有整段字节，只有解出来的帧表：按帧重新起播
        val bili = if (current?.source == MusicSource.BILIBILI) biliMedia else null
        val bytes = audio
        if (bili == null && bytes == null) return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        pausedAtMs = target
        state = if (play) State.PLAYING else State.PAUSED   // 解码线程据此决定声卡是开着还是停着
        val gen = ++generation
        stopLine()
        if (bili != null) startDecodeAac(gen, bili, target) else startDecode(gen, bytes!!, target)
    }

    fun next() {
        if (party.inRoom) {
            party.requestNext()
            return
        }
        val q = queue
        if (q.isEmpty()) return
        // 电台按顺序往下放；放到最后一首时先要几首新的再切
        if (radio != null) {
            if (index + 1 < q.size) playIndex(index + 1)
            else { val from = index; extendRadio { if (index == from) playIndex(from + 1) } }
            return
        }
        playIndex(if (shuffle && q.size > 1) pickRandom(q.size) else index + 1)
    }

    fun prev() {
        if (party.inRoom) {
            party.requestPrev()
            return
        }
        if (positionMs > 3000) {
            seek(0)
            return
        }
        playIndex(index - 1)
    }

    private fun pickRandom(size: Int): Int {
        var r: Int
        do { r = random.nextInt(size) } while (r == index)
        return r
    }

    fun togglePause() {
        // 一起听：房主本地生效并由 tick 上报；成员要房主开放了暂停权限才生效
        if (party.inRoom && !party.isHost) {
            if (!party.canPause) {
                party.deny("房主没有开放暂停/拖进度权限")
                return
            }
            if (state == State.PLAYING || state == State.PAUSED) {
                party.requestState(if (state == State.PLAYING) me.sorihiko.dango.party.TogetherProtocol.OP_PAUSE
                    else me.sorihiko.dango.party.TogetherProtocol.OP_RESUME, positionMs)
            }
        }
        when (state) {
            State.PLAYING -> {
                pausedAtMs = positionMs
                state = State.PAUSED
                line?.stop()
            }
            State.PAUSED -> {
                val l = line
                if (l != null) {
                    // 声卡输出只是停住了，接着放就行；解码线程看到状态变回 PLAYING 会继续写数据
                    state = State.PLAYING
                    l.start()
                } else {
                    seekLocal(pausedAtMs)
                }
            }
            else -> {}
        }
    }

    fun seek(ms: Long) {
        val bili = current?.source == MusicSource.BILIBILI
        if (party.inRoom && !party.isHost) {
            if (!party.canPause) {
                party.deny("房主没有开放暂停/拖进度权限")
                return
            }
            party.requestState(me.sorihiko.dango.party.TogetherProtocol.OP_SEEK, ms.coerceIn(0L, max(0L, durationMs - 500)))
            // 走中转收听时本机没有整首歌，进度由房主带头，这里不再本地跳
            if (audioRelay) return
            if (!bili && audio == null) return
        }
        if (bili) {
            seekAac(ms)
            return
        }
        if (audio == null) return
        seekLocal(ms)
    }

    private fun seekLocal(ms: Long) {
        val bytes = audio ?: return
        positionBaseMs = 0L
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        // 暂停时拖动：停在新位置，仍保持暂停
        if (state == State.PAUSED) pausedAtMs = target
        val gen = ++generation
        stopLine()
        startDecode(gen, bytes, target)
    }

    fun stop() {
        ++generation
        stopLine()
        audio = null
        biliMedia = null
        current = null
        state = State.STOPPED
        message = ""
    }

    // ---------------------------------------------------------------- 加载 / 解码

    private fun load(gen: Int, track: NeteaseTrack, startAt: (() -> Long)? = null, paused: Boolean = false) {
        audioRelay = false
        positionBaseMs = 0L
        // 换歌先把上一首的帧表丢掉：失败时若还留着，syncTo / seek 会拿旧歌的音频去播新歌
        biliMedia = null
        try {
            if (track.source == MusicSource.LOCAL) {
                // 房间里的本地歌：成员本来就没有这个文件，缺的是房主的中转，不是"刷新本地音乐"
                if (LocalLibrary.entry(track) == null && party.inRoom && !party.isHost) {
                    return fail(gen, "这首歌在你这边没有，需要房主开启本地音乐共享")
                }
                val bytes = LocalLibrary.read(track)
                if (gen != generation) return
                if (bytes == null || bytes.size < 10_000) return fail(gen, "找不到这个本地文件了，刷新一下本地音乐")
                audio = bytes
                message = ""
                trackGain = 1f
                val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
                if (paused) {
                    pausedAtMs = start
                    state = State.PAUSED
                } else {
                    startDecode(gen, bytes, start)
                }
                return
            }
            if (track.source == MusicSource.BILIBILI) {
                val media = BiliSource.open(track)
                if (gen != generation) return
                if (media == null) return fail(gen, "B 站音频取不到：链接可能过期、需要登录，或者这个稿件没有音轨")
                biliMedia = media
                audio = null
                message = ""
                trackGain = 1f
                val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
                if (paused) {
                    pausedAtMs = start
                    state = State.PAUSED
                } else {
                    startDecodeAac(gen, media, start)
                }
                return
            }
            val url = MusicSources.api(track.source).songUrl(track)
            if (gen != generation) return
            if (url == null) return fail(gen, if (track.source == MusicSource.NETEASE) "拿不到播放地址"
                else if (NativeAccounts.get(track.source) != null) "No playable MP3. Your login may have expired, or this song is restricted by membership or region."
                else "No guest MP3 available. Login, payment or regional restrictions may apply.")
            val bytes = if (track.source == MusicSource.NETEASE) NeteaseApi.request(url.url)
                else SourceHttp.bytes(url.url, if (track.source == MusicSource.QQ) "https://y.qq.com/" else "https://m.kugou.com/")
            if (gen != generation) return
            if (bytes == null || bytes.size < 10_000) {
                return fail(gen, if (track.source != MusicSource.NETEASE) "Unable to download this guest MP3. Try another song."
                    else if (NeteaseApi.loggedIn) "这首歌没有播放权限" else "需要登录才能播放（VIP 歌曲）")
            }
            audio = bytes
            message = when {
                url.trialOnly -> "Preview only: membership is needed for the full song"
                url.replaced -> "The original was unavailable, playing another version"
                else -> ""
            }
            if (url.trialOnly && party.inRoom) {
                me.sorihiko.dango.notice.Notices.post(
                    "《${track.name}》在你这边只能试听：其他玩家如果没有会员，可能也放不了完整版",
                    me.sorihiko.dango.notice.Notices.WARN,
                )
            }
            trackGain = loudnessGain(url)
            if (url.gainDb != 0f) DangoLogger.info("Loudness: {} gain {} dB, peak {}, applied x{}", track.source.key, url.gainDb, url.peak, trackGain)
            val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
            if (paused) {
                pausedAtMs = start
                state = State.PAUSED
            }
            startDecode(gen, bytes, start)
        } catch (e: Throwable) {
            // 包括 NoClassDefFoundError 之类的 Error：不接住的话线程会悄悄退出，界面一直停在"加载中"
            if (track.source == MusicSource.NETEASE) {
                DangoLogger.warn("Music load failed: {}", e.toString())
                fail(gen, "加载失败：" + describe(e))
            } else {
                DangoLogger.warn("Source {} load failed: {}", track.source.key, e.javaClass.simpleName)
                fail(gen, if (e is SourceAccessException) e.message.orEmpty() else "Playback request failed. Check your connection or log in again.")
            }
        }
    }

    /** 放不了就提示一下；房间内不自动跳歌，由 ListenParty 定时重试 */
    private fun fail(gen: Int, msg: String) {
        if (gen != generation) return
        state = State.ERROR
        message = msg
        if (party.inRoom) return
        if (current?.source != MusicSource.NETEASE) return
        loader.execute {
            Thread.sleep(1500)
            if (gen == generation && queue.size > 1 && current?.source == MusicSource.NETEASE) next()
        }
    }

    private fun describe(e: Throwable): String {
        val m = e.message?.take(60)
        return e.javaClass.simpleName + if (m.isNullOrBlank()) "" else ": $m"
    }

    /**
     * 打开声卡输出。默认设备打不开时（被别的程序独占、格式不支持等），
     * 依次试系统里其它能输出这个格式的设备。
     */
    /** 测试用：-Ddangomusic.openal=true 时电脑上也用游戏的 OpenAL 放歌 */
    private val forceOpenAl = java.lang.Boolean.getBoolean("dangomusic.openal")
    private var loggedOutput = ""

    /**
     * 打开声音输出：Java 自己带声音库时用 Java Sound（电脑上都是这样）；
     * 不带（安卓）或者强制时先用游戏的 OpenAL，打不开再退回 Java Sound；Java Sound 打不开也试一下 OpenAL。
     */
    private fun openOutput(format: AudioFormat, bufferBytes: Int): AudioOutput {
        if (forceOpenAl || !AudioOutput.javaSoundNative) platformOutput(format)?.let { return it }
        return try {
            JavaSoundOutput(openLine(format, bufferBytes)).also { logOutput("Java Sound") }
        } catch (e: Throwable) {
            platformOutput(format) ?: throw e
        }
    }

    private fun platformOutput(format: AudioFormat): AudioOutput? = try {
        me.sorihiko.dango.DangoMusic.instance.platform.openAudioOutput(format.sampleRate.toInt(), format.channels)
            ?.also { logOutput("game OpenAL") }
    } catch (e: Throwable) {
        DangoLogger.warn("Game audio output failed: {}", e.toString())
        null
    }

    private fun logOutput(name: String) {
        if (loggedOutput == name) return
        loggedOutput = name
        DangoLogger.info("BuWang Music: playing through {}", name)
    }

    private fun openLine(format: AudioFormat, bufferBytes: Int): SourceDataLine {
        var last: Throwable? = null
        try {
            val l = AudioSystem.getSourceDataLine(format)
            try {
                l.open(format, bufferBytes)
            } catch (e: Exception) {
                l.open(format)
            }
            return l
        } catch (e: Throwable) {
            last = e
            DangoLogger.warn("Default audio line failed: {}", e.toString())
        }
        val info = DataLine.Info(SourceDataLine::class.java, format)
        for (mi in AudioSystem.getMixerInfo()) {
            try {
                val mixer = AudioSystem.getMixer(mi)
                if (!mixer.isLineSupported(info)) continue
                val l = mixer.getLine(info) as SourceDataLine
                l.open(format)
                DangoLogger.info("Using audio device: {}", mi.name)
                return l
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的声音输出设备")
    }

    /** 音量均衡（设置里开关）：按音源给的响度把每首歌调到差不多响 */
    @Volatile var normalize = true
    /** 这首歌要乘的倍数（1 = 原样） */
    @Volatile private var trackGain = 1f

    private fun loudnessGain(url: NeteaseApi.SongUrl): Float {
        if (!normalize || url.gainDb == 0f) return 1f
        var gain = Math.pow(10.0, url.gainDb / 20.0).toFloat()
        // 有峰值就别把峰值推过 1（会破音）
        if (url.peak > 0f) gain = minOf(gain, 0.98f / url.peak)
        return gain.coerceIn(0.25f, 2f)
    }

    private fun startDecode(gen: Int, bytes: ByteArray, startMs: Long) =
        startDecode(gen, java.io.ByteArrayInputStream(bytes), startMs)

    /** 一起听歌的本地音乐中转：字节从网络里一块块来，解码线程读到一个会阻塞的流上 */
    private fun startDecode(gen: Int, input: java.io.InputStream, startMs: Long) {
        Thread({ decodeLoop(gen, input, startMs) }, "Dango-MusicDecode").apply {
            isDaemon = true
            start()
        }
    }

    /** 中转来的本地歌曲：队列还是房间里的共享队列，声音从 [stream] 里读 */
    fun playRelayTrack(tracks: List<NeteaseTrack>, at: Int, stream: java.io.InputStream, baseMs: Long = 0L) {
        val track = tracks.getOrNull(at) ?: return
        val gen = ++generation
        stopLine()
        audioRelay = true
        positionBaseMs = baseMs.coerceAtLeast(0L)
        queue = tracks
        radio = null
        index = at
        current = track
        state = State.LOADING
        message = ""
        DangoLogger.info("Relay stream: {}", track.name)
        loader.execute {
            if (gen == generation) startDecode(gen, stream, 0L)
        }
    }

    private fun decodeLoop(gen: Int, input: java.io.InputStream, startMs: Long) {
        var out: AudioOutput? = null
        var finished = false
        try {
            val bitstream = Bitstream(input)
            val decoder = Decoder()

            // 跳到目标位置：只读帧头不解码，很快
            var skipped = 0f
            while (skipped < startMs) {
                val h = bitstream.readFrame() ?: break
                skipped += h.ms_per_frame()
                bitstream.closeFrame()
            }
            if (gen != generation) return
            seekBaseMs = skipped.toLong()
            clearBeats()
            EnvironmentAudio.reset()
            var decodedMs = skipped
            var lowPass = 0f
            var avgEnergy = 0f
            var lastBeatMs = -1000f

            var appliedVolume = -1f
            while (gen == generation) {
                val header = bitstream.readFrame()
                if (header == null) {
                    finished = true
                    break
                }
                val sb = try {
                    decoder.decodeFrame(header, bitstream) as SampleBuffer
                } catch (_: Exception) {
                    bitstream.closeFrame()
                    continue
                }
                if (out == null) {
                    val format = AudioFormat(header.frequency().toFloat(), 16, sb.channelCount, true, false)
                    out = openOutput(format, header.frequency() * sb.channelCount * 2 / 4)   // 约 0.25 秒缓冲
                    line = out
                    if (state == State.PAUSED) {
                        out.stop()
                    } else {
                        out.start()
                        state = State.PLAYING
                    }
                }
                // 暂停：不再往声卡写数据，原地等待。
                // （Java Sound 在输出停止时被写数据会自动重新开始或直接返回，
                //   不这样等的话会一口气把整首歌"读完"，然后误以为放完了跳下一首）
                while (state == State.PAUSED && gen == generation) {
                    if (out.isRunning) out.stop()   // 写数据时可能被自动重新开始，确保停住
                    Thread.sleep(15)
                }
                if (gen != generation) break
                if (volume != appliedVolume) {
                    out.setVolume(volume)
                    appliedVolume = volume
                }
                val len = sb.bufferLength
                val pcm = sb.buffer

                // 鼓点：低频（底鼓）能量比最近一秒的平均值突然高出很多
                val frameMs = header.ms_per_frame()
                val ch = sb.channelCount.coerceAtLeast(1)
                var energy = 0f
                var k = 0
                while (k + ch - 1 < len) {
                    var mono = 0f
                    for (c in 0 until ch) mono += pcm[k + c].toFloat()
                    mono /= ch * 32768f
                    lowPass += (mono - lowPass) * 0.04f
                    energy += lowPass * lowPass
                    k += ch
                }
                energy /= (len / ch).coerceAtLeast(1)
                if (avgEnergy > 0f && energy > avgEnergy * 1.7f && energy > 2e-4f && decodedMs - lastBeatMs > 170f) {
                    val strength = ((energy / avgEnergy - 1.7f) / 2f + 0.45f).coerceIn(0f, 1f)
                    synchronized(beats) {
                        beats.addLast(Beat(decodedMs.toLong(), strength))
                        while (beats.size > 48) beats.removeFirst()
                    }
                    lastBeatMs = decodedMs
                }
                avgEnergy = if (avgEnergy == 0f) energy else avgEnergy + (energy - avgEnergy) * (frameMs / 1000f).coerceAtMost(1f)
                decodedMs += frameMs
                // 音量均衡、环境音效（水下变闷、洞穴回声……）在鼓点检测之后加，不影响节奏判断
                val gain = trackGain
                if (gain != 1f) for (idx in 0 until len) pcm[idx] = (pcm[idx] * gain).toInt().coerceIn(-32768, 32767).toShort()
                EnvironmentAudio.process(pcm, len, ch, header.frequency(), frameMs)
                val data = ByteArray(len * 2)
                for (k in 0 until len) {
                    val v = pcm[k].toInt()
                    data[k * 2] = (v and 0xFF).toByte()
                    data[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(data, 0, data.size)
                bitstream.closeFrame()
            }
            if (finished && gen == generation) {
                out?.drain()
            }
        } catch (e: Throwable) {
            DangoLogger.warn("Music decode failed: {}", e.toString())
            if (gen == generation) {
                state = State.ERROR
                message = "播放失败：" + describe(e)
            }
        } finally {
            if (line === out) line = null
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
        if (finished && gen == generation) {
            if (party.inRoom) {
                // 房间内放完：停在结尾，等房主/服务端切下一首
                pausedAtMs = durationMs
                state = State.PAUSED
                party.onTrackFinished()
            } else if (followMode) {
                // 跟随房主时放完就停在结尾，等房主切下一首
                pausedAtMs = durationMs
                state = State.PAUSED
            } else if (repeatOne) {
                // 复用已下载的音频，从头解码，不重新请求播放地址或下载。
                seek(0L)
            } else {
                next()
            }
        }
    }

    /** 拖进度：AAC 直接跳到对应帧，不重新下载 */
    private fun seekAac(ms: Long) {
        val media = biliMedia ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        if (state == State.PAUSED) pausedAtMs = target
        positionBaseMs = 0L
        val gen = ++generation
        stopLine()
        startDecodeAac(gen, media, target)
    }

    private fun startDecodeAac(gen: Int, media: me.sorihiko.dango.music.bili.BiliTrack, startMs: Long) {
        Thread({ decodeAacLoop(gen, media, startMs) }, "Dango-MusicDecode").apply {
            isDaemon = true
            start()
        }
    }

    /** 与 mp3 的 decodeLoop 同一套流程：解码、暂停等待、鼓点、音量均衡、环境音效。 */
    private fun decodeAacLoop(gen: Int, media: me.sorihiko.dango.music.bili.BiliTrack, startMs: Long) {
        var out: AudioOutput? = null
        var finished = false
        try {
            val first = media.indexAt(startMs)
            seekBaseMs = first.toLong() * 1024L * 1000L / media.sampleRate
            clearBeats()
            EnvironmentAudio.reset()
            val decoder = media.newDecoder()
            val buffer = media.newBuffer()
            val frameMs = media.frameMs.toFloat()
            val channels = media.channels
            var index = first
            var decodedMs = seekBaseMs.toFloat()
            var lowPass = 0f
            var avgEnergy = 0f
            var lastBeatMs = -1000f
            var appliedVolume = -1f
            while (gen == generation) {
                if (index >= media.frameCount) {
                    finished = true
                    break
                }
                val pcmBytes = try {
                    decoder.decodeFrame(media.frame(index), buffer)
                    buffer.data
                } catch (_: Throwable) {
                    null
                }
                index++
                if (pcmBytes == null || pcmBytes.isEmpty()) continue
                if (out == null) {
                    val format = AudioFormat(media.sampleRate.toFloat(), 16, channels, true, false)
                    out = openOutput(format, media.sampleRate * channels * 2 / 4)   // 约 0.25 秒缓冲
                    line = out
                    if (state == State.PAUSED) {
                        out.stop()
                    } else {
                        out.start()
                        state = State.PLAYING
                    }
                }
                while (state == State.PAUSED && gen == generation) {
                    if (out.isRunning) out.stop()
                    Thread.sleep(15)
                }
                if (gen != generation) break
                if (volume != appliedVolume) {
                    out.setVolume(volume)
                    appliedVolume = volume
                }
                val samples = pcmBytes.size / 2
                val pcm = ShortArray(samples)
                for (k in 0 until samples) {
                    pcm[k] = ((pcmBytes[k * 2].toInt() and 0xFF) or (pcmBytes[k * 2 + 1].toInt() shl 8)).toShort()
                }
                val ch = channels.coerceAtLeast(1)
                var energy = 0f
                var k = 0
                while (k + ch - 1 < samples) {
                    var mono = 0f
                    for (c in 0 until ch) mono += pcm[k + c].toFloat()
                    mono /= ch * 32768f
                    lowPass += (mono - lowPass) * 0.04f
                    energy += lowPass * lowPass
                    k += ch
                }
                energy /= (samples / ch).coerceAtLeast(1)
                if (avgEnergy > 0f && energy > avgEnergy * 1.7f && energy > 2e-4f && decodedMs - lastBeatMs > 170f) {
                    val strength = ((energy / avgEnergy - 1.7f) / 2f + 0.45f).coerceIn(0f, 1f)
                    synchronized(beats) {
                        beats.addLast(Beat(decodedMs.toLong(), strength))
                        while (beats.size > 48) beats.removeFirst()
                    }
                    lastBeatMs = decodedMs
                }
                avgEnergy = if (avgEnergy == 0f) energy else avgEnergy + (energy - avgEnergy) * (frameMs / 1000f).coerceAtMost(1f)
                decodedMs += frameMs
                val gain = trackGain
                if (gain != 1f) for (i in 0 until samples) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                EnvironmentAudio.process(pcm, samples, ch, media.sampleRate, frameMs)
                val data = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val value = pcm[i].toInt()
                    data[i * 2] = (value and 0xFF).toByte()
                    data[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                out.write(data, 0, data.size)
            }
            if (finished && gen == generation) {
                out?.drain()
            }
        } catch (e: Throwable) {
            DangoLogger.warn("Bilibili decode failed: {}", e.toString())
            if (gen == generation) {
                state = State.ERROR
                message = "播放失败：" + describe(e)
            }
        } finally {
            if (line === out) line = null
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
        if (finished && gen == generation) {
            if (party.inRoom) {
                pausedAtMs = durationMs
                state = State.PAUSED
                party.onTrackFinished()
            } else if (followMode) {
                pausedAtMs = durationMs
                state = State.PAUSED
            } else if (repeatOne) {
                seek(0L)
            } else {
                next()
            }
        }
    }

    private fun stopLine() {
        val l = line
        line = null
        try {
            l?.stop()
            l?.flush()
            l?.close()
        } catch (_: Exception) {
        }
    }
}
