package com.example.stockdiary.utils

import androidx.compose.ui.graphics.Color
import com.example.stockdiary.database.AssetType

/**
 * 股票代码管理器
 * 统一处理股票代码格式转换、市场推断、类型判断
 */
object StockCodeManager {
    
    /**
     * ETF代码前缀
     * - 沪市ETF：510, 511, 512, 513, 515, 516, 517, 518, 588
     * - 深市ETF：159
     */
    val ETF_PREFIXES = setOf("510", "511", "512", "513", "515", "516", "517", "518", "588")
    
    /**
     * 判断是否为ETF
     */
    fun isETF(symbol: String?): Boolean {
        if (symbol.isNullOrBlank()) return false
        val code = extractDigits(symbol)
        if (code.length < 3) return false
        val prefix = code.substring(0, 3)
        return prefix in ETF_PREFIXES || code.startsWith("159")
    }

    /**
     * 判断是否为股票（非 ETF/LOF/场内基金/场外基金）
     */
    fun isStock(symbol: String?): Boolean {
        if (symbol.isNullOrBlank()) return false
        val code = extractDigits(symbol)
        if (code.length < 6) return false
        return classifyCode(code) == CodeCategory.STOCK
    }

    /**
     * 从字符串中提取纯数字
     */
    fun extractDigits(input: String): String = input.filter { it.isDigit() }
    
    /**
     * 代码分类枚举
     */
    enum class CodeCategory(val label: String) {
        STOCK("股票"),
        ETF("ETF"),
        LOF("LOF基金"),
        EXCHANGE_FUND("场内基金"),
        OTC_FUND("场外基金"),
        UNKNOWN("未知");
        
        val color: Color get() = when (this) {
            STOCK -> Color(0xFF1565C0)
            ETF -> Color(0xFF2E7D32)
            LOF -> Color(0xFFE65100)
            EXCHANGE_FUND -> Color(0xFF6A1B9A)
            OTC_FUND -> Color(0xFF00838F)
            UNKNOWN -> Color.Gray
        }
        
        /** 映射到 AssetType（STOCK 或 FUND） */
        fun toAssetType(): AssetType = when (this) {
            STOCK -> AssetType.STOCK
            ETF, LOF, EXCHANGE_FUND, OTC_FUND -> AssetType.FUND
            UNKNOWN -> AssetType.STOCK
        }
    }

    /**
     * 统一分类股票/基金代码类型
     * 根据代码规则自动识别：股票、ETF、LOF、场内基金、场外基金
     */
    fun classifyCode(input: String): CodeCategory {
        val code = extractDigits(input)
        if (code.length < 6) return CodeCategory.UNKNOWN
        val c = code.take(6)
        
        // ETF: 510/511/512/513/515/516/517/518/588/159 开头
        if (isETF(c)) return CodeCategory.ETF
        
        // LOF: 16xxxx/18xxxx 深交所上市交易型开放式基金
        if (c.startsWith("16") || c.startsWith("18")) return CodeCategory.LOF
        
        // 场外基金: 01xxxx/02xxxx 全部，00xxxx 中 004xxx-009xxx
        if (isOTCFundCode(c)) return CodeCategory.OTC_FUND
        
        // 场内基金（非ETF/LOF）: 5xxxxx 或 1xxxxx（159/16/18 已排除）
        if (c.startsWith("5") || c.startsWith("1")) return CodeCategory.EXCHANGE_FUND
        
        // 股票: 6/0/3/4/8/9 开头
        if (c.startsWith("6") || c.startsWith("0") || c.startsWith("3") ||
            c.startsWith("4") || c.startsWith("8") || c.startsWith("9")) {
            return CodeCategory.STOCK
        }
        
        return CodeCategory.UNKNOWN
    }

    /**
     * 判断是否为场外基金代码（纯场外，不在交易所交易）
     * - 01xxxx/02xxxx - 开放式基金
     * - 00xxxx 中 004xxx-009xxx - 开放式基金
     * - 排除 000xxx-003xxx（深市股票）和 ETF
     */
    fun isOTCFundCode(code: String): Boolean {
        if (code.length != 6) return false
        
        if (isETF(code)) return false
        
        return when {
            // 01xxxx/02xxxx - 开放式基金（场外，无交易所行情）
            code.startsWith("01") || code.startsWith("02") -> true
            // 00xxxx 中只有 004xxx-009xxx 是场外基金，000xxx-003xxx 是深市股票
            code.startsWith("00") -> {
                val third = code.getOrNull(2) ?: return false
                third >= '4'
            }
            else -> false
        }
    }

    /**
     * 推断市场前缀
     * 6/5/9开头 -> sh
     * 0/3/15开头 -> sz
     * 4/8开头 -> bj
     */
    fun inferMarketPrefix(code: String): String = when {
        code.startsWith("6") || code.startsWith("5") || code.startsWith("9") -> "sh"
        code.startsWith("4") || code.startsWith("8") -> "bj"
        else -> "sz"
    }
    
