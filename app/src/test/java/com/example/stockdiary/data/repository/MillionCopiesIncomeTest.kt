package com.example.stockdiary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 货基「万份收益」落库守卫。
 *
 * DAO 侧用 COALESCE 实现「null 表示保留旧值」；这里锁定 Kotlin 侧的判定：
 * 只有有限且大于 0 的万份收益才允许覆盖上一次有效值，避免某天 0 值或解析降级
 * 把货基年化收入打到 0 并一直停在 0。
 */
class MillionCopiesIncomeTest {

    @Test
    fun `正常万份收益原样返回`() {
        assertEquals(0.6231, millionCopiesIncomeToPersist(0.6231)!!, 0.0)
    }

    @Test
    fun `零值不覆盖旧值`() {
        assertNull(millionCopiesIncomeToPersist(0.0))
    }

    @Test
    fun `负值与非法值不覆盖旧值`() {
        assertNull(millionCopiesIncomeToPersist(-0.1))
        assertNull(millionCopiesIncomeToPersist(Double.NaN))
        assertNull(millionCopiesIncomeToPersist(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `缺失值不覆盖旧值`() {
        assertNull(millionCopiesIncomeToPersist(null))
    }

    @Test
    fun `没有万份收益的标的不打过滤日志`() {
        // 股票/加密货币/退市股的 PendingPriceWrite 不传 mci，每轮各打一行只会挤占环形缓冲
        assertNull(filteredMciLogMessage("600900", null))
    }

    @Test
    fun `带了无效万份收益时才打过滤日志`() {
        listOf(0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { incoming ->
            val message = filteredMciLogMessage("000001", incoming)
            assertTrue("incoming=$incoming 应打过滤日志", message != null)
            assertTrue(message!!.contains("incoming=$incoming"))
        }
    }

    @Test
    fun `有效万份收益走正常写入日志_不打过滤日志`() {
        assertNull(filteredMciLogMessage("000001", 0.6231))
    }
}
