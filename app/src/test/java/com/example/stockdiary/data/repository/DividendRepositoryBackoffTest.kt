package com.example.stockdiary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeFailureBackoff] 指数退避纯函数回归（修复 #6）。
 *
 * 钉的契约：
 * - 已成功过的标的：base = 5min，指数 `base × 2^(count-1)`，封顶 6h；
 * - 从未成功过的标的：更短档位 base = 1min，封顶 1h（避免被当成永久失败长时间屏蔽）；
 * - 任何档位/参数下结果都**非负**（旧固定 TTL 写法无此保证；此处同时钉死 Int 溢出风险：
 *   用 `Long` 运算 + shift 上限 30，count 极端值也不溢出为负）；
 * - count <= 1 时退到基础档（不抛、不为 0）。
 */
class DividendRepositoryBackoffTest {

    @Test
    fun `已成功_首次失败_基础档5min`() {
        assertEquals(5L * 60 * 1000, computeFailureBackoff(1, everSucceeded = true))
    }

    @Test
    fun `已成功_指数增长_2次10min_3次20min`() {
        assertEquals(10L * 60 * 1000, computeFailureBackoff(2, everSucceeded = true))
        assertEquals(20L * 60 * 1000, computeFailureBackoff(3, everSucceeded = true))
    }

    @Test
    fun `已成功_高频失败封顶6h_不超上限`() {
        val capped = computeFailureBackoff(20, everSucceeded = true)
        assertEquals(6L * 60 * 60 * 1000, capped)
        assertTrue("封顶档必须 > 0", capped > 0)
    }

    @Test
    fun `从未成功_基础档1min_短于已成功档`() {
        assertEquals(1L * 60 * 1000, computeFailureBackoff(1, everSucceeded = false))
        assertTrue(
            "从未成功档应短于已成功档（更快重试）",
            computeFailureBackoff(1, everSucceeded = false) < computeFailureBackoff(1, everSucceeded = true)
        )
    }

    @Test
    fun `从未成功_封顶1h_不超上限`() {
        val capped = computeFailureBackoff(20, everSucceeded = false)
        assertEquals(1L * 60 * 60 * 1000, capped)
        assertTrue("封顶档必须 > 0", capped > 0)
    }

    @Test
    fun `count极端值_结果非负_不溢出`() {
        // count=0 / 负数 / 极大值：shift 被 coerce 到 [0,30]，base 恒为正 → 结果恒为非负正数。
        assertTrue(computeFailureBackoff(0, everSucceeded = true) > 0)
        assertTrue(computeFailureBackoff(-5, everSucceeded = true) > 0)
        assertTrue(computeFailureBackoff(0, everSucceeded = false) > 0)
        assertTrue(computeFailureBackoff(Int.MAX_VALUE, everSucceeded = true) > 0)
    }
}
