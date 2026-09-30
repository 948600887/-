package com.example.stockdiary.data

import com.example.stockdiary.database.TransactionType
import kotlin.math.abs
import kotlin.math.max

/**
 * 银河证券对账单截图解析器（纯函数，不依赖 Context/ML Kit，可单元测试）。
 *
 * ## 真实截图的结构
 *
 * 对账单是一张表格，一笔交易占**两行**，三列分别是「操作」「价格/数量」「金额/费用」：
 *
 * ```
 * 证券买入-中证红利      1.546      -1,082.30     ← 第 1 行：名称 / 价格 / 金额
 * 09-24 11:09             700         0.10        ← 第 2 行：日期 / 数量 / 费用
 * ```
 *
 * 注意：第 2 行的方向前缀（"买"/"卖"）**可能不存在**，名称与"证券买入"之间的连字符
 * **也可能不存在**（如「证券买入科创50」）。这两种形态都必须支持。
 *
 * ## 容错设计（OCR 输出并不干净）
 *
 * 1. 全角字符——全角数字（１２３）、全角减号（－）、全角逗号（，）、全角空格（　）。
 * 2. 数字被拆开——"1 . 546" / "1. 546"。
 * 3. 名称被插入空格——"中证 红利"。
 * 4. 详情行前夹了噪声行——允许向后看几行；但绝不能抢下一笔交易的详情行。
 * 5. 列错位——金额与「价格×数量」对不上时显式报错，让用户核对而不是默默写错数据。
 */
object GalaxyStatementParser {

    /** 首行（名称 + 价格 + 金额都在同一行；列合并后就是这种形态） */
    private val FIRST_LINE_WITH_NUMBERS_REGEX = Regex(
        """(证券买入|证券卖出)\s*-?\s*(.+?)\s+([\d,]+\.?\d*)\s+(-?[\d,]+\.?\d*)\s*$"""
    )

    /** 首行（只有操作 + 名称，价格/金额在另外的列里） */
    private val FIRST_LINE_NAME_ONLY_REGEX = Regex(
        """(证券买入|证券卖出)\s*-?\s*(\S.*?)\s*$"""
    )

    /** 详情行：方向? 日期 时间? 数量 费用 */
    private val SECOND_LINE_REGEX = Regex(
        """(?:(买|卖|其|更)\s*)?(\d{1,2})[-/月](\d{1,2})日?(?:\s+(\d{1,2}):(\d{2}))?\s+([\d,]+)\s+([\d,]+\.?\d*)"""
    )

    /** 找年份：截图顶部通常有"2026-09" */
    val YEAR_REGEX = Regex("""\b(20\d{2})[-/]\d{1,2}\b""")

    /** 逆回购/回购记录：不是证券买卖，跳过（仅统计，便于诊断截图格式变化） */
    private val REPO_REGEX = Regex("""逆回购|回购""")

    /** 首行关键字（用于识别"含关键字但格式没匹配上"的情况） */
    private val FIRST_LINE_KEYWORD_REGEX = Regex("""证券(买入|卖出)""")

    /** 独占一行的纯价格（列没合并成功时的兜底来源）；带负号的金额不算 */
    private val PURE_PRICE_REGEX = Regex("""[\d,]+\.\d+""")

    /** 名称尾部误粘上的数字串（如 "中证红利 1.546"） */
    private val TRAILING_NUMBER_TOKEN_REGEX = Regex("""(?:\s+[\d,]+\.?\d*)+$""")

    /** 名称内部的空白 */
    private val INNER_BLANK_REGEX = Regex("""\s+""")

    /** 首行之后最多向后看几行去找详情行 */
    private const val DETAIL_SEARCH_WINDOW = 4

    /**
     * 金额与「价格×数量」的允许偏差。
     * 对不上通常意味着表格列在 OCR 时错位了——宁可报错让用户核对，也不要默默写错数据。
     */
    private const val AMOUNT_TOLERANCE_RATIO = 0.02

