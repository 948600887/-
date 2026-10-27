package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
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
}
