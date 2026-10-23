package com.example.stockdiary.data.price

import android.content.Context

/**
 * API管理类：行情数据源的**标识常量**与自动优先级说明。
 *
 * ⚠️ 数据源选择已改为**全自动**（2026-10-23，应用户要求去掉「我的 → 行情数据源」设置）：
 * 候选链与顺序由 [StockDataManager.candidateSourceNames] 按
 * 「妙想Skill(仅当配置了 API Key) > 新浪 > 腾讯」自动决定，某个源连续失败时由
 * 数据源级熔断器自动短期禁用并降级到下一个，全程无需用户干预。
 *
 * 原先的 SharedPreferences 用户偏好读写（getPriceApi / savePriceApi，含切换时
 * 作废价格缓存的逻辑）已随设置一起删除——留下没人写的偏好只会让人误以为还能切源。
 *
 * 分红API：根据资产类型自动路由（股票→东方财富，ETF→天天基金，场外基金→天天基金），
 * 与股价源无关，也从来不受该设置影响。
 *
 * 本类不持有/返回任何 API 服务实例：数据源的选择与实例化在
 * [StockDataManager.getDataSource] 完成，服务缓存曾是历史分层残留，已删除。
 */
object ApiManager {

    // 股价API选项（仅作为数据源标识、日志与缓存标签使用，不再是用户可选项）
    const val API_SINA = "新浪财经"
    const val API_TENCENT = "腾讯财经"
    const val API_SKILL = "妙想Skill"
}
