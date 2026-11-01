package me.sorihiko.dango.music

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 本地音乐库：把用户挑选的若干个文件夹里的音频扫描成歌曲列表。
 *
 * 本地歌曲仍然是 [NeteaseTrack]，只是 source = [MusicSource.LOCAL]，nativeId 用
 * 路径指纹。这样播放器、播放列表、一起听歌都不用改数据结构。
 */
object LocalLibrary {

    private val AUDIO_EXTENSIONS = setOf("mp3")
    /** 中转要整首缓冲，超过这个大小就不读，避免把内存吃满 */
    private const val MAX_READ_BYTES = 128L * 1024 * 1024

    /** 一条本地歌曲：track 给界面用，path 是真实文件，album/folder 给筛选和分组用 */
    class Entry(
        val track: NeteaseTrack,
        val path: String,
        val album: String,
        /** 所属文件夹的完整路径（筛选按它比，避免同名文件夹串味） */
        val root: String,
        /** 所属文件夹的显示名 */
        val folder: String,
    )

    @Volatile var entries: List<Entry> = emptyList()
        private set

    @Volatile var folders: List<String> = emptyList()
        private set

    @Volatile var scanning = false
        private set

    private val byKey = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-LocalScan").apply { isDaemon = true } }
    private var lastWatch = 0L
    /** 扫描途中又有人要求刷新时，等这一轮结束后再扫一次 */
    private var rescan = false
    private val folderStamps = HashMap<String, Long>()
    private var configFile: Path? = null
    @Volatile private var loaded = false
    @Volatile private var watching = false

    // ------------------------------------------------------------ 配置

    private fun config(): Path {
        val cached = configFile
        if (cached != null) return cached
        val path = DangoMusic.instance.clientDir.resolve("local.json")
        configFile = path
        return path
    }

    /**
     * 模组自己的音乐目录：config/dangomusic/music。
     * 默认就在扫描列表里，玩家把 mp3 丢进去就行，不用在游戏里敲路径。
     */
    val musicDir: java.io.File
        get() = DangoMusic.instance.clientDir.resolve("music").toFile()

    fun load() {
        if (loaded) {
            // 已经载入过：只有列表还空着（例如上次扫描没找到文件）才补扫一次
            if (entries.isEmpty() && !scanning) refresh()
            return
        }
        loaded = true
        val managed = musicDir
        try {
            managed.mkdirs()
        } catch (e: Exception) {
            DangoLogger.warn("Cannot create local music folder: {}", e.toString())
        }
        val list = readConfig().toMutableList()
        val managedPath = try { managed.canonicalPath } catch (e: Exception) { managed.absolutePath }
        if (list.none { it.equals(managedPath, ignoreCase = true) } && managed.isDirectory) {
            list.add(0, managedPath)
            writeConfig(list)
        }
        folders = list
        refresh()
    }

    /** 系统文件夹选择器的结果：path 为空表示用户取消；supported=false 表示这个系统没有 */
    class FolderPick(val path: String?, val supported: Boolean)

