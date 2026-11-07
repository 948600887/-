package com.example.stockdiary.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重试批量删除时的选择集校验。
 *
 * 回归背景：snackbar 的「重试」回调跑在应用作用域，可能在上层已经切页之后才被点击；
 * 此时 rememberSaveable 的选择集已复位、或失败批次的资产已被删除，直接拿闭包里的旧 id
 * 发删除请求没有意义（也不安全）。这里锁死「失效即放弃重试」的口径。
 */
class PortfolioDeleteRetryTest {

    @Test
    fun `页面仍在前台时只重试仍然存在的 id`() {
        val retried = retrySelectionIds(
            screenActive = true,
            selectedIds = setOf(1L, 2L, 3L),
            existingIds = setOf(2L, 3L, 4L)
        )

        assertEquals(setOf(2L, 3L), retried)
    }

    @Test
    fun `切页后选择集失效时放弃重试`() {
        val retried = retrySelectionIds(
            screenActive = false,
            selectedIds = setOf(1L, 2L),
            existingIds = setOf(1L, 2L)
        )

        assertTrue("页面已离开组合，旧的选择集不能再用", retried.isEmpty())
    }

    @Test
    fun `失败批次已被删除时放弃重试`() {
        val retried = retrySelectionIds(
            screenActive = true,
            selectedIds = setOf(1L),
            existingIds = emptySet()
        )

        assertTrue(retried.isEmpty())
    }
}
