package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.room.withTransaction
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.StockDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException

class DividendRepository private constructor(
    private val context: Context
) {
    private val eastmoneyApi by lazy { EastmoneyDividendApiService(context) }
    private val sinaService = DividendService()
    private val fundDividendApi = FundDividendApiService()
    private val dividendDao by lazy { StockDatabase.getInstance(context).dividendDao() }
    
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTTL = 24 * 60 * 60 * 1000L

    /**
     * 每个代码「上次抓过的时间」，跨进程存活。
     *
     * ## 为什么非有它不可
     *
     * 实测（2026-10-14 的诊断报告）：一次全量抓取里 **525 次抓回 0 条、只有 77 次有数据** ——
     * 87% 的标的确实没有分红。而 [getDividendsBySymbol] 里写库那句是
     * `if (data.isNotEmpty())`，**空结果从不落库**；[tryGetFromCache] 又只在库里有记录时
     * 才算命中。两边一凑：空结果永远记不住。
     *
     * 于是进程一死（Android 后台进程被系统回收是常态），内存缓存全没，
     * 库里又查不到 → 全部重新抓一遍。59 个代码 × 3 个数据源 ≈ 250 个请求，
     * 每次冷启动来一轮，一天好几轮。后果是接口被打到返回 **HTTP 514（反爬限流）**
     * （实测 106 次），请求排队到 60 秒才返回，进程长时间占着大量内存做 Jsoup 解析
     * —— 然后更容易被系统回收，进入下一轮。这就是「闪退」反复出现的放大器。
     *
     * 所以：**「抓过，且确实没有分红」是一个必须记住的结论**，不是「没数据」。
     */
    private val fetchState by lazy {
        context.getSharedPreferences(FETCH_STATE_PREFS, Context.MODE_PRIVATE)
    }

    /** 记录一次抓取完成（不管抓没抓到）。必须在抓取**成功**后写，失败时写会把错误固化 24 小时 */
    private fun markFetched(symbol: String) {
        runCatching { fetchState.edit().putLong(symbol, System.currentTimeMillis()).apply() }
    }

    private fun lastFetchedAt(symbol: String): Long? {
        val value = runCatching { fetchState.getLong(symbol, 0L) }.getOrDefault(0L)
        return value.takeIf { it > 0L }
    }

    /** 预取分红时的并发上限：与 AssetSyncManager.MAX_CONCURRENT_SYNC 一致，别把接口打爆 */
    private val maxConcurrentFetch = 8
    
    private val cacheHits = AtomicInteger(0)
    private val cacheMisses = AtomicInteger(0)
    
    data class CacheEntry(
        val data: List<DividendRecord>,
        val timestamp: Long,
        val lastFullFetch: Long = System.currentTimeMillis()
    ) {
        fun isValid(ttl: Long): Boolean = System.currentTimeMillis() - timestamp < ttl
        fun needsFullFetch(): Boolean = System.currentTimeMillis() - lastFullFetch > 30L * 24 * 60 * 60 * 1000
    }
    
    companion object {
        /** 「上次抓取时间」存放的 preferences 名，见 [fetchState] 的说明 */
        private const val FETCH_STATE_PREFS = "dividend_fetch_state"

        // 基金/ETF 每10份分红超过100元视为异常（基金分红来自净值拆分，不可能达到）
        private const val MAX_DIVIDEND_PER_TEN_SHARES_FUND = 100.0
        // 股票每10股分红上限放宽：A股极端高分红如茅台约300元/10股（含特别分红更高），留足余量
        private const val MAX_DIVIDEND_PER_TEN_SHARES_STOCK = 1000.0

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DividendRepository? = null
        
        fun getInstance(context: Context): DividendRepository {
            return instance ?: synchronized(this) {
                instance ?: DividendRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    fun clearCache() {
        cache.clear()
    }
    
    fun getCacheStats(): Map<String, Any> = mapOf(
        "size" to cache.size,
        "hits" to cacheHits.get(),
        "misses" to cacheMisses.get()
    )
    
    fun close() {
        cache.clear()
    }
    
    suspend fun getDividendsBySymbol(
        symbol: String,
        forceRefresh: Boolean = false
    ): List<DividendRecord> {
        val normalizedSymbol = StockCodeManager.normalizeSymbol(symbol)

        if (!forceRefresh) {
            tryGetFromCache(normalizedSymbol)?.let { return it }
        }

        cacheMisses.incrementAndGet()

        val existingEntry = cache[normalizedSymbol]
        // forceRefresh=true 时强制全量刷新，避免旧错误数据通过增量合并残留
        val isIncremental = !forceRefresh && existingEntry != null && !existingEntry.needsFullFetch()

        return try {
            val data = fetchDividendData(normalizedSymbol)

            if (data.isNotEmpty()) {
                saveDividendsToDb(normalizedSymbol, data, isIncremental)
            }

            val mergedData = if (isIncremental && existingEntry != null) {
                mergeDividendRecords(existingEntry.data, data)
            } else {
                // 全量刷新后从数据库读取完整记录（含保留的手动记录），保证缓存与DB一致
                val dbRecords = dividendDao.getBySymbol(normalizedSymbol)
                if (dbRecords.isNotEmpty()) {
                    dbRecords.map { dbDiv ->
                        DividendRecord(
                            exDividendDate = dbDiv.date,
                            dividendPerShare = dbDiv.amount.toString(),
                            announceDate = "",
                            fiscalYear = dbDiv.date.take(4),
                            paymentDate = dbDiv.payDate
                        )
                    }
                } else {
                    data
                }
            }

            // 抓成功了就记一笔，**不管抓没抓到**。空结果也是结论，不记下来
            // 下次冷启动会再抓一遍（见 [fetchState] 的说明）
            markFetched(normalizedSymbol)

            cache[normalizedSymbol] = CacheEntry(
                data = mergedData,
                timestamp = System.currentTimeMillis(),
                lastFullFetch = if (isIncremental) existingEntry?.lastFullFetch ?: System.currentTimeMillis() else System.currentTimeMillis()
            )
            mergedData
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("获取分红数据失败: ${e.message}", e, "DividendRepository")
            cache[normalizedSymbol]?.data ?: emptyList()
        }
    }

    private suspend fun tryGetFromCache(normalizedSymbol: String): List<DividendRecord>? {
        val cached = cache[normalizedSymbol]
        if (cached != null && cached.isValid(cacheTTL)) {
            cacheHits.incrementAndGet()
            return cached.data
        }

        val dbRecords = dividendDao.getBySymbol(normalizedSymbol)
        if (dbRecords.isNotEmpty()) {
            val records =                 dbRecords.map { dbDiv ->
                DividendRecord(
                    exDividendDate = dbDiv.date,
                    dividendPerShare = dbDiv.amount.toString(),
                    announceDate = "",
                    fiscalYear = dbDiv.date.take(4),
                    paymentDate = dbDiv.payDate
                )
            }
            cache[normalizedSymbol] = CacheEntry(records, System.currentTimeMillis(), lastFullFetch = 0L)  // 数据库恢复的缓存不标记为全量拉取
            return records
        }

        // 库里一条都没有，但**近期确实抓过** → 结论是「这只没有分红」，不是「没抓过」。
        // 判据抽成纯函数 [shouldReuseEmptyResult] 是为了能单测：这条判断错了要么
        // 每次冷启动重抓几百个请求，要么把「真的有分红」误判成没有并压 24 小时
        val stamp = lastFetchedAt(normalizedSymbol)
        if (shouldReuseEmptyResult(
                dbRecordCount = dbRecords.size,
                lastFetchedAt = stamp,
                now = System.currentTimeMillis(),
                ttl = cacheTTL
            )
        ) {
            cacheHits.incrementAndGet()
            cache[normalizedSymbol] = CacheEntry(
                emptyList(),
                System.currentTimeMillis(),
                lastFullFetch = stamp ?: 0L
            )
            return emptyList()
        }

        return null
    }

    private suspend fun fetchDividendData(normalizedSymbol: String): List<DividendRecord> {
        // fund_ 前缀必须在这里剥掉。extractPureCode 只认 sh/sz/bj，不认 fund_，
        // 不剥的话 stockCode 会带着 "fund_" 传下去，于是 isOTCFundCode / isETF 全部判错，
        // 最后拿着 "fund_004502" 去请求接口——场外基金的分红永远抓不到，
        // 而且失败是静默的（接口返回空 → 不写库 → 分红弹窗显示「暂无」）
        val pureSymbol = normalizedSymbol.removePrefix("fund_")
        val stockCode = StockCodeManager.extractPureCode(pureSymbol)
        val isOTCFund = StockCodeManager.isOTCFundCode(stockCode)
        val isEtf = StockCodeManager.isETF(pureSymbol)

        var data: List<DividendRecord> = if (isOTCFund) {
            fetchFromFundApi(stockCode)
        } else {
            fetchFromEastmoney(stockCode)
        }

        if (data.isEmpty() && isEtf) {
            data = fetchETFDividends(stockCode)
        }

        if (data.isEmpty() && !isOTCFund && !isEtf) {
            val market = StockCodeManager.getMarketPrefix(pureSymbol)
            data = fetchFromSina(stockCode, market)
        }

        return data
    }

    private suspend fun fetchETFDividends(stockCode: String): List<DividendRecord> {
        val marketPre = if (stockCode.startsWith("5")) "SH" else "SZ"
        val formats = listOf(
            "${marketPre}.${stockCode}",
            "${marketPre}${stockCode}",
            "${stockCode}.${marketPre}"
        )
        for (fmt in formats) {
            val data = try {
                fetchFromEastmoneyWithFormat(fmt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("ETF东方财富格式[$fmt]重试失败: ${e.message}", "DividendRepository")
                emptyList()
            }
            if (data.isNotEmpty()) {
                AppLogger.d("ETF东方财富格式[$fmt]成功，获取到${data.size}条记录", "DividendRepository")
                return data
            }
        }

        return try {
            fetchFromFundApiForETF(stockCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ETF天天基金接口重试失败: ${e.message}", "DividendRepository")
            emptyList()
        }
    }

    private suspend fun saveDividendsToDb(
        symbol: String,
        data: List<DividendRecord>,
        isIncremental: Boolean
    ) {
        if (isIncremental) {
            val oneYearAgo = java.time.LocalDate.now().minusYears(1)
            val recentData = data.filter { record ->
                try {
                    val date = java.time.LocalDate.parse(
                        record.exDividendDate,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    )
                    date.isAfter(oneYearAgo)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            saveDividendsToDbInternal(symbol = symbol, records = recentData, isIncremental = true)
        } else {
            saveDividendsToDbInternal(symbol = symbol, records = data, isIncremental = false)
        }
    }
    
    /**
     * 合并增量分红记录到现有数据中，去重
     */
    private fun mergeDividendRecords(existing: List<DividendRecord>, newData: List<DividendRecord>): List<DividendRecord> {
        val existingDates = existing.map { it.exDividendDate }.toSet()
        val uniqueNewRecords = newData.filter { it.exDividendDate !in existingDates }
        return existing + uniqueNewRecords
    }
    
    private suspend fun fetchFromEastmoney(stockCode: String): List<DividendRecord> {
        return try {
            val result = eastmoneyApi.getDividendHistory(stockCode)
            if (result.isSuccess) {
                val dividendDataList = result.getOrNull() ?: emptyList()
                dividendDataList.map { divData ->
                    DividendRecord(
                        exDividendDate = divData.date,
                        dividendPerShare = divData.dividendPerShare.toString(),
                        announceDate = divData.reportDate ?: "",
                        fiscalYear = divData.date.substring(0, 4),
                        paymentDate = divData.paymentDate
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("东方财富分红数据获取失败: ${e.message}", "DividendRepository")
            emptyList()
        }
    }
    
    private suspend fun fetchFromEastmoneyWithFormat(securityCode: String): List<DividendRecord> {
        return try {
            val result = eastmoneyApi.getDividendHistoryBySecurityCode(securityCode)
            if (result.isSuccess) {
                val dividendDataList = result.getOrNull() ?: emptyList()
                dividendDataList.map { divData ->
                    DividendRecord(
                        exDividendDate = divData.date,
                        dividendPerShare = divData.dividendPerShare.toString(),
                        announceDate = divData.reportDate ?: "",
                        fiscalYear = divData.date.substring(0, 4),
                        paymentDate = divData.paymentDate
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ETF东方财富带格式[$securityCode]获取失败: ${e.message}", "DividendRepository")
            emptyList()
        }
    }
    
    private suspend fun fetchFromFundApiForETF(fundCode: String): List<DividendRecord> {
        return try {
            val result = fundDividendApi.getFundDividendHistory(fundCode)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ETF天天基金接口获取失败[${fundCode}]: ${e.message}", "DividendRepository")
            emptyList()
        }
    }
    
    private suspend fun fetchFromSina(stockCode: String, market: String): List<DividendRecord> {
        return try {
            val result = sinaService.fetchDividendData(stockCode, market)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("新浪分红数据获取失败: ${e.message}", "DividendRepository")
            emptyList()
        }
    }
    
    private suspend fun fetchFromFundApi(fundCode: String): List<DividendRecord> {
        return try {
            AppLogger.d("天天基金分红数据获取: $fundCode", "DividendRepository")
            val result = fundDividendApi.getFundDividendHistory(fundCode)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("天天基金分红数据获取失败: ${e.message}", "DividendRepository")
            emptyList()
        }
    }
    
    suspend fun saveDividends(symbol: String, records: List<DividendRecord>) {
        if (records.isEmpty()) return
        val normalized = StockCodeManager.normalizeSymbol(symbol)
        saveDividendsToDbInternal(normalized, records)
    }

    /**
     * 手动添加或更新分红记录。用于用户纠错。
     * @return 保存后的 Dividend id
     */
    suspend fun saveManualDividend(dividend: Dividend): Long {
        val normalized = StockCodeManager.normalizeSymbol(dividend.symbol)
        val toSave = dividend.copy(symbol = normalized, isManual = true)
        val id = if (toSave.id != 0) {
            dividendDao.update(toSave)
            toSave.id.toLong()
        } else {
            // 如果同一天已存在，则覆盖
            val existing = dividendDao.getBySymbolAndDate(normalized, toSave.date)
            if (existing != null) {
                dividendDao.update(toSave.copy(id = existing.id))
                existing.id.toLong()
            } else {
                dividendDao.insert(toSave)
            }
        }
        // 清除缓存，强制下次从数据库重新加载
        cache.remove(normalized)
        return id
    }

    /**
     * 手动删除分红记录。
     */
    suspend fun deleteManualDividend(id: Int, symbol: String) {
        dividendDao.deleteById(id.toLong())
        cache.remove(StockCodeManager.normalizeSymbol(symbol))
    }

    /**
     * 获取数据库中某资产的手动/自动分红记录（原始 Dividend 实体）。
     */
    suspend fun getDbDividendsBySymbol(symbol: String): List<Dividend> {
        return dividendDao.getBySymbol(StockCodeManager.normalizeSymbol(symbol))
    }

    private suspend fun saveDividendsToDbInternal(symbol: String, records: List<DividendRecord>, isIncremental: Boolean = false) {
        if (records.isEmpty()) return

        // 过滤异常分红数据：基金/ETF 上限 100 元/10份，股票上限 1000 元/10股（茅台类高分红不误杀）
        val maxAmount = if (StockCodeManager.isStock(symbol)) {
            MAX_DIVIDEND_PER_TEN_SHARES_STOCK
        } else {
            MAX_DIVIDEND_PER_TEN_SHARES_FUND
        }
        val validRecords = records.filter { record ->
            val amount = record.dividendPerShare.toDoubleOrNull() ?: 0.0
            if (amount <= 0) {
                AppLogger.w("跳过非正分红记录: $symbol, 日期=${record.exDividendDate}, 金额=$amount", "DividendRepository")
                false
            } else if (amount > maxAmount) {
                AppLogger.w("跳过异常高分红记录: $symbol, 日期=${record.exDividendDate}, 金额=$amount", "DividendRepository")
                false
            } else {
                true
            }
        }

        if (validRecords.isEmpty()) {
            AppLogger.w("无有效分红记录可保存: $symbol", "DividendRepository")
            return
        }

        StockDatabase.getInstance(context).withTransaction {
            if (!isIncremental) {
                // 全量更新：只删除自动抓取的记录，保留用户手动修正的记录(isManual=1)
                dividendDao.deleteNonManualBySymbol(symbol)
            }

            // 增量与全量统一逻辑：跳过已存在记录（含手动记录），避免覆盖用户修正
            val dividends = validRecords.mapNotNull { record ->
                val existing = dividendDao.getBySymbolAndDate(symbol, record.exDividendDate)
                if (existing == null) {
                    Dividend(
                        symbol = symbol,
                        name = "",
                        amount = record.dividendPerShare.toDoubleOrNull() ?: 0.0,
                        date = record.exDividendDate,
                        payDate = record.paymentDate,
                        isManual = false
                    )
                } else {
                    null
                }
            }

            if (dividends.isNotEmpty()) {
                dividendDao.insertAll(dividends)
            }
        }
        cache[symbol] = CacheEntry(validRecords, System.currentTimeMillis())
    }
    
    suspend fun preFetchDividends(symbols: List<String>, forceRefresh: Boolean = false) {
        val semaphore = Semaphore(maxConcurrentFetch)
        coroutineScope {
            symbols.map { symbol ->
                val normalized = StockCodeManager.normalizeSymbol(symbol)
                val entry = cache[normalized]
                if (forceRefresh || entry == null || !entry.isValid(cacheTTL)) {
                    async {
                        semaphore.withPermit {
                            getDividendsBySymbol(normalized, forceRefresh)
                        }
                    }
                } else null
            }.filterNotNull().forEach { it.await() }
        }
    }
}

/**
 * 库里一条分红记录都没有时，该不该把它当成「缓存命中」而不是「没抓过」。
 *
 * **纯函数**，不碰 Context / Room / 网络，所以能直接 JVM 单测。
 * 抽出来是因为这条判断错了代价都不小，而且两个方向的错都很隐蔽：
 *
 * - 该认而不认 → 87% 的标的（实测）每次冷启动重抓一遍，接口被打到 514 限流，
 *   进程长时间占着内存做解析，于是更容易被系统回收 → 又一轮重抓
 * - 不该认而认 → 一只真有分红的标的被压 24 小时不抓，界面上分红一直是空的
 *
 * @param dbRecordCount 库里该代码的记录条数
 * @param lastFetchedAt 上次抓取完成的时间戳；null 表示从没抓过
 * @param now 当前时间戳
 * @param ttl 认定「抓过」的有效期
 */
internal fun shouldReuseEmptyResult(
    dbRecordCount: Int,
    lastFetchedAt: Long?,
    now: Long,
    ttl: Long
): Boolean {
    // 库里有记录就走正常的库恢复路径，轮不到这里
    if (dbRecordCount > 0) return false
    // 从没抓过 → 真的没数据，必须去抓
    val fetched = lastFetchedAt ?: return false
    if (fetched <= 0L) return false
    // 抓过，但太久之前 → 结论可能已经过期（一只基金可能刚宣布首次分红）
    if (now - fetched >= ttl) return false
    // 时间戳来自未来（改过系统时间）→ 不认，宁可重抓也别无限期压住
    if (fetched > now) return false
    return true
}
