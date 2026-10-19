package com.example.stockdiary.utils

import androidx.compose.ui.graphics.Color
import com.example.stockdiary.database.AssetType

/**
 * 股票代码管理器
 * 统一处理股票代码格式转换、市场推断、类型判断
 */
object StockCodeManager {

    /**
     * 场外份额的显式标记前缀（`fund_400030`）。
     *
     * 基金E账户导出与天天基金份额都用它标「场外份额」，与场内代码**可以同号**：
     * 场外基金跟深市股票共用 00xxxx 号段，跟老三板共用 400xxx / 420xxx 号段。
     * 所以只按数字推类别必然出错，凡是要判类别的地方都得**先看这个前缀**。
     */
    const val FUND_PREFIX = "fund_"

    /**
     * 是否带场外份额标记前缀（大小写不敏感，容忍 `FUND_` / ` Fund_`）。
     *
     * 判定类函数一律先问它，再 fallback 到代码号段。
     */
    fun hasFundPrefix(symbol: String?): Boolean =
        !symbol.isNullOrBlank() && symbol.trim().startsWith(FUND_PREFIX, ignoreCase = true)

    /**
     * 剥掉场外份额前缀（大小写不敏感），没带前缀时原样返回。
     *
     * 比 `removePrefix("fund_")` 稳：normalizeSymbol 会**原样保留**前缀的大小写，
     * 于是 `FUND_400030` 也能入库，而大小写敏感的 removePrefix 剥不掉它，
     * 后面拿着带前缀的字符串去判号段 / 请求接口，失败还是静默的。
     */
    fun stripFundPrefix(symbol: String): String =
        if (hasFundPrefix(symbol)) symbol.trim().drop(FUND_PREFIX.length) else symbol

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
     *
     * 带 [FUND_PREFIX] 的一律不是股票：它是场外份额的显式标记。
     * 必须在这里判，不能指望 [classifyCode] —— 本函数先把 symbol 压成纯数字
     * （[extractDigits]）再交给 classifyCode，前缀到那时已经丢了，
     * 而 400030 / 420102 这些场外代码压完数字正好落在「4 开头 = 股票」的分支上。
     * 漏掉这一句的直接后果见 DividendRepository 的分红上限分支。
     */
    fun isStock(symbol: String?): Boolean {
        if (symbol.isNullOrBlank()) return false
        if (hasFundPrefix(symbol)) return false
        val code = extractDigits(symbol)
        if (code.length < 6) return false
        return classifyCode(code) == CodeCategory.STOCK
    }

    /**
     * 代码层面可识别的退市：老三板（退市板块）代码 400xxx / 420xxx，
     * 归一化后形如 bj400264。注意：暂停上市（停牌）无法仅凭代码判断，
     * 需交易所状态接口，此处不覆盖。
     */
    fun isDelisted(symbol: String?): Boolean {
        if (symbol.isNullOrBlank()) return false
        val code = extractDigits(symbol)
        if (code.length != 6) return false
        return code.startsWith("400") || code.startsWith("420")
    }

    /**
     * 同步链路专用的退市判定：[isDelisted] 之外再排除场外基金份额。
     *
     * 为什么不能直接用 [isDelisted]：它只做「6 位数字 + 400/420 前缀」，
     * 而天天基金的场外代码段里**真有** 400xxx / 420xxx（400030 东方添益债券、
     * 420102 就是用户持仓里的实例）。直接判它们会被误杀：价格冻结、还不计失败，
     * 用户端毫无信号，比没有豁免更糟。
     *
     * `fund_` 前缀是「场外份额」的显式标记（AssetSyncManager 的 FUND 分支靠它
     * 决定走天天基金净值），老三板股票不会带这个前缀，所以排除它既堵住误杀，
     * 又不放过真退市股（bj400264 / sh400123 这类）。
     */
    fun isDelistedForSync(symbol: String?): Boolean {
        if (symbol.isNullOrBlank()) return false
        if (hasFundPrefix(symbol)) return false
        return isDelisted(symbol)
    }

    /**
     * 从字符串中提取纯数字
     */
    fun extractDigits(input: String): String = input.filter { it.isDigit() }
    
    /**
     * 代码分类枚举
     *
     * @param color 浅色分类色（Color.kt 现值，浅色模式使用）
     * @param colorDark 深色分类色（深色模式提亮变体，避免深背景下对比不足）
     */
    enum class CodeCategory(val label: String, val colorDark: Color) {
        STOCK("股票", Color(0xFF64B5F6)),
        ETF("ETF", Color(0xFF81C784)),
        LOF("LOF基金", Color(0xFFFFB74D)),
        EXCHANGE_FUND("场内基金", Color(0xFFCE93D8)),
        OTC_FUND("场外基金", Color(0xFF4DD0E1)),
        UNKNOWN("未知", Color(0xFF9E9E9E));

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
     *
     * [FUND_PREFIX] 前缀优先于一切号段推断（详见该常量的说明）。
     * 注意本函数**也可能**被喂进已经剥掉前缀的纯数字（如 AssetSyncManager
     * 同步链路先 `removePrefix("fund_")` 再 classifyCode），那条路不受此处影响：
     * 纯数字不含前缀，走的还是原来的号段分支。
     */
    fun classifyCode(input: String): CodeCategory {
        if (hasFundPrefix(input)) return CodeCategory.OTC_FUND

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
     * 判断一个 symbol 是不是场外基金：先看 [FUND_PREFIX] 这个显式标记，再看号段。
     *
     * 「这个标的是不是场外」跟「这个代码属不属于场外号段」不是一回事：
     * 400030（东方添益债券）/ 420102 这类场外代码落在 400/420 号段上，
     * [isOTCFundCode] 按号段判不出来。凡是**靠这个结论选数据源**的调用方
     * （分红走天天基金还是东财、净值走天天基金还是股票行情）都必须用本函数，
     * 不能直接用 [isOTCFundCode]，否则真基金会被送去问不存在的证券代码。
     */
    fun isOTCFund(symbol: String?): Boolean {
        if (symbol.isNullOrBlank()) return false
        if (hasFundPrefix(symbol)) return true
        return isOTCFundCode(extractPureCode(symbol))
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
        if (hasFundPrefix(trimmed)) {
            return trimmed
        }
        
        // 纯数字代码，判断是否为场外基金
        val pureCode = extractDigits(trimmed)
        if (pureCode.length == 6) {
            // 场外基金统一落成 `fund_` 形态，不再保留纯数字。
            //
            // 从前这里返回纯数字，于是同一只基金在库里有两种形态：手动添加 /
            // 一码通持仓截图走本函数得到 `004502`，基金E账户导入与基金持仓截图一律拼
            // [FUND_PREFIX] 得到 `fund_004502`。两种形态 [isOTCFund] 都认，但字符串
            // 不相等，而快照对齐是按 symbol 精确分组的 —— 同一只基金被判成两只，
            // 落两条资产、持仓翻倍，还分别显示在「场内/场外」两个分组里（持仓页
            // 按 [hasFundPrefix] 分市场）。
            //
            // 只在**号段明确属于场外**（[isOTCFundCode]）时才加前缀：LOF 的场内外
            // 份额（sz163415 场内 / fund_163415 场外）与老三板（400030 既是场外
            // 基金号段也是退市股票号段）刻意保持两态 —— 那正是当年场内外互相覆盖的老坑
            if (isOTCFundCode(pureCode)) {
                return FUND_PREFIX + pureCode
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