package com.example.stockdiary.data.price

import android.content.Context
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.UnifiedPriceService
import kotlinx.coroutines.CancellationException

/**
 * 派生缓存清理门面：把「清哪些价格 / 基金缓存」的知识从 UI 层收回数据层。
 *
 * 之前 SettingsViewModel 直接调用 StockDataManager / SinaStockAPI / UnifiedPriceService /
 * FundAPI 的清除方法：设置页因此认识了四个数据源单例，新增一种派生缓存就得改界面层，
 * 也无法在单测里替换清理实现。现在只依赖本接口，各清理项仍调用原有实现，行为不变。
 */
interface PriceCacheMaintenance {
    suspend fun clearDerivedCaches()
}

/**
 * 生产实现：逐项清理，单项失败不影响其余（与设置页原有的 runCatching 语义一致）。
 *
 * **唯一例外是取消**：`runCatching { }` 会把 `CancellationException` 一并吞掉，
 * 于是用户在设置页点了「清除缓存」立刻退出页面时，协程已被取消、清理却还在往下跑
 * （违反项目「取消不吞」铁律：取消要一路向上抛，让调用方知道这次没做完）。
 * 所以这里显式重抛，只吞真正的清理失败。
 */
class DefaultPriceCacheMaintenance(private val appContext: Context) : PriceCacheMaintenance {
    override suspend fun clearDerivedCaches() {
        clearIgnoringFailure { StockDataManager.getInstance(appContext).clearCache() }
        clearIgnoringFailure { SinaStockAPI.invalidateCache() }
        clearIgnoringFailure { UnifiedPriceService.clearCache() }
        clearIgnoringFailure { FundAPI.clearPersistentCache() }
    }

    /** 清理单项：失败不中断后续项，但**取消必须原样抛出**（不得吞掉）。 */
    private inline fun clearIgnoringFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 与旧 runCatching 语义一致：单项失败静默跳过，继续清下一项
        }
    }
}
