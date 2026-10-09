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
     */
    suspend fun loadIncome(
        assets: List<Asset>,
        forceRefresh: Boolean = false,
        tag: String = "DividendIncomeLoader"
    ): IncomeResult = withContext(Dispatchers.IO) {
        val stockAssets = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
        val dividendsBySymbol = ConcurrentHashMap<String, Double>()
        val recordsBySymbol = ConcurrentHashMap<String, List<DividendRecord>>()
        val hasDividendFlag = AtomicBoolean(false)
        val semaphore = Semaphore(MAX_CONCURRENT_FETCH)

        coroutineScope {
            stockAssets.map { asset ->
                async {
                    val symbol = asset.symbol ?: return@async
                    val dividends = try {
                        semaphore.withPermit {
                            dividendRepository.getDividendsBySymbol(symbol, forceRefresh)
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
                }
            }.forEach { it.await() }
        }

        val totalAnnualIncome = DividendIncomeCalculator.calculateTotalAnnualIncome(assets, dividendsBySymbol)

        IncomeResult(
            dividendsBySymbol = dividendsBySymbol,
            recordsBySymbol = recordsBySymbol,
            totalAnnualIncome = totalAnnualIncome,
            hasDividendData = hasDividendFlag.get()
        )
    }
}