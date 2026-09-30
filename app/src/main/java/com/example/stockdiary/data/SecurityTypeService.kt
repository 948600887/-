package com.example.stockdiary.data

import com.example.stockdiary.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern
import kotlinx.coroutines.CancellationException

/**
 * 证券类型枚举
 */
enum class SecurityType {
    STOCK,        // 股票
    ETF,          // ETF
    FUND,         // 基金（开放式）
    BOND,         // 债券
    REPO,         // 回购
    CONVERTIBLE,  // 可转债
    INDEX,        // 指数
    OPTION,       // 期权
    UNKNOWN       // 未知
}

/**
 * 证券类型检测结果
 */
data class DetectionResult(
    val code: String,
    val type: SecurityType,
    val confidence: Double,  // 置信度 0-1
    val method: String,      // 检测方法
    val name: String = ""    // 证券名称
) {
    companion object {
        fun unknown(code: String, method: String): DetectionResult {
            return DetectionResult(
                code = code,
                type = SecurityType.UNKNOWN,
                confidence = 0.0,
                method = method
            )
        }
        
        fun error(message: String): DetectionResult {
            return DetectionResult(
                code = "",
                type = SecurityType.UNKNOWN,
                confidence = 0.0,
                method = "错误: $message"
            )
        }
    }
}

/**
 * 证券类型判断服务
 */
object SecurityTypeService {
    
    // 已知的ETF代码前缀
    private val etfPrefixes = setOf(
        "510", "511", "512", "513", "515", "516", "517", "518", "588", "159"
    )
    
    // 已知的基金代码前缀
    private val fundPrefixes = setOf(
        "500", "519", "16", "184", "185", "186"
    )
    
    /**
     * 智能判断证券类型
     */
    suspend fun detectSecurityType(
        code: String
    ): DetectionResult {
        return withContext(Dispatchers.IO) {
            // 步骤1: 清理和验证代码
            val cleanCode = cleanSecurityCode(code)
            if (cleanCode.length != 6) {
                return@withContext DetectionResult.error("无效的证券代码")
            }
            
            // 步骤2: 快速前缀判断
            val quickResult = quickDetectByPrefix(cleanCode)
            if (quickResult.confidence > 0.9) {
                return@withContext quickResult
            }
            
            // 步骤3: 多数据源验证
            val multiSourceResult = detectByMultipleSources(cleanCode)
            
            multiSourceResult
        }
    }
    
    /**
     * 清理证券代码
     */
    private fun cleanSecurityCode(code: String): String {
        // 首先检查原始代码是否包含市场前缀（如 sh515080）
        var workingCode = code.trim()
        
        // 如果以市场代码开头，先移除市场代码
        val marketPrefixes = setOf("sh", "sz", "bj", "SH", "SZ", "BJ")
        for (prefix in marketPrefixes) {
            if (workingCode.startsWith(prefix)) {
                workingCode = workingCode.substring(prefix.length)
                break
            }
        }
        
        // 移除所有非数字字符
        var clean = workingCode.replace(Regex("[^0-9]"), "")
        
        // 确保长度为6
        return clean.padStart(6, '0').take(6)
    }
    
    /**
     * 快速前缀判断
     */
    private fun quickDetectByPrefix(code: String): DetectionResult {
        val prefix3 = code.substring(0, 3)
        val prefix2 = code.substring(0, 2)
        
        return when {
            // 明确是ETF的代码
            prefix3 in etfPrefixes -> DetectionResult(
                code = code,
                type = SecurityType.ETF,
                confidence = 0.95,
                method = "代码前缀匹配"
            )
            
            // 明确是基金的代码
            prefix3 in fundPrefixes || prefix2 in fundPrefixes -> DetectionResult(
                code = code,
                type = SecurityType.FUND,
                confidence = 0.95,
                method = "代码前缀匹配"
            )
            
            // 科创板股票
            code.startsWith("688") -> DetectionResult(
                code = code,
                type = SecurityType.STOCK,
                confidence = 0.99,
                method = "代码前缀匹配"
            )
            
            // 创业板股票
            code.startsWith("300") -> DetectionResult(
                code = code,
                type = SecurityType.STOCK,
                confidence = 0.99,
                method = "代码前缀匹配"
            )
            
            // 主板股票
            code.startsWith("6") || code.startsWith("0") -> DetectionResult(
                code = code,
                type = SecurityType.STOCK,
                confidence = 0.9,
                method = "代码前缀匹配"
            )
            
            // 北交所股票
            code.startsWith("43") || code.startsWith("83") || code.startsWith("87") -> DetectionResult(
                code = code,
                type = SecurityType.STOCK,
                confidence = 0.9,
                method = "代码前缀匹配"
            )
            
            else -> DetectionResult(
                code = code,
                type = SecurityType.UNKNOWN,
                confidence = 0.3,
                method = "代码前缀匹配"
            )
        }
    }
    
