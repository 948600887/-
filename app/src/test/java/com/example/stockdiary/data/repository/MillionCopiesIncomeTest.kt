package com.example.stockdiary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
