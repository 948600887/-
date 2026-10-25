package com.example.stockdiary.data.dividend

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 分红除息日（ex-dividend date）的归一化与语义校验。
 *
 * ## 为什么需要它
 * 上游三个分红源各自只做「形态像日期」的检查，挡不住**形态合法但日历上不存在**的脏数据：
 * - 天天基金 HTML：`\d{4}-\d{2}-\d{2}` 正则——`2026-13-01` 照过；
 * - 东财 JSON：只查非空，`take(10)` 原样透传；
 * - 新浪 HTML：除息日裸取单元格文本，什么都不查。
 *
 * 这类字符串一旦流进 [DividendProgressCalculator]，`substring(5,7).toIntOrNull()` 会得到
 * month=13，而归月数组是 `DoubleArray(13)`（合法下标 0..12）→ `ArrayIndexOutOfBoundsException`，
 * 炸掉 Dashboard / 持仓 / 日历 / FIRE / 设置五个消费页面（commit `c65c1c6` 已在消费端加
 * `1..12` 守卫兜底，但那是最后一道防线——脏数据应该在入口就拦掉，不要带进整条管线）。
 *
 * ## 归一化规则（任一关不过即判脏，返回 null 由调用方丢弃该条记录）
 * 1. trim 去首尾空白；
 * 2. 取前 10 个字符——东财的 `EX_DIVIDEND_DATE` 是 `2023-12-19 00:00:00` 这种带时间的格式；
 * 3. 形态必须是 `\d{4}-\d{2}-\d{2}`——非补零写法（`2023-1-5`）一律视为脏，与消费端
 *    `substring(5,7)` 的隐式假设保持一致；
 * 4. `LocalDate.parse` 真实日历校验——挡掉 `2026-13-01`（月 13）、`2026-02-30`（2 月 30 日）。
 */
object DividendDateValidator {

    private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

    /**
     * @param raw 上游原始日期串；允许 null、空白、带时间部分、脏月份/脏日
     * @return 归一成 `yyyy-MM-dd` 的合法日期；任何一环不合法返回 null
     */
    fun normalizeOrNull(raw: String?): String? {
        val candidate = raw?.trim()?.take(10) ?: return null
        if (!DATE_PATTERN.matches(candidate)) return null
        return try {
            LocalDate.parse(candidate).toString()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
