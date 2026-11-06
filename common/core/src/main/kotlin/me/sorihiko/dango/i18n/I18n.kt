package me.sorihiko.dango.i18n

import java.util.Locale

/** UI 文案独立于游戏语言：游戏语言只决定没有保存设置时的默认值。 */
object I18n {
    const val ENGLISH = 0
    const val CHINESE = 1

    @Volatile var language = ENGLISH
        set(value) { field = value.coerceIn(ENGLISH, CHINESE) }

    fun defaultLanguage(gameLanguage: String): Int =
        if (gameLanguage.replace('-', '_').substringBefore('_').equals("zh", ignoreCase = true)) CHINESE else ENGLISH

    private val chinese: Map<String, String> by lazy {
        I18n::class.java.getResourceAsStream("/assets/dangomusic/lang/zh_cn.tsv")!!
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                    val parts = line.split('\t', limit = 2)
                    require(parts.size == 2) { "Invalid translation: $line" }
                    parts[0] to parts[1]
                }
            }
    }
    private val english: Map<String, String> by lazy { chinese.entries.associate { it.value to it.key } }

    /** 仅传入界面文案，不传入歌名、歌手、歌词或用户输入。 */
    fun text(source: String): String =
        if (language == CHINESE) chinese[source] ?: source else english[source] ?: source

    fun format(source: String, vararg args: Any): String = String.format(Locale.ROOT, text(source), *args)

    /** 后台状态可能在语言切换前产生，因此绘制时再翻译，保留错误详情。 */
    // 翻译跑在绘制路径上，任何一条规则出问题都不该让游戏崩掉：兜底返回原文
    fun message(source: String): String = try {
        translateMessage(source)
    } catch (e: Exception) {
        source
    }

    private fun translateMessage(source: String): String {
        val stopped = " · Auto-skip stopped after repeated playback failures."
        if (source.endsWith(stopped)) return message(source.removeSuffix(stopped)) + " · " + text(stopped.removePrefix(" · "))
        val exact = text(source)
        if (exact != source) return exact
        // 中文界面下运行期状态本来就是中文，按原文显示；下面的规则只用于中 → 英
        if (language == CHINESE) return source
        for (prefix in messagePrefixes) {
            if (source.startsWith(prefix)) return text(prefix) + source.substring(prefix.length)
        }
        for ((pattern, template) in messagePatterns) {
            val match = pattern.matchEntire(source) ?: continue
            // 有的规则没有分组（整句替换），不能直接取 group 1
            val detail = match.groupValues.getOrNull(1)
            return if (detail == null) text(template) else format(template, detail)
        }
        return source
    }

    private val messagePrefixes = listOf("加载失败：", "播放失败：", "Load failed: ", "Playback failed: ")
    private val messagePatterns = listOf(
        Regex("正在加入一起听歌…") to "Joining Listen Together…",
        Regex("还没连进服务器，没法一起听歌") to "Not connected to a server, so Listen Together is unavailable",
        Regex("发不出去：服务器没有开启一起听歌") to "Couldn't send: the server does not have Listen Together",
        Regex("服务器没有开启一起听歌：需要服务端也安装这个模组") to "The server does not have Listen Together: install the mod on the server as well",
        Regex("房主离开了一起听歌") to "The host left, so Listen Together ended",
        Regex("版本不一致：服务器和客户端需要同一个版本的 DangoMusic") to "Version mismatch: the server and the client need the same DangoMusic version",
        Regex("一起听歌的房间满了（最多 (\\d+) 人）") to "Listen Together is full (at most %s players)",
        Regex("操作没发出去（(.*)）") to "Couldn't send your action to the host: %s",
        Regex("""Kugou rejected the login \(error (-?\d+)\)\. Refresh the QR code and try again\.""") to "Kugou rejected the login (error %s). Refresh the QR code and try again.",
    )
}
