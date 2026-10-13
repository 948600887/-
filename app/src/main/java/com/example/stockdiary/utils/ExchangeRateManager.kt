package com.example.stockdiary.utils

/**
 * 汇率管理器
 * 提供主要货币对人民币的汇率。当前使用内置静态汇率，未来可扩展为联网刷新。
 * 汇率表示：1 单位外币 = ? CNY
 */
object ExchangeRateManager {

    private val rates = mapOf(
        "CNY" to 1.0,
        "USD" to 7.25,
        "HKD" to 0.93,
        "EUR" to 7.85,
        "JPY" to 0.048,
        "GBP" to 9.15,
        "AUD" to 4.75,
        "CAD" to 5.25,
        "SGD" to 5.35,
        "CHF" to 8.05
    )

    private val displayNames = mapOf(
        "CNY" to "人民币",
        "USD" to "美元",
        "HKD" to "港币",
        "EUR" to "欧元",
        "JPY" to "日元",
        "GBP" to "英镑",
        "AUD" to "澳元",
        "CAD" to "加元",
        "SGD" to "新加坡元",
        "CHF" to "瑞士法郎"
    )

    fun getRate(currency: String): Double = rates[currency.uppercase()] ?: 1.0

    fun toCny(amount: Double, currency: String): Double = amount * getRate(currency)

    fun fromCny(amountCny: Double, currency: String): Double {
        val rate = getRate(currency.uppercase())
        return if (rate > 0) amountCny / rate else amountCny
    }

    fun getDisplayName(currency: String): String = displayNames[currency.uppercase()] ?: currency.uppercase()

    /**
     * 中文币种名 → 货币代码。
     *
     * 基金E账户导出的「结算币种」列给的是中文名（「人民币」「美元」…），
     * 而库里存的是代码。认不出的返回 null，由调用方决定怎么办——
     * 这里**不**默认返回 CNY：把「离岸人民币」之类静默当成人民币，
     * 会让外币净值按人民币数值直接汇总，错得无声无息。
     */
    fun codeOfDisplayName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return displayNames.entries.firstOrNull { it.value == trimmed }?.key
    }

    fun supportedCurrencies(): List<String> = rates.keys.toList()
}
