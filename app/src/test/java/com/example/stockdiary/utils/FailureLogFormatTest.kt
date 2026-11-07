package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FailureLogFormat] 的格式回归。
 *
 * 这三个字段位置（轮次 / 标的 / 源）是诊断界面按轮次过滤、脚本切分日志的契约，
 * 一旦漂移，线上日志就再也对不上源统计，因此用单测钉死。
 */
class FailureLogFormatTest {

    @Test
    fun failureLine_前缀固定且带原因与耗时() {
        val line = FailureLogFormat.failureLine(12, "600519", "新浪", "HTTP 403", 213)
        assertEquals("[轮次#12][600519][新浪] 失败: HTTP 403 | 耗时 213ms", line)
    }

    @Test
    fun failureLine_非同步轮次记0且未测量时不写耗时() {
        val line = FailureLogFormat.failureLine(FailureLogFormat.NO_ROUND, "000001", "腾讯", "空响应")
        assertEquals("[轮次#0][000001][腾讯] 失败: 空响应", line)
    }

    @Test
    fun failureLine_原因缺失时不留空尾巴() {
        val line = FailureLogFormat.failureLine(1, "600519", "新浪", null, 5)
        assertEquals("[轮次#1][600519][新浪] 失败: 未知原因 | 耗时 5ms", line)
    }

    @Test
    fun skipLine_用跳过而非失败() {
        val line = FailureLogFormat.skipLine(3, "600519", "新浪", "停牌")
        assertTrue(line.startsWith("[轮次#3][600519][新浪] 跳过: 停牌"))
    }

    @Test
    fun skipLine_原因里的空白被视为缺失() {
        val line = FailureLogFormat.skipLine(3, "600519", "新浪", "   ")
        assertEquals("[轮次#3][600519][新浪] 跳过: 未知原因", line)
    }

    @Test
    fun batchFailureLine_用批次标签代替单一标的() {
        val line = FailureLogFormat.batchFailureLine(7, "600519…(共20只)", "新浪", "连接超时", 1500)
        assertEquals("[轮次#7][600519…(共20只)][新浪] 失败: 连接超时 | 耗时 1500ms", line)
    }

    @Test
    fun 负轮次号归一到0() {
        val line = FailureLogFormat.failureLine(-5, "600519", "新浪", "x")
        assertTrue(line.startsWith("[轮次#0]"))
    }

    @Test
    fun 原因桶_把自由文案归一() {
        assertEquals("停牌", FailureLogFormat.reasonBucket("无有效报价（停牌）"))
        assertEquals("HTTP 错误", FailureLogFormat.reasonBucket("HTTP 403 非成功"))
        assertEquals("超时", FailureLogFormat.reasonBucket("connect timed out"))
        assertEquals("网络不可用", FailureLogFormat.reasonBucket("网络连接不可用"))
        assertEquals("解析失败", FailureLogFormat.reasonBucket("净值无效"))
        assertEquals("熔断/冷却", FailureLogFormat.reasonBucket("熔断冷却中"))
        assertEquals("未知原因", FailureLogFormat.reasonBucket(null))
        assertEquals("其他", FailureLogFormat.reasonBucket("外星信号"))
    }

    @Test
    fun sourceSummaryLine_含成功失败跳过与原因分布() {
        val line = FailureLogFormat.sourceSummaryLine(
            roundId = 7,
            source = "新浪",
            success = 10,
            failed = 3,
            skipped = 5,
            failureReasons = mapOf("HTTP 错误" to 2, "超时" to 1),
            skipReasons = mapOf("停牌" to 5),
            durationMs = 1234
        )
        assertEquals(
            "[轮次#7][新浪] 汇总: 成功 10，失败 3（HTTP 错误 2、超时 1），跳过 5（停牌 5） | 耗时 1234ms",
            line
        )
    }

    @Test
    fun sourceSummaryLine_无分布时不加空括号() {
        val line = FailureLogFormat.sourceSummaryLine(1, "腾讯", 2, 0, 0)
        assertEquals("[轮次#1][腾讯] 汇总: 成功 2，失败 0，跳过 0", line)
    }
}
