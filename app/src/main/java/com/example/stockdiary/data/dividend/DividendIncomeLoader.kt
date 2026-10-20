package com.example.stockdiary.data.dividend

import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.sync.withHeartbeat
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

class DividendIncomeLoader(
    private val dividendRepository: DividendRepository,
    /**
     * 「预计收入」计算结果的持久层。默认 null：纯 JVM 单测没有 Android Context，
     * 传 null 时整条缓存逻辑自动跳过（行为退化为改造前，保证既有单测零改动、零网络）。
     * 生产环境由 [com.example.stockdiary.di.AppContainer] 注入真实实例。
     */
    private val incomeCache: DividendIncomeCache? = null
) {
    companion object {
        /** 与 AssetSyncManager.MAX_CONCURRENT_SYNC 同数量级：持仓上百支时不要一次打出上百个并发请求 */
        private const val MAX_CONCURRENT_FETCH = 8

        /** 计算当天日期的字符串格式（yyyy-MM-dd），与 [DividendIncomeCache.CachedIncome.date] 同口径 */
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
    }

    data class IncomeResult(
        val dividendsBySymbol: Map<String, Double>,
        /** 原始分红记录，供「收息进度 / 月度现金流 / 下一笔倒计时」使用 */
        val recordsBySymbol: Map<String, List<DividendRecord>> = emptyMap(),
        val totalAnnualIncome: Double,
        val hasDividendData: Boolean
    )

    /**
     * @param forceRefresh 默认 false。为 true 时会先清掉该标的的非手动分红记录再全量重抓，
     *        属于「数据可疑时手动修复」的动作，不应该成为默认行为——否则新加一个调用点忘了传参
     *        就会静默清库。
     * @param cacheOnly 冷启动用。为 true 时**只读缓存，TTL 过期也照用**（上限 30 天），
     *        一个请求都不发；只有当某个标的连过期数据都没有时才让它走正常抓取流程。
     *        打开 App 不该为了刷新「预计收入」去敲几十个接口 —— 那是用户点「刷新」的事。
     */
    suspend fun loadIncome(
        assets: List<Asset>,
        forceRefresh: Boolean = false,
        tag: String = "DividendIncomeLoader",
        cacheOnly: Boolean = false
    ): IncomeResult = withContext(Dispatchers.IO) {
        val stockAssets = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }

        // ===== 同日短路：零网络复用上次算好的结果，杀掉同日抖动 =====
        // 抖动根因：同一天内 TTM 滑窗不变，但不同次抓取拿到的 records 集不同 → TTM 求和变。
        // 这里在发起任何抓取前，先零网络把各标的缓存记录取出来，算出 dataHash；
        // 若上次落盘的缓存「哈希一致 + 当天 + 非强制刷新」就直接复用，不进 prefetch、不算 TTM。
        // 仅当底层 records 真变了（dataHash 不同）或跨日（date != 今天）才重算 ——
        // 即「保留跨日 TTM 漂移 + 仅底层数据变化才重算」。
        if (!forceRefresh) {
            val cachedRecords = LinkedHashMap<String, List<DividendRecord>>()
            var allHit = true
            for (asset in stockAssets) {
                val symbol = asset.symbol ?: continue
                val data = dividendRepository.getCachedAllowingStale(symbol)
                if (data == null) {
                    allHit = false
                    break
                }
                cachedRecords[symbol] = data
            }

            if (allHit && cachedRecords.isNotEmpty()) {
                val currentHash = computeDataHash(cachedRecords)
                val today = LocalDate.now().format(DATE_FORMATTER)
                val cached = incomeCache?.loadCached()
                // 同日 + 底层数据未变 → 直接复用缓存的 IncomeResult，彻底消掉同日抖动
                if (cached != null && cached.dataHash == currentHash && cached.date == today) {
                    AppLogger.d("同日且底层分红数据未变，复用缓存的预计收入（不重算）", tag)
                    return@withContext IncomeResult(
                        dividendsBySymbol = cached.dividendsBySymbol,
                        // 用刚读到的缓存 records 回填，保证收息进度/日历拿到的仍是当前记录
                        recordsBySymbol = cachedRecords,
                        totalAnnualIncome = cached.totalAnnualIncome,
                        hasDividendData = cached.hasDividendData
                    )
                }
                // 全命中但未复用（缓存为空 / 跨日 / 底层数据变了）：
                // cacheOnly 零请求重算并落盘；正常路径继续往下走抓取。
                if (cacheOnly) {
                    return@withContext buildAndSave(assets, cachedRecords, currentHash, today, tag)
                }
            }
            // allHit=false（有标的连过期数据都没有）时，cacheOnly 原有语义是「整批走抓取」，
            // 这里自然落到下面的正常路径，不会重复读一遍缓存。
        }

        val dividendsBySymbol = ConcurrentHashMap<String, Double>()
        val recordsBySymbol = ConcurrentHashMap<String, List<DividendRecord>>()
        val hasDividendFlag = AtomicBoolean(false)
        val doneCount = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT_FETCH)

        // 先把这一批一次性抓完（东财那条路合并成少量请求），再逐个读缓存。
        // 顺序不能反：先 prefetch 再逐个 getDividendsBySymbol，后者才会命中预取结果；
        // 反过来就是每个代码各发一次请求，批量白发。
        // 结果按次传回给下面的逐条循环：并发的另一个抓取周期有自己的一份，
        // 不会互相清空 —— 曾经的共享字段就是这么把批量结果弄丢、退回逐条级联的
        val batchResults = dividendRepository.prefetchDividends(
            symbols = stockAssets.mapNotNull { it.symbol },
            forceRefresh = forceRefresh
        )

        // 心跳：刷新后的这一步（「同步完成后再算一次收入」）同样可能跑几十秒，
        // 从前中间完全静默，卡住了只能干等。跑到 IO 线程之后更要在日志里留痕
        withHeartbeat(
            tag = tag,
            phase = "分红收入计算",
            status = { "已算完 ${doneCount.get()}/${stockAssets.size} 个标的" }
        ) {
            coroutineScope {
                stockAssets.map { asset ->
                    async {
                        val symbol = asset.symbol ?: return@async
                        val dividends = try {
                            semaphore.withPermit {
                                dividendRepository.getDividendsBySymbol(symbol, forceRefresh, batchResults)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("获取${symbol}分红失败: ${e.message}", e, tag)
                            emptyList()
                        }
                        val ttmDividend = DividendYieldCalculator.getTTMDividend(dividends)
                        dividendsBySymbol[symbol] = ttmDividend
                        recordsBySymbol[symbol] = dividends
                        if (ttmDividend > 0) hasDividendFlag.set(true)
                        doneCount.incrementAndGet()
                    }
                }.forEach { it.await() }
            }
        }

        val totalAnnualIncome = DividendIncomeCalculator.calculateTotalAnnualIncome(assets, dividendsBySymbol)

        val result = IncomeResult(
            dividendsBySymbol = dividendsBySymbol,
            recordsBySymbol = recordsBySymbol,
            totalAnnualIncome = totalAnnualIncome,
            hasDividendData = hasDividendFlag.get()
        )

        // 落盘：仅当注入了缓存层。date=今天、dataHash=本次 records 哈希，
        // 下次同日且底层数据不变时即可短路复用，杀掉同日抖动
        incomeCache?.save(result, computeDataHash(recordsBySymbol), LocalDate.now().format(DATE_FORMATTER))

        result
    }

    /**
     * 从「symbol → 分红记录」直接算出结果并落盘，不走抓取。
     *
     * 抽出来是为了让冷启动的零请求路径和正常抓取路径**共用同一套汇总口径** ——
     * 两条路各算一遍 TTM 和年度收入，迟早会给出不一样的「预计收入」。
     */
    private fun buildAndSave(
        assets: List<Asset>,
        recordsBySymbol: Map<String, List<DividendRecord>>,
        dataHash: String,
        date: String,
        tag: String
    ): IncomeResult {
        val result = buildResult(assets, recordsBySymbol)
        incomeCache?.save(result, dataHash, date)
        AppLogger.d("冷启动零请求重算预计收入并落盘（date=$date）", tag)
        return result
    }

    /**
     * 从「symbol → 分红记录」直接算出结果，不走抓取。
     *
     * 抽出来是为了让冷启动的零请求路径和正常抓取路径**共用同一套汇总口径** ——
     * 两条路各算一遍 TTM 和年度收入，迟早会给出不一样的「预计收入」。
     */
    private fun buildResult(
        assets: List<Asset>,
        recordsBySymbol: Map<String, List<DividendRecord>>
    ): IncomeResult {
        val dividendsBySymbol = recordsBySymbol.mapValues { (_, records) ->
            DividendYieldCalculator.getTTMDividend(records)
        }
        return IncomeResult(
            dividendsBySymbol = dividendsBySymbol,
            recordsBySymbol = recordsBySymbol,
            totalAnnualIncome = DividendIncomeCalculator.calculateTotalAnnualIncome(assets, dividendsBySymbol),
            hasDividendData = dividendsBySymbol.values.any { it > 0 }
        )
    }

    /**
     * 由各标的原始分红记录算出**稳定哈希**，用于判断「底层分红数据有没有变」。
     *
     * 只要 records（symbol → 除息日/每股分红列表）不变，哈希就不变；
     * 哈希变了即底层 records 真变了（如 24h 缓存刷新后抓到新数据），必须重算。
     * 口径：按 symbol 排序，每只标的再按（除息日, 每股分红）排序后拼成规范串再取哈希，
     * 与序列化/抓取顺序无关，保证同一份数据每次算出同一个哈希。
     */
    private fun computeDataHash(recordsBySymbol: Map<String, List<DividendRecord>>): String {
        val sb = StringBuilder()
        recordsBySymbol.keys.sorted().forEach { symbol ->
            sb.append(symbol).append('|')
            recordsBySymbol[symbol]
                ?.sortedWith(compareBy({ it.exDividendDate }, { it.dividendPerShare }))
                ?.forEach { record ->
                    sb.append(record.exDividendDate).append('=').append(record.dividendPerShare).append(';')
                }
            sb.append('\n')
        }
        // Java String.hashCode 是字符的纯函数、跨 JVM 运行稳定，足够做「内容是否相同」判等
        return sb.toString().hashCode().toString()
    }
}
