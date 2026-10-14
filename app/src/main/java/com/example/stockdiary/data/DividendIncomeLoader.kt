package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

class DividendIncomeLoader(
    private val dividendRepository: DividendRepository
) {
    companion object {
        /** 与 AssetSyncManager.MAX_CONCURRENT_SYNC 同数量级：持仓上百支时不要一次打出上百个并发请求 */
        private const val MAX_CONCURRENT_FETCH = 8
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

        // 冷启动快速路径：全部有缓存就直接用，零请求。
        // 有一个标的没有缓存就整批走正常流程 —— 只抓缺的那些（prefetch 会合并成批量请求），
        // 而不是把 59 个代码全部重抓一遍
        if (cacheOnly && !forceRefresh) {
            val cached = LinkedHashMap<String, List<DividendRecord>>()
            var allCached = true
            for (asset in stockAssets) {
                val symbol = asset.symbol ?: continue
                val data = dividendRepository.getCachedAllowingStale(symbol)
                if (data == null) {
                    allCached = false
                    break
                }
                cached[symbol] = data
            }
            if (allCached && cached.isNotEmpty()) {
                AppLogger.d("冷启动命中缓存，未发起任何分红请求", tag)
                // 汇总口径必须与正常抓取路径一致：传**全部资产**（含存款利息），
                // 不能传 stockAssets —— 存款走利息分支，被过滤掉就会漏算利息，
                // 冷启动与同步后两个屏幕的「预计收入」会来回跳
                return@withContext buildResult(assets, cached)
            }
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

        IncomeResult(
            dividendsBySymbol = dividendsBySymbol,
            recordsBySymbol = recordsBySymbol,
            totalAnnualIncome = totalAnnualIncome,
            hasDividendData = hasDividendFlag.get()
        )
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
}