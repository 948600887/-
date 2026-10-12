package com.example.stockdiary.data

import com.example.stockdiary.utils.StockCodeManager

/**
 * 「中国结算 · 一码通持有查询」持仓截图解析器（纯函数，可直接 JVM 单测）。
 *
 * ## 截图形态
 *
 * ```
 * 数据日期 2026-10-09
 * 名称/代码          持有数量 ⓘ        其中冻结数量
 * 黄金LOF                262                 -
 * 160719
 * 国投白银LOF           1264                 -
 * 161226
 * ```
 *
 * 每条持仓的名称在**上**、6 位代码在**下**（都左对齐），数量在中间列。
 * 对真实截图（1080×3113）做墨迹投影量出来的垂直位置：
 *
 * ```
 * 名称「R东方1」  y 1358–1393
 * 数量「100」      y 1386–1413   ← 与名称仅重叠 26%，够不上 0.6 的同行阈值
 * 冻结「-」        y 1396–1401   ← 与数量几乎完全重合
 * 代码「400264」   y 1416–1440
 * ```
 *
 * 于是行聚合出来的形态很可能是「名称 / `100 -` / 代码」三行——
 * 数量与冻结列天然同行，见 [isPureQuantity] 的说明。
 * 数量究竟是并进名称行、并进代码行、还是自己一行，取决于 OCR 把它的
 * 垂直中心算到了哪边，三种都得认（见 [parse]）。
 *
 * ## 为什么要单独写一个解析器
 *
 * 现有的 [GalaxyStatementParser] 认的是**交割单**（流水）：「证券买入-名称 价格 金额 /
 * 买 日期 数量 费用」。持仓页是完全不同的表格，那边一行也配不上。
 * 而且持仓是**快照**（当前余额），不是流水，不需要按日期回放。
 *
 * ## 比交割单省事的地方
 *
 * 持仓页**带证券代码**。交割单没有代码，得靠 [SymbolResolver] 凭名称去猜，
 * 还得小心同名多代码（沪深300ETF 有 sh510300/sz159919 两只）填错。
 * 这里代码是现成的，直接可用。
 */

/**
 * 一条解析出来的持仓。
 *
 * [rawName] / [rawQuantity] 保留 OCR 原样，供预览页核对——
 * 数量错一位资产就差一个数量级，必须让用户看得见原始值。
 */
data class ParsedHolding(
    /** 证券名称，已去掉数量与杂字符 */
    val name: String,
    /** 6 位证券代码 */
    val symbol: String,
    /** 持有数量（中文单位已展开，见 [parseChineseQuantity]） */
    val quantity: Double,
    /** 该记录在还原行里的行号（从 1 起），用于定位与报错 */
    val lineNumber: Int,
    /** 数量在截图里的原始写法（如 "1.44万"），预览页展示用 */
    val rawQuantity: String
) {
    /** 按代码前缀推断的类别：ETF / LOF / 股票 / 场内基金…… */
    val category: StockCodeManager.CodeCategory get() = StockCodeManager.classifyCode(symbol)
}

/** 解析结果：[holdings] 为成功配对的记录，[skipped] 为认得出代码却没配全的行（供诊断）。 */
data class HoldingSnapshotResult(
    val holdings: List<ParsedHolding>,
    /** 数据日期，如 "2026-10-09"；截图里没有则为 null */
    val dataDate: String?,
    /** 有代码但缺名称或数量的行，原样保留以便在预览页提示「这几行没读懂」 */
    val incomplete: List<String>
)

object HoldingSnapshotParser {

    /**
     * 表头、Tab 名、功能入口等**不是持仓**的行里会出现这些词。
     * 它们本身不含 6 位代码，本来就会被跳过；但万一某行既像名称又含这些词
     * （例如 OCR 把表头和第一条挤成一行），用它兜一道，别把表头当证券名。
     */
    private val HEADER_KEYWORDS = listOf(
        "名称", "代码", "持有数量", "冻结", "数据日期", "一码通",
        "查询", "余额", "变更", "配号", "中签", "放弃认购", "股息红利", "所得税"
    )

    /** 数量里的中文单位。截图实测出现过「1.44万」。 */
    private val CHINESE_UNITS = mapOf(
        "亿" to 100_000_000.0,
        "万" to 10_000.0,
        "千" to 1_000.0
    )

    private val SIX_DIGIT = Regex("""\d{6}""")

    /** 「数据日期 2026-10-09」/「2026-10-09」 */
    private val DATA_DATE_REGEX = Regex("""(20\d{2})-(\d{2})-(\d{2})""")

