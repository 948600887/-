package com.example.stockdiary.data.repository

import android.content.Context
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.DividendDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A16（阻断）「残缺解析洗库」的**写入侧集成**回归。
 *
 * 此前只有 [shouldReplaceRecordsOnFullRefresh] / [shouldUpdateDividendCache] 的纯函数级用例，
 * 「[DividendWriter.saveInternal] 真的在删库前调用了它」这条路径**零断言**——
 * 把 `DividendWriter.kt` 里 `replaceAllowed = shouldReplaceRecordsOnFullRefresh(...)`
 * 改成 `replaceAllowed = true`，纯函数用例照样全绿，而残缺解析会重新开始洗库
 * （库里 10 条自动记录被 2 条残缺结果替换，年化收入当场缩水，正是用户原始
 * 「刷新跳动」的残留根因）。
 *
 * 这两例就是那个缺口的钉子：
 * - 负向：残缺（库里 10 条 / 本次解析 2 条）→ `deleteNonManualBySymbol` **零调用**，
 *   只走 upsert 保留旧记录，且内存缓存**不**被残缺集合污染；
 * - 正向：条数正常（10 条 / 10 条）→ `deleteNonManualBySymbol` **确实调用一次**，
 *   缓存也照常更新。
 *
 * 只有负向断言是不够的——把整个删库调用删掉也能过；正负两例合起来才钉得住守卫。
 *
 * 纯 JVM：DAO / 内存缓存用 mockk，事务执行器注入 `{ it() }`（不进 Room、不用 Robolectric）。
 */
class DividendWriterDeleteGuardTest {

    private val symbol = "sh600519"

    /** 库里已有的自动分红记录（amount > 0 且 isManual = false 才计入 existingAutoCount） */
    private fun existingAutoRecords(count: Int): List<Dividend> = List(count) { i ->
        Dividend(
            id = i + 1,
            symbol = symbol,
            name = "贵州茅台",
            amount = 20.0 + i,
            date = "2019-06-${(i + 1).toString().padStart(2, '0')}",
            isManual = false
        )
    }

    /** 本次解析到的分红记录（金额取正常区间，不会被异常值过滤掉） */
    private fun parsedRecords(count: Int): List<DividendRecord> = List(count) { i ->
        DividendRecord(
            exDividendDate = "2024-06-${(i + 1).toString().padStart(2, '0')}",
            dividendPerShare = "21.${i}",
            announceDate = "2024-05-20",
            fiscalYear = "2024"
        )
    }

    @Test
    fun `全量刷新残缺解析_绝不删库且只做upsert保留旧记录`() = runTest {
        val dao = mockk<DividendDao>(relaxed = true)
        val cache = mockk<DividendMemoryCache>(relaxed = true)
        coEvery { dao.getBySymbol(symbol) } returns existingAutoRecords(10)
        coEvery { dao.getBySymbolAndDate(symbol, any()) } returns null

        val writer = DividendWriter(
            context = mockk<Context>(relaxed = true),
            dao = dao,
            cache = cache,
            transactionRunner = { it() }
        )

        // 全量刷新 + 只解析到 2 条（残缺）
        writer.saveFromFetch(symbol, parsedRecords(2), isIncremental = false)

        coVerify(exactly = 0) { dao.deleteNonManualBySymbol(any()) }
        coVerify(exactly = 1) { dao.insertAll(any()) }
        // 残缺集合也不许进内存缓存：否则 24h 内 UI 会直接读到「只解析到一部分」的列表
        verify(exactly = 0) { cache.put(any(), any()) }
    }

    @Test
    fun `全量刷新解析条数正常_确实删除旧自动记录并更新缓存`() = runTest {
        val dao = mockk<DividendDao>(relaxed = true)
        val cache = mockk<DividendMemoryCache>(relaxed = true)
        coEvery { dao.getBySymbol(symbol) } returns existingAutoRecords(10)
        coEvery { dao.getBySymbolAndDate(symbol, any()) } returns null

        val writer = DividendWriter(
            context = mockk<Context>(relaxed = true),
            dao = dao,
            cache = cache,
            transactionRunner = { it() }
        )

        // 全量刷新 + 解析到 10 条（完整）：与库里条数持平，允许替换
        writer.saveFromFetch(symbol, parsedRecords(10), isIncremental = false)

        coVerify(exactly = 1) { dao.deleteNonManualBySymbol(symbol) }
        coVerify(exactly = 1) { dao.insertAll(any()) }
        verify(exactly = 1) { cache.put(symbol, any()) }
    }
}
