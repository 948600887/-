package com.example.stockdiary.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A16（阻断）「残缺解析洗库」回归。
 *
 * [shouldReplaceRecordsOnFullRefresh] 是全量刷新删库守卫的纯函数决策点：本次解析到的有效记录数
 * 不少于库里已有的自动记录数，才允许「删旧 + 插新」；否则（解析到的比库里少）说明这次解析
 * 不完整，绝不删库，只做 upsert 保留旧记录。
 *
 * 此前 `classifyFundDividendPage` 对 `recordCount > 0` 一律认成 HAS_RECORDS，全量刷新路径
 * 无条件 `deleteNonManualBySymbol` 再只插回本次 `validRecords` —— 一旦某次解析残缺
 * （首次 10 条、二次仅 2 条），就把另外 8 条洗掉，年化收入当场缩水，下次抓全了又涨回来，
 * 正是用户原始「刷新跳动」的残留根因。
 *
 * ## 关于 [shouldUpdateDividendCache] 两个用例（2026-11-11 勘误，R3）
 *
 * 它们是**纯函数层的防御性契约**，**不是 A16 主链路验证**：QA 已确认
 * `DividendRepository.kt:744-749` 写进缓存的是从 DB 读回的完整 `mergedData`，
 * 「缓存守卫使主链路失效」不成立，A16 仍有效。这里只钉住「缓存写入决策与删库决策同口径」，
 * 防止将来改纯函数时把两者拆开。`DividendRepository` 目前无完整单测（仅策略纯函数），
 * 主链路 cache 断言需 mock 大量 DAO，非低成本，本轮不补。
 */
class DividendRepositoryCachePolicyTest {

    @Test
    fun `首次完整刷新_库空解析到10条_允许替换`() {
        // 首抓：库里 0 条，解析到 10 条 → 10 >= 0 → 允许插入
        assertTrue(shouldReplaceRecordsOnFullRefresh(0, 10))
    }

    @Test
    fun `残缺刷新_库有10条仅解析到2条_禁止删库`() {
        // A16 核心场景：「首次 10 条 / 二次仅 2 条」两次 forceRefresh 全量刷新：
        // 第二次解析到的 2 条明显少于库里已有的 10 条 → 判定不完整 → 绝不删库，保留 10 条，
        // 年化收入不缩水。
        assertFalse(
            "二次残缺解析（2 < 库里 10）必须禁止删库，保留旧记录",
            shouldReplaceRecordsOnFullRefresh(10, 2)
        )
    }

    @Test
    fun `完整刷新_库有10条解析到10条_允许替换`() {
        assertTrue(shouldReplaceRecordsOnFullRefresh(10, 10))
    }

    @Test
    fun `更多刷新_库有10条解析到11条_允许替换`() {
        assertTrue(shouldReplaceRecordsOnFullRefresh(10, 11))
    }

    @Test
    fun `负数入参_禁止替换_避免误删`() {
        assertFalse(shouldReplaceRecordsOnFullRefresh(-1, 5))
        assertFalse(shouldReplaceRecordsOnFullRefresh(5, -1))
    }

    @Test
    fun `防御性契约_残缺解析不覆盖缓存_非A16主链路验证`() {
        // 纯函数防御性契约：全量刷新已判定禁止替换（残缺）时，缓存也不写本次残缺集合。
        // 注意：这不是 A16 主链路验证 —— 主链路写的是从 DB 读回的完整 mergedData（见类 KDoc）。
        assertFalse(
            "全量刷新残缺（replaceAllowed=false）时不得覆盖内存缓存",
            shouldUpdateDividendCache(isIncremental = false, replaceAllowed = false)
        )
    }

    @Test
    fun `防御性契约_完整或增量路径更新缓存_非A16主链路验证`() {
        // 同上：纯函数契约，非主链路验证。
        assertTrue(shouldUpdateDividendCache(isIncremental = false, replaceAllowed = true))
        assertTrue(
            "增量路径不删库、只补新记录，缓存照常更新",
            shouldUpdateDividendCache(isIncremental = true, replaceAllowed = false)
        )
    }
}
