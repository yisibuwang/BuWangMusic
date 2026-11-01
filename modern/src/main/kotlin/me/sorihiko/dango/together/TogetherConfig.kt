package me.sorihiko.dango.together

import me.sorihiko.dango.DangoLogger
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.io.File
import java.nio.file.Path
import java.util.Properties

/**
 * 服务端配置：`config/dangomusic-server.properties`。
 *
 * 只用 Minecraft 自己的网络栈转发，所以服务端能控制的上限很少，主要就是：
 *  - 允不允许把本地音乐转发给别的玩家（带宽是房主与服务器出的）；
 *  - 码率上限（挡位由房主选，超过上限的挡位会报错）。
 *
 * 文件不存在时自动写一份带注释的默认配置，改完重启服务器生效。
 */
object TogetherConfig {

    /** 允许共享本地音乐（关掉后房间里只能放各音源自己的歌） */
    @Volatile
    var allowLocalMusic: Boolean = true
        private set

    /** 最高码率 kbps；0 表示不限制 */
    @Volatile
    var maxBitrate: Int = 0
        private set

    /** 允许房间里放 B 站曲目（每个客户端自己取流，服务端只同步标题与进度） */
    @Volatile
    var allowBilibili: Boolean = true
        private set

    private const val FILE_NAME = "dangomusic-server.properties"

    private const val DEFAULTS = """# BuWang Music 服务端配置
# 改完重启服务器生效。

# 是否允许房间里放 B 站曲目。关掉后粘贴 B 站歌单、点 B 站歌都会被拒绝，
# 已经在队列里的 B 站歌也不会再同步进度。每个客户端自己取流，服务端不转发音频。
allow-bilibili=true

# 是否允许房主把本地音乐的声音转发给房间里没有这个文件的成员。
# 关掉后一起听歌只能用各音乐平台的歌；本地歌在别人那里会显示为无法播放。
allow-local-music=true

# 中转码率的最高值（kbps）。0 = 不限制，房主可以选原始/128/96/64。
# 服务器上行有限就填个 96 或 64，房主的挡位会被限制在这里。
max-bitrate=0
"""

    fun reset() {
        allowLocalMusic = true
        maxBitrate = 0
        allowBilibili = true
    }

    /** 服务器启动/房间第一次被访问时读一次 */
    fun load(server: MinecraftServer) {
        val file = fileFor(server)
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(DEFAULTS, Charsets.UTF_8)
            }
            val properties = Properties()
            file.reader(Charsets.UTF_8).use { properties.load(it) }
            allowLocalMusic = properties.getProperty("allow-local-music")?.trim()?.toBooleanStrictOrNull() ?: true
            maxBitrate = (properties.getProperty("max-bitrate")?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0)
            allowBilibili = properties.getProperty("allow-bilibili")?.trim()?.toBooleanStrictOrNull() ?: true
            DangoLogger.info(
                "Listen Together server config: local music {}, bilibili {}, max bitrate {}",
                if (allowLocalMusic) "on" else "off",
                if (allowBilibili) "on" else "off",
                if (maxBitrate == 0) "unlimited" else "$maxBitrate kbps",
            )
        } catch (e: Exception) {
            DangoLogger.warn("Listen Together: reading {} failed: {}", file.path, e.toString())
        }
    }

    private fun fileFor(server: MinecraftServer): File {
        val root: Path = try {
            server.getWorldPath(LevelResource.ROOT).parent ?: Path.of(".")
        } catch (e: Exception) {
            Path.of(".")
        }
        return root.resolve("config").resolve(FILE_NAME).toFile()
    }
}
