package com.example.stockdiary.data.dividend

import android.content.Context
import com.example.stockdiary.utils.AppLogger
import org.json.JSONObject

/**
 * 「预计收入」计算结果的轻量持久层。
 *
 * ## 为什么需要它
 *
 * 抖动根因（已查清）：同一天内 TTM 滑窗不变，但不同次抓取拿到的 records 集不同
 * （基金靠天天基金 HTML 抓取、东财批量仅部分命中）→ TTM 求和变，
 * Dashboard / 组合页的「预计收入 / 年度分红」同一天内反复跳。
 *
 * 这里把**算出来的结果**落盘，仅当底层分红数据真的变化（`dataHash` 变）或跨日
 * （`date != 今天`）才重算，否则同日刷新永远复用旧结果 —— 杀掉同日抖动，
 * 同时保留跨日 TTM 滑窗漂移（[DividendYieldCalculator.getTTMDividend] 仍按
 * `LocalDate.now()` 算，本类不去碰它）。
 *
 * 用 `SharedPreferences` 而非新 Room 表：Room 当前 version 16，动 schema 要写 migration，
 * 而这份缓存只是「计算结果备忘录」，丢了随时能重算，不值得为此升版本号。
 * 序列化沿用项目既有做法（[org.json.JSONObject]，不引新依赖）。
 */
class DividendIncomeCache(context: Context) {

    companion object {
        /** SharedPreferences 文件名（参照 [com.example.stockdiary.data.repository.AssetRepository] 的 REPAIR_PREFS 用法） */
        const val DIVIDEND_INCOME_PREFS = "dividend_income_cache"

        /** 单一缓存键；将来若改结构，升这个 key 即可整体失效，避免读旧 schema */
        private const val KEY_CACHE = "income_cache_v1"
    }

    /**
     * 缓存下来的「预计收入」结果。
     *
     * @param date 计算当天的 yyyy-MM-dd
     * @param dataHash 当前各标的原始分红记录（symbol → 除息日/每股分红列表）算出的稳定哈希，
     *                用于判断「底层数据有没有变」——哈希不同即底层 records 变了，必须重算
     * @param dividendsBySymbol 即 [IncomeResult.dividendsBySymbol]
     * @param totalAnnualIncome 即 [IncomeResult.totalAnnualIncome]
     * @param hasDividendData 即 [IncomeResult.hasDividendData]
     */
    data class CachedIncome(
        val date: String,
        val dataHash: String,
        val dividendsBySymbol: Map<String, Double>,
        val totalAnnualIncome: Double,
        val hasDividendData: Boolean
    )

    private val prefs = context.getSharedPreferences(DIVIDEND_INCOME_PREFS, Context.MODE_PRIVATE)

    /**
     * 读出上一次落盘的缓存；读不到 / 解析失败都返回 null（宁可重算，不能把脏数据当真）。
     */
    fun loadCached(): CachedIncome? {
        val raw = runCatching { prefs.getString(KEY_CACHE, null) }.getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val date = json.optString("date", "")
            val dataHash = json.optString("dataHash", "")
            val totalAnnualIncome = json.optDouble("totalAnnualIncome", 0.0)
            val hasDividendData = json.optBoolean("hasDividendData", false)
            val dividendsJson = json.optJSONObject("dividendsBySymbol") ?: JSONObject()
            val dividendsBySymbol = LinkedHashMap<String, Double>()
            dividendsJson.keys().forEach { key ->
                dividendsBySymbol[key] = dividendsJson.optDouble(key, 0.0)
            }
            CachedIncome(
                date = date,
                dataHash = dataHash,
                dividendsBySymbol = dividendsBySymbol,
                totalAnnualIncome = totalAnnualIncome,
                hasDividendData = hasDividendData
            )
        }.onFailure { e ->
            AppLogger.w("读取预计收入缓存失败（下次重算）: ${e.message}", "DividendIncomeCache")
        }.getOrNull()
    }

    /**
     * 把一次计算结果落盘。
     *
     * @param result 本次算出来的 [IncomeResult]
     * @param dataHash 本次底层 records 的稳定哈希（见 [CachedIncome.dataHash]）
     * @param date 计算当天 yyyy-MM-dd
     */
    fun save(result: DividendIncomeLoader.IncomeResult, dataHash: String, date: String) {
        runCatching {
            val json = JSONObject()
            json.put("date", date)
            json.put("dataHash", dataHash)
            json.put("totalAnnualIncome", result.totalAnnualIncome)
            json.put("hasDividendData", result.hasDividendData)
            // Map 排序后写入，保证相同内容序列化出相同字符串（也方便排查）
            val dividendsJson = JSONObject()
            result.dividendsBySymbol.entries.sortedBy { it.key }.forEach { (symbol, amount) ->
                dividendsJson.put(symbol, amount)
            }
            json.put("dividendsBySymbol", dividendsJson)
            prefs.edit().putString(KEY_CACHE, json.toString()).apply()
        }.onFailure { e ->
            // 缓存写失败绝不该影响主流程：下次刷新照样重算，最多多耗一次抓取
            AppLogger.w("保存预计收入缓存失败: ${e.message}", "DividendIncomeCache")
        }
    }
}
