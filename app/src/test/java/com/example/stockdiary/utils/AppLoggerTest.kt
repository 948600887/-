package com.example.stockdiary.utils

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppLogger.trimToLastLines] 的回归。
 *
 * 它是把日志文件截到「最后 N 行」的核心（[AppLogger.trimLogFile] 流式单遍调用它）。
 * 一个定长滑动窗口，保证只保留尾部、内存上限 N 行。截断错了会直接丢排障线索。
 */
class AppLoggerTest {

    /** object 级 appContext 必须复位，否则会串到别的测试类（AI_GUIDE §8） */
    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun trimToLastLines_低于上限原样返回且补末尾换行() {
        val r = AppLogger.trimToLastLines(listOf("a", "b", "c").asSequence(), 10)
        assertEquals("a\nb\nc\n", r)
    }

    @Test
    fun trimToLastLines_正好等于上限() {
        val r = AppLogger.trimToLastLines(listOf("a", "b", "c").asSequence(), 3)
        assertEquals("a\nb\nc\n", r)
    }

    @Test
    fun trimToLastLines_超过上限只保留尾部() {
        val r = AppLogger.trimToLastLines(listOf("a", "b", "c", "d", "e").asSequence(), 2)
        assertEquals("d\ne\n", r)
    }

    @Test
    fun trimToLastLines_空序列返回空串() {
        val r = AppLogger.trimToLastLines(emptyList<String>().asSequence(), 5)
        assertEquals("", r)
    }

    @Test
    fun trimToLastLines_上限为0返回空串() {
        val r = AppLogger.trimToLastLines(listOf("a", "b").asSequence(), 0)
        assertEquals("", r)
    }

    @Test
    fun trimToLastLines_大量只保留尾部不爆内存() {
        // 10000 行只保留最后 3 行：验证滑动窗口不会把全部行都留在内存里
        val n = 10000
        val seq = (1..n).map { "line$it" }.asSequence()
        val r = AppLogger.trimToLastLines(seq, 3)
        assertEquals("line9998\nline9999\nline10000\n", r)
    }

    // ---- 轮转与保留窗口（Stage A）----

    private fun tempDir(): File = Files.createTempDirectory("applog-test").toFile()

    @Test
    fun prepareLogFile_未超上限不轮转() {
        val dir = tempDir()
        val log = File(dir, "app.log").apply { writeText("x".repeat(100)) }

        AppLogger.prepareLogFile(log)

        assertTrue(log.exists())
        assertFalse(File(dir, "app.log.1").exists())
    }

    @Test
    fun prepareLogFile_超过8MB轮转为app_log_1() {
        val dir = tempDir()
        val log = File(dir, "app.log").apply { writeText("x".repeat(AppLogger.MAX_FILE_BYTES.toInt())) }

        AppLogger.prepareLogFile(log)

        // 活跃文件被改名归档，原路径不再存在（下次追加会重新创建）
        assertFalse(log.exists())
        assertTrue(File(dir, "app.log.1").exists())
    }

    @Test
    fun rotateLogFiles_保留四份并丢弃最旧() {
        val dir = tempDir()
        // 预置 .1/.2/.3，各自内容标记，便于验证后移顺序
        listOf(1, 2, 3).forEach { i -> File(dir, "app.log.$i").writeText("old$i") }
        val log = File(dir, "app.log").apply { writeText("newest") }

        assertTrue(AppLogger.rotateLogFiles(log))

        // 最新的落到 .1，原来的 .1→.2、.2→.3，原来的 .3 被删除（总保留 4 份）
        assertEquals("newest", File(dir, "app.log.1").readText())
        assertEquals("old1", File(dir, "app.log.2").readText())
        assertEquals("old2", File(dir, "app.log.3").readText())
        assertFalse(File(dir, "app.log.4").exists())
    }

    @Test
    fun rotateLogFiles_归档文件按行数兜底裁剪() {
        val dir = tempDir()
        val over = AppLogger.MAX_LOG_LINES + 5
        val log = File(dir, "app.log").apply {
            writeText((1..over).joinToString("\n", postfix = "\n") { "line$it" })
        }

        assertTrue(AppLogger.rotateLogFiles(log))

        val archived = File(dir, "app.log.1")
        val lines = archived.readLines()
        assertEquals(AppLogger.MAX_LOG_LINES, lines.size)
        // 保留的是尾部：最早的行被裁掉、最后一行仍在
        assertEquals("line6", lines.first())
        assertEquals("line$over", lines.last())
    }

    @Test
    fun rotateLogFiles_行数未超上限时原样归档() {
        val dir = tempDir()
        val log = File(dir, "app.log").apply { writeText("a\nb\n") }

        assertTrue(AppLogger.rotateLogFiles(log))

        assertEquals("a\nb\n", File(dir, "app.log.1").readText())
    }

