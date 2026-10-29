package com.example.stockdiary.data.importer

import com.example.stockdiary.data.dividend.DividendDateValidator
import com.example.stockdiary.utils.NumberFormatUtils

/**
 * 「基金E账户」持仓截图解析器（纯函数，可直接 JVM 单测）。
 *
 * ## 什么时候会用到它
 *
 * 正常情况下应该走 [FundEStatementParser]——那要的是导出文件，代码是独立一列、
 * 一个字符都不会错。只有导出功能不可用、或者手上只有截图时，才退回到这条 OCR 的路。
 *
 * ## 卡片长什么样（对真实截图 1024×17256 做像素测量得出的结构）
 *
 * 每条持仓是一张白底卡片，还原成视觉行后是这样：
 *
 * ```
 * 中银如意宝货币A (004502)              ← 名称 + (代码)
 * 持有份额    参考净值    资产情况        ← 表头行，三列同一水平位置
 * 10,025.31   1.0000     10,025.31      ← 数值行，顺序固定
 * ```
 *
 * 长名称会换行，代码被挤到下一行单独成段（约 4~8 条这样）：
 *
 * ```
 * 兴全商业模式优选混合型证券投资基金(LOF)
 * (163415)
 * 持有份额    参考净值    资产情况
 * 84.75       4.7090      399.09
 * ```
 *
 * 卡片间距 310~373px 不等。整张图能装多少条取决于截图是否截全——
 * 实测一张长截图只装得下约 50 张卡片，而导出明细有 136 条，
 * 所以截图这条路**天然不完整**，这也是为什么优先用导出文件。
 *
 * ## 四个必须处理的坑（都是实测出来的）
 *
 * 1. **括号全角半角混用**：既有 `汇添富货币市场基金A（519518）`（全角）也有
 *    `兴全商业模式优选混合证券投资基金(LOF)`（半角）。
 * 2. **名称里自带括号**：`(LOF)` 那个不是代码。判定只能是「括号内恰好 6 位数字」。
 * 3. **长名称换行 → 代码单独成一行**，得与上一行的名称配对，不能写死行号。
 * 4. **数值带千分位**，且**净值 4 位小数、份额 2 位小数**。
 *
 * ## 为什么没有复用 HoldingSnapshotParser
 *
 * 一码通那张是「名称一行、代码一行、数量一行」三条独立行，按代码配对；
 * 这里是「名称带代码、表头、数值」三行一组，按卡片切。
 * 状态机不一样，硬凑只会两边都错。
 *
 * ## 为什么以表头行为锚，而不是以「名称」为锚
 *
 * 名称长什么样都可能——带不带括号、换不换行、有没有 (LOF) 后缀；
 * 而表头行只有一种样子：三个列名凑齐。所以**由表头行定位一张卡片**，
 * 再往上找名称与代码、往下取数值，比维护一个「当前看到哪张卡片」的状态机可靠得多。
 *
 * 代价是名称只取单行（见 [readNameAndCode] 的说明）。
 */
object FundSnapshotParser {

    /** 表头行的三个列名。三个都出现才认这是卡片表头 */
    private val CARD_HEADER = listOf("持有份额", "参考净值", "资产情况")

    private val CODE_IN_PARENTHESIS = Regex("""[(（](\d{6})[)）]""")

    /** 行里的数字，允许千分位：「10,025.31」「1.0000」 */
    private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")

    /** 「数据日期：2026年10月08日」「2026/10/8」 */
    private val DATA_DATE_REGEX = Regex("""(20\d{2})[-/年](\d{1,2})[-/月](\d{1,2})日?""")

