package com.example.stockdiary.ui.screens

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.totalValueCny

/**
 * 低价值虚拟货币隐藏：在「显示开关」按类别过滤之后，再进一步把加密货币从持仓列表移除
 * （总资产仍按全量算，见 [androidx.compose.runtime.State]）。
 *
 * 开启 [hideLowValueCrypto] 后：
 * - **已知价**且人民币市值低于 [thresholdCny] 的加密货币被隐藏（阈值 [thresholdCny] 是人民币）；
 * - [hideUnknownPriceCrypto] 同时开启时，**无价格**（[Asset.currentPrice] == null，
 *   界面显示「价格待更新」/ ¥0.00）的加密货币也一并隐藏。
 *
 * 设计取舍（与导入侧的 [com.example.stockdiary.data.importer.filterDustByValue] 一致）：
 * 价格未知默认**不藏**——没行情不等于没持仓，静默丢掉等于弄丢真实资产；要藏得用户自己开开关。
 * 无价格隐藏**依赖** [hideLowValueCrypto] 开启，它只是后者的一个子项。
 *
 * 纯函数、无 Compose 依赖，便于单测。
 */
internal fun filterLowValueCrypto(
    assets: List<Asset>,
    hideLowValueCrypto: Boolean,
    thresholdCny: Double,
    hideUnknownPriceCrypto: Boolean = false
): List<Asset> {
    if (!hideLowValueCrypto) return assets
    return assets.filterNot { asset ->
        asset.type == AssetType.CRYPTO && when {
            // 无价格：仅在用户显式开启 hideUnknownPriceCrypto 时才藏
            asset.currentPrice == null -> hideUnknownPriceCrypto
            // 有价格：人民币市值低于阈值才藏
            // 负市值（借币/组合保证金欠币）一律保留：负必然小于阈值，
            // 不拦的话大额负债会从列表与分组小计里消失，而顶部总资产仍扣着它 → 逐项加总对不上。
            else -> asset.totalValueCny >= 0.0 && asset.totalValueCny < thresholdCny
        }
    }
}
