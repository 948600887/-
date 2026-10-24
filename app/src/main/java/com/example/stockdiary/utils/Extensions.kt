package com.example.stockdiary.utils

import com.example.stockdiary.data.dividend.DividendData
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.theme.AppTheme
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// ========== 资产列表去重（给 Room 全表 Flow 用） ==========

/**
 * 判断两个资产列表在「界面真正依赖的字段」上是否等价——用于给资产 Flow 去重。
 *
 * 背景：Room 的 `getAllAssets()` 每次写库都会重发整表，而 `Asset` 里的
 * `lastSyncTime` / `lastUpdateTime` 每次落价都会变。**不去重 = 一次同步触发上百次整屏重组**
 * （日历页已实测过：一次全量刷新让「生成资产日历」白跑 20+ 次，见 `CalendarViewModel`）。
 *
 * 口径：包含名称/类型/代码/账户/数量/参考单价/现价/币种/备注/利率/到期日/万份收益
 * （界面确实会显示这些），**排除 lastSyncTime / lastUpdateTime**。
 *
 * ⚠️ 与 `CalendarViewModel.calendarSignature` 的区别：日历连现价都不依赖，
 * 这里**必须含 currentPrice**，否则仪表盘/持仓页的现价与市值不会刷新。
 *
 * 用逐字段比较而非拼字符串签名：避免每次 emit 都要为 N 个资产各分配一个字符串
 * （那本身就是主线程上的 O(n) 分配）。
 */
fun List<Asset>.isDisplayEquivalentTo(other: List<Asset>): Boolean {
    if (size != other.size) return false
    for (i in indices) {
        val a = this[i]
        val b = other[i]
        if (a.name != b.name || a.type != b.type || a.symbol != b.symbol ||
            a.account != b.account || a.balance != b.balance ||
            a.purchasePrice != b.purchasePrice || a.currentPrice != b.currentPrice ||
            a.currency != b.currency || a.notes != b.notes ||
            a.interestRate != b.interestRate || a.dueDate != b.dueDate ||
            a.millionCopiesIncome != b.millionCopiesIncome
        ) return false
    }
    return true
}

// ========== 数字格式化 ==========

/**
 * 按小数位数缓存的 [DecimalFormat]，**每线程一份**。
 *
 * 原来的实现是 `String.format("%.${digits}f", v)`：每次调用都要拼一个格式串、再新建
 * `Formatter` 与内部 `DecimalFormat`（还要查一次 Locale），而这个函数在持仓列表滚动、
 * 日历 42 个格子渲染、CSV 导出里每行都被调用几十次——是本 App 组合期最热的几条路径之一。
 *
 * 为什么用 ThreadLocal 而不是一个全局 ConcurrentHashMap：
 * `format()` 不只跑在主线程，`PriceSyncWorker` / `SnapshotWorker` / `DataExporter` 等后台
 * 任务也在调它；而 **DecimalFormat 不是线程安全的**——它的 `format()` 会复用实例内部的
 * `DigitList` 等可变字段，两个线程同时调同一个实例会互相踩、产出错误的数字（比抛异常更糟，
 * 因为错了也看不出来）。原来的 `String.format` 是线程安全的，这里不能把它换成共享实例。
 *
 * 每线程一份之后实例是线程封闭的：查找只是一次 ThreadLocal.get() + 一次 HashMap 查询，
 * 仍然比每次新建 Formatter 便宜一个数量级。
 */
private val decimalFormatCache = ThreadLocal<MutableMap<Int, DecimalFormat>>()

/** digits 的合法上限：再高的精度本 App 用不到，挡住异常输入也避免建出超长 pattern */
// 加密货币数量/价格常见到 10 位小数，必须留足；给到 12 留点余量，避免 CRYPTO_DECIMALS 上调时被截断
private const val MAX_FORMAT_DIGITS = 12

private fun decimalFormatter(digits: Int): DecimalFormat {
    // 不用 ThreadLocal.withInitial：那是 Java 8 API，低版本 Android 上要靠脱糖才有，
    // 直接判空建一个更稳
    val perThread = decimalFormatCache.get()
        ?: HashMap<Int, DecimalFormat>().also { decimalFormatCache.set(it) }
    return perThread[digits]
        ?: createDecimalFormat(digits).also { perThread[digits] = it }
}

