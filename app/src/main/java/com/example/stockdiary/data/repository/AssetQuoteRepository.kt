package com.example.stockdiary.data.repository

import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.BinanceHostGate
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.network.model.StockData

/**
 * 行情 / 基金查询的收口层（审计 H1：UI 不应直接调 network 层做 I/O）。
 *
 * 之前 [com.example.stockdiary.ui.dialogs.AddAssetDialog] 在 Composable 内直接调
 * [UnifiedPriceService] / [FundAPI] / [SinaStockAPI]，既耦合网络、又无法单测，
 * 还把 `network.*` 的响应模型带进了 `ui` 包签名。这里把它们收口成可注入、可替换的查询门面，
 * UI 只通过本仓库拿**领域模型**结果，不再 import 任何 network 类。
 *
 * 领域模型（[StockQuote] / [FundQuote]）与 network 层的 `StockData` / `FundAPI.FundInfo`
 * 解耦：哪怕远端 DTO 改字段，UI 也不受影响。
 */
class AssetQuoteRepository {

    /** 股票 / 场内基金行情：统一走行情门面（60s TTL + 单飞 + 多源容错）。 */
    suspend fun getStockQuote(formattedCode: String): Result<StockQuote> {
        val result = UnifiedPriceService.getPrice(formattedCode, AssetType.STOCK)
        val info = (result.getOrNull() as? AssetPrice.Stock)?.data
        return if (info != null) {
            Result.success(info.toStockQuote())
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("未找到该代码"))
        }
    }

    /** 场外基金净值（天天基金）。返回 null 表示没查到。 */
    suspend fun getFundInfo(symbol: String): FundQuote? {
        val raw = FundAPI.getFundInfo(symbol) ?: return null
        return raw.toFundQuote(symbol)
    }

    /** 带缓存的基金查询（网络层兜底），失败以 [Result.failure] 返回。 */
    suspend fun getFundInfoWithCache(symbol: String): Result<FundQuote?> {
        // FundAPI 内部用的是项目自定义 common.Result，这里转成 Kotlin Result 统一对外契约
        val common = FundAPI.getFundInfoWithCache(symbol)
        return if (common.isSuccess) {
            Result.success(common.getOrNull()?.toFundQuote(symbol))
        } else {
            Result.failure(common.exceptionOrNull() ?: Exception("未找到该代码"))
        }
    }

    /** 把用户输入的代码格式化成行情查询用的标准代码（纯字符串处理，非网络调用）。 */
    fun formatStockCode(symbol: String): String = SinaStockAPI.formatStockCode(symbol)

    /**
     * 币安主机级熔断是否生效（纯读、无副作用）。UI 用它决定「币安暂不可达」提示文案，
     * 不再直接 import [BinanceHostGate]。
     */
    fun isBinanceUnavailable(): Boolean = BinanceHostGate.isUnavailable()
}

/** 股票 / 场内基金行情的领域模型（UI 渲染用，与 network.StockData 解耦） */
data class StockQuote(
    val name: String,
    val price: Double,
    val changePercent: Double?
)

/** 场外基金净值的领域模型（UI 渲染用，与 network.FundAPI.FundInfo 解耦） */
data class FundQuote(
    val name: String,
    val code: String,
    val price: Double,
    val netWorth: Double?,
    val changePercent: Double?,
    val date: String?
)

private fun StockData.toStockQuote(): StockQuote =
    StockQuote(name = name, price = price, changePercent = changePercent)

private fun com.example.stockdiary.network.FundAPI.FundInfo.toFundQuote(fallbackCode: String): FundQuote =
    FundQuote(
        name = name,
        code = code.ifEmpty { fallbackCode },
        price = price,
        netWorth = netWorth,
        changePercent = changePercent,
        date = date
    )
