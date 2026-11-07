package com.example.stockdiary.network

import com.example.stockdiary.data.price.NoopPriceSourceAdapter
import com.example.stockdiary.data.price.PriceSourceAdapter
import com.example.stockdiary.data.price.SourceDomain
import com.example.stockdiary.data.price.SourceIds

/**
 * 数据源注册表（G4 脚手架）。
 *
 * **无 Context 的 `object`**：刻意不持 Context 字段，杜绝 `network → di` 的反向依赖
 * （项目用手写 AppContainer，不引入 Hilt）。
 *
 * 本组只做结构就位 + 占位注册，**不替换现有取价逻辑、不接任何新数据源**：为后续按
 * [SourceDomain] 接入真实 [PriceSourceAdapter] 铺路。现有取价仍由 [StockDataManager] /
 * [UnifiedPriceService] 负责，注册表此刻只是「登记逻辑源 → 占位适配器」的空壳。
 */
object SourceRegistry {
    private val adapters = LinkedHashMap<SourceDomain, PriceSourceAdapter>()
    @Volatile
    private var fallback: PriceSourceAdapter? = null

    /** 进程加载时登记全部逻辑源的占位适配器（结构就位，方便后续逐个替换成真实实现） */
    init {
        registerDefaults()
    }

    /** 把每个 [SourceDomain] 登记为一个占位 [NoopPriceSourceAdapter]（幂等，已登记则跳过） */
    fun registerDefaults() {
        if (adapters.isNotEmpty()) return
        SourceDomain.values().forEach { domain ->
            adapters[domain] = NoopPriceSourceAdapter(domain, defaultPlaceholderSourceId(domain))
        }
    }

    /** 占位源 ID：已知域映射到 [SourceIds] 稳定 ID，其余用域名小写兜底（仅占位、不参与真实血缘口径） */
    private fun defaultPlaceholderSourceId(domain: SourceDomain): String = when (domain) {
        SourceDomain.STOCK_A -> SourceIds.SINA
        SourceDomain.FUND_OTC -> SourceIds.EASTMONEY_FUND
        // 场内基金当前实际走股票行情门面（新浪/腾讯），占位仍标新浪；
        // REIT 的分红/收益分配实际只在天天基金，不能沿用股票源。
        SourceDomain.FUND_EXCHANGE -> SourceIds.SINA
        SourceDomain.CRYPTO -> SourceIds.BINANCE
        SourceDomain.REIT -> SourceIds.EASTMONEY_FUND
        SourceDomain.FX -> "fx"
        SourceDomain.DIVIDEND -> "dividend"
    }

    /** 登记某域的适配器（覆盖占位） */
    fun register(domain: SourceDomain, adapter: PriceSourceAdapter) {
        adapters[domain] = adapter
    }

    /** 取某域已登记的适配器；未登记返回 null */
    fun getAdapter(domain: SourceDomain): PriceSourceAdapter? = adapters[domain]

    /** 取回退适配器（当前未设置则为 null） */
    fun fallbackFor(domain: SourceDomain): PriceSourceAdapter? = fallback

    /** 设置回退适配器（某域无专属适配器时回落到这里） */
    fun setFallback(adapter: PriceSourceAdapter?) {
        fallback = adapter
    }

    /** 清空全部登记与回退 */
    fun clear() {
        adapters.clear()
        fallback = null
    }

    /** 测试专用复位：清空进程内单例，保证用例间隔离 */
    fun resetForTesting() {
        clear()
    }
}