private fun createDecimalFormat(digits: Int): DecimalFormat =
    // DecimalFormat 默认 HALF_EVEN，而 String.format("%.Nf") 用的是 HALF_UP。
    // 不改 roundingMode 的话 1.005 这类值会差一个最小单位，属于"看不见但会被测出来"的行为漂移。
    DecimalFormat("0" + if (digits > 0) "." + "0".repeat(digits) else "").apply {
        roundingMode = RoundingMode.HALF_UP
        // 与 String.format 一致：不带千分位（pattern 里没有 ","，这里再显式关一次免得将来被误改）
        isGroupingUsed = false
        // 小数分隔符跟随系统 FORMAT locale，与 String.format 取的是同一套 Locale.Category
        decimalFormatSymbols = DecimalFormatSymbols.getInstance()
    }

/**
 * 定点小数格式化。
 *
 * 输出与原先的 `String.format("%.${digits}f")` **逐字一致**（无千分位、HALF_UP、负数带 `-`），
 * 只是换成缓存的 DecimalFormat，省掉每次拼串 + 新建 Formatter 的开销。
 */
fun Double.format(digits: Int = 2): String {
    // NaN / ±Infinity 两边输出不同（DecimalFormat 给 "∞"，String.format 给 "Infinity"）。
    // 这两个值不出现在正常路径上，交给旧实现即可保证行为完全不变；判断本身只是一次位运算。
    if (!isFinite()) return String.format("%.${digits}f", this)
    return decimalFormatter(digits.coerceIn(0, MAX_FORMAT_DIGITS)).format(this)
}

fun Float.format(digits: Int = 2) = this.toDouble().format(digits)
fun Int.format(digits: Int = 2) = this.toDouble().format(digits)
fun Long.format(digits: Int = 2) = this.toDouble().format(digits)

/**
 * 金额的**唯一**格式化入口。
 *
 * 之前各页面散着 `.format(0) / .format(1) / .format(2) / .format(3)` 四种精度，同一个
 * 「年度分红」在持仓页 0 位、资产页 2 位，用户左右切页看到两个数不一样，第一反应是"哪个算错了"。
 * 统一成金额一律 2 位后，两处就对得上了。
 *
 * @param digits 固定 2 位，保留参数是为了将来真有需要时单点可改，调用方不要传
 */
fun Double.formatMoney(symbol: String = "¥", digits: Int = MONEY_DIGITS): String = "$symbol${format(digits)}"

/** 金额的小数位（[formatMoney] 的默认精度）：全 App 金额一律 2 位，不要再写死别的 */
const val MONEY_DIGITS = 2

/** 加密货币小数位上限：币安等现货的数量/价格常见到 10 位小数（如 0.1234567890）。
 * 少于这个数会被舍成 0 或 0.12，甚至被导入的变更判定当成「无变化」而跳过更新。
 * 所有加密货币精度都由这个常量驱动——将来要改成 12 位只动这一行。 */
const val CRYPTO_DECIMALS = 10

/** 报价精度：加密货币给足 [CRYPTO_DECIMALS] 位（低价山寨币可能是 0.0000000001 量级，按 2 位会显示成 ¥0.00），
 * 基金与 ETF 显示到厘（3 位），其余显示到分（2 位）。
 *
 * 原先 AssetCards / PortfolioSearch 各写了一遍同样的判定，AdjustPositionDialog 又硬编成
 * 恒 3 位——同一只股票在列表显示 12.34、在加减仓弹窗显示 12.340。这里单点定义，三处共用。
 */
fun pricePrecisionFor(type: AssetType, symbol: String?): Int =
    when {
        type == AssetType.CRYPTO -> CRYPTO_DECIMALS
        type == AssetType.FUND || StockCodeManager.isETF(symbol) -> 3
        else -> 2
    }

/**
 * 持仓数量（份额）小数位：加密货币按 [CRYPTO_DECIMALS]（交易所现货数量常见 6~10 位小数），
 * 股票整数（股），其余 2 位（基金/ETF 等）。与 [pricePrecisionFor] 同理，单点定义避免各页漂移。
 */
fun quantityPrecisionFor(type: AssetType, symbol: String?): Int =
    when {
        type == AssetType.CRYPTO -> CRYPTO_DECIMALS
        type == AssetType.STOCK -> 0
        else -> 2
    }