    fun parse(lines: List<String>): HoldingSnapshotResult {
        val holdings = ArrayList<ParsedHolding>()
        val incomplete = ArrayList<String>()
        var dataDate: String? = null

        val normalized = lines.map { normalize(it) }

        normalized.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed

            if (dataDate == null) {
                // 形态合法还要日历真实：「2026年13月45日」这种 OCR 脏数据不能当数据日期
                // 展示（与分红除息日同一个坑，见 DividendDateValidator）。不合法就不占位，
                // 后面行里再出现真日期时仍有机会被认出来。
                DATA_DATE_REGEX.find(line)?.let { m ->
                    val (y, mo, d) = m.destructured
                    val normalized = "%04d-%02d-%02d".format(y.toInt(), mo.toInt(), d.toInt())
                    if (DividendDateValidator.normalizeOrNull(normalized) != null) dataDate = m.value
                }
            }

            // 三个列名凑齐才算表头行。少一个都可能是普通文本
            if (!CARD_HEADER.all { line.contains(it) }) return@forEachIndexed

            // 数值行 = 表头下面第一个非空行
            val valueLine = normalized.drop(index + 1).firstOrNull { it.isNotBlank() }
                ?: return@forEachIndexed
            val numbers = NUMBER.findAll(valueLine)
                .map { it.value.replace(",", "") }
                .mapNotNull { it.toDoubleOrNull() }
                .toList()
            // 一张卡片恰好三个数：份额 | 净值 | 资产。
            // 少于三个说明这行不是数值行（可能是下一张卡片的名称被误当表头），
            // 以及多于三个说明列序或格式变了——两种都宁可放过，别硬猜
            if (numbers.size != 3) return@forEachIndexed

            val card = readNameAndCode(normalized, index)
            if (card == null) {
                // 认得出表头却找不到名称/代码：多半是截图被裁掉了一半
                incomplete.add(valueLine)
                return@forEachIndexed
            }

            val share = numbers[0]
            val nav = numbers[1]
            // 基金份额必须为正：场外基金不存在「负份额」这种合法状态（负余额只发生在币安加密货币，
            // 由 BinanceImportService 单独保留），负值视为识别错误或脏数据跳过，口径与 FundEStatementParser 一致。
            if (share <= 0.0 || nav <= 0.0) {
                incomplete.add(valueLine)
                return@forEachIndexed
            }

            holdings.add(
                ParsedHolding(
                    name = card.first,
                    symbol = FundEStatementParser.SYMBOL_PREFIX + card.second,
                    quantity = share,
                    lineNumber = index + 1,
                    rawQuantity = NumberFormatUtils.formatPlain(share),
                    nav = nav,
                    // OCR 读不出「每张卡各自的净值日期」，不能拿整张截图的数据日期来冒充：
                    // 那样 navDate 恒等于 dataDate，UI 上「净值滞后」的红字提示永远不会触发
                    // （QDII 等净值常滞后 2~3 天，用户会被误导成"净值就是当天的"）。
                    // 留 null 表示"未知"，由 UI 按无净值日期处理。
                    navDate = null
                )
            )
        }

        return HoldingSnapshotResult(
            holdings = holdings,
            dataDate = dataDate,
            incomplete = incomplete
        )
    }

    /**
     * 从表头行往上找名称与代码。
     *
     * 只认两种形态，别的都当「没找到」：
     * 1. 名称行自带代码：`中银如意宝货币A (004502)`
     * 2. 名称换行、代码单独成段：`易方达稳健收益债券型证券投资基金B类` / `(110008)`
     *
     * 刻意**不拼合多行名称**：往上无限制收，会把「按产品展示 按账户展示」
     * 这种 tab 名、甚至上一张卡片的痕迹吞进名称里。
     * 名称短一点可以接受——库里已有的资产名字以库里为准，预览页也看得见；
     * 吞错东西不能接受。
     */
    private fun readNameAndCode(lines: List<String>, headerIndex: Int): Pair<String, String>? {
        var j = headerIndex - 1
        while (j >= 0 && lines[j].isBlank()) j--
        if (j < 0) return null

        val above = lines[j]

        // 先判「独立代码行」：`(163415)` 这种整行只有一个带代码的括号。
        // 必须先判它，否则 `(163415)` 会被上面的分支当成本行自带代码，
        // 而剥掉代码后名称为空
        if (looksLikeStandaloneCode(above)) {
            val code = CODE_IN_PARENTHESIS.find(above)?.groupValues?.get(1) ?: return null
            var k = j - 1
            while (k >= 0 && lines[k].isBlank()) k--
            if (k < 0) return null
            val name = lines[k]
            // 名称行不该是表头，也不该是纯数字（那是上一张卡片的数值行）
            if (CARD_HEADER.any { name.contains(it) }) return null
            if (NUMBER.replace(name, "").isBlank()) return null
            return name to code
        }

        CODE_IN_PARENTHESIS.find(above)?.groupValues?.get(1)?.let { code ->
            return nameOf(above) to code
        }

        return null
    }

    /** 整行只有「(123456)」这种东西 */
    internal fun looksLikeStandaloneCode(line: String): Boolean =
        CODE_IN_PARENTHESIS.replace(line, "").isBlank()

    /** 去掉代码与括号，留下名称；没剩下就用原行兜底 */
    private fun nameOf(line: String): String =
        CODE_IN_PARENTHESIS.replace(line, "").trim().trimEnd('(', '（', ' ').trim()
            .ifBlank { line.trim() }

    private fun normalize(line: String): String =
        line.replace('\u00A0', ' ')
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
}