    /**
     * 金额偏差的绝对容差（元）。
     * 券商有「最低 5 元佣金」：一笔 300 元的小额买入佣金占比可达 1.7%，
     * 再加印花税/过户费就超过 2% 的相对容差，真实交易会被误判成列错位。
     * 所以相对容差之外再放一个绝对容差，两者取大。
     */
    private const val AMOUNT_TOLERANCE_ABS = 6.0

    /** 一个「首行」锚点的解析结果 */
    private data class Anchor(
        val operation: String,
        val name: String,
        val price: Double?,
        val amount: Double?
    )

    /**
     * 解析银河对账单。
     *
     * @param lines  已按视觉行还原的行文本（同一行的多列已合并）
     * @param year   年份（截图通常不含年份，由调用方推断后传入）
     * @param logger 可选日志回调。单元测试不传时为 no-op，保证 parser 不依赖 Android 框架。
     */
    fun parse(
        lines: List<String>,
        year: Int,
        logger: (String) -> Unit = {}
    ): List<BrokerageImportService.ParsedTransaction> {
        logger("GalaxyParser 收到 ${lines.size} 行, year=$year")
        val normalized = lines.map { normalizeLine(it) }
        val result = mutableListOf<BrokerageImportService.ParsedTransaction>()
        var rowNo = 1
        var repoLines = 0
        var i = 0
        while (i < normalized.size) {
            val anchor = matchAnchor(normalized[i])
            if (anchor == null) {
                if (REPO_REGEX.containsMatchIn(normalized[i])) {
                    repoLines++
                } else if (FIRST_LINE_KEYWORD_REGEX.containsMatchIn(normalized[i])) {
                    // 含「证券买入/卖出」但整体没匹配 — 正则需扩展
                    logger("WARN 含关键字但首行正则未匹配: ${normalized[i]}")
                }
                i++
                continue
            }

            var detailIdx = -1
            var fallbackPrice: Double? = null
            var k = i + 1
            while (k < normalized.size && k <= i + DETAIL_SEARCH_WINDOW) {
                val candidate = normalized[k]
                // 撞到下一笔的首行就立刻停，绝不抢它的详情行（否则数量/费用会张冠李戴）
                if (matchAnchor(candidate) != null) break
                if (SECOND_LINE_REGEX.containsMatchIn(candidate)) {
                    detailIdx = k
                    break
                }
                // 列没合并成功时，价格会独占一行，且紧跟在名称行之后
                if (fallbackPrice == null && k == i + 1 && PURE_PRICE_REGEX.matches(candidate)) {
                    fallbackPrice = candidate.replace(",", "").toDoubleOrNull()
                }
                k++
            }

            if (detailIdx < 0) {
                logger("WARN 首行匹配但未找到详情行（日期/数量/费用）。首行=${normalized[i]}")
                i++
                continue
            }

            val detailMatch = SECOND_LINE_REGEX.find(normalized[detailIdx])!!
            result.add(buildTransaction(anchor, detailMatch, fallbackPrice, year, rowNo))
            logger("第 ${i + 1}/${detailIdx + 1} 行配对成功: ${normalized[i]} | ${normalized[detailIdx]}")
            rowNo++
            i = detailIdx + 1
        }
        if (repoLines > 0) {
            logger("已跳过逆回购/回购相关 $repoLines 行（非证券买卖，不入库）")
        }
        logger("GalaxyParser 解析完成，共 ${result.size} 笔交易")
        return result
    }

    /**
     * 判断一行是否是「首行」并取出它的信息。
     * 先按"名称+价格+金额俱全"匹配，失败再退到"只有名称"的形态。
     */
    private fun matchAnchor(line: String): Anchor? {
        FIRST_LINE_WITH_NUMBERS_REGEX.find(line)?.let { m ->
            return Anchor(
                operation = m.groupValues[1],
                name = cleanName(m.groupValues[2]),
                price = m.groupValues[3].replace(",", "").toDoubleOrNull(),
                amount = m.groupValues[4].replace(",", "").toDoubleOrNull()
            )
        }
        FIRST_LINE_NAME_ONLY_REGEX.find(line)?.let { m ->
            // 名称后面若误粘了数字（价格只识别到一半），把它们摘掉
            val raw = m.groupValues[2].replace(TRAILING_NUMBER_TOKEN_REGEX, "")
            return Anchor(
                operation = m.groupValues[1],
                name = cleanName(raw),
                price = null,
                amount = null
            )
        }
        return null
    }