    /**
     * Windows 上拉系统自带的文件夹选择框（和资源管理器里"打开文件夹"同一个对话框）。
     * 只在客户端用，不用引入 AWT，也不会和 GLFW 抢线程。
     */
    fun pickFolder(): FolderPick {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("win")) return FolderPick(null, false)
        return try {
            val script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;" +
                "Add-Type -AssemblyName System.Windows.Forms;" +
                "\$d=New-Object System.Windows.Forms.FolderBrowserDialog;" +
                "\$d.Description='选择音乐文件夹';" +
                "\$d.ShowNewFolderButton=\$true;" +
                "if(\$d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK){Write-Output \$d.SelectedPath}"
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-WindowStyle", "Hidden", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
            FolderPick(text.ifEmpty { null }, true)
        } catch (e: Exception) {
            DangoLogger.warn("Folder picker failed: {}", e.toString())
            FolderPick(null, true)
        }
    }

    /** 文件夹的显示名 */
    fun label(path: String): String = path.substringAfterLast(java.io.File.separatorChar).ifEmpty { path }

    /** 是不是模组自己的音乐目录（这个不允许删，删了下次加载也会回来） */
    fun isManaged(path: String): Boolean {
        val managed = try { musicDir.canonicalPath } catch (e: Exception) { musicDir.absolutePath }
        return path.equals(managed, ignoreCase = true)
    }

    /** 用系统资源管理器打开文件夹；打开不了返回 false */
    fun reveal(folder: String): Boolean {
        val dir = java.io.File(folder)
        if (!dir.isDirectory) return false
        return try {
            var opened = false
            if (java.awt.Desktop.isDesktopSupported()) {
                val desktop = java.awt.Desktop.getDesktop()
                if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                    desktop.open(dir)
                    opened = true
                }
            }
            if (!opened) {
                val os = System.getProperty("os.name").orEmpty().lowercase()
                val command = when {
                    os.contains("win") -> arrayOf("explorer", dir.absolutePath)
                    os.contains("mac") -> arrayOf("open", dir.absolutePath)
                    else -> arrayOf("xdg-open", dir.absolutePath)
                }
                ProcessBuilder(*command).start()
                opened = true
            }
            opened
        } catch (e: Exception) {
            DangoLogger.warn("Cannot open folder {}: {}", folder, e.toString())
            false
        }
    }

    private fun readConfig(): List<String> {
        val file = config().toFile()
        if (!file.isFile) return emptyList()
        return try {
            val root = com.google.gson.JsonParser().parse(file.readText()).asJsonObject
            root.getAsJsonArray("folders")?.mapNotNull { it.asString?.takeIf { p -> p.isNotBlank() } } ?: emptyList()
        } catch (e: Exception) {
            DangoLogger.warn("Local music config is broken: {}", e.toString())
            emptyList()
        }
    }

    private fun writeConfig(list: List<String>) {
        try {
            val body = list.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
            config().toFile().writeText("{\"folders\":[" + body + "]}")
        } catch (e: Exception) {
            DangoLogger.warn("Cannot write local music config: {}", e.toString())
        }
    }

    /** 添加一个文件夹；返回给用户看的错误，成功返回 null */
    @Synchronized
    fun addFolder(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "请输入文件夹路径"
        val path = Paths.get(text)
        if (!Files.isDirectory(path)) return "找不到这个文件夹"
        val realPath = try { path.toRealPath() } catch (e: Exception) { path.toAbsolutePath() }
        if (realPath.parent == null) return "别选整个磁盘，选一个具体的音乐文件夹"
        val real = realPath.toString()
        if (folders.any { it.equals(real, ignoreCase = true) }) return "这个文件夹已经添加过了"
        folders = folders + real
        writeConfig(folders)
        refresh()
        return null
    }

    @Synchronized
    fun removeFolder(raw: String) {
        if (isManaged(raw)) return
        folders = folders.filterNot { it.equals(raw, ignoreCase = true) }
        writeConfig(folders)
        refresh()
    }

    // ------------------------------------------------------------ 扫描

    fun refresh() {
        if (scanning) {
            rescan = true
            return
        }
        scanning = true
        val dirs = folders
        worker.execute {
            val found = ArrayList<Entry>()
            val stamps = HashMap<String, Long>()
            for (dir in dirs) {
                val root = try { Paths.get(dir) } catch (e: Exception) { continue }
                stamps[dir] = stamp(root)
                scanFolder(root, found)
            }
            val sorted = found.sortedWith(compareBy({ it.track.name.lowercase() }, { it.track.artists.firstOrNull().orEmpty() }))
            byKey.clear()
            sorted.forEach { byKey[it.track.key] = it }
            entries = sorted
            synchronized(folderStamps) {
                folderStamps.clear()
                folderStamps.putAll(stamps)
            }
            scanning = false
            DangoLogger.info("Local music: {} tracks from {} folders", sorted.size, dirs.size)
            if (rescan) {
                rescan = false
                refresh()
            }
        }
    }

    /**
     * 文件夹内容变了就自动重扫：界面每帧可以随便调，真正的目录遍历在扫描线程里做。
     */
    fun watch() {
        if (scanning || watching) return
        val now = System.currentTimeMillis()
        if (now - lastWatch < 5_000L) return
        lastWatch = now
        watching = true
        worker.execute {
            try {
                val dirs = folders
                if (dirs.isEmpty()) return@execute
                var changed = false
                val stamps = HashMap<String, Long>()
                for (dir in dirs) {
                    val current = try { stamp(Paths.get(dir)) } catch (e: Exception) { -1L }
                    stamps[dir] = current
                    synchronized(folderStamps) { if (folderStamps[dir] != current) changed = true }
                }
                if (changed || (entries.isEmpty() && dirs.isNotEmpty())) {
                    synchronized(folderStamps) { folderStamps.putAll(stamps) }
                    refresh()
                }
            } catch (e: Exception) {
                DangoLogger.warn("Local music watch failed: {}", e.toString())
            } finally {
                watching = false
            }
        }
    }

    private fun stamp(root: Path): Long = try {
        Files.walk(root, 6).use { stream ->
            stream.filter { Files.isRegularFile(it) && isAudio(it) }
                .mapToLong { runCatching { Files.getLastModifiedTime(it).toMillis() + Files.size(it) }.getOrDefault(0L) }
                .max().orElse(0L)
        }
    } catch (e: Exception) {
        -1L
    }

    private fun isAudio(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase() ?: return false
        val dot = name.lastIndexOf('.')
        return dot > 0 && name.substring(dot + 1) in AUDIO_EXTENSIONS
    }

    private fun scanFolder(root: Path, out: MutableList<Entry>) {
        val folderName = root.fileName?.toString() ?: root.toString()
        val rootPath = try { root.toRealPath().toString() } catch (e: Exception) { root.toAbsolutePath().toString() }
        try {
            Files.walk(root, 6).use { stream ->
                stream.filter { Files.isRegularFile(it) && isAudio(it) }.forEach { file ->
                    val entry = try { entryOf(file, folderName, rootPath) } catch (e: Exception) { null }
                    if (entry != null) out += entry
                }
            }
        } catch (e: Exception) {
            DangoLogger.warn("Local music: cannot scan {}: {}", root, e.toString())
        }
    }

    private fun entryOf(file: Path, folderName: String, rootPath: String): Entry {
        val size = Files.size(file)
        if (size < 4096) throw IllegalStateException("too small")
        val tags = Mp3Tags.read(file)
        val name = tags.title.ifBlank { file.fileName.toString().substringBeforeLast('.') }
        val artist = tags.artist.ifBlank { "未知歌手" }
        val album = tags.album.ifBlank { folderName }
        val duration = tags.durationMs.coerceAtLeast(1000L)
        val id = "l:" + fingerprint(file, size)
        val track = NeteaseTrack(
            id = MusicSources.stableId(MusicSource.LOCAL, id),
            name = name,
            artists = listOf(artist),
            durationMs = duration,
            coverUrl = "",
            source = MusicSource.LOCAL,
            nativeId = id,
        )
        return Entry(track, file.toAbsolutePath().toString(), album, rootPath, folderName)
    }

    private fun fingerprint(file: Path, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val text = "${file.toAbsolutePath()}|$size|${runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)}"
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    // ------------------------------------------------------------ 读取

    fun entry(track: NeteaseTrack): Entry? = byKey[track.key]

    fun read(track: NeteaseTrack): ByteArray? {
        val entry = entry(track) ?: return null
        return try {
            val file = Paths.get(entry.path)
            if (Files.size(file) > MAX_READ_BYTES) {
                DangoLogger.warn("Local music: file too large to buffer: {}", file.fileName)
                return null
            }
            Files.readAllBytes(file)
        } catch (e: Exception) {
            DangoLogger.warn("Local music: cannot read {}: {}", track.name, e.toString())
            null
        }
    }

}

