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

    fun supportedCurrencies(): List<String> = rates.keys.toList()
}
