package com.example.stockdiary.data.price

import com.example.stockdiary.network.model.AssetPrice

/**
 * 数据源适配器接口（G4 脚手架）。
 *
 * 描述「某个 [SourceDomain] 的一个逻辑数据源」能怎么取一个代码的价。本组仅给出最小占位
 * 实现 [NoopPriceSourceAdapter]，不接真实网络、不落缓存；为后续替换 / 扩展取价逻辑铺路，
 * 不影响运行时既有取价语义（现有取价仍走 [StockDataManager] /
 * [com.example.stockdiary.network.UnifiedPriceService]）。
 */
interface PriceSourceAdapter {
    /** 本适配器负责的逻辑数据源域 */
    val domain: SourceDomain

    /** 稳定源 ID（[SourceIds] 常量或等价的稳定串），用于血缘归属 */
    val sourceId: String

    /**
     * 取一个代码的价。占位实现一律返回失败（该域尚未接入真实适配器）。
     *
     * @return 成功为 [AssetPrice]，失败为携带原因的 [Result]
     */
    suspend fun fetch(code: String): Result<AssetPrice>

    /** 行情缓存被清空（如 [com.example.stockdiary.network.UnifiedPriceService.clearCache]）时回调，供适配器同步清自己的缓存 */
    fun onCacheCleared()
}

/**
 * 最小占位实现（G4）。
 *
 * 不接真实网络、不落缓存：所有取价返回失败（表示该域尚未接入真实适配器），
 * [onCacheCleared] 为 no-op。仅用于让 [com.example.stockdiary.network.SourceRegistry]
 * 的结构在门禁下编译通过、且后续替换为真实适配器时无需改动注册表骨架。
 */
class NoopPriceSourceAdapter(
    override val domain: SourceDomain,
    override val sourceId: String
) : PriceSourceAdapter {
    override suspend fun fetch(code: String): Result<AssetPrice> =
        Result.failure(
            UnsupportedOperationException("SourceDomain.$domain 尚未接入真实适配器（占位 NoopPriceSourceAdapter）")
        )

    override fun onCacheCleared() {
        // 占位实现无缓存，no-op
    }
}
