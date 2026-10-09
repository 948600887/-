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

        return null
    }

    private suspend fun fetchDividendData(normalizedSymbol: String): List<DividendRecord> {
        val stockCode = StockCodeManager.extractPureCode(normalizedSymbol)
        val isOTCFund = StockCodeManager.isOTCFundCode(stockCode)
        val isEtf = StockCodeManager.isETF(normalizedSymbol)

        var data: List<DividendRecord> = if (isOTCFund) {
            fetchFromFundApi(stockCode)
        } else {
            fetchFromEastmoney(stockCode)
        }

        if (data.isEmpty() && isEtf) {
            data = fetchETFDividends(stockCode)
        }

        if (data.isEmpty() && !isOTCFund && !isEtf) {
            val market = StockCodeManager.getMarketPrefix(normalizedSymbol)
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
