package com.example.stockdiary.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片提示集合的转移规则：成功清除、失败置位、未提及保留。
 *
 * 这是「价格旧 ≠ 刷新失败」的核心保证——本轮被跳过（断网有旧价/熔断/不可交易/
 * 退市跳过）的资产不会出现在任一集合，因此上一次真失败的提示不会被悄悄洗白。
 */
class AssetSyncFailureStoreTest {

    @Test
    fun `真失败加入集合`() {
        assertEquals(
            setOf(1L, 2L),
            computeFailedAssetIds(current = setOf(1L), succeeded = emptySet(), failed = listOf(2L))
        )
    }

    @Test
    fun `成功从集合移除`() {
        assertEquals(
            setOf(2L),
            computeFailedAssetIds(current = setOf(1L, 2L), succeeded = listOf(1L), failed = emptyList())
        )
    }

    @Test
    fun `未提及的资产原样保留_跳过不能洗白提示`() {
        assertEquals(
            setOf(1L),
            computeFailedAssetIds(current = setOf(1L), succeeded = emptyList(), failed = emptyList())
        )
    }

    @Test
    fun `同一资产同时出现在两边时以失败为准`() {
        assertEquals(
            setOf(1L),
            computeFailedAssetIds(current = emptySet(), succeeded = listOf(1L), failed = listOf(1L))
        )
    }
}
