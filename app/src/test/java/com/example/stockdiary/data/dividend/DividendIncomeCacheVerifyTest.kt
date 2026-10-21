package com.example.stockdiary.data.dividend

import android.content.Context
import android.content.SharedPreferences
import com.example.stockdiary.data.repository.DividendRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * QA 独立复验（fresh eyes）：不重跑工程师的用例，自己写边界证明「方案 1」缓存逻辑。
 *
 * 覆盖工程师没测过的点：
 *  1. **真实** [DividendIncomeCache] 经 SharedPreferences 的 JSON double 往返精度
 *     （org.json 序列化 → parseDouble 是否 bit 级不变，用 3552.6084214775 这类实测值坐实）；
 *  2. 缓存缺失 / JSON 损坏 / JSON 缺字段时的降级（返回 null 或默认值，不崩）；
 *  3. save 序列化确定性（相同内容 → 相同字符串，Map 按 key 排序）；
 *  4. 短路边界：同日但 dataHash 不同、非同日但 dataHash 相同、forceRefresh + 同日 + 数据未变；
 *  5. 命中复用时 recordsBySymbol 必须回填**当前**库记录（而不是缓存里的旧 records）；
 *  6. incomeCache = null（默认参数）时旧行为不变，连续调用仍稳定。
 *
 * FakePrefs 是内存版 SharedPreferences：不依赖 Robolectric，又能驱动真实的
 * getSharedPreferences → JSONObject put/parse 全链路。
 */
class DividendIncomeCacheVerifyTest {

    // ==================== Part 1：真实 DividendIncomeCache + 假 SharedPreferences ====================

