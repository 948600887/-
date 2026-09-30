package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

/**
 * Asset 同步管理器
 * 负责资产价格同步，使用并发控制避免过载
 */
object AssetSyncManager {
    
    private const val MAX_CONCURRENT_SYNC = 8
    
    /**
     * 同步所有资产的价格（并行版本，限流控制）
     *
     * @param startTime 同步开始时间戳（毫秒），传入 >0 时启用时间上限检查，避免后台任务被系统强制终止
     * @param timeLimitMs 时间上限（毫秒），接近上限时跳过后续未开始的资产并提前返回
     */
    suspend fun syncAllAssets(
        context: Context,
        repository: AssetRepository,
        assets: List<Asset>,
        onProgress: (SyncProgress) -> Unit,
        forceRefresh: Boolean = false,
        startTime: Long = 0L,
        timeLimitMs: Long = 0L
    ): SyncResult {
        if (assets.isEmpty()) {
            return SyncResult(0, 0)
        }

        val enforceTimeLimit = startTime > 0L && timeLimitMs > 0L

        val successCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val totalCount = assets.size
        val errorTypes = ConcurrentLinkedQueue<SyncErrorType>()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)

        val semaphore = Semaphore(MAX_CONCURRENT_SYNC)
        val stockDataManager = StockDataManager.getInstance(context)
        val dividendRepository = DividendRepository.getInstance(context)

