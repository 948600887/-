package com.example.stockdiary.utils

import java.io.File
import java.nio.file.Files
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
}
