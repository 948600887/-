package com.example.stockdiary.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.example.stockdiary.network.EastmoneyDelistedQuoteApi
import java.time.LocalDate

/**
 * 退市股兜底收盘价的「按报价日去重」缓存。
 *
 * 目的只有一个：同一交易日不重复请求东财。存储一条
 * `检查日 | 交易日 | 收盘价`：
 * - 检查日（抓取日）用于「今天已经问过东财了」的短路，避免每轮同步都打一次；
 * - 交易日（K 线日期）是行情自己的时间，**不能**拿抓取时刻充数——
 *   这份数据可能滞后一周（老三板按周成交），内部必须分得清。
 *
 * 日期只用于内部去重 / 日志，不进入用户界面：界面仍按普通价格展示。
 * 进程重启后依然有效（已检查日持久化），但第二天会重新确认是否有新收盘。
 */
class DelistedQuoteCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 今天是否已经向东财确认过该标的（无论当时拿到没拿到价） */
    fun isCheckedOn(symbol: String, day: LocalDate = LocalDate.now()): Boolean {
        val raw = prefs.getString(key(symbol), null) ?: return false
        return raw.substringBefore('|').toLongOrNull() == day.toEpochDay()
    }

    /** 读取上次拿到的历史收盘（没有或上次失败时为 null） */
    fun cachedQuote(symbol: String): EastmoneyDelistedQuoteApi.DelistedQuote? {
        val raw = prefs.getString(key(symbol), null) ?: return null
        val parts = raw.split('|')
        if (parts.size < 3) return null
        val tradeDate = parts[1]
        val price = parts[2].toDoubleOrNull() ?: return null
        if (tradeDate == "-" || !price.isFinite() || price <= 0.0) return null
        return EastmoneyDelistedQuoteApi.DelistedQuote(price = price, tradeDate = tradeDate)
    }

    /**
     * 记录本次检查结果。[quote] 为 null 表示「今天问过了，但没有可用历史收盘」，
     * 同样要落检查日，避免同一交易日反复重试。
     */
    fun record(
        symbol: String,
        quote: EastmoneyDelistedQuoteApi.DelistedQuote?,
        day: LocalDate = LocalDate.now()
    ) {
        val value = "${day.toEpochDay()}|${quote?.tradeDate ?: "-"}|${quote?.price ?: "-"}"
        prefs.edit().putString(key(symbol), value).apply()
        trimIfNeeded()
    }

    /** 只保留最近 [MAX_ENTRIES] 个标的，防止退市名单长期累积把偏好文件撑大 */
    private fun trimIfNeeded() {
        val all = prefs.all
        if (all.size <= MAX_ENTRIES) return
        val stale = all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .sortedBy { (it.value as? String)?.substringBefore('|')?.toLongOrNull() ?: Long.MIN_VALUE }
            .take(all.size - MAX_ENTRIES)
        prefs.edit().apply {
            stale.forEach { remove(it.key) }
        }.apply()
    }

    private fun key(symbol: String): String = KEY_PREFIX + symbol.trim().uppercase()

    private companion object {
        const val PREFS_NAME = "delisted_quote_cache"
        const val KEY_PREFIX = "delisted_"
        const val MAX_ENTRIES = 100
    }
}
