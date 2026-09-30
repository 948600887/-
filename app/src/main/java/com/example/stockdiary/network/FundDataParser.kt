package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 天天基金数据解析器：从 pingzhongdata JS 响应中提取基金名称、净值和回报率。
 * FundAPI 和 EnhancedFundAPI 共享此解析逻辑。
 */
object FundDataParser {
    private const val TAG = "FundDataParser"
    
    /** 预编译正则：提取 JS 变量值，如 var fS_name = "xxx" */
    private val jsVarRegex = Regex("""(\w+)\s*=\s*"([^"]+)"""")
    
    /** 预编译正则：匹配 Data_netWorthTrend 数组 */
    private val trendArrayRegex = Regex(
        """Data_netWorthTrend\s*=\s*\[(.*?)]\s*;""",
        RegexOption.DOT_MATCHES_ALL
    )
    
    /** 预编译正则：匹配单个净值数据点 */
    private val nawItemRegex = Regex(
        """\{[^}]*"x":(\d+)[^}]*"y":([0-9.]+)[^}]*"equityReturn":([0-9.\-]+)[^}]*\}"""
    )
    
    /** 日期格式化：时间戳 → yyyy-MM-dd */
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    
    data class NawData(
        val naw: Double = 0.0,
        val equityReturn: Double? = null,
        val date: String? = null
    )
    
    /**
     * 从 JS 响应中提取指定变量值
     */
    fun extractJsVar(js: String, varName: String): String? {
        return jsVarRegex.findAll(js).find { match ->
            match.groupValues[1] == varName
        }?.groupValues?.get(2)
    }
    
    /**
     * 解析 Data_netWorthTrend 最后一条记录，获取最新净值
     */
    fun parseNawData(response: String): NawData {
        return try {
            val trendMatch = trendArrayRegex.find(response) ?: return NawData()
            
            val trendContent = trendMatch.groupValues[1]
            val matches = nawItemRegex.findAll(trendContent).toList()
            
            if (matches.isEmpty()) return NawData()
            
            val lastItem = matches.last()
            val timestamp = lastItem.groupValues[1].toLongOrNull() ?: return NawData()
            val naw = lastItem.groupValues[2].toDoubleOrNull() ?: 0.0
            val equityReturn = lastItem.groupValues[3].toDoubleOrNull()
            
            val dateStr = try {
                val instant = Instant.ofEpochMilli(timestamp)
                val localDate = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate()
                localDate.format(dateFormatter)
            } catch (e: Exception) {
                null
            }
            
            AppLogger.d("最新净值: $naw, 日期: $dateStr, 回报: $equityReturn", TAG)
            NawData(naw = naw, equityReturn = equityReturn, date = dateStr)
        } catch (e: Exception) {
            AppLogger.e("解析净值数据失败: ${e.message}", e, TAG)
            NawData()
        }
    }
}