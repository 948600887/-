package com.example.stockdiary.common

import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 全 App 日期/时间 pattern 的**唯一口径源**。
 *
 * 背景：此前 `DateTimeFormatter.ofPattern("yyyy-MM-dd")` 之类的字面量散落 17 个文件 44 处，
 * 带来两个真实问题：
 * 1. **口径漂移**：同一个日期在导出用 `yyyy-MM-dd HH:mm:ss`、日志用 `.SSS`、日历用 `yyyy年MM月`，
 *    想统一改某处显示（如跟随系统 locale）要翻遍全项目；
 * 2. **重复开销**：`ofPattern` 每次都要重新解析 pattern 并建一整套解析/格式化字段，
 *    而日历 42 格、图表每个刻度、成交额每行都在调它。
 *
 * 用法约定：
 * - 要新 pattern 先在这里加常量，**禁止调用方再写 ofPattern 字面量**；
 * - 确有运行时动态 pattern（如导入探测多格式）的场景，走 [ofPattern] 缓存入口，
 *    不要自己 new——那样又回到每次重新解析的老路。
 *
 * [DateTimeFormatter] 本身不可变、线程安全，缓存的实例可以随便复用。
 */
object DateFormats {

    // ---- 常用 pattern 字符串（SDF 等需要字符串的场合也引用这些常量，保证同一口径） ----

    /** ISO 日期：数据库 date 列、快照 date、行情接口的主力格式 */
    const val ISO_DATE = "yyyy-MM-dd"
    /** ISO 日期时间（秒） */
    const val ISO_DATETIME = "yyyy-MM-dd HH:mm:ss"
    /** 列表/详情里的日期时间展示（不含秒） */
    const val DISPLAY_DATETIME = "yyyy-MM-dd HH:mm"
    /** ISO 日期时间（毫秒）：日志用 */
    const val ISO_DATETIME_MILLIS = "yyyy-MM-dd HH:mm:ss.SSS"
    /** 紧凑日期：部分基金/行情接口用 yyyyMMdd */
    const val COMPACT_DATE = "yyyyMMdd"
    /** 斜杠日期：部分第三方数据源 */
    const val SLASH_DATE = "yyyy/MM/dd"
    /** 中文日期：导出文件、展示场景 */
    const val CN_DATE = "yyyy年MM月dd日"
    /** 中文日期时间：导出文件用 */
    const val CN_DATETIME = "yyyy年MM月dd日 HH:mm"
    /** 中文月份：日历标题 */
    const val CN_MONTH = "yyyy年MM月"
    /** 时刻：HH:mm */
    const val TIME_ONLY = "HH:mm"
    /** 月日时刻：设置页同步时间展示 */
    const val MONTH_DAY_TIME = "MM-dd HH:mm"
    /** 文件时间戳：导出文件名 */
    const val FILE_TIMESTAMP = "yyyyMMdd_HHmmss"

    /**
     * 动态 pattern 的缓存入口：同一 pattern 全 App 只构造一个 [DateTimeFormatter]。
     *
     * 仅供「运行时才能确定 pattern」的场景（如导入器按候选格式列表逐个试解析）。
     * 静态能确定的 pattern 一律引用下面的预构造常量。
     *
     * 注意：cache 必须声明在预构造 val **之前**——它们初始化时要调这个函数，
     * Kotlin object 按声明顺序初始化，反了会 NPE。
     */
    private val formatterCache = ConcurrentHashMap<String, DateTimeFormatter>()

    fun ofPattern(pattern: String): DateTimeFormatter =
        formatterCache.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern) }

    // ---- 预构造的 DateTimeFormatter（热点路径直接引用，零构造开销） ----
    // 全部走 ofPattern：保证静态入口与动态入口收敛到同一实例，全 App 一个 pattern 一份。

    val ISO_DATE_FORMATTER: DateTimeFormatter = ofPattern(ISO_DATE)
    val ISO_DATETIME_FORMATTER: DateTimeFormatter = ofPattern(ISO_DATETIME)
    val ISO_DATETIME_MILLIS_FORMATTER: DateTimeFormatter = ofPattern(ISO_DATETIME_MILLIS)
    val COMPACT_DATE_FORMATTER: DateTimeFormatter = ofPattern(COMPACT_DATE)
    val SLASH_DATE_FORMATTER: DateTimeFormatter = ofPattern(SLASH_DATE)
    val CN_DATE_FORMATTER: DateTimeFormatter = ofPattern(CN_DATE)
    val CN_DATETIME_FORMATTER: DateTimeFormatter = ofPattern(CN_DATETIME)
    val CN_MONTH_FORMATTER: DateTimeFormatter = ofPattern(CN_MONTH)
    val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter = ofPattern(FILE_TIMESTAMP)
}
