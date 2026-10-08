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

    /**
     * 操作关键字。
     *
     * OCR 对这几率的误认是有规律的，全部纳入匹配而不是当成未知行跳过：
     * - 券 → 卷 / 劵（字形极近，是最高频的误认）
     * - 入 → 人（买入被认成「买人」）
     * - 出 → 人（卖出被认成「卖人」）
     * - 字与字之间被插入空格（「证券买 入」）
     *
     * 不这么做的话，这类行会连一条告警都不留地消失——用户看到的就是「凭空少一行」。
     */
    private const val OP_PREFIX = """证\s*[券卷劵]\s*"""

    /**
     * 「证券」前缀**可以不存在**。
     *
     * 银河 App 不同页面/字号下操作列的文案不一样：对账单页是「证券买入-中证红利」，
     * 而成交明细页只有「买入-科创50」。真实长截图里两种格式还会同时出现。
     * 硬要求「证券」二字，整页的交易会一笔不剩地消失。
     */
    private const val OP_PREFIX_OPT = """(?:$OP_PREFIX)?"""
    private const val OP_BUY = """买\s*[入人]"""
    private const val OP_SELL = """卖\s*[出入]"""
    /** 方向捕获组：命中即「买」或「卖」的首字，据此归一化成买卖方向 */
    private const val OP_DIR = """($OP_BUY|$OP_SELL)"""

    /** 首行（名称 + 价格 + 金额都在同一行；列合并后就是这种形态） */
    private val FIRST_LINE_WITH_NUMBERS_REGEX = Regex(
        """($OP_PREFIX)?$OP_DIR\s*-?\s*(.+?)\s+([\d,]+\.?\d*)\s+(-?[\d,]+\.?\d*)\s*$"""
    )

    /** 首行（只有操作 + 名称，价格/金额在另外的列里） */
    private val FIRST_LINE_NAME_ONLY_REGEX = Regex(
        """($OP_PREFIX)?$OP_DIR\s*-?\s*(\S.*?)\s*$"""
    )

    /**
     * 裸方向词（没有「证券」前缀）时，名称里出现这些词说明这一行是 App 的导航/统计栏，
     * 不是交易。典型的是成交明细页的 tab 栏「明细 买入 卖出 条件单 看行情」——
     * 它含「买入」「卖出」，不设门槛就会被当成一笔交易塞进预览。
     */
    private val NOISE_NAME_REGEX = Regex("""条件单|看行情|明细|持仓|委托|撤单|自选|查询""")

    /** 名称里混进日期时间，说明这行其实是详情行被误判成了首行 */
    private val DATE_TIME_IN_NAME_REGEX = Regex("""\d{1,2}[-/月]\d{1,2}|\d{1,2}[:：]\d{2}""")

    /** 关键字独占一行（名称被 OCR 拆到下一行） */
    private val OPERATION_ONLY_REGEX = Regex("""$OP_PREFIX_OPT$OP_DIR\s*[-—－]?\s*""")

    /**
     * 详情行：方向? 日期 时间? 数量 费用?
     *
     * 每一段都放宽到 OCR 的实际输出形态：
     * - 日期后面可能跟着句点（"09-23."）
     * - 数量可能是小数（基金份额）
     * - 时间可能用全角冒号
     * - 费用列可能整列缺失（此时按 0 处理并提示，不能因此丢掉整笔）
     */
    private val SECOND_LINE_REGEX = Regex(
        """(?:(买|卖|其|更)\s*)?(\d{1,2})[-/月](\d{1,2})[日.。]?(?:\s+(\d{1,2})[:：](\d{2}))?\s+([\d,]+(?:\.\d+)?)(?:\s+([\d,]+\.?\d*))?"""
    )

    /** 找年份：截图顶部通常有"2026-09" */
    val YEAR_REGEX = Regex("""\b(20\d{2})[-/]\d{1,2}\b""")

    /** 逆回购/回购记录：不是证券买卖，跳过（仅统计，便于诊断截图格式变化） */
    private val REPO_REGEX = Regex("""逆回购|回购""")

    /** 首行关键字（用于识别"含关键字但格式没匹配上"的情况） */
    private val FIRST_LINE_KEYWORD_REGEX = Regex("""$OP_PREFIX(?:$OP_BUY|$OP_SELL)""")

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

    /**
     * 首行认出来了、明细行没认出来时的提示。
     * 这一笔会被保留下来交给用户补全，而不是丢弃。
     */
    const val ERR_DETAIL_MISSING = "未识别到成交明细（日期/数量/费用），请手工补全"

    /** 一个「首行」锚点的解析结果 */
    private data class Anchor(
        /** 已归一化的方向（OCR 误字「买人」同样归为买入） */
        val isBuy: Boolean,
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
            val line = normalized[i]

            // ① OCR 把首行和详情行压成了一行：先把详情段切出来，剩下的才当首行
            var anchorText = line
            var inlineDetail: MatchResult? = null
            SECOND_LINE_REGEX.find(line)?.let { m ->
                val head = line.substring(0, m.range.first).trim()
                if (head.isNotEmpty() && matchAnchor(head) != null) {
                    anchorText = head
                    inlineDetail = m
                }
            }

            var anchor = matchAnchor(anchorText)

            // ② 关键字独占一行（名称/价格被拆到了下一行）：并上下一行再试一次
            var consumed = 0
            if (anchor == null || anchor.name.isBlank()) {
                val next = normalized.getOrNull(i + 1)
                if (next != null && (OPERATION_ONLY_REGEX.matches(line) || anchor?.name?.isBlank() == true)) {
                    matchAnchor("$line $next")?.let { merged ->
                        if (merged.name.isNotBlank()) {
                            anchor = merged
                            anchorText = "$line $next"
                            consumed = 1
                        }
                    }
                }
            }

            if (anchor == null) {
                if (REPO_REGEX.containsMatchIn(line)) {
                    repoLines++
                } else if (FIRST_LINE_KEYWORD_REGEX.containsMatchIn(line)) {
                    // 含「证券买入/卖出」但整体没匹配 — 正则需扩展
                    logger("WARN 含关键字但首行正则未匹配: $line")
                }
                i++
                continue
            }
            // 后续只读。上面在 lambda 里赋值过，智能转换失效，这里显式取非空
            val matched = anchor!!

            if (inlineDetail != null) {
                result.add(buildTransaction(matched, inlineDetail!!, null, year, rowNo))
                rowNo++
                i += 1 + consumed
                continue
            }

            // ③ 常规形态：向后找紧跟的详情行
            val hit = findDetail(normalized, i + 1 + consumed, matched.isBuy)
            if (hit.index >= 0) {
                val detailMatch = SECOND_LINE_REGEX.find(normalized[hit.index])!!
                result.add(buildTransaction(matched, detailMatch, hit.fallbackPrice, year, rowNo))
                logger("第 ${i + 1}/${hit.index + 1} 行配对成功: $anchorText | ${normalized[hit.index]}")
                rowNo++
                i = hit.index + 1
                continue
            }

            // ④ 首行连成一片、详情行被整体推到后面（ML Kit 按列切块没合并回来）。
            //    就近搜索必然让后一笔抢走前一笔的明细，只能按出现顺序一一配对。
            if (hit.blockedByAnchor) {
                val block = pairAnchorBlock(normalized, i, year, rowNo, logger)
                if (block != null) {
                    result.addAll(block.first)
                    rowNo = block.second
                    i = block.third
                    continue
                }
            }

            // ⑤ 仍然配不上也不丢：留一条待补全记录。
            //    静默丢行是最糟的失败模式——用户只会觉得「少了一笔」却无从下手。
            logger("WARN 首行匹配但未找到详情行，已保留为待补全记录: $anchorText")
            result.add(buildIncomplete(matched, year, rowNo))
            rowNo++
            i += 1 + consumed
        }
        if (repoLines > 0) {
            logger("已跳过逆回购/回购相关 $repoLines 行（非证券买卖，不入库）")
        }
        logger("GalaxyParser 解析完成，共 ${result.size} 笔交易")
        return result
    }

    private data class DetailHit(
        /** 详情行下标；-1 = 没找到 */
        val index: Int,
        /** 列没合并成功时，从独占一行的文本里兜底取到的价格 */
        val fallbackPrice: Double?,
        /** 是被下一个首行挡住才停下的（说明出现了「列被拆开」的形态） */
        val blockedByAnchor: Boolean
    )

    /**
     * 从 [from] 开始向后找紧跟的详情行。
     *
     * 两条保护：
     * - 撞到下一个首行立刻停，绝不抢它的详情行；
     * - 详情行的方向前缀若与首行冲突（逆回购的明细是「其」），跳过继续找，
     *   否则证券买入会张冠李戴地用上逆回购的数量与费用。
     */
    private fun findDetail(normalized: List<String>, from: Int, isBuy: Boolean): DetailHit {
        var fallbackPrice: Double? = null
        var k = from
        while (k < normalized.size && k <= from + DETAIL_SEARCH_WINDOW) {
            val candidate = normalized[k]
            if (matchAnchor(candidate) != null) {
                return DetailHit(-1, fallbackPrice, blockedByAnchor = true)
            }
            val m = SECOND_LINE_REGEX.find(candidate)
            if (m != null && !directionConflicts(m.groupValues[1], isBuy)) {
                return DetailHit(k, fallbackPrice, blockedByAnchor = false)
            }
            // 列没合并成功时，价格会独占一行，且紧跟在名称行之后
            if (fallbackPrice == null && k == from && PURE_PRICE_REGEX.matches(candidate)) {
                fallbackPrice = candidate.replace(",", "").toDoubleOrNull()
            }
            k++
        }
        return DetailHit(-1, fallbackPrice, blockedByAnchor = false)
    }

    /** 详情行的方向前缀与首行的买卖方向是否矛盾（决定能不能配对） */
    private fun directionConflicts(dir: String, isBuy: Boolean): Boolean = when (dir) {
        "买" -> !isBuy
        "卖" -> isBuy
        // 「其」是逆回购等非证券交易的前缀，「更」是更正单，都不属于证券买卖
        "其", "更" -> true
        else -> false
    }

    /**
     * 「列被拆开」形态的配对：连续多个首行挤在一起，它们的详情行被整体推到后面。
     *
     * 这种排版下「谁离得近就配给谁」必然错位——第 1 个首行的明细其实排在第 n 个首行之后。
     * 唯一可靠的对应关系是「各自序列内的先后顺序」，所以按出现顺序一一配对，
     * 并给每笔打上「位置异常，请核对」，让用户在预览页复核。
     *
     * @return (解析出的交易, 下一个 rowNumber, 下一轮要从哪一行继续)；无法配对时返回 null
     */
    private fun pairAnchorBlock(
        normalized: List<String>,
        start: Int,
        year: Int,
        startRowNo: Int,
        logger: (String) -> Unit
    ): Triple<List<BrokerageImportService.ParsedTransaction>, Int, Int>? {
        val anchors = mutableListOf<Pair<Int, Anchor>>()
        var lastAnchor = -1
        var k = start
        while (k < normalized.size && k <= start + DETAIL_SEARCH_WINDOW) {
            val a = matchAnchor(normalized[k])
            if (a != null) {
                anchors.add(k to a)
                lastAnchor = k
            } else if (lastAnchor >= 0 && SECOND_LINE_REGEX.containsMatchIn(normalized[k])) {
                break // 详情段开始了
            }
            k++
        }
        if (anchors.size < 2 || lastAnchor < 0) return null

        val details = mutableListOf<Int>()
        var d = lastAnchor + 1
        while (d < normalized.size && d <= lastAnchor + DETAIL_SEARCH_WINDOW) {
            val line = normalized[d]
            if (matchAnchor(line) != null) break
            if (SECOND_LINE_REGEX.containsMatchIn(line)) details.add(d)
            d++
        }
        // 详情行不够分，说明不是「整段被拆开」，交给调用方按缺明细处理
        if (details.size < anchors.size) return null

        logger("WARN 检测到列块未合并（${anchors.size} 个首行连在一起），按出现顺序配对明细")
        val out = mutableListOf<BrokerageImportService.ParsedTransaction>()
        var rowNo = startRowNo
        anchors.forEachIndexed { idx, (_, a) ->
            val m = SECOND_LINE_REGEX.find(normalized[details[idx]])!!
            out.add(buildTransaction(a, m, null, year, rowNo, "明细行位置异常，请核对"))
            rowNo++
        }
        return Triple(out, rowNo, details[anchors.size - 1] + 1)
    }

    /**
     * 判断一行是否是「首行」并取出它的信息。
     * 先按"名称+价格+金额俱全"匹配，失败再退到"只有名称"的形态。
     */
    private fun matchAnchor(line: String): Anchor? {
        FIRST_LINE_WITH_NUMBERS_REGEX.find(line)?.let { m ->
            // group1=「证券」前缀(可空) group2=方向 group3=名称 group4=价格 group5=金额
            val name = cleanName(m.groupValues[3])
            if (isTrustworthyName(m.groupValues[1], name)) {
                return Anchor(
                    isBuy = m.groupValues[2].contains("买"),
                    name = name,
                    price = m.groupValues[4].replace(",", "").toDoubleOrNull(),
                    amount = m.groupValues[5].replace(",", "").toDoubleOrNull()
                )
            }
        }
        FIRST_LINE_NAME_ONLY_REGEX.find(line)?.let { m ->
            // 名称后面若误粘了数字（价格只识别到一半），把它们摘掉
            val raw = m.groupValues[3].replace(TRAILING_NUMBER_TOKEN_REGEX, "")
            val name = cleanName(raw)
            if (isTrustworthyName(m.groupValues[1], name)) {
                return Anchor(
                    isBuy = m.groupValues[2].contains("买"),
                    name = name,
                    price = null,
                    amount = null
                )
            }
        }
        return null
    }

    /**
     * 名称可不可信。
     *
     * 带「证券」前缀的行直接采信——前缀本身就是很强的证据。
     * 只有裸「买入/卖出」时才额外校验：银河成交明细页的 tab 栏
     * 「明细 买入 卖出 条件单 看行情」同样含方向词，而详情行
     * 「卖 09-22 13:00 700 0.10」也能被裸正则啃成「名称=09-22 13:00」。
     * 这两类都必须挡在门外，否则会凭空多出假交易。
     */
    private fun isTrustworthyName(prefix: String, name: String): Boolean {
        if (name.isBlank()) return false
        if (prefix.isNotBlank()) return true
        return !NOISE_NAME_REGEX.containsMatchIn(name) && !DATE_TIME_IN_NAME_REGEX.containsMatchIn(name)
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
        // 后顾断言排除了「日期后面的句点」：'09-23. 10:48' 若被粘成 '09-23.10:48'，
        // 日期就整个消失了，这笔会连一条告警都留不下地丢失。
        s = s.replace(Regex("""(?<![-/月]\d)(\d)\s*\.\s*(\d)""")) { m ->
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

    /**
     * 明细行没找到时的兜底记录。
     *
     * 关键：宁可给用户一条「数据不全、待补全」的红字行，也不要让这笔凭空消失——
     * 首行已经把名称、价格、金额都认出来了，这些信息对用户核对仍然有用。
     * 这条记录的 errors 非空，[BrokerageImportService.commit] 不会把它写进库。
     */
    private fun buildIncomplete(
        anchor: Anchor,
        year: Int,
        rowNo: Int
    ): BrokerageImportService.ParsedTransaction {
        val errors = mutableListOf<String>(ERR_DETAIL_MISSING)
        if (anchor.price == null) errors.add("价格识别异常：未识别到有效价格")
        errors.add(BrokerageImportService.ERR_SYMBOL_REQUIRED)
        return BrokerageImportService.ParsedTransaction(
            date = "", // 日期在明细行里，此处无从得知；留给用户在预览页补
            symbol = "",
            name = anchor.name,
            type = if (anchor.isBuy) TransactionType.BUY.value else TransactionType.SELL.value,
            quantity = 0.0,
            price = anchor.price ?: 0.0,
            fee = 0.0,
            account = "银河证券",
            rowNumber = rowNo,
            errors = errors,
            isDuplicate = false,
            skip = false
        )
    }

    private fun buildTransaction(
        anchor: Anchor,
        secondMatch: MatchResult,
        fallbackPrice: Double?,
        year: Int,
        rowNo: Int,
        extraError: String? = null
    ): BrokerageImportService.ParsedTransaction {
        val month = secondMatch.groupValues[2].padStart(2, '0')
        val day = secondMatch.groupValues[3].padStart(2, '0')
        // groupValues[4], [5] 是时间（小时:分钟），可选，不需要
        val quantityStr = secondMatch.groupValues[6]
        val feeStr = secondMatch.groupValues[7]

        // 方向以「证券买入/证券卖出」为准：详情行的方向前缀可能缺失或是 OCR 误字
        val type = if (anchor.isBuy) {
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
        // 费用列整列缺失时按 0 处理，但要说出来——静默当 0 会让成本悄悄算少
        if (feeStr.isBlank()) errors.add("费用未识别，已按 0 处理，请核对")
        if (extraError != null) errors.add(extraError)
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