    /**
     * 多数据源验证
     */
    private suspend fun detectByMultipleSources(code: String): DetectionResult {
        val detectors = listOf(
            ::detectByTencent,      // 腾讯财经
            ::detectBySina,         // 新浪财经
            ::detectByNameAnalysis  // 名称分析
        )
        
        val results = mutableListOf<DetectionResult>()
        
        for (detector in detectors) {
            try {
                val result = detector(code)
                if (result.type != SecurityType.UNKNOWN) {
                    results.add(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 忽略单个数据源失败
            }
        }
        
        if (results.isEmpty()) {
            return DetectionResult(
                code = code,
                type = SecurityType.UNKNOWN,
                confidence = 0.1,
                method = "多数据源验证"
            )
        }
        
        // 投票决定最终结果
        return voteResults(results)
    }
    
    /**
     * 通过腾讯财经验证
     */
    private suspend fun detectByTencent(code: String): DetectionResult {
        return withContext(Dispatchers.IO) {
            val markets = listOf("sh", "sz", "bj")
            
            for (market in markets) {
                val url = "https://qt.gtimg.cn/q=$market$code"
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = NetworkClient.defaultClient.newCall(request).execute()
                response.use {
                    val text = it.body?.string() ?: ""

                    if (text.isNotEmpty() && text.contains("~")) {
                        val fields = text.split("~")
                        if (fields.size > 20) {
                            val name = fields[1]

                            val type = analyzeSecurityType(name, code)

                            return@withContext DetectionResult(
                                code = code,
                                type = type,
                                confidence = 0.85,
                                method = "腾讯财经接口",
                                name = name
                            )
                        }
                    }
                }
            }
            
            DetectionResult.unknown(code, "腾讯财经接口")
        }
    }
    
    /**
     * 通过新浪财经验证
     */
    private suspend fun detectBySina(code: String): DetectionResult {
        return withContext(Dispatchers.IO) {
            val markets = listOf("sh", "sz", "bj")
            
            for (market in markets) {
                val url = "https://hq.sinajs.cn/list=$market$code"
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Referer", "https://finance.sina.com.cn")
                    .build()
                
                val response = NetworkClient.defaultClient.newCall(request).execute()
                response.use {
                    val text = it.body?.string() ?: ""

                    val data = parseSinaQuote(text)
                    if (data.isNotEmpty()) {
                        val name = data["name"] ?: ""
                        val type = analyzeSecurityType(name, code)

                        return@withContext DetectionResult(
                            code = code,
                            type = type,
                            confidence = 0.8,
                            method = "新浪财经接口",
                            name = name
                        )
                    }
                }
            }
            
            DetectionResult.unknown(code, "新浪财经接口")
        }
    }
    
    /**
     * 解析新浪行情数据
     */
    private fun parseSinaQuote(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        try {
            val pattern = Pattern.compile("var hq_str_[^=]+=\"([^\"]+)\"")
            val matcher = pattern.matcher(text)
            
            if (matcher.find()) {
                val dataStr = matcher.group(1) ?: return result
                val fields = dataStr.split(",")
                
                if (fields.size >= 32) {
                    result["name"] = fields[0]
                    result["open"] = fields[1]
                    result["price"] = fields[3]
                    result["high"] = fields[4]
                    result["low"] = fields[5]
                    result["volume"] = fields[8]
                    result["amount"] = fields[9]
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 忽略解析错误
        }
        
        return result
    }
    
    /**
     * 通过名称分析验证
     */
    private suspend fun detectByNameAnalysis(code: String): DetectionResult {
        return withContext(Dispatchers.IO) {
            // 尝试通过多个市场获取名称
            val markets = listOf("sh", "sz", "bj")
            
            for (market in markets) {
                val url = "https://qt.gtimg.cn/q=$market$code"
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = NetworkClient.defaultClient.newCall(request).execute()
                response.use {
                    val text = it.body?.string() ?: ""

                    if (text.isNotEmpty() && text.contains("~")) {
                        val fields = text.split("~")
                        if (fields.size > 1) {
                            val name = fields[1]
                            val type = analyzeSecurityType(name, code)

                            return@withContext DetectionResult(
                                code = code,
                                type = type,
                                confidence = 0.75,
                                method = "名称分析",
                                name = name
                            )
                        }
                    }
                }
            }
            
            DetectionResult.unknown(code, "名称分析")
        }
    }
    
    /**
     * 分析证券类型
     */
    private fun analyzeSecurityType(name: String, code: String): SecurityType {
        val lowerName = name.lowercase()
        
        return when {
            // ETF关键词
            lowerName.contains("etf") || 
            lowerName.contains("交易型开放式指数") -> SecurityType.ETF
            
            // LOF关键词
            lowerName.contains("lof") || 
            lowerName.contains("上市开放式基金") -> SecurityType.FUND
            
            // 基金关键词
            lowerName.contains("基金") || 
            lowerName.contains("货币") || 
            lowerName.contains("债券") || 
            lowerName.contains("混合") || 
            lowerName.contains("股票") -> {
                // 进一步判断是ETF还是普通基金
                if (code.startsWith("159") || code.startsWith("51")) {
                    SecurityType.ETF
                } else {
                    SecurityType.FUND
                }
            }
            
            // 可转债关键词
            lowerName.contains("转债") || lowerName.contains("可转") -> SecurityType.CONVERTIBLE
            
            // 债券关键词
            lowerName.contains("国债") || 
            lowerName.contains("国开") || 
            lowerName.contains("地方债") -> SecurityType.BOND
            
            // 回购关键词
            lowerName.contains("回购") -> SecurityType.REPO
            
            // 默认认为是股票
            else -> SecurityType.STOCK
        }
    }
    
    /**
     * 投票决定最终结果
     */
    private fun voteResults(results: List<DetectionResult>): DetectionResult {
        if (results.isEmpty()) {
            return DetectionResult.unknown("", "投票")
        }
        
        // 统计每种类型的票数和置信度总和
        val votes = mutableMapOf<SecurityType, Pair<Int, Double>>()
        
        for (result in results) {
            val current = votes.getOrDefault(result.type, 0 to 0.0)
            votes[result.type] = (current.first + 1) to (current.second + result.confidence)
        }
        
        // 找到最高票数
        var maxVotes = 0
        var bestType = SecurityType.UNKNOWN
        var bestConfidence = 0.0
        
        for ((type, value) in votes) {
            val (count, confidenceSum) = value
            if (count > maxVotes || (count == maxVotes && confidenceSum > bestConfidence)) {
                maxVotes = count
                bestType = type
                bestConfidence = confidenceSum
            }
        }
        
        val avgConfidence = bestConfidence / maxVotes
        
        return DetectionResult(
            code = results.first().code,
            type = bestType,
            confidence = avgConfidence.coerceIn(0.0, 1.0),
            method = "多数据源投票",
            name = results.firstOrNull { it.name.isNotEmpty() }?.name ?: ""
        )
    }
    
    /**
     * 快速判断是否为ETF
     */
    fun isETF(code: String): Boolean {
        val cleanCode = cleanSecurityCode(code)
        if (cleanCode.length != 6) return false
        
        val prefix3 = cleanCode.substring(0, 3)
        return prefix3 in etfPrefixes
    }
}