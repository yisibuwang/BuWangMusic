package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoLogger

import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * 绕开 Java 25 的 C2 编译器 bug。
 *
 * Java 25 的 C2 编译器（把常用的方法优化成机器码的那个）在优化我们画界面的代码时会自己崩掉，
 * 整个游戏直接闪退（不是我们的代码出错，日志 hs_err 里是 "C2 CompilerThread" 崩溃）。
 * 26.x 本来就用 Java 25；1.21.x 有的启动器也会给它配 Java 25，一样会崩。
 *
 * 所以在 Java 25 及以上，启动时告诉 JVM：我们自己的代码不用 C2 优化，也不被 C2 内联进别的方法里
 * （只用 C1，画界面够快了）。Java 21 及以下没有这个问题，什么都不做。
 *
 * 规则要先写成文件再让 JVM 读。JVM 读不了路径里有非英文字符的文件（比如 Windows 用户名是中文时的
 * 临时目录 C:\Users\张三\AppData\Local\Temp），所以只往纯英文的目录里写，一个不行换下一个，
 * 并且看 JVM 的回复确认真的装上了。
 */
object JitWorkaround {
    private const val DIRECTIVES = """[
      { match: ["me/sorihiko/dango/*.*"], c2: { Exclude: true } },
      { match: ["*.*"], c2: { inline: ["-me/sorihiko/dango/*.*"] } }
    ]"""

    /** 装上了返回 true；Java 25 以下不需要，也返回 true */
    fun install(): Boolean {
        val java = try { Runtime.version().feature() } catch (_: Throwable) { 0 }
        if (java < 25) return true
        val dirs = candidateDirs()
        for (dir in dirs) {
            if (tryInstall(dir)) {
                DangoLogger.info("Dango Music: JIT workaround installed (Java {})", java)
                return true
            }
        }
        DangoLogger.warn(
            "Dango Music: cannot install the Java {} JIT workaround (tried {} folders); the game may crash in a \"C2 CompilerThread\". " +
                "Using Java 21 where the Minecraft version allows it avoids the crash.", java, dirs.size,
        )
        return false
    }

    /** 可以写规则文件的目录：临时目录、游戏目录、公共目录…… 只留路径全是英文字符的 */
    private fun candidateDirs(): List<File> {
        val raw = listOfNotNull(
            System.getProperty("java.io.tmpdir"),
            File(".").absoluteFile.parent,
            System.getenv("PUBLIC"),
            System.getenv("ProgramData"),
            System.getenv("SystemDrive")?.let { "$it\\Temp" },
            "/tmp",
        )
        return raw.map { File(it).absoluteFile }
            .filter { dir -> dir.path.all { it.code in 32..126 } }
            .distinctBy { it.path }
    }

    private fun tryInstall(dir: File): Boolean {
        var file: File? = null
        return try {
            if (!dir.isDirectory) dir.mkdirs()
            file = File.createTempFile("dangomusic-jit", ".json", dir)
            file.writeText(DIRECTIVES)
            val result = ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"),
                "compilerDirectivesAdd",
                arrayOf<Any>(arrayOf(file.absolutePath)),
                arrayOf(Array<String>::class.java.name),
            )
            // 成功时 JVM 回 "2 compiler directives added"，失败时是 "Could not load file: ..." 之类
            Regex("""\d+ compiler directives added""").containsMatchIn(result.toString())
        } catch (_: Throwable) {
            false
        } finally {
            // JVM 读完就不需要了；读的是当下的内容，删掉不影响已经装上的规则
            try { file?.delete() } catch (_: Throwable) {}
        }
    }
}
