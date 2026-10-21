package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.repository.DividendRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * REIT / 非 ETF 场内基金（508xxx 等）分红路由回归。
 *
 * 根因：[DividendRepository.fetchDividendData] 曾经只把 [isEtf] 当门槛来决定是否走
 * 天天基金基金分红源；REITs（classifyCode = EXCHANGE_FUND 但 isEtf = false）因此永远到不了
 * 那个真正有数据的源，只能落进东财股票分红表(空) → 新浪(空)，静默显示 0 并被 7 天负缓存固化
 * （用户实测 sh508085 等 REIT 长年 0 分红）。
 *
 * 测法：对真实实例做 spy，把三个内部数据源桩掉，直接调内部 [DividendRepository.fetchDividendData]：
 * - REIT 应路由到 [DividendRepository.fetchFromFundApiForETF] 并返回其记录，且不得落到新浪
 *   （新浪是股票口径源，没有 REIT 收益分配，修复前正是这里静默返回空）；
 * - 普通股票(sh600519) 行为不变，仍走新浪兜底。
 * 不碰 DB：[fetchDividendData] 本身不读写 Room。
 */
class ReitDividendRoutingTest {

    private val context = mockk<Context>(relaxed = true)
    private val repository = spyk(DividendRepository.getInstance(context))

    @After
    fun tearDown() = unmockkAll()

    private fun reitRecord() = DividendRecord(
        exDividendDate = "2026-08-24",
        dividendPerShare = "0.5872",
        announceDate = "2026-08-20",
        fiscalYear = "2026",
        paymentDate = "2026-08-26"
    )

    @Test
    fun `REIT 经批量空结果后路由到天天基金并拿到收益分配`() = runBlocking {
        // 注：REIT 走批量空结果路径时 eastmoneyAnswered=true，when 直接短路返回空，
        // fetchFromEastmoney 不会被调用——这正是修复前的死路，这里无需（也不能）桩它。
        coEvery { repository.fetchFromSina(any(), any()) } returns emptyList()
        coEvery { repository.fetchFromFundApiForETF("508085") } returns listOf(reitRecord())

        // 走真实批量入口：批量对东财返回空（REIT 不在股票分红表里），answered 但无数据
        val result = repository.fetchDividendData(
            "sh508085",
            mapOf("sh508085" to emptyList())
        )

        assertEquals(listOf(reitRecord()), result)
        coVerify(exactly = 1) { repository.fetchFromFundApiForETF("508085") }
        // 关键回归点：REIT 不得再落到新浪（修复前正是这里静默返回空）
        coVerify(exactly = 0) { repository.fetchFromSina(any(), any()) }
    }

    @Test
    fun `普通股票不受改动影响_仍走新浪兜底`() = runBlocking {
        coEvery { repository.fetchFromSina("600519", "sh") } returns emptyList()
        coEvery { repository.fetchFromFundApiForETF(any()) } returns emptyList()

        val result = repository.fetchDividendData(
            "sh600519",
            mapOf("sh600519" to emptyList())
        )

        assertEquals(emptyList<DividendRecord>(), result)
        coVerify(exactly = 1) { repository.fetchFromSina("600519", "sh") }
        coVerify(exactly = 0) { repository.fetchFromFundApiForETF(any()) }
    }
}