    /**
     * 统一格式化股票代码
     * 支持多种输入格式：
     * - "000001" -> "sz000001"
     * - "000001.SZ" -> "sz000001"
     * - "600519" -> "sh600519"
     * - "sh600519" -> "sh600519"
     * - "510050" -> "sh510050" (沪市ETF)
     * - "159919" -> "sz159919" (深市ETF)
     */
    fun formatToSinaCode(input: String): String {
        var code = input.trim().lowercase()

        // 如果已经是新浪格式，直接返回
        if (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj")) {
            return code
        }

        // 如果带有后缀，如 ".SH" 或 ".SZ"
        if (code.contains(".")) {
            val parts = code.split(".")
            if (parts.size == 2) {
                val market = parts[1].lowercase()
                val stockNum = parts[0]
                return "$market$stockNum"
            }
        }
        
        // 纯数字代码，根据规则判断
        if (code.matches(Regex("\\d+"))) {
            return when {
                code.startsWith("6") || code.startsWith("5") || code.startsWith("9") -> "sh$code"
                code.startsWith("0") || code.startsWith("3") || code.startsWith("15") || code.startsWith("16") || code.startsWith("18") -> "sz$code"
                code.startsWith("4") || code.startsWith("8") -> "bj$code"
                else -> code
            }
        }
        
        return code
    }
    
    /**
     * 标准化symbol格式（统一缓存键和数据库查询键）
     * 确保所有模块使用相同的symbol格式
     */
    fun normalizeSymbol(symbol: String): String {
        if (symbol.isBlank()) return ""
        val trimmed = symbol.trim()
        
        // 如果已包含市场前缀，直接返回小写
        if (trimmed.startsWith("sh", ignoreCase = true) || 
            trimmed.startsWith("sz", ignoreCase = true) ||
            trimmed.startsWith("bj", ignoreCase = true)) {
            return trimmed.lowercase()
        }
        
        // 保留fund_前缀
        if (trimmed.startsWith("fund_", ignoreCase = true)) {
            return trimmed
        }
        
        // 纯数字代码，判断是否为场外基金
        val pureCode = extractDigits(trimmed)
        if (pureCode.length == 6) {
            // 场外基金代码：00xxxx/01xxxx/02xxxx 部分，以及 16xxxx/18xxxx/519xxx 等
            // 不添加市场前缀，保持纯数字格式
            if (isOTCFundCode(pureCode)) {
                return pureCode
            }
        }
        
        // 纯6位数字代码，推断市场并添加前缀
        if (pureCode.length == 6) {
            val market = inferMarketPrefix(pureCode)
            return "$market$pureCode"
        }
        
        // 其他情况返回原始值
        return trimmed
    }
    
    /**
     * 从新浪格式提取纯数字代码
     * "sh600519" -> "600519"
     * "sz000001" -> "000001"
     */
    fun extractPureCode(sinaCode: String): String {
        return if (sinaCode.length > 2 && 
            (sinaCode.startsWith("sh") || sinaCode.startsWith("sz") || sinaCode.startsWith("bj"))) {
            sinaCode.substring(2)
        } else {
            sinaCode
        }
    }
    
    /**
     * 获取市场前缀
     * "sh600519" -> "sh"
     * "000001" -> "sz" (根据规则推断)
     */
    fun getMarketPrefix(code: String): String {
        val sinaCode = formatToSinaCode(code)
        return if (sinaCode.length >= 2) {
            sinaCode.substring(0, 2)
        } else {
            "sh"
        }
    }
    
    /**
     * 获取交易所代码（腾讯/东方财富格式）
     * 6/9开头 -> XSHG（上交所）
     * 0/3开头 -> XSHE（深交所）
     * 8开头 -> BJSE（北交所）
     */
    fun getExchangeCode(symbol: String): String {
        val code = extractDigits(symbol)
        return when {
            code.startsWith("6") || code.startsWith("9") -> "XSHG"
            code.startsWith("0") || code.startsWith("3") -> "XSHE"
            code.startsWith("8") -> "BJSE"
            else -> "XSHG"
        }
    }
    
    /**
     * 解析分红金额（统一处理新浪分红文本格式）
     * 返回每10股派息金额，API层不除以10
     */
    fun parseDividendAmount(text: String): Float {
        try {
            val trimmed = text.trim()
            val doubleValue = trimmed.toDoubleOrNull()
            if (doubleValue != null) return doubleValue.toFloat()
            if (trimmed.contains("派")) {
                val parts = trimmed.split("派")
                if (parts.size > 1) {
                    val dividendPart = parts[1].filter { it.isDigit() || it == '.' }
                    val value = dividendPart.toDoubleOrNull()
                    if (value != null) return value.toFloat()
                }
            }
            if (trimmed.endsWith("元")) {
                val value = trimmed.replace("元", "").trim().toDoubleOrNull()
                if (value != null) return value.toFloat()
            }
            val digits = trimmed.filter { it.isDigit() || it == '.' }
            val extracted = digits.toDoubleOrNull()
            if (extracted != null) return extracted.toFloat()
        } catch (e: Exception) {
            // ignore
        }
        return 0f
    }
    
    /**
     * 从日期字符串中提取财年（前4位数字）
     */
    fun extractFiscalYear(dateStr: String): Int {
        return try {
            extractDigits(dateStr).take(4).toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
}