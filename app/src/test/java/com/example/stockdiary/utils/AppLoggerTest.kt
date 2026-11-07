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
}
