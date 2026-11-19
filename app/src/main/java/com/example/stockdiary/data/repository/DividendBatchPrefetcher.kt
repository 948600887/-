package com.example.stockdiary.data.repository

import com.example.stockdiary.data.dividend.DividendData
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.network.EastmoneyDividendApiService
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 东财合并请求批量预取。
 *
 * 从 [DividendRepository] 外提：这里只负责「把一批标的合成少量请求并归组回结果」，
 * 缓存是否命中由 [DividendCacheReader] 判定，逐条级联与预算编排仍在仓库层。
 *
 * 结果**按次返回**而不是放共享字段：资产页与持仓页同时刷新时，两个预取周期
 * 曾互相 clear / remove，后启动的清掉前一个的结果，整批退化成逐条级联。
 */
internal class DividendBatchPrefetcher(
    private val eastmoneyApi: EastmoneyDividendApiService,
    private val cacheReader: DividendCacheReader
) {

    /**
     * 一次性把一批标的的分红抓完，结果按次返回，由调用方在随后的逐条
     * `getDividendsBySymbol` 里作为 `batchResults` 传回来。
     *
     * 东财的 filter 支持 `SECURITY_CODE in ("a","b",…)`，一个请求就能问全部代码；
     * 场外基金没有批量入口（天天基金每基金一个 HTML 页面），仍逐个抓。
     * 批量拿到记录的标的不再级联；批量说没有的标的，级联照旧走完，只跳过重复问东财。
     */
    suspend fun prefetch(
        symbols: List<String>,
        forceRefresh: Boolean = false
    ): Map<String, List<DividendRecord>> {
        if (symbols.isEmpty()) return emptyMap()

        val normalized = symbols.map { StockCodeManager.normalizeSymbol(it) }.distinct()

        // 已经有缓存的不用再问。forceRefresh 时全问（调用方明确要刷新）
        val pending = normalized.filter { symbol ->
            forceRefresh || cacheReader.tryGetFromCache(symbol) == null
        }
        if (pending.isEmpty()) return emptyMap()

        // 每个标的候选的 SECURITY_CODE：直接码 + （ETF 才有的）三种格式变体。
        // ETF 的格式历史上换过，接口三种都认，一起塞进批量查询比顺序重试省两个请求
        val codesBySymbol = mutableMapOf<String, MutableList<String>>()
        val codeToSymbol = mutableMapOf<String, String>()
        val eastmoneySymbols = mutableListOf<String>()

        for (symbol in pending) {
            // fund_ 前缀（场外份额的显式标记）必须比号段推断先判：
            // 400030（东方添益债券）/ 420102 落在 400/420 号段，isOTCFundCode 认不出来
            // （它只认 01/02 开头与 004xxx-009xxx），于是这些真基金会被塞进东财批量查询、
            // 拿不到结果后再级联到新浪股票接口，一路问的都是不存在的证券代码。
            // 场外基金走天天基金，没有批量入口 —— 与下面逐条抓取用的是同一个判据。
            //
            // 数据影响（已知且已认可）：修完之后 fund_400030 / fund_420102 的分红来源
            // 由「东财批量 + 新浪」切到天天基金，已入库的分红记录可能随之变化。
            if (StockCodeManager.isOTCFund(symbol)) continue

            val pure = StockCodeManager.stripFundPrefix(symbol)
            val code = StockCodeManager.extractPureCode(pure)

            eastmoneySymbols += symbol
            val candidates = codesBySymbol.getOrPut(symbol) { mutableListOf() }
            candidates += code
            codeToSymbol.putIfAbsent(code, symbol)

            if (StockCodeManager.isETF(pure)) {
                val marketPre = if (code.startsWith("5")) "SH" else "SZ"
                for (fmt in listOf("${marketPre}.$code", "${marketPre}$code", "$code.$marketPre")) {
                    candidates += fmt
                    codeToSymbol.putIfAbsent(fmt, symbol)
                }
            }
        }

        if (eastmoneySymbols.isEmpty()) return emptyMap()

        // 分块发：每块一个请求，URL 长度与代码数双保险（见 [chunkSecurityCodes]）。
        // 不切块的后果不是慢，是整批失败后退回逐条级联 —— 59 个标的打出 250~400 个请求，
        // 那就是这次要修掉的「点刷新闪退」
        val chunks = chunkSecurityCodes(codeToSymbol.keys.toList())
        // 被**成功**块覆盖的标的才会写进结果；块失败的标的不写 ——
        // 没有条目 ⇔ 级联里照常逐条重问，不能用「批量失败」冒充「批量问过且没有」
        val answeredSymbols = ConcurrentHashMap.newKeySet<String>()
        val recordsByCode = ConcurrentHashMap<String, MutableList<DividendData>>()

        coroutineScope {
            val chunkSemaphore = Semaphore(MAX_CONCURRENT_BATCH_CHUNKS)
            chunks.map { chunk ->
                async {
                    chunkSemaphore.withPermit {
                        val result = try {
                            eastmoneyApi.getDividendHistoryBySecurityCodes(chunk)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 只废这一块：块内标的不写结果条目，随后会走逐条级联，
                            // 其他块的结果照常生效 —— 一块失败不能把整批拖垮
                            AppLogger.w(
                                "批量预取分块失败（${chunk.size} 个代码）: ${e.message}",
                                "DividendRepository"
                            )
                            return@withPermit
                        }
                        if (!result.isSuccess) {
                            AppLogger.w(
                                "批量预取分块未成功（${chunk.size} 个代码）",
                                "DividendRepository"
                            )
                            return@withPermit
                        }
                        result.getOrNull().orEmpty().forEach { record ->
                            recordsByCode.computeIfAbsent(record.ticker) { mutableListOf() }
                                .add(record)
                        }
                        chunk.forEach { code -> codeToSymbol[code]?.let { answeredSymbols += it } }
                    }
                }
            }.forEach { it.await() }
        }

        if (answeredSymbols.isEmpty()) {
            // 全块失败：一个条目都不写，全部标的走逐条级联（与改造前批量失败的行为一致）
            AppLogger.w("批量预取所有分块均失败，退回逐条路径", "DividendRepository")
            return emptyMap()
        }

        // 按接口返回的 SECURITY_CODE 归组，再发回给各个标的。
        // 同一标的可能靠多种格式命中，取并集并按除息日去重。
        // 这里要把 [DividendData]（接口层）转成 [DividendRecord]（领域层）——
        // 口径与逐条路径 fetchFromEastmoneyWithFormat 保持一致，否则同一天的分红
        // 会因为走了批量还是逐条而算出不同的金额
        val batchResults = HashMap<String, List<DividendRecord>>(eastmoneySymbols.size)
        for (symbol in eastmoneySymbols) {
            if (symbol !in answeredSymbols) continue
            val codes = codesBySymbol[symbol].orEmpty()
            val merged = codes.flatMap { recordsByCode[it].orEmpty() }
                .map { it.toDividendRecord() }
                .distinctBy { it.exDividendDate }
                .sortedByDescending { it.exDividendDate }
            batchResults[symbol] = merged
        }

        AppLogger.i(
            "批量预取：${eastmoneySymbols.size} 只走东财的标的分 ${chunks.size} 个请求，" +
                "${batchResults.size} 只拿到批量答案，其中 " +
                "${batchResults.values.count { it.isNotEmpty() }} 只有分红记录",
            "DividendRepository"
        )
        return batchResults
    }
}
