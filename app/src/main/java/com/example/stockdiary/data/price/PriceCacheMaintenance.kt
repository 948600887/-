package com.example.stockdiary.data.price

import android.content.Context
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.UnifiedPriceService

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

/** 生产实现：逐项清理，单项失败不影响其余（与设置页原有的 runCatching 语义一致）。 */
class DefaultPriceCacheMaintenance(private val appContext: Context) : PriceCacheMaintenance {
    override suspend fun clearDerivedCaches() {
        runCatching { StockDataManager.getInstance(appContext).clearCache() }
        runCatching { SinaStockAPI.invalidateCache() }
        runCatching { UnifiedPriceService.clearCache() }
        runCatching { FundAPI.clearPersistentCache() }
    }
}
