package com.example.stockdiary.data

import android.content.Context

/**
 * API管理类，用于管理用户选择的API服务
 * 股价API：用户可切换（新浪/腾讯）
 * 分红API：根据资产类型自动路由（股票→东方财富，ETF→新浪，场外基金→天天基金）
 */
object ApiManager {
    private const val PREF_NAME = "api_preferences"
    private const val KEY_PRICE_API = "price_api"
    
    // 股价API选项
    const val API_SINA = "新浪财经"
    const val API_TENCENT = "腾讯财经"
    const val API_SKILL = "妙想Skill"
    
    // 缓存API服务实例
    private var priceApiService: Any? = null
    private var lastPriceApi: String? = null
    
    /**
     * 保存用户选择的股价API
     */
    fun savePriceApi(context: Context, api: String) {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        preferences.edit().putString(KEY_PRICE_API, api).apply()
        // 清除缓存
        if (lastPriceApi != api) {
            priceApiService = null
            lastPriceApi = api
            // 同时清除价格缓存，确保切换后获取新数据
            PriceCacheManager.invalidateAll()
        }
    }
    
    /**
     * 获取用户选择的股价API
     */
    fun getPriceApi(context: Context): String {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return preferences.getString(KEY_PRICE_API, API_SINA) ?: API_SINA
    }
    
    /**
     * 获取股价API服务实例（带缓存）
     */
    fun getPriceApiService(context: Context): Any {
        val api = getPriceApi(context)
        val cached = priceApiService
        if (cached != null && lastPriceApi == api) {
            return cached
        }
        val service = when (api) {
            API_SINA -> com.example.stockdiary.network.SinaStockAPI
            API_TENCENT -> com.example.stockdiary.network.TencentStockAPI
            API_SKILL -> com.example.stockdiary.network.SinaStockAPI
            else -> com.example.stockdiary.network.SinaStockAPI
        }
        priceApiService = service
        lastPriceApi = api
        return service
    }
    
    /**
     * 清除缓存的API服务实例
     */
    fun clearCache() {
        priceApiService = null
        lastPriceApi = null
    }
}
