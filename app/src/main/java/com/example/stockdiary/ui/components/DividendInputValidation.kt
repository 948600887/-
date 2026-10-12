package com.example.stockdiary.ui.components

import com.example.stockdiary.ui.dialogs.FieldError
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 「新增/编辑分红」表单的纯校验逻辑。
 *
 * 原先这段校验写在 AlertDialog 的 confirmButton 里，只在点保存的那一刻跑一次，
 * 而且三个字段的错误都塞进同一个 `error: String?`，最后集中显示成底部一行红字——
 * 出错的输入框自己不标红，用户得在两三个框里猜是哪一条。
 * 现在日期和金额各出各的错误，[DividendInputErrors.firstField] 指出该聚焦哪一个。
 *
 * 纯 Kotlin，不碰 Compose，可直接 JVM 单测。
 */

private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/**
 * 超过这个值基本可以确定是单位填错了（把「每股分红」当成了「每10股分红」），直接拦下。
 *
 * 注意原先这里写的是 100，会误伤真实的高价股：贵州茅台 2023 年度每 10 股派 308.76 元，
 * 直接被判为非法、存不进去。现在 100 只作为提醒阈值（见 [dividendAmountHint]），
 * 硬拦的口子开到 10000。
 */
internal const val AMOUNT_HARD_LIMIT = 10_000.0

/** 超过这个值只提醒、不拦截。 */
internal const val AMOUNT_WARN_LIMIT = 100.0

/** [date] 除息日，[amount] 分红金额；null 表示该字段没问题。 */
data class DividendInputErrors(
    val date: FieldError? = null,
    val amount: FieldError? = null
) {
    val isValid: Boolean
        get() = date == null && amount == null

    /** 第一个出错的字段名，用于聚焦；全部合法则为 null */
    val firstField: String?
        get() = when {
            date != null -> "date"
            amount != null -> "amount"
            else -> null
        }

    /** 汇总提示，如「请检查：除息日、分红金额」；全部合法则为 null */
    val summary: String?
        get() = listOfNotNull(date, amount)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("、") { it.label }
            ?.let { "请检查：$it" }
}

/**
 * 校验分红录入。
 *
 * @param unitWord 份额单位，股票写「股」、基金/ETF 写「份」，提示语要跟着变
 */
fun validateDividendInput(
    date: String,
    amountText: String,
    unitWord: String = "股"
): DividendInputErrors {
    return DividendInputErrors(
        date = when {
            date.isBlank() -> FieldError("除息日", "必填")
            !DATE_PATTERN.matches(date) -> FieldError("除息日", "格式应为 yyyy-MM-dd")
            // 光有格式不够：2026-02-30 长得对但不是真实存在的日子，
            // 存进去之后排序和到期计算都会歪。
            else -> try {
                LocalDate.parse(date)
                null
            } catch (e: DateTimeParseException) {
                FieldError("除息日", "不是有效的日期")
            }
        },

        amount = when {
            amountText.isBlank() -> FieldError("分红金额", "必填")
            else -> {
                val value = amountText.toDoubleOrNull()
                when {
                    value == null -> FieldError("分红金额", "请输入有效数字")
                    value < 0 -> FieldError("分红金额", "不能为负数")
                    value > AMOUNT_HARD_LIMIT -> FieldError(
                        "分红金额",
                        "超过 ${AMOUNT_HARD_LIMIT.toLong()} 元，请检查是否填成了每${unitWord}分红"
                    )
                    else -> null
                }
            }
        }
    )
}

/**
 * 金额偏大时的**非阻断**提醒：只是请用户再看一眼单位，不影响保存。
 *
 * 返回 null 表示无需提醒（金额不大，或者已经大到被 [validateDividendInput] 判为错误了）。
 */
fun dividendAmountHint(amountText: String, unitWord: String = "股"): String? {
    val value = amountText.toDoubleOrNull() ?: return null
    if (value <= AMOUNT_WARN_LIMIT || value > AMOUNT_HARD_LIMIT) return null
    val plain = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    return "每10${unitWord}分红 $plain 元，请确认填的是每10${unitWord}而不是每${unitWord}"
}