/**
 * 最多保留 [digits] 位小数、去掉末尾多余的 0（和小数点）。
 * 用于加密货币等「数值跨度大、小数位多」的场景：BTC 价 67000.12 不会显示成 67000.1200000000，
 * 低价山寨币 0.0000000001 也保得住有效位。小数分隔符跟随系统（zh 为 '.'），故两个都 trim。
 */
fun Double.formatTrim(digits: Int): String {
    val s = format(digits)
    val sep = DecimalFormatSymbols.getInstance().decimalSeparator
    return s.trimEnd('0').trimEnd(sep)
}

/** 币种符号：原币价格没有符号时会被当成人民币读（USD 报价显示成 67000 而不是 $67000） */
fun currencySymbol(currency: String?): String = when (currency?.uppercase()) {
    "USD" -> "$"
    "HKD" -> "HK$"
    else -> "¥"
}

fun Double?.orZero(): Double = this ?: 0.0
fun Float?.orZero(): Float = this ?: 0f
fun Int?.orZero(): Int = this ?: 0
fun Long?.orZero(): Long = this ?: 0L

fun Double?.orDefault(default: Double): Double = this ?: default
fun Float?.orDefault(default: Float): Float = this ?: default
fun Int?.orDefault(default: Int): Int = this ?: default
fun Long?.orDefault(default: Long): Long = this ?: default

fun Double.toPercentString(digits: Int = 2) = "${(this * 100).format(digits)}%"
fun Double.toCurrencyString(symbol: String = "¥") = "$symbol${this.format(2)}"

fun Double.toProfitDisplay(currency: String = "¥"): String =
    "${if (this >= 0) "+" else ""}$currency${this.format(2)}"

/**
 * 盈亏色（红涨绿跌，A 股惯例）：>=0 红、<0 绿。
 * 主题感知：随 AppTheme.colors 切换深浅两套；调用点均位于 @Composable 内。
 */
@Composable
fun Double.profitColor(): Color =
    if (this >= 0) AppTheme.colors.lossRed else AppTheme.colors.profitGreen

/**
 * 按 pattern 缓存的 [DateTimeFormatter]。
 *
 * `DateTimeFormatter.ofPattern()` 每次都要重新解析 pattern 并建一整套解析/格式化字段，
 * 而 `toDateString` / `toLocalDate` / `formatDateLabel` 都是被逐行、逐格调用的
 * （日历 42 格、图表每个刻度标签、成交额每行解析一次）—— pattern 却是固定的那两三个。
 * DateTimeFormatter 本身不可变、线程安全，缓存下来可以随便复用。
 */
private val dateTimeFormatters = ConcurrentHashMap<String, DateTimeFormatter>()

private fun patternFormatter(pattern: String): DateTimeFormatter =
    dateTimeFormatters.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern) }

fun Long.toFileTimestamp(): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault()).toLocalDateTime()
        .format(patternFormatter("yyyyMMdd_HHmmss"))

fun Int.toKMB(): String = when {
    this >= 1_000_000_000 -> "${(this / 1_000_000_000.0).format(1)}B"
    this >= 1_000_000 -> "${(this / 1_000_000.0).format(1)}M"
    this >= 1_000 -> "${(this / 1_000.0).format(1)}K"
    else -> this.toString()
}

fun Double.toKMB(): String = when {
    this >= 1_000_000_000 -> "${(this / 1_000_000_000).format(1)}B"
    this >= 1_000_000 -> "${(this / 1_000_000).format(1)}M"
    this >= 1_000 -> "${(this / 1_000).format(1)}K"
    else -> this.format(1)
}

// ========== 日期格式化 ==========

fun Long.toDateString(format: String = "yyyy-MM-dd"): String {
    return try {
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault()).toLocalDateTime()
            .format(patternFormatter(format))
    } catch (e: Exception) {
        ""
    }
}

fun Long.toDisplayDate(): String = toDateString("yyyy-MM-dd")
fun Long.toDisplayTime(): String = toDateString("HH:mm")
fun Long.toDisplayDateTime(): String = toDateString("yyyy-MM-dd HH:mm")

fun String.toLocalDate(format: String = "yyyy-MM-dd"): LocalDate? {
    return try {
        LocalDate.parse(this, patternFormatter(format))
    } catch (e: Exception) {
        null
    }
}