    // ---- 时间戳必须逐条对应入队时刻 ----
    //
    // 回归背景：真机诊断 51602 行日志只有 8168 个唯一时间戳，最夸张一次 1000 行
    // 共用同一毫秒 —— 因为旧实现是「写盘线程给整批取一次时间」。同批内的先后顺序、
    // 每步耗时全部失真，而 OkHttp 行本身写着 411ms。

    private fun record(level: String, message: String, epochMillis: Long): AppLogger.LogRecord =
        AppLogger.LogRecord(level = level, tag = "Test", message = message, epochMillis = epochMillis)

    @Test
    fun allocateTimestamps_跨毫秒逐条递增且与入队顺序一致() {
        val base = 1_763_000_000_000L
        val records = listOf(
            record("DEBUG", "a", base),
            record("DEBUG", "b", base + 1),
            record("DEBUG", "c", base + 2),
        )

        val timestamps = AppLogger.allocateTimestamps(records)

        assertEquals(3, timestamps.size)
        assertEquals("三条不同毫秒必须得到三个不同时间戳", 3, timestamps.toSet().size)
        // 固定格式 `yyyy-MM-dd HH:mm:ss.SSS` 的字典序即时间序
        assertTrue(timestamps[0] < timestamps[1])
        assertTrue(timestamps[1] < timestamps[2])
    }

    @Test
    fun allocateTimestamps_同一毫秒复用_但顺序仍与记录一一对应() {
        val base = 1_763_000_000_000L
        // 故意乱序：第二条比第一条早 500ms，证明不是「整批取一个值」
        val records = listOf(
            record("DEBUG", "later", base + 500),
            record("DEBUG", "earlier", base),
            record("DEBUG", "same-as-later", base + 500),
        )

        val timestamps = AppLogger.allocateTimestamps(records)

        assertEquals(3, timestamps.size)
        assertEquals(timestamps[0], timestamps[2])
        assertTrue("第 2 条记录更早，其时间戳必须更小", timestamps[1] < timestamps[0])
    }

    @Test
    fun allocateTimestamps_一千条不退化成一整批同一时间戳() {
        // 旧实现下这里只会有 1 个唯一值
        val base = 1_763_000_000_000L
        val records = (1..1000).map { record("DEBUG", "line$it", base + it) }

        val timestamps = AppLogger.allocateTimestamps(records)

        assertEquals(1000, timestamps.size)
        assertEquals("1000 条各差 1ms 必须有 1000 个唯一时间戳", 1000, timestamps.toSet().size)
    }

    @Test
    fun allocateTimestamps_空列表返回空() {
        assertTrue(AppLogger.allocateTimestamps(emptyList()).isEmpty())
    }

