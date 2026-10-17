package com.example.stockdiary.utils

import android.content.Context

/** 汇率持久化快照：某一时刻的全量汇率表 + 刷新时间戳 */
data class SavedRates(
    val rates: Map<String, Double>,
    val timestampMs: Long
)

/**
 * 汇率持久化层。
 *
 * 抽成接口是为了**可替换**：正式实现走 SharedPreferences（离线 / 进程重启后
 * 仍用上次的 live 汇率，而不是退回静态默认），单测换 [InMemoryRateStore]——
 * JVM 单测里 SharedPreferences 是 no-op stub，拿不到真实读写语义。
 */
interface RateStore {
    /** @return 上次保存的快照；从未保存过返回 null */
    fun load(): SavedRates?

    /** 全量覆盖保存（调用方保证 [rates] 已过护栏） */
    fun save(rates: Map<String, Double>, timestampMs: Long)
}

/** 进程内存储：管理器未 init 时的默认态，也是单测替身 */
class InMemoryRateStore : RateStore {
    private var saved: SavedRates? = null

    override fun load(): SavedRates? = saved

    override fun save(rates: Map<String, Double>, timestampMs: Long) {
        // 拷贝一份：调用方后续若改动传入的 map，不能穿透污染已存快照
        saved = SavedRates(rates.toMap(), timestampMs)
    }
}

/**
 * SharedPreferences 实现。prefs 名按项目惯例 `<feature>_prefs`。
 *
 * 存储格式为 `USD=6.686;EUR=7.5119` 的分号串：币种代码是 `[A-Z]{3}`、值是
 * double，分隔符不可能出现在数据里，不引额外依赖也更稳。
 */
class SharedPrefsRateStore(context: Context) : RateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SavedRates? {
        val raw = prefs.getString(KEY_RATES, null) ?: return null
        val rates = decodeRates(raw)
        if (rates.isEmpty()) return null
        return SavedRates(rates, prefs.getLong(KEY_TIMESTAMP, 0L))
    }

    override fun save(rates: Map<String, Double>, timestampMs: Long) {
        prefs.edit()
            .putString(KEY_RATES, encodeRates(rates))
            .putLong(KEY_TIMESTAMP, timestampMs)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "exchange_rate_prefs"
        private const val KEY_RATES = "rates"
        private const val KEY_TIMESTAMP = "last_refresh_time"
    }
}

/** 序列化为 `CODE=值;CODE=值`；map 无序但解码不依赖顺序 */
private fun encodeRates(rates: Map<String, Double>): String =
    rates.entries.joinToString(";") { "${it.key}=${it.value}" }

/** 反序列化；坏行跳过，返回尽可能多的有效项（护栏由调用方再过一遍） */
private fun decodeRates(raw: String): Map<String, Double> {
    val result = HashMap<String, Double>()
    for (entry in raw.split(';')) {
        val pair = entry.split('=')
        if (pair.size != 2) continue
        val code = pair[0].trim().uppercase()
        val value = pair[1].trim().toDoubleOrNull() ?: continue
        if (code.isNotEmpty()) {
            result[code] = value
        }
    }
    return result
}