    /** 去掉名称里的空白（OCR 常在名称中间插空格） */
    private fun cleanName(raw: String): String =
        raw.replace(INNER_BLANK_REGEX, "").replace("\u00A0", "").trim()

    /**
     * 行归一化：把 OCR 常见噪声抹平到正则能处理的形态。
     *
     * 只做「确定安全」的替换，不做通用去空格——否则会把相邻的两个数字
     * （如 "700 0.10"）粘成一个数，那属于静默数据损坏，比解析失败更危险。
     */
    private fun normalizeLine(raw: String): String {
        var s = toHalfWidth(raw)
        // 小数点两侧被拆开：'1 . 546' / '1. 546' / '1 .546' → '1.546'
        s = s.replace(Regex("""(\d)\s*\.\s*(\d)""")) { m ->
            m.groupValues[1] + "." + m.groupValues[2]
        }
        // 千分位逗号两侧被拆开：'1 , 082' → '1,082'（限定 3 位数字，避免吞掉分隔两个数字的空格）
        s = s.replace(Regex("""(\d)\s*,\s*(\d{3})(?!\d)""")) { m ->
            m.groupValues[1] + "," + m.groupValues[2]
        }
        // 连续空白（含制表符、不换行空格）压成单个空格，并去掉首尾空白
        s = s.replace(Regex("""[ \t\u00A0]+"""), " ").trim()
        return s
    }

    /** 全角 → 半角；全角空格（U+3000）转普通空格 */
    private fun toHalfWidth(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch == '\u3000' -> sb.append(' ')
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun buildTransaction(
        anchor: Anchor,
        secondMatch: MatchResult,
        fallbackPrice: Double?,
        year: Int,
        rowNo: Int
    ): BrokerageImportService.ParsedTransaction {
        val month = secondMatch.groupValues[2].padStart(2, '0')
        val day = secondMatch.groupValues[3].padStart(2, '0')
        // groupValues[4], [5] 是时间（小时:分钟），可选，不需要
        val quantityStr = secondMatch.groupValues[6]
        val feeStr = secondMatch.groupValues[7]

        // 方向以「证券买入/证券卖出」为准：详情行的方向前缀可能缺失或是 OCR 误字
        val type = if (anchor.operation.contains("买入")) {
            TransactionType.BUY.value
        } else {
            TransactionType.SELL.value
        }

        val price = anchor.price ?: fallbackPrice ?: 0.0
        val quantity = quantityStr.replace(",", "").toDoubleOrNull() ?: 0.0
        val fee = feeStr.replace(",", "").toDoubleOrNull() ?: 0.0

        val errors = mutableListOf<String>()
        if (price <= 0) errors.add("价格识别异常：未识别到有效价格")
        if (quantity <= 0) errors.add("数量识别异常: $quantityStr")
        // 交叉校验：金额应当约等于 价格×数量（仅差手续费）。差太多说明列错位了。
        // 容差取「相对 2%」与「绝对 6 元」的较大者——小额交易触发最低 5 元佣金时不会被误拦
        val amount = anchor.amount
        if (amount != null && price > 0 && quantity > 0) {
            val expected = price * quantity
            val actual = abs(amount)
            if (actual > 0 && abs(expected - actual) > max(AMOUNT_TOLERANCE_ABS, actual * AMOUNT_TOLERANCE_RATIO)) {
                errors.add("金额与价格×数量不符，可能列错位，请核对")
            }
        }
        // 截图无代码，必须用户补填
        errors.add(BrokerageImportService.ERR_SYMBOL_REQUIRED)

        return BrokerageImportService.ParsedTransaction(
            date = "$year-$month-$day",
            symbol = "", // 截图无代码，留空待补
            name = anchor.name,
            type = type,
            quantity = quantity,
            price = price,
            fee = fee,
            account = "银河证券",
            rowNumber = rowNo,
            errors = errors,
            isDuplicate = false,
            skip = false
        )
    }
}