fun String.toTimestamp(format: String = "yyyy-MM-dd"): Long {
    return toLocalDate(format)?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L
}

fun Long.toRelativeTime(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> toDisplayDate()
    }
}

fun Long.isToday(): Boolean {
    val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val todayEnd = todayStart + 86_400_000
    return this in todayStart until todayEnd
}

// ========== 网络相关 ==========

fun String.isValidUrl(): Boolean = startsWith("http://") || startsWith("https://")

fun String.appendIfMissing(suffix: String): String = 
    if (endsWith(suffix)) this else this + suffix

fun String.prependIfMissing(prefix: String): String = 
    if (startsWith(prefix)) this else prefix + this

// ========== 字符串处理 ==========

fun String?.orEmpty(): String = this ?: ""
fun String?.orBlank(default: String): String = this?.takeIf { it.isNotBlank() } ?: default

fun String.isValidStockCode(): Boolean {
    if (isEmpty()) return false
    return all { it.isDigit() || it in "shSHszSZ" }
}

fun String.normalizeStockCode(): String = uppercase()
fun String.extractStockCode(): String = filter { it.isDigit() }

// ========== 集合处理 ==========

fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

fun <T> List<T>.takeIfNotEmpty(): List<T>? = if (isNotEmpty()) this else null

fun <K, V> Map<K, V>?.orEmpty(): Map<K, V> = this ?: emptyMap()

inline fun <T, R> List<T>.mapNotNullToList(transform: (T) -> R?): List<R> =
    mapNotNull(transform)

fun <T> List<T>.joinToStringLimited(separator: String = ", ", limit: Int = 3): String {
    if (size <= limit) return joinToString(separator)
    return "${take(limit).joinToString(separator)}...(+${size - limit})"
}

fun List<com.example.stockdiary.data.dividend.DividendData>.getLatest(): List<com.example.stockdiary.data.dividend.DividendData> =
    this.sortedByDescending { it.date }.take(1)

fun <T : Collection<*>> Result<T>.hasContent(): Boolean = isSuccess && getOrNull()?.isNotEmpty() == true

// ========== 颜色处理 ==========

fun Int.toColorString(): String = String.format("#%06X", 0xFFFFFF and this)

// ========== 范围判断 ==========

fun Double.isInRange(min: Double, max: Double): Boolean = this in min..max
fun Float.isInRange(min: Float, max: Float): Boolean = this in min..max
fun Int.isInRange(min: Int, max: Int): Boolean = this in min..max

fun Double.isPositive(): Boolean = this > 0
fun Double.isNegative(): Boolean = this < 0
fun Double.isZero(): Boolean = this == 0.0

// ========== 图表/日历通用格式化 ==========

/** 将 yyyy-MM-dd 日期简化为 M/d */
fun String.formatDateLabel(): String {
    return try {
        val date = LocalDate.parse(this, patternFormatter("yyyy-MM-dd"))
        "${date.monthValue}/${date.dayOfMonth}"
    } catch (e: Exception) { this }
}

/**
 * 将数值格式化为 Y 轴标签（万/K/原值）。
 *
 * @param step 相邻网格线的差值，精度随它自适应：步长太小还按整万取整，五条线会全塌成
 *   同一个数（净值在 18.9 万附近波动时五条全标"18万"，参照系失效——用户实测）。
 *   万元小数位到两位仍不够（步长 < 100 元）时直接显示原值，保证相邻标签可区分。
 */
fun Double.formatYAxisLabel(step: Double = Double.MAX_VALUE): String {
    return when {
        this >= 10000 && step >= 10000 -> "${(this / 10000).toInt()}万"
        this >= 10000 && step >= 1000 -> "${(this / 10000).format(1)}万"
        this >= 10000 && step >= 100 -> "${(this / 10000).format(2)}万"
        this >= 10000 -> this.toInt().toString()
        this >= 1000 -> "${(this / 1000).toInt()}K"
        else -> this.toInt().toString()
    }
}

/** 大数字紧凑格式：10000+ → "X.X万" */
fun Double.compactFormat(): String {
    if (this >= 10000) {
        val wan = this / 10000.0
        return if (wan >= 100) "${wan.format(0)}万" else "${wan.format(1)}万"
    }
    return this.format(0)
}
