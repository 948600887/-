package com.example.stockdiary.data.price

import android.content.Context

/**
 * API管理类，负责股价API的用户偏好读写（SharedPreferences）
 * 股价API：用户可切换（新浪/腾讯）
 * 分红API：根据资产类型自动路由（股票→东方财富，ETF→新浪，场外基金→天天基金）
 *
 * 本类不再持有/返回任何 API 服务实例：数据源的选择与实例化在
 * [StockDataManager.getDataSource] 完成，服务缓存曾是历史分层残留，已删除。
 */
object ApiManager {
    private const val PREF_NAME = "api_preferences"
    private const val KEY_PRICE_API = "price_api"
    
    // 股价API选项
    const val API_SINA = "新浪财经"
    const val API_TENCENT = "腾讯财经"
    const val API_SKILL = "妙想Skill"
    
    /**
     * 保存用户选择的股价API
     * 仅在偏好真实发生变化时作废已持久化的价格缓存，确保切换后拿到新数据源的价格
     */
    fun savePriceApi(context: Context, api: String) {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val changed = preferences.getString(KEY_PRICE_API, API_SINA) != api
        preferences.edit().putString(KEY_PRICE_API, api).apply()
        if (changed) {
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
}
