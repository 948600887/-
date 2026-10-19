package com.example.stockdiary.utils

import com.example.stockdiary.data.dividend.DividendData
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.stockdiary.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ========== 数字格式化 ==========

fun Double.format(digits: Int = 2) = String.format("%.${digits}f", this)
fun Float.format(digits: Int = 2) = String.format("%.${digits}f", this)
fun Int.format(digits: Int = 2) = String.format("%.${digits}f", this.toDouble())
fun Long.format(digits: Int = 2) = String.format("%.${digits}f", this.toDouble())

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

fun Long.toFileTimestamp(): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault()).toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

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
            .format(DateTimeFormatter.ofPattern(format))
    } catch (e: Exception) {
        ""
    }
}

fun Long.toDisplayDate(): String = toDateString("yyyy-MM-dd")
fun Long.toDisplayTime(): String = toDateString("HH:mm")
fun Long.toDisplayDateTime(): String = toDateString("yyyy-MM-dd HH:mm")

fun String.toLocalDate(format: String = "yyyy-MM-dd"): LocalDate? {
    return try {
        LocalDate.parse(this, DateTimeFormatter.ofPattern(format))
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

fun com.example.stockdiary.data.dividend.DividendData.isNewest(): Boolean = true

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
        val date = LocalDate.parse(this, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
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