/** 只读 mp3 标签和时长；解析失败时退回文件名。 */
internal object Mp3Tags {

    class Tags(val title: String, val artist: String, val album: String, val durationMs: Long)

    private val MPEG1_LAYER3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_LAYER3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATE1 = intArrayOf(44100, 48000, 32000)
    private val RATE2 = intArrayOf(22050, 24000, 16000)

    fun read(file: Path): Tags {
        val size = Files.size(file)
        val head = Files.newInputStream(file).use { input -> input.readNBytes(256 * 1024) }
        var title = ""
        var artist = ""
        var album = ""
        var audioStart = 0
        // ID3v2
        if (head.size > 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val tagSize = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
            audioStart = 10 + tagSize
            var pos = 10
            val end = minOf(head.size, audioStart)
            while (pos + 10 <= end) {
                val id = String(head, pos, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val frameSize = ((head[pos + 4].toInt() and 0xff) shl 24) or ((head[pos + 5].toInt() and 0xff) shl 16) or
                    ((head[pos + 6].toInt() and 0xff) shl 8) or (head[pos + 7].toInt() and 0xff)
                if (frameSize <= 0 || pos + 10 + frameSize > end) break
                val body = pos + 10
                when (id) {
                    "TIT2" -> title = text(head, body, frameSize)
                    "TPE1" -> artist = text(head, body, frameSize)
                    "TALB" -> album = text(head, body, frameSize)
                }
                pos = body + frameSize
            }
        }
        var duration = cbrDuration(head, audioStart, size)
        if (duration <= 0) duration = durationOfFile(file, audioStart)
        if (duration <= 0) duration = (size * 8) / (128 * 1000) * 1000
        return Tags(title.ifBlank { "" }, artist.ifBlank { "" }, album.ifBlank { "" }, duration)
    }

    private fun text(data: ByteArray, offset: Int, length: Int): String {
        if (length <= 1) return ""
        val encoding = data[offset].toInt() and 0xff
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return try {
            String(data, offset + 1, length - 1, charset).trim('\u0000', ' ', '\uFFFE', '\uFEFF')
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 逐帧走完整个文件算时长，VBR 也能算准；帧头对不上就退一个字节重新对齐。
     */
    /** 帧头：用来判断码率是否恒定 */
    private class Frame(val mpeg1: Boolean, val bitrate: Int, val sampleRate: Int, val length: Int)

    private fun frameAt(data: ByteArray, pos: Int, end: Int): Frame? {
        if (pos + 4 > end) return null
        val b1 = data[pos + 1].toInt() and 0xff
        val b2 = data[pos + 2].toInt() and 0xff
        if (data[pos].toInt() and 0xff != 0xff || (b1 and 0xe0) != 0xe0) return null
        val version = (b1 shr 3) and 0x03
        val layer = (b1 shr 1) and 0x03
        val bitrateIndex = (b2 shr 4) and 0x0f
        val rateIndex = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01
        if (layer != 1 || bitrateIndex !in 1..14 || rateIndex > 2 || version == 1) return null
        val mpeg1 = version == 3
        val bitrate = (if (mpeg1) MPEG1_LAYER3 else MPEG2_LAYER3)[bitrateIndex]
        val sampleRate = when (version) {
            3 -> RATE1[rateIndex]
            2 -> RATE2[rateIndex]
            else -> intArrayOf(11025, 12000, 8000)[rateIndex]
        }
        if (bitrate <= 0 || sampleRate <= 0) return null
        val perFrame = if (mpeg1) 1152 else 576
        val length = perFrame / 8 * bitrate * 1000 / sampleRate + padding
        if (length < 4) return null
        return Frame(mpeg1, bitrate, sampleRate, length)
    }

    /**
     * CBR 文件（绝大多数 mp3）只看头部就够了：码率不变时，时长 = 音频字节数 / 码率。
     * 这样扫描几百首歌不用把每个文件从头读到尾；发现码率在变才退回逐帧走。
     */
    private fun cbrDuration(head: ByteArray, audioStart: Int, size: Long): Long {
        var cur = audioStart
        var first: Frame? = null
        var frames = 0
        while (cur + 4 <= head.size && frames < 24) {
            val frame = frameAt(head, cur, head.size) ?: break
            if (first == null) first = frame
            else if (frame.bitrate != first.bitrate || frame.sampleRate != first.sampleRate) return 0L
            frames++
            cur += frame.length
        }
        val rate = first?.bitrate ?: return 0L
        if (frames < 8) return 0L
        val audioBytes = size - audioStart
        if (audioBytes <= 0) return 0L
        return audioBytes * 8 / (rate * 1000L) * 1000L
    }

    private fun durationOfFile(file: Path, audioStart: Int): Long {
        return try {
            val raw = Files.newInputStream(file)
            val input = java.io.PushbackInputStream(java.io.BufferedInputStream(raw, 64 * 1024), 4)
            input.use {
                var skipped = 0L
                while (skipped < audioStart) {
                    val step = input.skip(audioStart - skipped)
                    if (step <= 0) break
                    skipped += step
                }
                val header = ByteArray(4)
                var samples = 0L
                var rate = 0
                var frames = 0L
                while (true) {
                    var read = 0
                    while (read < 4) {
                        val n = input.read(header, read, 4 - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < 4) break
                    val b1 = header[1].toInt() and 0xff
                    val b2 = header[2].toInt() and 0xff
                    val version = (b1 shr 3) and 0x03
                    val layer = (b1 shr 1) and 0x03
                    val bitrateIndex = (b2 shr 4) and 0x0f
                    val rateIndex = (b2 shr 2) and 0x03
                    val padding = (b2 shr 1) and 0x01
                    if (header[0].toInt() and 0xff != 0xff || (b1 and 0xe0) != 0xe0 ||
                        layer != 1 || bitrateIndex !in 1..14 || rateIndex > 2 || version == 1
                    ) {
                        // 不是帧头：把这 3 个字节退回去，整体前进 1 字节重新对齐
                        input.unread(header, 1, 3)
                        continue
                    }
                    val mpeg1 = version == 3
                    val bitrate = (if (mpeg1) MPEG1_LAYER3 else MPEG2_LAYER3)[bitrateIndex]
                    val sampleRate = when (version) {
                        3 -> RATE1[rateIndex]
                        2 -> RATE2[rateIndex]
                        else -> intArrayOf(11025, 12000, 8000)[rateIndex]
                    }
                    if (bitrate <= 0 || sampleRate <= 0) {
                        input.unread(header, 1, 3)
                        continue
                    }
                    val perFrame = if (mpeg1) 1152 else 576
                    val frameLength = perFrame / 8 * bitrate * 1000 / sampleRate + padding
                    if (frameLength < 4) break
                    if (rate == 0) rate = sampleRate
                    samples += perFrame.toLong()
                    frames++
                    var left = frameLength - 4
                    while (left > 0) {
                        val step = input.skip(left.toLong())
                        if (step <= 0) break
                        left -= step.toInt()
                    }
                    if (left > 0) break
                }
                if (frames <= 0 || rate <= 0) 0L else samples * 1000 / rate
            }
        } catch (e: Exception) {
            DangoLogger.warn("Cannot read mp3 duration of {}: {}", file.fileName.toString(), e.toString())
            0L
        }
    }
}

/** 本地音源：让播放器/搜索等既有路径也能拿到本地歌曲。 */
object LocalMusicSource : MusicSourceApi {
    override val source: MusicSource get() = MusicSource.LOCAL

    override fun search(query: String): List<NeteaseTrack> {
        val q = query.trim()
        if (q.isEmpty()) return LocalLibrary.entries.map { it.track }
        return LocalLibrary.entries.asSequence()
            .filter { entry ->
                entry.track.name.contains(q, ignoreCase = true) ||
                    entry.track.artists.any { it.contains(q, ignoreCase = true) } ||
                    entry.album.contains(q, ignoreCase = true)
            }
            .map { it.track }
            .toList()
    }

    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = null

    override fun lyrics(track: NeteaseTrack) = null
}