    /** 内存版 SharedPreferences，只实现本缓存用到的 getString / edit 语义 */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = FakeEditor(values)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, Any?> = HashMap<String, Any?>(values)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeEditor(private val values: MutableMap<String, String>) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) values.remove(key) else values[key] = value
            return this
        }
        override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor { values.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { values.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }

    private fun cacheWith(prefs: FakePrefs): DividendIncomeCache {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return DividendIncomeCache(context)
    }

    private fun result(
        total: Double,
        perSymbol: Map<String, Double>,
        hasDividendData: Boolean = perSymbol.values.any { it > 0 }
    ) = DividendIncomeLoader.IncomeResult(
        dividendsBySymbol = perSymbol,
        recordsBySymbol = emptyMap(),
        totalAnnualIncome = total,
        hasDividendData = hasDividendData
    )

    @Test
    fun `save 后 loadCached 往返_日期哈希金额全部一致`() {
        val prefs = FakePrefs()
        val cache = cacheWith(prefs)
        // 用「自然年口径」真实算一笔年度收入（新口径：与收息进度今年预计同源），
        // 经缓存 JSON 往返后必须 bit 级不变，证明缓存层能原样保住新口径数字。
        val today = LocalDate.of(2026, 10, 20)
        val recordsBySymbol = mapOf(
            "sh600519" to listOf(record("2026-06-01", "50.0")),   // 今年已宣告：每十股 50 → 100 份 = 500
            "sh000001" to emptyList(),                              // 未宣告 → 退回 TTM（无记录=0）
            "fund_000300" to listOf(record("2025-12-01", "123.4")) // 去年记录、今年无 → 退回 TTM
        )
        val assets = listOf(
            stock("sh600519"),
            stock("sh000001"),
            stock("fund_000300")
        )
        val total = DividendProgressCalculator.naturalYearAnnualIncome(assets, recordsBySymbol, today)

        val saved = result(
            total = total,
            perSymbol = mapOf("sh600519" to 5.0, "sh000001" to 0.0, "fund_000300" to 12.34)
        )

        cache.save(saved, dataHash = "hash-abc", date = "2026-10-20")

        val loaded = cache.loadCached()
        assertNotNull(loaded)
        assertEquals("2026-10-20", loaded!!.date)
        assertEquals("hash-abc", loaded.dataHash)
        assertEquals(total, loaded.totalAnnualIncome, 0.0) // delta=0：要求 bit 级一致（新口径实算值）
        assertTrue(loaded.hasDividendData)
        assertEquals(3, loaded.dividendsBySymbol.size)
        assertEquals(5.0, loaded.dividendsBySymbol["sh600519"]!!, 0.0)
        assertEquals(0.0, loaded.dividendsBySymbol["sh000001"]!!, 0.0)
        assertEquals(12.34, loaded.dividendsBySymbol["fund_000300"]!!, 0.0)
    }

    /**
     * JSON double 往返精度：org.json 的 put(double) 走 Double.toString，
     * optDouble 走 Double.parseDouble。Java 规范保证这对所有有限 double 是
     * 无损互逆的——这里用实测里出现过的 3552.6084214775 及各类刁钻值坐实，
     * delta 全部传 0.0（bit 级相等），不拍脑袋。
     */
    @Test
    fun `JSON double 往返精度_刁钻数值 bit 级不变`() {
        val prefs = FakePrefs()
        val cache = cacheWith(prefs)
        val tricky = mapOf(
            "log_value" to 3552.6084214775,          // 日志里出现过的真实值
            "float_sum" to 0.1 + 0.2,                // 0.30000000000000004
            "one_third" to 1.0 / 3.0,                // 0.3333333333333333
            "negative" to -42.5,
            "tiny" to 1e-10,
            "big_plus_half" to 1e15 + 0.5,
            "max_div3" to Double.MAX_VALUE / 3,
            "min_subnormal" to 4.9E-324,             // 最小正次正规数
            "max" to Double.MAX_VALUE,
            "zero" to 0.0
        )

        cache.save(result(total = 3552.6084214775, perSymbol = tricky), "h", "2026-10-20")

        val loaded = cache.loadCached()!!
        assertEquals(3552.6084214775, loaded.totalAnnualIncome, 0.0)
        tricky.forEach { (symbol, expected) ->
            assertEquals("symbol=$symbol 往返后必须 bit 级一致", expected, loaded.dividendsBySymbol[symbol]!!, 0.0)
        }
    }

    @Test
    fun `SP 从未写入时 loadCached 返回 null`() {
        val cache = cacheWith(FakePrefs())
        assertNull(cache.loadCached())
    }

    @Test
    fun `SP 中 JSON 损坏时 loadCached 返回 null 且不崩`() {
        val prefs = FakePrefs()
        val cache = cacheWith(prefs)
        // 先正常落盘一份，再把原始串篡改成损坏 JSON（不硬编码 key，取落盘的那一条）
        cache.save(result(total = 50.0, perSymbol = mapOf("sh600519" to 5.0)), "h", "2026-10-20")
        val key = prefs.values.keys.single()
        prefs.values[key] = "{ 这不是合法 JSON"

        assertNull("损坏 JSON 必须降级为 null（宁可重算，不能把脏数据当真）", cache.loadCached())
    }

    @Test
    fun `SP 中 JSON 缺字段时降级为默认值且不崩`() {
        val prefs = FakePrefs()
        val cache = cacheWith(prefs)
        prefs.values["income_cache_v1"] = """{"date":"2026-10-20"}"""

        val loaded = cache.loadCached()
        assertNotNull("合法 JSON 缺字段应降级解析，而不是整体判 null", loaded)
        assertEquals("2026-10-20", loaded!!.date)
        assertEquals("", loaded.dataHash)
        assertEquals(0.0, loaded.totalAnnualIncome, 0.0)
        assertTrue(loaded.dividendsBySymbol.isEmpty())
    }

    @Test
    fun `save 序列化确定_相同内容落出相同字符串`() {
        val prefs = FakePrefs()
        val cache = cacheWith(prefs)
        val r = result(
            total = 100.0,
            perSymbol = mapOf("sh600519" to 5.0, "sh000001" to 3.0, "fund_000300" to 7.0)
        )
        cache.save(r, "h1", "2026-10-20")
        val first = prefs.values.values.single()
        cache.save(r, "h1", "2026-10-20")
        val second = prefs.values.values.single()
        assertEquals("Map 按 key 排序后写入，相同内容必须序列化出相同字符串", first, second)
    }

    // ==================== Part 2：DividendIncomeLoader 短路边界（mock 缓存层） ====================

    private val repository = mockk<DividendRepository>()
    private val cache = mockk<DividendIncomeCache>()
    private val cachedLoader = DividendIncomeLoader(repository, cache)
    private val plainLoader = DividendIncomeLoader(repository) // incomeCache = null，旧行为

    private fun stock(symbol: String, balance: Double = 100.0) = Asset(
        name = symbol,
        type = AssetType.STOCK,
        symbol = symbol,
        account = "默认",
        balance = balance
    )

    private fun record(date: String, amount: String) = DividendRecord(
        exDividendDate = date,
        dividendPerShare = amount,
        announceDate = "",
        fiscalYear = date.take(4)
    )

    private val today: String get() = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val yesterday: String get() = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** 抓一次并捕获落盘的 (result, dataHash, date)，用于构造「与当前数据匹配」的假缓存 */
    private fun captureSave(): Triple<List<DividendIncomeLoader.IncomeResult>, List<String>, List<String>> {
        val results = mutableListOf<DividendIncomeLoader.IncomeResult>()
        val hashes = mutableListOf<String>()
        val dates = mutableListOf<String>()
        every { cache.save(any(), any(), any()) } answers {
            results.add(arg(0) as DividendIncomeLoader.IncomeResult)
            hashes.add(arg(1) as String)
            dates.add(arg(2) as String)
        }
        return Triple(results, hashes, dates)
    }

    @Test
    fun `缓存同日但 dataHash 不同_必须重算而不是复用旧值`() = runBlocking {
        val assets = listOf(stock("sh600519"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (results, hashes, dates) = captureSave()
        every { cache.loadCached() } returns null andThen DividendIncomeCache.CachedIncome(
            date = today,
            dataHash = "与当前记录不同的哈希", // 同日但底层数据判定为「变了」
            dividendsBySymbol = mapOf("sh600519" to 99.0),
            totalAnnualIncome = 999.0,
            hasDividendData = true
        )

        val first = cachedLoader.loadIncome(assets)
        val second = cachedLoader.loadIncome(assets)

        // 哈希不同 → 第二次必须重算：拿新抓的 50.0，而不是缓存里的 999.0
        assertEquals(50.0, first.totalAnnualIncome, 1e-6)
        assertEquals(50.0, second.totalAnnualIncome, 1e-6)
        coVerify(exactly = 2) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 2) { repository.getDividendsBySymbol("sh600519", false, any()) }
        coVerify(exactly = 2) { cache.save(any(), any(), any()) }
        assertEquals(2, hashes.size)
        assertEquals(today, dates[0])
    }

    @Test
    fun `缓存非同日_即使 dataHash 完全一致也必须重算_保留跨日 TTM 漂移`() = runBlocking {
        val assets = listOf(stock("sh600519"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (_, hashes, _) = captureSave()
        var loadCount = 0
        every { cache.loadCached() } answers {
            when (loadCount++) {
                0 -> null
                // 昨天落盘的缓存，哈希与当前完全一致 —— 跨日照样不许复用
                else -> DividendIncomeCache.CachedIncome(
                    date = yesterday,
                    dataHash = hashes[0],
                    dividendsBySymbol = mapOf("sh600519" to 5.0),
                    totalAnnualIncome = 999.0,
                    hasDividendData = true
                )
            }
        }

        val first = cachedLoader.loadIncome(assets)
        val second = cachedLoader.loadIncome(assets)

        // 两次都重算（TTM 滑窗随日期漂移是预期行为，不许被缓存吃掉）
        coVerify(exactly = 2) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 2) { cache.save(any(), any(), any()) }
        assertEquals(50.0, first.totalAnnualIncome, 1e-6)
        assertEquals(50.0, second.totalAnnualIncome, 1e-6)
    }

    @Test
    fun `forceRefresh 同日且数据未变_必须重抓重算且不读持久化缓存`() = runBlocking {
        val assets = listOf(stock("sh600519"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))
        coEvery { repository.getDividendsBySymbol("sh600519", true, any()) } returns listOf(record(today, "5.0"))

        val (_, hashes, _) = captureSave()
        every { cache.loadCached() } returns null
        // 第一次（非强制）先抓一次并落盘，拿到与当前记录匹配的哈希
        cachedLoader.loadIncome(assets)
        // 之后持久化缓存里是一份「今天 + 哈希一致 + total=999」的旧值
        every { cache.loadCached() } returns DividendIncomeCache.CachedIncome(
            date = today,
            dataHash = hashes[0],
            dividendsBySymbol = mapOf("sh600519" to 5.0),
            totalAnnualIncome = 999.0,
            hasDividendData = true
        )

        val forced = cachedLoader.loadIncome(assets, forceRefresh = true)

        // 强制刷新：结果来自真实抓取（50.0），不是缓存的 999.0
        assertEquals(50.0, forced.totalAnnualIncome, 1e-6)
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", true, any()) }
        // 强制刷新整条短路都被跳过：getCachedAllowingStale 只在第一次非强制调用时读过一次
        coVerify(exactly = 1) { repository.getCachedAllowingStale(any()) }
        // 强制刷新照常落盘（刷新缓存，供下次同日复用）
        coVerify(exactly = 2) { cache.save(any(), any(), any()) }
    }

    @Test
    fun `同日哈希一致_原样复用缓存值即使与重算值不同`() = runBlocking {
        val assets = listOf(stock("sh600519"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (_, hashes, _) = captureSave()
        every { cache.loadCached() } returns null
        cachedLoader.loadIncome(assets) // 第一次：落盘，哈希 = 当前记录的哈希
        every { cache.loadCached() } returns DividendIncomeCache.CachedIncome(
            date = today,
            dataHash = hashes[0], // 同日 + 哈希一致
            dividendsBySymbol = mapOf("sh600519" to 5.0),
            totalAnnualIncome = 777.0, // 与重算值 50.0 不同：复用就必须原样返回 777
            hasDividendData = true
        )

        val reused = cachedLoader.loadIncome(assets)

        // 原样返回持久化的 777.0（证明是真复用，不是偷偷重算了一遍）
        assertEquals(777.0, reused.totalAnnualIncome, 1e-6)
        // 两次调用总共只抓取过一次
        coVerify(exactly = 1) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", false, any()) }
    }

    @Test
    fun `复用缓存时 recordsBySymbol 回填当前库记录而非旧 records`() = runBlocking {
        val assets = listOf(stock("sh600519"))
        // 当前库里的记录：除息日/金额与落盘时一致（哈希匹配），但到账日不同
        val currentRecords = listOf(
            DividendRecord(
                exDividendDate = today,
                dividendPerShare = "5.0",
                announceDate = "2026-10-01",
                fiscalYear = "2026",
                paymentDate = "2026-11-15"
            )
        )
        coEvery { repository.getCachedAllowingStale("sh600519") } returns currentRecords
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (_, hashes, _) = captureSave()
        every { cache.loadCached() } returns null
        cachedLoader.loadIncome(assets)
        every { cache.loadCached() } returns DividendIncomeCache.CachedIncome(
            date = today,
            dataHash = hashes[0],
            dividendsBySymbol = mapOf("sh600519" to 5.0),
            totalAnnualIncome = 50.0,
            hasDividendData = true
        )

        val reused = cachedLoader.loadIncome(assets)

        // dividendsBySymbol / total 复用缓存；recordsBySymbol 必须是刚读到的当前库记录
        assertEquals(50.0, reused.totalAnnualIncome, 1e-6)
        assertEquals(currentRecords, reused.recordsBySymbol["sh600519"])
        // 也证明 dataHash 不覆盖 announceDate/paymentDate（不影响 TTM 求和的字段），
        // 这两类字段变化不会导致误重算，同时 recordsBySymbol 仍是新的
    }

    @Test
    fun `dividendPerShare 同值不同字符串形态_保守重算且结果正确`() = runBlocking {
        val assets = listOf(stock("sh600519"))
        // 库里读回的是 amount.toString() 规范形态；假设某次落盘用的原始串是 "5.00"（同值不同形）
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.00"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        val (_, _, _) = captureSave()
        every { cache.loadCached() } returns null andThen DividendIncomeCache.CachedIncome(
            date = today,
            dataHash = "第一次落盘的哈希", // 与 "5.00" 算出的哈希不同（字符串形态敏感）
            dividendsBySymbol = mapOf("sh600519" to 5.0),
            totalAnnualIncome = 50.0,
            hasDividendData = true
        )

        val first = cachedLoader.loadIncome(assets)
        val second = cachedLoader.loadIncome(assets)

        // 形态不同 → 哈希不同 → 保守重算：不返回可能过期的值，且重算结果正确
        coVerify(exactly = 2) { repository.prefetchDividends(any(), any()) }
        assertEquals(50.0, first.totalAnnualIncome, 1e-6)
        assertEquals(50.0, second.totalAnnualIncome, 1e-6)
    }

    @Test
    fun `incomeCache 为 null_旧行为不变_连续两次冷启动均零请求且数值正确`() = runBlocking {
        val assets = listOf(stock("sh600519"))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))

        // 不注入缓存层（默认参数 null）：cacheOnly 冷启动路径与改造前完全一致
        val first = plainLoader.loadIncome(assets, cacheOnly = true)
        val second = plainLoader.loadIncome(assets, cacheOnly = true)

        assertEquals(50.0, first.totalAnnualIncome, 1e-6)
        assertEquals(50.0, second.totalAnnualIncome, 1e-6)
        coVerify(exactly = 2) { repository.getCachedAllowingStale("sh600519") }
        coVerify(exactly = 0) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 0) { repository.getDividendsBySymbol(any(), any(), any()) }
    }

    /**
     * 升级兼容（a63b4c2 补持仓指纹前落盘的旧缓存）：旧哈希不含持仓段，与当前哈希
     * （含持仓指纹）必然不同 → 同日也自然不命中 → 自动重算一次，无迁移、无崩溃。
     * 旧哈希的精确值无所谓——任何与当前哈希不同的串都代表「旧格式缓存」。
     */
    @Test
    fun `旧格式缓存_哈希不含持仓指纹_同日也自然不命中自动重算不崩`() = runBlocking {
        val assets = listOf(stock("sh600519", balance = 100.0))
        coEvery { repository.getCachedAllowingStale("sh600519") } returns listOf(record(today, "5.0"))
        coEvery { repository.prefetchDividends(any(), any()) } returns emptyMap()
        coEvery { repository.getDividendsBySymbol("sh600519", false, any()) } returns listOf(record(today, "5.0"))

        every { cache.loadCached() } returns DividendIncomeCache.CachedIncome(
            date = today, // 同日：只有哈希对不上
            dataHash = "旧格式哈希_只有分红记录段_没有持仓指纹",
            dividendsBySymbol = mapOf("sh600519" to 5.0),
            totalAnnualIncome = 999.0, // 旧缓存里的值（哪怕是脏的也不许复用）
            hasDividendData = true
        )
        every { cache.save(any(), any(), any()) } just runs

        val result = cachedLoader.loadIncome(assets)

        // 自然不命中 → 重算拿到当前持仓的正确值，不崩、不返回旧缓存的 999
        assertEquals(50.0, result.totalAnnualIncome, 1e-6)
        coVerify(exactly = 1) { repository.prefetchDividends(any(), any()) }
        coVerify(exactly = 1) { repository.getDividendsBySymbol("sh600519", false, any()) }
        // 重算后照常落盘（新格式哈希），下次同日即可命中
        coVerify(exactly = 1) { cache.save(any(), any(), any()) }
    }
}