    /**
     * 解析还原后的视觉行。
     *
     * 用状态机而不是按固定行号取：数量到底落在名称行、代码行还是自己一行，
     * 取决于 OCR 把它的垂直中心算到了哪边，写死行号一旦差半行就整段错位。
     */
    fun parse(lines: List<String>): HoldingSnapshotResult {
        val holdings = mutableListOf<ParsedHolding>()
        val incomplete = mutableListOf<String>()
        var dataDate: String? = null

        // 已看到、还没配上代码的候选：名称 + 数量（任一可能为 null）
        var pendingName: String? = null
        var pendingQuantity: Double? = null
        var pendingRawQuantity: String? = null
        var pendingLine = 0

        lines.forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = normalize(rawLine)
            if (line.isBlank()) return@forEachIndexed

            if (dataDate == null) {
                DATA_DATE_REGEX.find(line)?.let { dataDate = it.value }
            }

            val symbol = extractSymbol(line)
            if (symbol != null) {
                // 代码行。数量可能跟在代码后面同一行（"160719 262"）。
                val inline = extractNumbersAfterSymbol(line, symbol)
                val quantity = inline?.first ?: pendingQuantity
                val rawQuantity = inline?.second ?: pendingRawQuantity
                val name = pendingName

                // quantity >= 0 是硬要求：Asset 构造器里有 require(balance >= 0)，
                // 负数会在落库时抛异常，把整次导入打断成一句「数量不能为负数」，
                // 用户根本看不出是哪一行的问题。这里提前判掉，让它走 incomplete 报出来。
                if (name != null && quantity != null && quantity.isFinite() && quantity >= 0.0) {
                    holdings.add(
                        ParsedHolding(
                            name = name,
                            symbol = symbol,
                            quantity = quantity,
                            lineNumber = pendingLine.takeIf { it > 0 } ?: lineNo,
                            rawQuantity = rawQuantity ?: formatPlain(quantity)
                        )
                    )
                } else {
                    // 有代码缺名称或数量：十有八九是 OCR 漏了一行，报出来比静默丢掉好
                    incomplete.add(rawLine.trim())
                }
                pendingName = null
                pendingQuantity = null
                pendingRawQuantity = null
                pendingLine = 0
                return@forEachIndexed
            }

            // 非代码行。三种可能：名称行（可能带数量）、单独的数量行、以及其它噪声。
            val quantityToken = extractQuantityToken(line)
            val namePart = stripQuantity(line)

            if (isPureQuantity(line)) {
                // 数量独占一行："262" / "1.44万"
                if (quantityToken != null) {
                    pendingQuantity = quantityToken
                    pendingRawQuantity = quantityTokenRaw(line)
                }
                return@forEachIndexed
            }

            if (looksLikeName(namePart)) {
                pendingName = namePart
                pendingLine = lineNo
                // 名称行里也带数量（"黄金LOF 262"）：先记下，代码行若自带数量会覆盖它
                pendingQuantity = quantityToken
                pendingRawQuantity = quantityToken?.let { quantityTokenRaw(line) }
            }
            // 其余（表头、功能入口、数据日期行）直接忽略
        }

        // 收尾：最后还有没配上代码的候选，说明截图被裁在中间，同样报出来
        // （用 ?.let 而不是 if != null：pendingName 被上面的 lambda 改写过，
        //   编译器不给它做智能转换）
        pendingName?.let { incomplete.add(it) }