    @Test
    fun formatEpochMillis_格式为yyyy_MM_dd_HH_mm_ss_SSS() {
        val formatted = AppLogger.formatEpochMillis(1_763_000_000_123L)
        assertTrue(
            "实际输出: $formatted",
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$""").matches(formatted)
        )
    }

    // ---- clearLog 的「已清空 vs 仍在排队」语义 ----

    /**
     * 超时必须返回 false 且**不抛**。
     *
     * 用未启动的 [FutureTask] 构造「必然未完成」的任务：直接断言 `clearLog(0)` 的布尔值是
     * 竞态的（清理任务可能在 `get()` 之前就跑完，测试会偶发飘红），所以确定性覆盖
     * 放在这个注入了 Future 的入口上。
     */
    @Test
    fun clearLogAwaiting_超时返回false且不抛异常() {
        val notStarted = FutureTask(Callable { true })   // 只构造不 run：state 恒为 NEW
        assertFalse(AppLogger.clearLogAwaiting(notStarted, 0))
    }

    /** 任务在等待期内跑完、且删干净，才算「已确认清空」 */
    @Test
    fun clearLogAwaiting_任务完成返回true() {
        val done = FutureTask(Callable { true }).apply { run() }
        assertTrue(AppLogger.clearLogAwaiting(done, 1))
    }

    /**
     * 任务跑完但自我汇报「没删净」时必须透传 false。
     *
     * 这正是修掉的那个 bug：从前 `clearLogAwaiting` 只等 `get()` 返回就无条件写 true，
     * 于是 `delete()` 失败也会被当成「已确认清空」，UI 走 reload 又给用户看一遍旧日志。
     */
    @Test
    fun clearLogAwaiting_任务完成但删除失败返回false() {
        val done = FutureTask(Callable { false }).apply { run() }
        assertFalse(AppLogger.clearLogAwaiting(done, 1))
    }

    // ---- deleteFiles：删失败必须如实汇报 ----

    @Test
    fun deleteFiles_全部删掉返回true且文件消失() {
        val dir = Files.createTempDirectory("goldvault-del").toFile()
        try {
            val files = (0..2).map { File(dir, "app.log.$it").apply { writeText("x") } }
            assertTrue(AppLogger.deleteFiles(files))
            files.forEach { assertFalse("应已删除: ${it.name}", it.exists()) }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * `delete()` 返回 false 必须记 false（修前的实现把返回值丢了，永远报成功）。
     *
     * 用「不存在的路径」构造确定性失败：`File.delete()` 对不存在的路径固定返回 false，
     * 不依赖文件锁/只读属性这类跨平台不一致的行为。
     */
    @Test
    fun deleteFiles_删除失败返回false() {
        val dir = Files.createTempDirectory("goldvault-del-fail").toFile()
        try {
            val ghost = File(dir, "gone.log")   // 从未创建 → delete() 必返 false
            assertFalse(AppLogger.deleteFiles(listOf(ghost)))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 一个删不掉不能拖累其余：成功的照样删掉，整体仍报 false */
    @Test
    fun deleteFiles_部分失败仍尽力删完其余() {
        val dir = Files.createTempDirectory("goldvault-del-mixed").toFile()
        try {
            val real = File(dir, "app.log").apply { writeText("x") }
            val ghost = File(dir, "gone.log")
            assertFalse(AppLogger.deleteFiles(listOf(ghost, real)))
            assertFalse("能删的仍要删掉，不能中途 return", real.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 端到端冒烟：真跑一次清空必须收敛到 true（2 秒远远够），且**任何情况下都不抛**。
     *
     * 单测里 appContext 未初始化 → 没有日志文件可删，清理任务本身是空操作，
     * 所以这里验证的是「排队 + 等待 + 返回值」这条链路没坏，不是删文件本身。
     */
    /**
     * 端到端冒烟：日志目录可用、目录里没有残留文件 → 真跑一次清空必须收敛到 true
     * （2 秒远远够），且**任何情况下都不抛**。
     *
     * 必须显式 init 一个指向临时目录的 Context：从前这条用例靠的是「appContext 为
     * null → 没文件可删 → 空集合恒真 → true」，而那恰恰是修掉的 bug（拿不到目录被
     * 报成已确认清空）。现在「目录在、没文件」才是真的空，「拿不到目录」见下一条。
     */
    @Test
    fun clearLog_目录可用且为空时返回true_零超时也不抛() {
        val root = Files.createTempDirectory("goldvault-clear-ok").toFile()
        try {
            AppLogger.init(contextWithFilesDir(root), debug = false)
            assertTrue(AppLogger.clearLog())
            // 零超时只验证「不抛」：是否已完成取决于线程调度，断言布尔会引入 flaky
            AppLogger.clearLog(0)
        } finally {
            AppLogger.resetForTest()
            root.deleteRecursively()
        }
    }

    /**
     * **拿不到日志目录 = 无法确认 = false**（外部存储未挂载时 `getExternalFilesDir` 返 null）。
     *
     * 这条钉住的正是「空集合恒真」：拿不到目录时 `getLogFiles()` 返 emptyList()，
     * `deleteFiles(emptyList())` 空转返 true，于是 clearLog 报「已确认清空」，
     * 调用方提示清空成功、reload 又把旧日志显示一遍。
     */
    @Test
    fun clearLog_取不到日志目录时返回false() {
        try {
            AppLogger.init(contextWithFilesDir(null), debug = false)
            assertFalse(AppLogger.clearLog())
        } finally {
            AppLogger.resetForTest()
        }
    }

    /** `getExternalFilesDir` 指向一个「无法在其下建目录」的路径（父级是文件）→ 同样算拿不到目录 */
    @Test
    fun clearLog_日志目录无法创建时返回false() {
        val root = Files.createTempDirectory("goldvault-clear-nodir").toFile()
        try {
            // parent 是个普通文件 → File(parent, "GoldVault").mkdirs() 必失败
            val blocker = File(root, "not-a-dir").apply { writeText("x") }
            AppLogger.init(contextWithFilesDir(blocker), debug = false)
            assertFalse(AppLogger.clearLog())
        } finally {
            AppLogger.resetForTest()
            root.deleteRecursively()
        }
    }

    /** 造一个 `getExternalFilesDir` 返 [dir] 的 Context；`applicationContext` 也要指回自己 */
    private fun contextWithFilesDir(dir: File?): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getExternalFilesDir(null) } returns dir
        return context
    }
}
