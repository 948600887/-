package com.example.stockdiary.data.dividend

/**
 * 分红数据模型
 * 用于存储从Tsanghi API获取的分红数据
 */
data class DividendData(
    val ticker: String,              // 股票代码
    val date: String,                // 除权除息日
    val dividend: Double,            // 分红总额（万元）
    val dividendPerShare: Double,    // 每十股分红（元）
    val reportDate: String? = null,  // 报告期
    val exDividendDate: String? = null, // 股权登记日
    val paymentDate: String? = null     // 派息日
)

/**
 * 分红记录模型
 * 用于存储和计算分红数据
 */
data class DividendRecord(
    val exDividendDate: String,      // 除权除息日
    val dividendPerShare: String,    // 每十股分红（元）
    val announceDate: String,        // 公告日期
    val fiscalYear: String,          // 财年
    /**
     * 分红发放日（钱真正到账的日子），通常为除息日之后 1~4 周。
     * 只有部分数据源给得出（天天基金的「分红发放日」列）；拿不到就是 null，
     * 界面/通知退回用除息日，并在文案上说清那是除息日。
     */
    val paymentDate: String? = null
)

/**
 * 分红指标模型
 * 用于存储分红相关的计算指标
 */
data class DividendMetrics(
    val totalRecords: Int = 0,                      // 总记录数
    val recentRecord: DividendRecord? = null,       // 最近记录
    val totalCashDividend: Double = 0.0,            // 总现金分红
    val avgAnnualDividend: Double = 0.0,            // 平均年化分红
    val dividendFrequency: Float = 0f,              // 分红频率
    val dividendStability: Float = 0f               // 分红稳定性
)

/**
 * API响应模型
 * 用于解析Tsanghi API的响应数据
 */
data class DividendApiResponse(
    val code: Int,
    val msg: String,
    val data: List<DividendData>
)