        val stocksAndFunds = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }

        onProgress(SyncProgress(0, totalCount, "正在获取分红数据..."))
        dividendRepository.preFetchDividends(stocksAndFunds.mapNotNull { it.symbol }, forceRefresh)

        coroutineScope {
            val jobs = assets.map { asset ->
                async {
                    // 接近时间上限时跳过后续未开始的资产，已启动的任务继续完成
                    if (enforceTimeLimit && System.currentTimeMillis() - startTime > timeLimitMs) {
                        truncated.set(true)
                        return@async
                    }
                    semaphore.withPermit {
                        syncSingleAsset(asset, repository, stockDataManager, successCount, failedCount, errorTypes, forceRefresh)
                        val completed = completedCount.incrementAndGet()
                        onProgress(SyncProgress(completed, totalCount, "同步 ${asset.name}..."))
                    }
                }
            }
            // 等待所有任务完成
            jobs.forEach { it.await() }
        }

        val networkErrorCount = errorTypes.count { it == SyncErrorType.NETWORK }
        val serverErrorCount = errorTypes.count { it == SyncErrorType.SERVER }
        val dataErrorCount = errorTypes.count { it == SyncErrorType.DATA }
        val unknownErrorCount = errorTypes.count { it == SyncErrorType.UNKNOWN }
        val dominantErrorType = errorTypes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        return SyncResult(
            successCount = successCount.get(),
            failedCount = failedCount.get(),
            networkErrorCount = networkErrorCount,
            serverErrorCount = serverErrorCount,
            dataErrorCount = dataErrorCount,
            unknownErrorCount = unknownErrorCount,
            dominantErrorType = dominantErrorType,
            truncatedByTimeLimit = truncated.get()
        )
    }
    
    private suspend fun syncSingleAsset(
        asset: Asset,
        repository: AssetRepository,
        stockDataManager: StockDataManager,
        successCount: AtomicInteger,
        failedCount: AtomicInteger,
        errorTypes: ConcurrentLinkedQueue<SyncErrorType>,
        forceRefresh: Boolean
    ) {
        val symbol = asset.symbol

        var isSuccess = false
        var lastError: Throwable? = null

        try {
            when (asset.type) {
                AssetType.FUND -> {
                    val fundCode = symbol?.removePrefix("fund_")
                    if (fundCode == null) {
                        failedCount.incrementAndGet()
                        errorTypes.offer(SyncErrorType.DATA)
                        return
                    }
                    // ETF/LOF/场内基金用股票API获取价格，场外基金用基金API
                    val pureCode = StockCodeManager.extractDigits(fundCode)
                    val category = StockCodeManager.classifyCode(pureCode)
                    val isExchangeTraded = category == StockCodeManager.CodeCategory.ETF ||
                        category == StockCodeManager.CodeCategory.LOF ||
                        category == StockCodeManager.CodeCategory.EXCHANGE_FUND

                    if (isExchangeTraded) {
                        // 场内交易基金：使用股票API（新浪等），symbol 已带市场前缀
                        val result = stockDataManager.getStockData(fundCode, forceRefresh = forceRefresh)
                        if (result.isSuccess) {
                            val stockData = result.getOrNull()
                            if (stockData != null) {
                                repository.updatePriceBySymbol(symbol, stockData.price)
                                isSuccess = true
                            } else {
                                lastError = IllegalStateException("返回数据为空")
                            }
                        } else {
                            lastError = result.exceptionOrNull()
                        }
                    } else {
                        // 场外基金：使用基金API（天天基金），传纯数字代码
                        val fundResult = com.example.stockdiary.network.UnifiedPriceService.getPrice(
                            pureCode,
                            AssetType.FUND,
                            forceRefresh = forceRefresh
                        )
                        fundResult.onSuccess { price ->
                            when (price) {
                                is com.example.stockdiary.network.model.AssetPrice.Fund -> {
                                    repository.updatePriceBySymbol(symbol, price.data.price)
                                    isSuccess = true
                                }
                                else -> { lastError = IllegalStateException("返回类型不匹配") }
                            }
                        }.onFailure { lastError = it }
                    }
                }
                AssetType.STOCK -> {
                    val stockSymbol = symbol
                    if (stockSymbol == null) {
                        failedCount.incrementAndGet()
                        errorTypes.offer(SyncErrorType.DATA)
                        return
                    }
                    val result = stockDataManager.getStockData(stockSymbol, forceRefresh = forceRefresh)

                    if (result.isSuccess) {
                        val stockData = result.getOrNull()
                        if (stockData != null) {
                            repository.updatePriceBySymbol(stockSymbol, stockData.price)
                            isSuccess = true
                        } else {
                            lastError = IllegalStateException("返回数据为空")
                        }
                    } else {
                        lastError = result.exceptionOrNull()
                    }
                }
                AssetType.CRYPTO -> {
                    val cryptoSymbol = symbol
                    if (cryptoSymbol == null) {
                        failedCount.incrementAndGet()
                        errorTypes.offer(SyncErrorType.DATA)
                        return
                    }
                    val cryptoResult = com.example.stockdiary.network.UnifiedPriceService.getPrice(
                        cryptoSymbol,
                        AssetType.CRYPTO,
                        forceRefresh = forceRefresh
                    )

                    cryptoResult.onSuccess { price ->
                        when (price) {
                            is com.example.stockdiary.network.model.AssetPrice.Crypto -> {
                                repository.updatePriceBySymbol(cryptoSymbol, price.data.price)
                                isSuccess = true
                            }
                            else -> { lastError = IllegalStateException("返回类型不匹配") }
                        }
                    }.onFailure { lastError = it }
                }
                else -> {
                    return
                }
            }

            if (isSuccess) {
                successCount.incrementAndGet()
            } else {
                failedCount.incrementAndGet()
                errorTypes.offer(SyncStrategy.classifyError(lastError))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("同步资产 ${asset.name} 失败: ${e.message}", "AssetSyncManager")
            failedCount.incrementAndGet()
            errorTypes.offer(SyncStrategy.classifyError(e))
        }
    }
    
}

data class SyncProgress(
    val current: Int,
    val total: Int,
    val message: String
)

data class SyncResult(
    val successCount: Int,
    val failedCount: Int,
    val networkErrorCount: Int = 0,
    val serverErrorCount: Int = 0,
    val dataErrorCount: Int = 0,
    val unknownErrorCount: Int = 0,
    val dominantErrorType: SyncErrorType? = null,
    /** 是否因接近后台任务时间上限而提前结束（仍有未同步的资产） */
    val truncatedByTimeLimit: Boolean = false
) {
    val hasRetryableErrors: Boolean
        get() = networkErrorCount > 0 || serverErrorCount > 0 || unknownErrorCount > 0
}