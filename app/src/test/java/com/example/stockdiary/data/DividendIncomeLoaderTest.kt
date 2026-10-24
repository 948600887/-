package com.example.stockdiary.data

import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.dividend.DividendIncomeCache
import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    /**
     * 注入可桩的缓存层，专门验证「同日复用 / 数据变化重算 / 跨日重算 / forceRefresh」四条短路语义。
     * [DividendIncomeLoader] 的缓存参数是可空的：传 null（[loader]）时整条缓存逻辑自动跳过，
     * 行为与改造前完全一致 —— 上面那些既有单测因此零改动。这里传 [cache] 走真正的缓存分支。
     */
    private val cache = mockk<DividendIncomeCache>()
    private val cachedLoader = DividendIncomeLoader(repository, cache)

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
        coVerify(exactly = 0) { repository.getDividendsBySymbol(any(), any(), any()) }
    }

    @Test
    fun `cacheOnly 有一个标的没缓存就整批走抓取`() = runBlocking {
        val today = LocalDate.now().toString()
        val assets = listOf(stock("sh600519"), stock("sh000001"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.getCachedAllowingStale("sh000001") } returns null // 连过期数据都没有
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))
        coEvery { repository.getDividendsBySymbol("sh000001", false, any()) } returns emptyList()

        val result = loader.loadIncome(assets, cacheOnly = true)

        // 批量预取必须带上全部标的，而不是只抓缺的那只——合并请求才省得下请求数
        coVerify(exactly = 1) { repository.prefetchDividends(listOf("sh600519", "sh000001"), false) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", false, any()) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh000001", false, any()) }
        assertEquals(50.0, result.totalAnnualIncome, 1e-6)
    }

    @Test
    fun `cacheOnly 与 forceRefresh 同时为 true 时不走快速路径`() = runBlocking {
        val today = LocalDate.now().toString()
        val assets = listOf(stock("sh600519"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", true, any()) } returns listOf(record(today, "5.0"))

        loader.loadIncome(assets, forceRefresh = true, cacheOnly = true)

        // 刷新路径刻意不看缓存：宁可多发一次批量请求，也不能把旧数据当新的
        coVerify(exactly = 1) { repository.prefetchDividends(listOf("sh600519"), true) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", true, any()) }
    }

    @Test
    fun `只有存款没有股票基金时零请求且结果正确`() = runBlocking {
        val assets = listOf(deposit(balance = 100_000.0, rate = 3.0))
        // 没有股票基金时 prefetch 仍会被调一次（空列表直接返回），必须打桩
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()

        val result = loader.loadIncome(assets, cacheOnly = true)

        // 利息照算（100000 × 3% = 3000），分红一侧为空
        assertEquals(3000.0, result.totalAnnualIncome, 1e-6)
        assertTrue(result.dividendsBySymbol.isEmpty())
        assertTrue(!result.hasDividendData)
        coVerify(exactly = 1) { repository.prefetchDividends(emptyList(), false) }
        coVerify(exactly = 0) { repository.getDividendsBySymbol(any(), any(), any()) }
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

    // ============ 以下为「方案 1：持久化预计收入」的新增回归 ============

    private fun income(date: String, total: Double, perSymbol: Map<String, Double>) =
        DividendIncomeCache.CachedIncome(
            date = date,
            dataHash = "h_$date",
            dividendsBySymbol = perSymbol,
            totalAnnualIncome = total,
            hasDividendData = perSymbol.values.any { it > 0 }
        )

    /**
     * 同日、非强制刷新、底层数据未变 → 第二次应**复用**缓存结果，不再进 prefetch / getDividendsBySymbol。
     *
     * 这是「杀掉同日抖动」的核心断言：第一次正常抓取落盘后，第二次（同一天、records 不变）
     * 必须零网络直接返回旧值。
     */
    @Test
    fun `同日非强制刷新复用缓存结果不重抓`() = runBlocking {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val assets = listOf(stock("sh600519"), stock("sh000001"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.getCachedAllowingStale("sh000001") } returns listOf(record(today, "3.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))
        coEvery { repository.getDividendsBySymbol("sh000001", false, any()) } returns listOf(record(today, "3.0"))

        val savedResults = mutableListOf<DividendIncomeLoader.IncomeResult>()
        val savedHashes = mutableListOf<String>()
        val savedDates = mutableListOf<String>()
        every { cache.save(any(), any(), any(), any()) } answers {
            savedResults.add(arg(0) as DividendIncomeLoader.IncomeResult)
            savedHashes.add(arg(1) as String)
            savedDates.add(arg(2) as String)
        }
        // 第一次无缓存 → 正常抓取落盘；第二次返回第一次落盘的内容（哈希/日期一致）→ 复用
        var loadCount = 0
        every { cache.loadCached(any()) } answers {
            when (loadCount++) {
                0 -> null
                else -> DividendIncomeCache.CachedIncome(
                    date = savedDates[0],
                    dataHash = savedHashes[0],
                    dividendsBySymbol = savedResults[0].dividendsBySymbol,
                    totalAnnualIncome = savedResults[0].totalAnnualIncome,
                    hasDividendData = savedResults[0].hasDividendData
                )
            }
        }

        val first = cachedLoader.loadIncome(assets)
        val second = cachedLoader.loadIncome(assets)

        // 两次结果一致（复用）
        assertEquals(first.totalAnnualIncome, second.totalAnnualIncome, 1e-9)
        // 第二次不应再进抓取：prefetch / getDividendsBySymbol 只在第一次各调一次
        coVerify(exactly = 1) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", false, any()) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh000001", false, any()) }
        // 落盘只在第一次发生（复用那次走缓存分支、不save）
        coVerify(exactly = 1) { cache.save(any(), any(), any(), any()) }
    }

    /**
     * 底层分红数据变化（dataHash 变）→ 必须重算并落新缓存，不能把旧值当新的。
     */
    @Test
    fun `底层分红数据变化触发重算并落新缓存`() = runBlocking {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val assets = listOf(stock("sh600519"))
        // 第一次底层记录 5.0；第二次底层记录变成 9.0（数据真变了）
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0")) andThen
            listOf(record(today, "9.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns
            listOf(record(today, "5.0")) andThen listOf(record(today, "9.0"))

        val savedResults = mutableListOf<DividendIncomeLoader.IncomeResult>()
        val savedHashes = mutableListOf<String>()
        val savedDates = mutableListOf<String>()
        every { cache.save(any(), any(), any(), any()) } answers {
            savedResults.add(arg(0) as DividendIncomeLoader.IncomeResult)
            savedHashes.add(arg(1) as String)
            savedDates.add(arg(2) as String)
        }
        // 第二次 loadCached 返回「旧哈希」缓存（对应 5.0），迫使重算
        var loadCount = 0
        every { cache.loadCached(any()) } answers {
            when (loadCount++) {
                0 -> null
                else -> DividendIncomeCache.CachedIncome(
                    date = savedDates[0],
                    dataHash = savedHashes[0],
                    dividendsBySymbol = savedResults[0].dividendsBySymbol,
                    totalAnnualIncome = savedResults[0].totalAnnualIncome,
                    hasDividendData = savedResults[0].hasDividendData
                )
            }
        }

        val first = cachedLoader.loadIncome(assets)
        val second = cachedLoader.loadIncome(assets)

        // 数据变了 → 结果必须不同（100股 × 5/10 = 50 vs 100股 × 9/10 = 90）
        assertNotEquals(first.totalAnnualIncome, second.totalAnnualIncome, 1e-9)
        assertEquals(50.0, first.totalAnnualIncome, 1e-6)
        assertEquals(90.0, second.totalAnnualIncome, 1e-6)
        // 两次都走了抓取（prefetch 各一次）并都落盘
        coVerify(exactly = 2) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 2) { repository.getDividendsBySymbol("sh600519", false, any()) }
        coVerify(exactly = 2) { cache.save(any(), any(), any(), any()) }
    }

    /**
     * 跨日（缓存 date != 今天）→ 必须重算，保留 TTM 滑窗漂移。
     */
    @Test
    fun `跨日触发重算`() = runBlocking {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val assets = listOf(stock("sh600519"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val savedResults = mutableListOf<DividendIncomeLoader.IncomeResult>()
        val savedHashes = mutableListOf<String>()
        val savedDates = mutableListOf<String>()
        every { cache.save(any(), any(), any(), any()) } answers {
            savedResults.add(arg(0) as DividendIncomeLoader.IncomeResult)
            savedHashes.add(arg(1) as String)
            savedDates.add(arg(2) as String)
        }
        // 缓存的 date 是 2000-01-01（跨日），但哈希一致 → 仍应重算
        var loadCount = 0
        every { cache.loadCached(any()) } answers {
            when (loadCount++) {
                0 -> null
                else -> DividendIncomeCache.CachedIncome(
                    date = "2000-01-01",
                    dataHash = savedHashes[0],
                    dividendsBySymbol = savedResults[0].dividendsBySymbol,
                    totalAnnualIncome = savedResults[0].totalAnnualIncome,
                    hasDividendData = savedResults[0].hasDividendData
                )
            }
        }

        val first = cachedLoader.loadIncome(assets)
        val second = cachedLoader.loadIncome(assets)

        // 两次都走抓取（跨日不复用），各自重算
        coVerify(exactly = 2) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 2) { repository.getDividendsBySymbol("sh600519", false, any()) }
        coVerify(exactly = 2) { cache.save(any(), any(), any(), any()) }
        // 两次结果一致（同一份数据），但都是重算得到的，而非复用旧缓存
        assertEquals(first.totalAnnualIncome, second.totalAnnualIncome, 1e-9)
    }

    /**
     * forceRefresh=true → 必须绕过短路、强制重抓重算，不能把旧缓存静默当新的。
     */
    @Test
    fun `forceRefresh 强制重算不读缓存`() = runBlocking {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val assets = listOf(stock("sh600519"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", true, any()) } returns listOf(record(today, "5.0"))

        every { cache.save(any(), any(), any(), any()) } just runs
        // 即使有一份「完全匹配今天 + 哈希」的缓存，forceRefresh 也应无视
        every { cache.loadCached(any()) } returns income(today, 999.0, mapOf("sh600519" to 99.0))

        val result = cachedLoader.loadIncome(assets, forceRefresh = true)

        // 结果来自真实抓取（100股 × 5/10 = 50），不是缓存里的 999
        assertEquals(50.0, result.totalAnnualIncome, 1e-6)
        // 强制刷新走 getDividendsBySymbol(..., true, ...) 这条路
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", true, any()) }
        // 强制刷新不会去读 getCachedAllowingStale（短路路径被 forceRefresh 跳过）
        coVerify(exactly = 0) { repository.getCachedAllowingStale(any()) }
        coVerify(exactly = 1) { cache.save(any(), any(), any(), any()) }
    }

    /** 捕获落盘的 (result, dataHash, date) 三段，用于构造「与首次计算匹配」的假缓存 */
    private fun captureSave():
        Triple<MutableList<DividendIncomeLoader.IncomeResult>, MutableList<String>, MutableList<String>> {
        val results = mutableListOf<DividendIncomeLoader.IncomeResult>()
        val hashes = mutableListOf<String>()
        val dates = mutableListOf<String>()
        every { cache.save(any(), any(), any(), any()) } answers {
            results.add(arg(0) as DividendIncomeLoader.IncomeResult)
            hashes.add(arg(1) as String)
            dates.add(arg(2) as String)
        }
        return Triple(results, hashes, dates)
    }

    /**
     * 修「同日改持仓但预计收入不更新」掩藏（QA advisory）：分红记录不变，但当天加仓
     * （balance 变）→ dataHash 变 → 必须重算，不能把旧值当新的。
     */
    @Test
    fun `同日分红不变但持仓 balance 变化_必须重算`() = runBlocking {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val assetsA = listOf(stock("sh600519", balance = 100.0))
        val assetsB = listOf(stock("sh600519", balance = 200.0)) // 当天加仓
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (results, hashes, dates) = captureSave()
        var loadCount = 0
        every { cache.loadCached(any()) } answers {
            when (loadCount++) {
                0 -> null
                // 第二次返回「用旧持仓(100)算出的哈希」的缓存；当前持仓已变 200 → 哈希不同 → 重算
                else -> DividendIncomeCache.CachedIncome(
                    date = dates[0],
                    dataHash = hashes[0],
                    dividendsBySymbol = results[0].dividendsBySymbol,
                    totalAnnualIncome = results[0].totalAnnualIncome,
                    hasDividendData = results[0].hasDividendData
                )
            }
        }

        val first = cachedLoader.loadIncome(assetsA) // 100 股 × 5/10 = 50
        val second = cachedLoader.loadIncome(assetsB) // 200 股 × 5/10 = 100，必须重算

        assertEquals(50.0, first.totalAnnualIncome, 1e-6)
        assertEquals(100.0, second.totalAnnualIncome, 1e-6)
        // 两次都走了抓取（哈希因持仓而不同）+ 都落盘
        coVerify(exactly = 2) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 2) { repository.getDividendsBySymbol("sh600519", false, any()) }
        coVerify(exactly = 2) { cache.save(any(), any(), any(), any()) }
    }

    /**
     * currency 也是 calculateAnnualIncome 的真实输入（toCny 折算）→ 变 currency 必须重算。
     * 只断言「重算发生」（prefetch/save 各两次），不锁具体金额——USD 折算依赖 ExchangeRateManager 状态。
     */
    @Test
    fun `同日分红不变但持仓 currency 变化_必须重算`() = runBlocking {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val assetsA = listOf(stock("sh600519").copy(currency = "CNY"))
        val assetsB = listOf(stock("sh600519").copy(currency = "USD"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (results, hashes, dates) = captureSave()
        var loadCount = 0
        every { cache.loadCached(any()) } answers {
            when (loadCount++) {
                0 -> null
                else -> DividendIncomeCache.CachedIncome(
                    date = dates[0],
                    dataHash = hashes[0],
                    dividendsBySymbol = results[0].dividendsBySymbol,
                    totalAnnualIncome = results[0].totalAnnualIncome,
                    hasDividendData = results[0].hasDividendData
                )
            }
        }

        cachedLoader.loadIncome(assetsA)
        cachedLoader.loadIncome(assetsB) // currency 变了 → 哈希不同 → 重算

        coVerify(exactly = 2) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 2) { repository.getDividendsBySymbol("sh600519", false, any()) }
        coVerify(exactly = 2) { cache.save(any(), any(), any(), any()) }
    }

    /**
     * 防止「哈希加持仓字段后永远不命中」把方案1的消抖动效果打没：
     * 分红 + 持仓全不变 → 必须仍走缓存复用（原样返回持久化值，不偷偷重算）。
     */
    @Test
    fun `同日分红与持仓全不变_仍走缓存复用`() = runBlocking {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val assets = listOf(stock("sh600519", balance = 100.0))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (_, hashes, _) = captureSave()
        var loadCount = 0
        every { cache.loadCached(any()) } answers {
            when (loadCount++) {
                0 -> null
                // 全不变 → 第二次哈希与首次一致，缓存 date 也是今天 → 复用
                else -> DividendIncomeCache.CachedIncome(
                    date = today,
                    dataHash = hashes[0],
                    dividendsBySymbol = mapOf("sh600519" to 5.0),
                    totalAnnualIncome = 777.0, // 与重算值 50 不同：复用就必须原样返回 777
                    hasDividendData = true
                )
            }
        }

        cachedLoader.loadIncome(assets)
        val reused = cachedLoader.loadIncome(assets)

        assertEquals(777.0, reused.totalAnnualIncome, 1e-6)
        // 两次调用总共只抓取过一次
        coVerify(exactly = 1) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", false, any()) }
    }
}
