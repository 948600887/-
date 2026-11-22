package com.example.stockdiary.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.stockdiary.common.DateFormats
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.DividendDao
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException

/** 基金/ETF 每10份分红超过100元视为异常（基金分红来自净值拆分，不可能达到） */
private const val MAX_DIVIDEND_PER_TEN_SHARES_FUND = 100.0

/** 股票每10股分红上限放宽：A股极端高分红如茅台约300元/10股（含特别分红更高），留足余量 */
private const val MAX_DIVIDEND_PER_TEN_SHARES_STOCK = 1000.0

/**
 * 分红写库（含全量/增量两条路径、异常值过滤、残缺解析保护与缓存更新）。
 *
 * 从 [DividendRepository] 外提：这里只负责「把记录安全地落到 Room，并同步内存缓存」，
 * 不决定什么时候抓、抓哪个源。写库规则单独成文件，是为了让「什么情况下会删旧记录」
 * 这类高风险改动只影响这一处。
 */
internal class DividendWriter(
    private val context: Context,
    private val dao: DividendDao,
    private val cache: DividendMemoryCache,
    /**
     * 事务执行器。默认实现才碰 [StockDatabase]（懒加载到调用期，构造期不打开数据库），
     * 现有生产调用点无需改动。
     *
     * 抽成参数只有一个目的：让「全量刷新是否真的删库」这条 A16 判定的**集成路径**
     * 能在纯 JVM 单测里跑（传 `{ it() }` 即可），不必起 Room、也不必
     * `mockkStatic("androidx.room.RoomDatabaseKt")`（本仓库有过 mockkStatic 自递归
     * StackOverflow 的前科）。**生产行为零变化。**
     */
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block ->
        StockDatabase.getInstance(context).withTransaction { block() }
    }
) {

    suspend fun saveDividends(symbol: String, records: List<DividendRecord>) {
        if (records.isEmpty()) return
        val normalized = StockCodeManager.normalizeSymbol(symbol)
        saveInternal(normalized, records)
    }

    /**
     * 手动添加或更新分红记录。用于用户纠错。
     * @return 保存后的 Dividend id
     */
    suspend fun saveManualDividend(dividend: Dividend): Long {
        val normalized = StockCodeManager.normalizeSymbol(dividend.symbol)
        val toSave = dividend.copy(symbol = normalized, isManual = true)
        val id = if (toSave.id != 0) {
            dao.update(toSave)
            toSave.id.toLong()
        } else {
            // 如果同一天已存在，则覆盖
            val existing = dao.getBySymbolAndDate(normalized, toSave.date)
            if (existing != null) {
                dao.update(toSave.copy(id = existing.id))
                existing.id.toLong()
            } else {
                dao.insert(toSave)
            }
        }
        // 清除缓存，强制下次从数据库重新加载
        cache.remove(normalized)
        return id
    }

    /** 手动删除分红记录 */
    suspend fun deleteManualDividend(id: Int, symbol: String) {
        dao.deleteById(id.toLong())
        cache.remove(StockCodeManager.normalizeSymbol(symbol))
    }

    /** 抓取结果落库：增量只补最近一年的新记录，全量走完整替换判定 */
    suspend fun saveFromFetch(
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
                        DateFormats.ISO_DATE_FORMATTER
                    )
                    date.isAfter(oneYearAgo)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            saveInternal(symbol = symbol, records = recentData, isIncremental = true)
        } else {
            saveInternal(symbol = symbol, records = data, isIncremental = false)
        }
    }

    private suspend fun saveInternal(
        symbol: String,
        records: List<DividendRecord>,
        isIncremental: Boolean = false
    ) {
        if (records.isEmpty()) return

        // 过滤异常分红数据：基金/ETF 上限 100 元/10份，股票上限 1000 元/10股（茅台类高分红不误杀）
        // symbol 是规范化后的（fund_ 前缀原样保留）。isStock 对带该前缀的场外份额返回 false，
        // 所以 fund_400030 这类**必须**拿基金的 100 上限——此前它误判成股票，用的是 1000。
        val maxAmount = if (StockCodeManager.isStock(symbol)) {
            MAX_DIVIDEND_PER_TEN_SHARES_STOCK
        } else {
            MAX_DIVIDEND_PER_TEN_SHARES_FUND
        }
        val validRecords = records.filter { record ->
            val amount = record.dividendPerShare.toDoubleOrNull() ?: 0.0
            // isFinite 必须先判：`<= 0` 与 `> 上限` 对 NaN 都是 false，NaN 会一路写进
            // dividend.amount，让年化收入/收息进度整块变 NaN（且 SUM 掺 NaN 后所有历史点一起失真），
            // 还会让备份被 requireFinitePayload 永久拒绝 —— 用户只能靠重新导入自救。
            // 同仓价格与资产数值路径都有这道闸，唯此处从前没有。
            if (!amount.isFinite()) {
                AppLogger.w("跳过分红金额非有限值: $symbol, 日期=${record.exDividendDate}, 金额=$amount", "DividendRepository")
                false
            } else if (amount <= 0) {
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

        // 全量刷新时是否允许删旧插新；残缺解析下为 false（DB 保留旧记录）
        var replaceAllowed = false

        transactionRunner {
            if (!isIncremental) {
                // 全量更新：先核对「本次解析是否完整」，不完整则**不能**删库。
                //
                // A16（阻断）：R1 只护住空结果分支；这里补全「残缺解析」分支——
                // 一次全量刷新如果解析到的有效记录数少于库里已有的自动记录数，说明解析
                // 大概率不完整（HTML 200、表头在、但只解析出一小部分行），此时若照旧
                // 无条件 deleteNonManualBySymbol 再只插回残缺集合，就会把其余记录洗掉，
                // 年化收入当场缩水（用户原始「刷新跳动」残留根因）。
                // 不完整时跳过删库，只做下方 upsert（按 symbol+date 跳过已存在），保留旧值。
                val existingAutoCount = dao.getBySymbol(symbol).count { !it.isManual && it.amount > 0 }
                replaceAllowed = shouldReplaceRecordsOnFullRefresh(existingAutoCount, validRecords.size)
                if (replaceAllowed) {
                    // 完整或更多：只删除自动抓取的记录，保留用户手动修正的记录(isManual=1)
                    dao.deleteNonManualBySymbol(symbol)
                } else {
                    AppLogger.w(
                        "全量刷新解析到的有效记录(${validRecords.size})少于库里已有自动记录($existingAutoCount)，" +
                            "按残缺解析处理，跳过删库以保留旧分红记录: $symbol",
                        "DividendRepository"
                    )
                }
            }

            // 增量与全量统一逻辑：跳过已存在记录（含手动记录），避免覆盖用户修正
            val dividends = validRecords.mapNotNull { record ->
                val existing = dao.getBySymbolAndDate(symbol, record.exDividendDate)
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
                dao.insertAll(dividends)
            }
        }
        // A16：残缺解析时上面已跳过删库（DB 保留旧记录），内存缓存同样不能写入残缺集合 ——
        // 否则 [DividendCacheReader.tryGetFromCache] 会在 24h 内直接返回「只解析到一部分」的列表，
        // DB 与缓存口径不一致、年化收入缩水。此时保留旧缓存（若有）不覆盖。
        if (shouldUpdateDividendCache(isIncremental, replaceAllowed)) {
            cache.put(symbol, DividendCacheEntry(validRecords, System.currentTimeMillis()))
            cache.trimIfNeeded()
        }
    }
}
