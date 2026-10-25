package com.example.stockdiary.data.importer

import com.example.stockdiary.utils.ExchangeRateManager

/**
 * 「基金E账户」导出表解析器（纯函数，输入二维表，可直接 JVM 单测）。
 *
 * ## 为什么走导出文件而不是截图
 *
 * 基金E账户的「公募基金查询」页右上角有导出，给出的是 xlsx 明细。
 * 里面**基金代码是独立一列**，不用从名称后面的括号里抠，
 * 也就绕开了截图那条路上的一整串坑：括号全角半角混用、名称里的「(LOF)」假括号、
 * 长名称换行把代码挤到下一行、千分位与四位小数。
 * 而且这是官方给的数据，一个字符都不会错。
 *
 * ## 表长什么样（实测）
 *
 * ```
 * 基金E账户App投资者公募基金持有信息
 * 姓名        证件类型        证件号码
 * *梦阳       中国居民身份证   371***********2673      ← 隐私行
 * （空行）
 * 序号 基金代码 基金名称 份额类别 基金管理人 基金账户 销售机构 交易账户 持有份额 份额日期 基金净值 净值日期 资产情况 结算币种
 * 1   004502  中银如意宝货币A 前收费 中银基金 … 中国银行 … 10025.31 2026/10/08 1.0000 2026/10/08 10025.31 人民币
 * …
 * 总记录数：136 笔
 * 数据来源：基金E账户App
 * 说明：
 * 数量单位：持有份额单位为"份"
 * 免责声明：…
 * ```
 *
 * 实测一份：136 条明细 → 按代码合并后 93 只，合计 99,731.62（与页面顶部总资产一致）。
 *
 * ## 隐私
 *
 * 头部的**姓名与证件号码绝不能**进预览页、日志或数据库。
 * 做法是**只从表头行之后开始读，且只取需要的那几列**——
 * 按「哪个是表头」定位而不是写死行号，这样基金E账户改版往上加几行也不会读到隐私行。
 *
 * ## 为什么按列名定位而不是按列号
 *
 * 写死「代码在第 2 列」一旦官方调整列序就会整列错位，而且错得很安静：
 * 名字和数量对不上，但每列都有值，看起来一切正常。按列名定位则只会缺列报错。
 */
object FundEStatementParser {

    /** 代码列：场内份额不属于基金E账户，所以这里的都是场外基金 */
    private const val COL_CODE = "基金代码"
    private const val COL_NAME = "基金名称"
    private const val COL_SHARE = "持有份额"
    private const val COL_NAV = "基金净值"
    private const val COL_NAV_DATE = "净值日期"
    private const val COL_CURRENCY = "结算币种"

    /**
     * 场外基金的存库前缀。
     *
     * 必须与场内形态分开，理由有两条（缺一条都会出错）：
     * 1. **号段重叠**：`000001` 既是华夏成长混合（场外）又是平安银行（深市股票）。
     * 2. **LOF 场内场外同代码**：同一只 LOF 的场内份额记在一码通那边（`sz163415`），
     *    场外份额记在这里（`fund_163415`）——不加前缀会互相覆盖。
     */
    internal const val SYMBOL_PREFIX = "fund_"

    private val SIX_DIGIT = Regex("""\d{6}""")
    private val ORDER_REGEX = Regex("""\d+(\.0)?""")
    private val DATE_REGEX = Regex("""(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})""")

    data class Result(
        val holdings: List<ParsedHolding>,
        /** 这份数据的数据日期：取出现次数最多的净值日期 */
        val navDate: String?,
        /** 能定位到行、却有问题的记录，原样报给界面，别静默丢 */
        val problems: List<String>
    )

