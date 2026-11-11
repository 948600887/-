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
    fun `残缺解析_DB保留旧记录_内存缓存也不覆盖`() {
        // A16 第二半：DB 侧已判定禁止替换（残缺），缓存必须同口径不写入本次残缺集合，
        // 否则 tryGetFromCache 会优先命中残缺数据、DB 与缓存不一致，年化收入照样缩水。
        assertFalse(
            "全量刷新残缺（replaceAllowed=false）时不得覆盖内存缓存",
            shouldUpdateDividendCache(isIncremental = false, replaceAllowed = false)
        )
    }

    @Test
    fun `完整解析或增量路径_正常更新缓存`() {
        assertTrue(shouldUpdateDividendCache(isIncremental = false, replaceAllowed = true))
        assertTrue(
            "增量路径不删库、只补新记录，缓存照常更新",
            shouldUpdateDividendCache(isIncremental = true, replaceAllowed = false)
        )
    }
}
