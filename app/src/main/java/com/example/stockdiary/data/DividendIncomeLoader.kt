package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

class DividendIncomeLoader(
    private val dividendRepository: DividendRepository
) {
    data class IncomeResult(
        val dividendsBySymbol: Map<String, Double>,
        /** 原始分红记录，供「收息进度 / 月度现金流 / 下一笔倒计时」使用 */
        val recordsBySymbol: Map<String, List<DividendRecord>> = emptyMap(),
        val totalAnnualIncome: Double,
        val hasDividendData: Boolean
    )

    suspend fun loadIncome(
        assets: List<Asset>,
        forceRefresh: Boolean = true,
        tag: String = "DividendIncomeLoader"
    ): IncomeResult = withContext(Dispatchers.IO) {
        val stockAssets = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
        val dividendsBySymbol = ConcurrentHashMap<String, Double>()
        val recordsBySymbol = ConcurrentHashMap<String, List<DividendRecord>>()
        val hasDividendFlag = AtomicBoolean(false)

        coroutineScope {
            stockAssets.map { asset ->
                async {
                    val symbol = asset.symbol ?: return@async
                    val dividends = try {
                        dividendRepository.getDividendsBySymbol(symbol, forceRefresh)
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