        return HoldingSnapshotResult(
            holdings = holdings,
            dataDate = dataDate,
            incomplete = incomplete
        )
    }

    /** 去掉 OCR 常见杂字符与多余空格，并把「1.44 万」这种被空格拆开的单位合回来 */
    internal fun normalize(line: String): String {
        var s = line.replace('\u00A0', ' ').trim()
        for (unit in CHINESE_UNITS.keys) {
            s = s.replace(" $unit", unit)
        }
        return s.replace(Regex("""\s{2,}"""), " ").trim()
    }

    /**
     * 取出 6 位证券代码。
     *
     * 只在**行首**认定：截图里代码列是左对齐的，代码永远出现在行的最前面
     * （"160719" 或最多带一个市场前缀）。若允许任意位置匹配，
     * 「持有数量」列里恰好 6 位的数字（买 123456 股并非不可能）会被误当成代码。
     */
    internal fun extractSymbol(line: String): String? {
        val head = line.trimStart().takeWhile { !it.isWhitespace() }
        val digits = head.filter { it.isDigit() }
        if (digits.length != 6 || !SIX_DIGIT.matches(digits)) return null
        // 别把表头数字或日期当代码：日期含 "-"，已被上面过滤；
        // 这里再要求 6 位且首位是合法市场前缀
        if (digits.first() !in "01345689") return null
        return digits
    }

    /**
     * 从代码行里取「代码之后」的数量，返回（数值, 原始写法）。
     * 没有则返回 null，交给名称行/独立数量行提供。
     */
    private fun extractNumbersAfterSymbol(line: String, symbol: String): Pair<Double, String>? {
        val idx = line.indexOf(symbol)
        if (idx < 0) return null
        val tail = line.substring(idx + symbol.length).trim()
        if (tail.isBlank()) return null
        val token = firstQuantityToken(tail) ?: return null
        return token.second to token.first
    }

    /**
     * 判断这一行是「只有数量」的行（"262" / "1.44万" / "-" / "156 -"）。
     *
     * 不能写成「去掉数量后为空」——裸数字串前面没有空白，
     * [stripQuantity] 的尾部数字规则匹配不到它，会原样留下，判定就永远为假。
     * 所以直接从 token 本身入手：**允许夹带冻结列的横杠，但不允许出现文字**。
     *
     * ## 「156 -」为什么必须认（真实截图实测）
     *
     * 中国结算持仓页一行有四列：名称（左，偏上）、代码（左，偏下）、持有数量（中）、
     * 其中冻结数量（右）。对真实截图（1080×3113）做墨迹投影量出来的垂直位置是：
     *
     * ```
     * 名称「R东方1」  y 1358–1393
     * 数量「100」      y 1386–1413   ← 与名称只重叠 26%，够不上 0.6 的同行阈值
     * 冻结「-」        y 1396–1401   ← 与数量几乎完全重合
     * 代码「400264」   y 1416–1440
     * ```
     *
     * 也就是说：**数量与冻结横杠天然同处一行**，行聚合把它们拼成 "100 -" 是拼对了；
     * 名称、数量、代码则是三条独立的行。若这里要求「整行只有一个 token」，
     * 真机上只要 ML Kit 认出了那个孤零零的 "-"，14 条持仓会**一条都进不来**
     * （全变成「有代码没数量」）。认不认那个横杠取决于 OCR，不能赌。
     */
    internal fun isPureQuantity(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (t.all { it in "-—–" }) return true
        val tokens = t.split(' ', '\t').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        var hasQuantity = false
        for (token in tokens) {
            // 6 位代码已在 parse() 里先行拦截，这里再挡一次，避免把代码当数量
            if (SIX_DIGIT.matches(token)) return false
            // 冻结列的横杠：跳过，但它自己不算数量
            if (token.all { it in "-—–" }) continue
            if (parseChineseQuantity(token) == null) return false
            hasQuantity = true
        }
        return hasQuantity
    }

    /** 取行里的数量（数值, 原始写法）；没有则 null */
    internal fun extractQuantityToken(line: String): Double? = firstQuantityToken(line)?.second

    /** 数量在截图里的原始写法（"1.44万"） */
    private fun quantityTokenRaw(line: String): String? = firstQuantityToken(line)?.first

    /**
     * 找行里第一个像数量的片段。
     *
     * 注意**必须跳过 6 位代码**，否则 "160719 262" 会把代码本身当成数量。
     */
    private fun firstQuantityToken(text: String): Pair<String, Double>? {
        val tokens = text.trim().split(' ', '\t').filter { it.isNotBlank() }
        for (token in tokens) {
            if (SIX_DIGIT.matches(token)) continue // 是代码不是数量
            parseChineseQuantity(token)?.let { return token to it }
        }
        return null
    }

    /**
     * 解析数量，支持中文单位。
     *
     * 「1.44万」= 14400 —— 截图里真实出现过。按字面读成 1.44 的话，
     * 持仓会差一万倍，而且界面上看不出异常（1.44 万分之像个合法的持仓数），
     * 这是这个解析器里最危险的一处，所以单独抽出来并有专门单测。
     */
    internal fun parseChineseQuantity(token: String): Double? {
        val t = token.trim()
        if (t.isEmpty()) return null
        val unit = CHINESE_UNITS.entries.firstOrNull { t.endsWith(it.key) }
        if (unit != null) {
            val numberPart = t.dropLast(unit.key.length).trim()
            if (numberPart.isEmpty()) return null
            val base = numberPart.toDoubleOrNull() ?: return null
            return base * unit.value
        }
        // 「-」表示没有：截图里「其中冻结数量」列全是这个
        if (t.all { it in "-—–" }) return null
        return t.toDoubleOrNull()
    }

    /**
     * 去掉数量、单位与冻结列的横杠，留下名称。
     *
     * 顺序要紧：**先剥横杠，再剥尾部裸数字**。
     * "黄金LOF 262 -" 若先剥数字，末尾是 "-"，那条「以数字结尾」的规则匹配不上，
     * 262 会留在名称里变成「黄金LOF 262」。
     */
    internal fun stripQuantity(line: String): String {
        var s = line
        // 先去掉带单位的部分，再去掉裸数字
        for (unit in CHINESE_UNITS.keys) {
            s = s.replace(Regex("""[\d.]+${Regex.escape(unit)}"""), " ")
        }
        // 冻结列落到本行的横杠：只认**独立成 token** 的横杠，
        // 否则「华泰-XX」「1-3月」这类名字里的连字符会被误伤
        s = s.replace(Regex("""(^|\s)[-—–]+(?=\s|$)"""), " ")
        s = s.replace(Regex("""\s\d[\d,]*\.?\d*\s*$"""), " ")
        return s.trim()
    }

    /**
     * 判断去掉数量后的剩余部分像不像证券名称。
     *
     * 要求含中文或字母（代码行、纯数字行已被前面分派），
     * 且不能命中表头关键词——否则「名称/代码」「持有数量」会被当成一只叫这名的证券。
     */
    internal fun looksLikeName(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.none { it.isLetter() || it.code in 0x4E00..0x9FFF }) return false
        if (HEADER_KEYWORDS.any { text.contains(it) }) return false
        return true
    }

    private fun formatPlain(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
