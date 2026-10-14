package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogDiagnostics] 里**纯文本**那部分的回归。
 *
 * 这些用例存在的理由不是凑数：这份报告是闪退排障的唯一线索来源，
 * 一旦它把崩溃埋在几百行日志后面、或者静默截断掉崩溃前那几行、
 * 或者干脆给个空页面，排障链路就断在最不该断的地方。
 */
class LogDiagnosticsTest {

    private fun report(
        crashes: List<Pair<String, String>> = emptyList(),
        appLog: String? = null,
        crashFileCount: Int = crashes.size
    ) = LogDiagnostics.Report(
        crashes = crashes,
        appLog = appLog,
        appLogPath = "/sdcard/Android/data/com.example.stockdiary/files/GoldVault/app.log",
        crashFileCount = crashFileCount
    )

    /** 造一份足够长的日志，能触发截断 */
    private fun longLog(lines: Int = 1000, crashAt: Int = 1000): String =
        (1..lines).joinToString("\n") { i ->
            if (i == crashAt) "[10:00:00.000] [ERROR/CrashHandler] 炸了" else "[10:00:00.000] [INFO/Refresh] 第 $i 行"
        }

    // ---------- 顺序：崩溃必须在最前面 ----------

    @Test
    fun 崩溃报告排在app日志前面() {
        // 用户打开这份报告九成是为了看崩溃。埋在几百行日志后面 = 排障链路直接断掉
        val text = LogDiagnostics.composeReport(
            report(
                crashes = listOf("crash_001.log" to "java.lang.RuntimeException: 炸了\n\tat Foo.bar"),
                appLog = "一行日志"
            )
        )
        val crashAt = text.indexOf("java.lang.RuntimeException")
        val logAt = text.indexOf("一行日志")
        assertTrue("崩溃报告应该在 app.log 之前: $text", crashAt >= 0 && crashAt < logAt)
    }

    @Test
    fun 多份崩溃报告按新的在前() {
        val text = LogDiagnostics.composeReport(
            report(
                crashes = listOf(
                    "crash_200.log" to "新的崩溃",
                    "crash_100.log" to "旧的崩溃"
                )
            )
        )
        assertTrue(text.indexOf("新的崩溃") < text.indexOf("旧的崩溃"))
    }

    // ---------- 截断：必须留尾部，且必须说出来 ----------

    @Test
    fun 日志超长时保留尾部_崩溃前那几行不能丢() {
        val text = LogDiagnostics.composeReport(report(appLog = longLog()))
        assertTrue("崩溃那一行必须还在: $text", text.contains("炸了"))
        assertTrue("开头那些行该被截掉", !text.contains("第 1 行"))
    }

    @Test
    fun 截断必须明确写出丢了多少行_不能静默() {
        // 静默截断会让人以为"崩溃前就只有这几行"，是最误导的一种失败
        val text = LogDiagnostics.composeReport(report(appLog = longLog(1000)), tailLines = 400)
        assertTrue("要写明全文行数与省略行数: $text", text.contains("1000") && text.contains("600"))
    }

    @Test
    fun 没到上限时不做截断_也不报省略() {
        val text = LogDiagnostics.composeReport(report(appLog = "a\nb\nc"), tailLines = 400)
        assertTrue("不该出现省略说明: $text", !text.contains("已省略"))
        assertTrue(text.contains("a\nb\nc"))
    }

    // ---------- 空的情况必须能自解释 ----------

    @Test
    fun 什么都没有时给出可解释的文案_不是空白页() {
        // 空白页会让用户以为这个功能也是坏的，而实际上只是"这次还什么都没发生"
        val text = LogDiagnostics.composeReport(report())
        assertTrue("要说明为什么没有: $text", text.contains("app.log 不存在"))
        assertTrue("要给出可能的原因: $text", text.contains("可能的原因"))
    }

    @Test
    fun app日志为空字符串与为null要区分开() {
        // null = 文件都没有；空串 = 有文件但没内容。混为一谈就查不出"到底写没写过"
        assertTrue(LogDiagnostics.composeReport(report(appLog = null)).contains("不存在"))
        assertTrue(LogDiagnostics.composeReport(report(appLog = "")).contains("空的"))
    }

    @Test
    fun 有崩溃文件但内容读不出来时不假装没事() {
        val text = LogDiagnostics.composeReport(
            report(crashes = emptyList(), crashFileCount = 3)
        )
        assertTrue("磁盘上有 3 份要如实说出来: $text", text.contains("3 份"))
    }

    // ---------- tail：纯函数的边界 ----------

    @Test
    fun tail_保留行数足够时原样返回() {
        val (kept, dropped) = LogDiagnostics.tail("a\nb\nc", 10)
        assertEquals("a\nb\nc", kept)
        assertEquals(0, dropped)
    }

    @Test
    fun tail_保留行数正好等于总行数时不算截断() {
        val (kept, dropped) = LogDiagnostics.tail("a\nb\nc", 3)
        assertEquals("a\nb\nc", kept)
        assertEquals(0, dropped)
    }

    @Test
    fun tail_只留最后两行() {
        val (kept, dropped) = LogDiagnostics.tail("a\nb\nc", 2)
        assertEquals("b\nc", kept)
        assertEquals(1, dropped)
    }

    @Test
    fun tail_保留零行时全丢但行数要对() {
        val (kept, dropped) = LogDiagnostics.tail("a\nb\nc", 0)
        assertEquals("", kept)
        assertEquals(3, dropped)
    }

    @Test
    fun tail_末尾带换行不多算一行() {
        // 日志最后一行通常带 '\n'，如果多算一行，截断位置会整体偏掉一行
        val (kept, dropped) = LogDiagnostics.tail("a\nb\nc\n", 2)
        assertEquals("b\nc\n", kept)
        assertEquals(1, dropped)
    }

    @Test
    fun tail_空文本不炸() {
        val (kept, dropped) = LogDiagnostics.tail("", 10)
        assertEquals("", kept)
        assertEquals(0, dropped)
    }
}
