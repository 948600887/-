package com.example.stockdiary.data.importer

import com.example.stockdiary.database.StockBasic

/**
 * 预置股票列表，用于首次初始化股票基本信息数据库
 * 当数据库为空时，使用此列表作为种子数据
 */
data class StockPreset(
    val code: String,
    val name: String,
    val isETF: Boolean
) {
    fun toStockBasic(): StockBasic = StockBasic(
        code = code,
        name = name,
        market = code.substring(0, 2),
        listDate = null,
        isETF = isETF
    )

    companion object {
        val DEFAULT_PRESETS: List<StockPreset> = listOf(
            // 沪市股票
            StockPreset("sh600000", "浦发银行", false),
            StockPreset("sh600519", "贵州茅台", false),
            StockPreset("sh601398", "工商银行", false),
            StockPreset("sh601288", "农业银行", false),
            StockPreset("sh601988", "中国银行", false),
            StockPreset("sh601668", "中国建筑", false),
            StockPreset("sh601318", "中国平安", false),
            StockPreset("sh600036", "招商银行", false),
            StockPreset("sh601857", "中国石油", false),
            StockPreset("sh600028", "中国石化", false),
            // 深市股票
            StockPreset("sz000001", "平安银行", false),
            StockPreset("sz000858", "五粮液", false),
            StockPreset("sz000002", "万科A", false),
            StockPreset("sz000333", "美的集团", false),
            StockPreset("sz002594", "比亚迪", false),
            StockPreset("sz000651", "格力电器", false),
            StockPreset("sz000725", "京东方A", false),
            StockPreset("sz000063", "中兴通讯", false),
            StockPreset("sz000977", "浪潮信息", false),
            StockPreset("sz002415", "海康威视", false),
            // ETF
            StockPreset("sh510050", "上证50ETF", true),
            StockPreset("sh510300", "沪深300ETF", true),
            StockPreset("sh510500", "中证500ETF", true),
            StockPreset("sh512880", "证券ETF", true),
            StockPreset("sh510880", "红利ETF", true),
            StockPreset("sh512000", "券商ETF", true),
            StockPreset("sh512760", "芯片ETF", true),
            StockPreset("sh515000", "科技ETF", true),
            StockPreset("sh516000", "新能源ETF", true),
            StockPreset("sh516900", "碳中和ETF", true),
            StockPreset("sz159919", "沪深300ETF", true),
            StockPreset("sz159928", "消费ETF", true),
            StockPreset("sz159949", "创业板ETF", true),
            StockPreset("sz159952", "国防ETF", true),
            StockPreset("sz159967", "创新药ETF", true),
            StockPreset("sz159995", "芯片ETF", true),
        )
    }
}