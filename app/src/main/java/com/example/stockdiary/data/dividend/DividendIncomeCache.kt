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

        /** 缓存键前缀；将来若改结构，升这个前缀即可整体失效，避免读旧 schema */
        private const val KEY_CACHE = "income_cache_v1"

        /** 记录各分片「最近使用」顺序的键（用于淘汰） */
        private const val KEY_SCOPES = "income_cache_scopes"

        /**
         * 分片上限。
         *
         * 缓存必须**按口径分片**：三个页面传给计算的资产集合互不相同（首页=全量非负债、
         * 持仓页=按显示开关+隐藏小额过滤后的子集、日历页=只含股票/基金/存款），
         * 算出的 dataHash 自然也不同。若像从前那样所有口径共用一把键，后算的会覆盖先算的，
         * 先算的那页下次进来必然哈希不匹配 → 冷启动同一份数据被算 2~3 遍
         * （诊断日志里 CalendarVM / DashboardVM 各打一句「冷启动零请求重算」即是）。
         *
         * 分片后各口径各存一片、互不覆盖。上限取 6：三个固定页面 + 用户切换显示开关等
         * 引起的集合变化留些余量，超出就淘汰最久未用的。
         */
        private const val MAX_SCOPES = 6

        /**
         * 未显式指定口径时的兜底分片。
         *
         * 参数刻意放**最后且带默认值**：老的「不传口径」调用点（含既有单测）
         * 无需改动即可编译，行为退化为「所有调用共用一片」—— 也就是改造前的样子。
         * 生产链路（[DividendIncomeLoader]）必须显式传入口径，否则又回到互相覆盖。
         */
        const val DEFAULT_SCOPE = "default"
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
     * 读出某个口径上一次落盘的缓存；读不到 / 解析失败都返回 null（宁可重算，不能把脏数据当真）。
     *
     * @param scopeKey 口径标识（参与本次计算的标的集合指纹），见 [MAX_SCOPES] 的说明
     */
    fun loadCached(scopeKey: String = DEFAULT_SCOPE): CachedIncome? {
        val raw = runCatching { prefs.getString(cacheKey(scopeKey), null) }.getOrNull() ?: return null
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
        ?.also { touchScope(scopeKey) }
    }

    /**
     * 把一次计算结果落盘。
     *
     * @param scopeKey 口径标识（见 [loadCached]）
     * @param result 本次算出来的 [IncomeResult]
     * @param dataHash 本次底层 records 的稳定哈希（见 [CachedIncome.dataHash]）
     * @param date 计算当天 yyyy-MM-dd
     */
    fun save(
        result: DividendIncomeLoader.IncomeResult,
        dataHash: String,
        date: String,
        scopeKey: String = DEFAULT_SCOPE
    ) {
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
            prefs.edit().putString(cacheKey(scopeKey), json.toString()).apply()
            touchScope(scopeKey)
        }.onFailure { e ->
            // 缓存写失败绝不该影响主流程：下次刷新照样重算，最多多耗一次抓取
            AppLogger.w("保存预计收入缓存失败: ${e.message}", "DividendIncomeCache")
        }
    }

    private fun cacheKey(scopeKey: String) = "${KEY_CACHE}_$scopeKey"

    /**
     * 把某个口径记为「最近使用」，并淘汰超出 [MAX_SCOPES] 的最久未用分片。
     *
     * 必须淘汰：显示开关切换、加减仓都会产生新的集合指纹，不设上限的话
     * SharedPreferences 里的分片会无限堆积，老分片再也读不到却一直占位。
     */
    private fun touchScope(scopeKey: String) {
        runCatching {
            val scopes = (prefs.getString(KEY_SCOPES, null) ?: "")
                .split(",")
                .filter { it.isNotBlank() }
                .toMutableList()
            scopes.remove(scopeKey)
            scopes.add(0, scopeKey)

            val editor = prefs.edit()
            while (scopes.size > MAX_SCOPES) {
                val evict = scopes.removeAt(scopes.size - 1)
                editor.remove(cacheKey(evict))
            }
            editor.putString(KEY_SCOPES, scopes.joinToString(",")).apply()
        }.onFailure { e ->
            // 淘汰失败不影响缓存本身：最坏结果是分片多留几份，下次继续尝试
            AppLogger.w("更新预计收入缓存分片索引失败: ${e.message}", "DividendIncomeCache")
        }
    }
}
