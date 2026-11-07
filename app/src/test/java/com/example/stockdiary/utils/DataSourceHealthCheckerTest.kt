package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 体检摘要格式：日志行与诊断快照共用同一份措辞，靠单测钉死。
 */
class DataSourceHealthCheckerTest {

    private fun health(name: String, ok: Boolean) = DataSourceHealth(
        name = name,
        url = "https://example.com",
        isAvailable = ok,
        latency = 12,
        lastCheck = 0
    )

    @Test
    fun healthSummary_逐源标注正常与否() {
        val summary = DataSourceHealthChecker.healthSummary(
            listOf(health("新浪财经", true), health("天天基金", false))
        )
        assertEquals("新浪财经=正常 · 天天基金=不可用", summary)
    }

    @Test
    fun healthSummary_空列表给空串() {
        assertEquals("", DataSourceHealthChecker.healthSummary(emptyList()))
    }
}