    fun parse(rows: List<List<String>>): Result {
        val headerIndex = rows.indexOfFirst { row -> row.any { it.trim() == COL_CODE } }
        if (headerIndex < 0) {
            return Result(
                holdings = emptyList(),
                navDate = null,
                problems = listOf("没找到「基金代码」这一列，请确认导出的是基金E账户的持有明细")
            )
        }

        val header = rows[headerIndex].map { it.trim() }
        val codeCol = header.indexOf(COL_CODE)
        val nameCol = header.indexOf(COL_NAME)
        val shareCol = header.indexOf(COL_SHARE)
        val navCol = header.indexOf(COL_NAV)
        val navDateCol = header.indexOf(COL_NAV_DATE)
        val currencyCol = header.indexOf(COL_CURRENCY)

        if (shareCol < 0) {
            return Result(
                holdings = emptyList(),
                navDate = null,
                problems = listOf("表头里没有「持有份额」列，无法导入份额")
            )
        }

        val holdings = ArrayList<ParsedHolding>()
        val problems = ArrayList<String>()
        val dateCounts = HashMap<String, Int>()

        for (i in headerIndex + 1 until rows.size) {
            val row = rows[i]

            // 空行一律跳过，**不当成数据区结束**。
            // 官方只要在表头与数据之间加一个空行（很常见的排版），
            // 这里若 break 就会一条都读不到，而用户看到的提示是
            // 「没找到基金代码这一列」——完全指错方向。
            // 真正的结束标志是首列不再是数字（表尾的「总记录数」「免责声明」）
            if (row.all { it.isBlank() }) continue

            val first = row.firstOrNull()?.trim().orEmpty()
            // 序号必须是数字。
            // 「总记录数：136 笔」「数据来源：…」「免责声明：…」都在这里被挡住——
            // 它们是表尾的说明，继续往下读只会把免责声明当成一条持仓
            if (!ORDER_REGEX.matches(first)) break

            val lineNo = i + 1
            val code = row.getOrNull(codeCol)?.trim().orEmpty()
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            val shareText = row.getOrNull(shareCol)?.trim().orEmpty()
            val navText = row.getOrNull(navCol)?.trim().orEmpty()
            val navDateRaw = row.getOrNull(navDateCol)?.trim().orEmpty()
            val currencyRaw = row.getOrNull(currencyCol)?.trim().orEmpty()

            val quantity = parseNumber(shareText)
            val nav = parseNumber(navText)
            val navDate = normalizeDate(navDateRaw)
            val currency = ExchangeRateManager.codeOfDisplayName(currencyRaw)

            when {
                !SIX_DIGIT.matches(code) ->
                    problems.add("第 $lineNo 行代码不是 6 位数字：$code")

                name.isBlank() ->
                    problems.add("第 $lineNo 行缺基金名称（代码 $code）")

                // 份额必须为正：场外基金不存在「负份额」这种合法状态（负余额只发生在币安加密货币，
                // 由 BinanceImportService 单独保留），这里负份额一律视为识别错误跳过，
                // 与 HoldingSnapshotParser 的口径保持一致。
                quantity == null ->
                    problems.add("第 $lineNo 行份额读不出来：$shareText")
                quantity <= 0.0 ->
                    problems.add("第 $lineNo 行份额为负、非有效持仓，已跳过：$shareText")

                // 币种认不出来时不能默认成 CNY：那会让外币净值按人民币数值汇总，
                // 错得无声无息。报出来让用户自己判断
                currencyRaw.isNotBlank() && currency == null ->
                    problems.add("第 $lineNo 行结算币种没认出来：$currencyRaw（代码 $code）")

                else -> {
                    holdings.add(
                        ParsedHolding(
                            name = name,
                            symbol = SYMBOL_PREFIX + code,
                            quantity = quantity,
                            lineNumber = lineNo,
                            rawQuantity = shareText,
                            nav = nav?.takeIf { it > 0.0 },
                            navDate = navDate,
                            currency = currency ?: "CNY"
                        )
                    )
                    if (navDate != null) {
                        dateCounts[navDate] = (dateCounts[navDate] ?: 0) + 1
                    }
                }
            }
        }

        return Result(
            holdings = holdings,
            navDate = dateCounts.maxByOrNull { it.value }?.key,
            problems = problems
        )
    }

    /** 去掉千分位与中文逗号再转数字："10,025.31" → 10025.31 */
    internal fun parseNumber(text: String): Double? =
        text.replace(",", "")
            .replace("，", "")
            .replace(" ", "")
            .trim()
            .toDoubleOrNull()

    /** "2026/10/08"、"2026年10月8日" → "2026-10-08" */
    internal fun normalizeDate(text: String): String? {
        val match = DATE_REGEX.find(text.trim()) ?: return null
        val (year, month, day) = match.destructured
        return "%04d-%02d-%02d".format(year.toInt(), month.toInt(), day.toInt())
    }
}
