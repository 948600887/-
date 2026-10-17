package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

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
    
    /** 预编译正则：匹配 Data_millionCopiesIncome 数组（货币基金的每万份收益走势） */
    private val millionCopiesArrayRegex = Regex(
        """Data_millionCopiesIncome\s*=\s*\[(.*?)\]\s*;""",
        RegexOption.DOT_MATCHES_ALL
    )
    /** 预编译正则：匹配单条 [时间戳, 每万份收益] */
    private val millionCopiesItemRegex = Regex("""\[(\d+),([0-9.]+)\]""")
    
    /** 日期格式化：时间戳 → yyyy-MM-dd */
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    
    data class NawData(
        val naw: Double = 0.0,
        val equityReturn: Double? = null,
        val date: String? = null,
        val millionCopiesIncome: Double? = null
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
     * 解析净值数据，获取最新净值。
     *
     * 优先从 Data_netWorthTrend 取最后一条记录（普通开放式基金）。
     * 当该走势缺失时（货币基金只有每万份收益走势），回退到货币基金解析路径。
     */
    fun parseNawData(response: String): NawData {
        return try {
            val trendMatch = trendArrayRegex.find(response)
            if (trendMatch != null) {
                val trendContent = trendMatch.groupValues[1]
                val matches = nawItemRegex.findAll(trendContent).toList()
                if (matches.isEmpty()) return NawData()
                val lastItem = matches.last()
                val timestamp = lastItem.groupValues[1].toLongOrNull() ?: return NawData()
                val naw = lastItem.groupValues[2].toDoubleOrNull() ?: 0.0
                val equityReturn = lastItem.groupValues[3].toDoubleOrNull()
                val dateStr = formatTrendDate(timestamp)
                AppLogger.d("最新净值: $naw, 日期: $dateStr, 回报: $equityReturn", TAG)
                NawData(naw = naw, equityReturn = equityReturn, date = dateStr)
            } else {
                parseMoneyFundNaw(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析净值数据失败: ${e.message}", e, TAG)
            NawData()
        }
    }

    /**
     * 货币基金解析路径：没有 Data_netWorthTrend，只有每万份收益走势，
     * 单位净值恒为 1.0000。日期取每万份收益走势最后一条的时间戳。
     */
    private fun parseMoneyFundNaw(response: String): NawData {
        // 货币基金没有 Data_netWorthTrend，只有每万份收益走势；单位净值恒为 1.0000。
        val arrMatch = millionCopiesArrayRegex.find(response) ?: return NawData()
        val content = arrMatch.groupValues[1]
        val lastItem = millionCopiesItemRegex.findAll(content).lastOrNull()
        val timestamp = lastItem?.groupValues?.getOrNull(1)?.toLongOrNull()
        val income = lastItem?.groupValues?.getOrNull(2)?.toDoubleOrNull()
        val dateStr = timestamp?.let { formatTrendDate(it) }
        return NawData(naw = 1.0, equityReturn = null, date = dateStr, millionCopiesIncome = income)
    }

    /**
     * 将毫秒时间戳格式化为 yyyy-MM-dd；任何异常都返回 null，交由上层处理。
     */
    private fun formatTrendDate(timestamp: Long): String? {
        return try {
            val instant = Instant.ofEpochMilli(timestamp)
            ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
        } catch (e: Exception) {
            null
        }
    }
}