package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [DividendIncomeLoader] 的 cacheOnly 冷启动路径回归。
 *
 * 「打开 App 不该为了刷新预计收入去敲几十个接口」是本轮功耗优化的核心承诺，
 * 而这条快速路径此前没有任何单测兜底（[DividendIncomeUseCaseTest] 的注释
 * 还把这件事寄托给「Loader 自己单测」，但那个文件并不存在）。必须钉死的行为：
 *
 *  1. 全部标的都有缓存（含「抓过且确实没有分红」的空列表）→ 零请求；
 *  2. 只要有一个标的连过期数据都没有 → 整批走正常抓取（内部先批量预取）；
 *  3. cacheOnly 与 forceRefresh 同时为 true → 刻意不走快速路径（刷新要新数据）；
 *  4. 用户只有存款/负债、没有任何股票基金 → 同样零请求，且不炸。
 *
 * DividendRepository 是带私有构造函数的单例，这里用**严格** MockK 桩：
 * 没打桩的方法一旦被调用就会抛异常，正好兼任「不该发请求」的断言。
 */
class DividendIncomeLoaderTest {

    private val repository = mockk<DividendRepository>()
    private val loader = DividendIncomeLoader(repository)

    private fun stock(symbol: String, balance: Double = 100.0) = Asset(
        name = symbol,
        type = AssetType.STOCK,
        symbol = symbol,
        account = "默认",
        balance = balance
    )

    private fun deposit(balance: Double = 100_000.0, rate: Double? = null) = Asset(
        name = "存款",
        type = AssetType.BANK_DEPOSIT,
        symbol = null,
        account = "银行",
        balance = balance,
        interestRate = rate
    )

    private fun record(date: String, amount: String) = DividendRecord(
        exDividendDate = date,
        dividendPerShare = amount,
        announceDate = "",
        fiscalYear = date.take(4)
    )

    @Test
    fun `cacheOnly 全部命中缓存时零请求`() = runBlocking {
        val today = LocalDate.now().toString()
        val assets = listOf(stock("sh600519"), stock("sh000001"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        // 空列表 = 「抓过，且确实没有分红」，是有效答案，同样算命中
        coEvery { repository.getCachedAllowingStale("sh000001") } returns emptyList()

        val result = loader.loadIncome(assets, cacheOnly = true)

        // 100 股 × 每十股 5 元 ÷ 10 = 50；没分红的那只贡献 0
        assertEquals(50.0, result.totalAnnualIncome, 1e-6)
        assertEquals(5.0, result.dividendsBySymbol["sh600519"] ?: 0.0, 1e-6)
        assertEquals(0.0, result.dividendsBySymbol["sh000001"] ?: -1.0, 1e-6)
        assertTrue(result.hasDividendData)
        assertEquals(1, result.recordsBySymbol["sh600519"]?.size)
        assertTrue(result.recordsBySymbol["sh000001"]?.isEmpty() == true)

        // 严格桩下任何抓取调用都会当场抛异常；这里再显式钉死「一次都没发」
        coVerify(exactly = 1) { repository.getCachedAllowingStale("sh600519") }
        coVerify(exactly = 1) { repository.getCachedAllowingStale("sh000001") }
        coVerify(exactly = 0) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 0) { repository.getDividendsBySymbol(any(), any()) }
    }

    @Test
    fun `cacheOnly 有一个标的没缓存就整批走抓取`() = runBlocking {
        val today = LocalDate.now().toString()
        val assets = listOf(stock("sh600519"), stock("sh000001"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.getCachedAllowingStale("sh000001") } returns null // 连过期数据都没有
        coEvery { repository.prefetchDividends(any(), any()) } returns Unit
        coEvery { repository.getDividendsBySymbol("sh600519", false) } returns listOf(record(today, "5.0"))
        coEvery { repository.getDividendsBySymbol("sh000001", false) } returns emptyList()

        val result = loader.loadIncome(assets, cacheOnly = true)

        // 批量预取必须带上全部标的，而不是只抓缺的那只——合并请求才省得下请求数
        coVerify(exactly = 1) { repository.prefetchDividends(listOf("sh600519", "sh000001"), false) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", false) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh000001", false) }
        assertEquals(50.0, result.totalAnnualIncome, 1e-6)
    }

    @Test
    fun `cacheOnly 与 forceRefresh 同时为 true 时不走快速路径`() = runBlocking {
        val today = LocalDate.now().toString()
        val assets = listOf(stock("sh600519"))
        coEvery { repository.prefetchDividends(any(), any()) } returns Unit
        coEvery { repository.getDividendsBySymbol("sh600519", true) } returns listOf(record(today, "5.0"))

        loader.loadIncome(assets, forceRefresh = true, cacheOnly = true)

        // 刷新路径刻意不看缓存：宁可多发一次批量请求，也不能把旧数据当新的
        coVerify(exactly = 1) { repository.prefetchDividends(listOf("sh600519"), true) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", true) }
    }

    @Test
    fun `只有存款没有股票基金时零请求且结果正确`() = runBlocking {
        val assets = listOf(deposit(balance = 100_000.0, rate = 3.0))
        // 没有股票基金时 prefetch 仍会被调一次（空列表直接返回），必须打桩
        coEvery { repository.prefetchDividends(any(), any()) } returns Unit

        val result = loader.loadIncome(assets, cacheOnly = true)

        // 利息照算（100000 × 3% = 3000），分红一侧为空
        assertEquals(3000.0, result.totalAnnualIncome, 1e-6)
        assertTrue(result.dividendsBySymbol.isEmpty())
        assertTrue(!result.hasDividendData)
        coVerify(exactly = 1) { repository.prefetchDividends(emptyList(), false) }
        coVerify(exactly = 0) { repository.getDividendsBySymbol(any(), any()) }
    }

    @Test
    fun `cacheOnly 快速路径的汇总口径必须与抓取路径一致_存款利息也要算进去`() = runBlocking {
        // 回归：快速路径曾用 buildResult(stockAssets, …) 汇总，把存款利息漏掉；
        // 而「同步完成后再算一次」的正常路径用全部资产，含利息。
        // 两条路对同一用户给出不同的「预计收入」——首页/日历冷启动显示分红数，
        // 同步完成后跳成 分红+利息，下次冷启动又跳回去。
        val today = LocalDate.now().toString()
        val assets = listOf(stock("sh600519"), deposit(balance = 100_000.0, rate = 3.0))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))

        val result = loader.loadIncome(assets, cacheOnly = true)

        // 100 股 × 每十股 5 元 ÷ 10 = 50，加存款利息 100000 × 3% = 3000
        assertEquals(3050.0, result.totalAnnualIncome, 1e-6)
    }
}
