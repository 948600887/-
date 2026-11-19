package com.example.stockdiary.data.repository

import android.content.Context
import com.example.stockdiary.utils.StockCodeManager

/**
 * 分红抓取状态簿记（SharedPreferences，跨进程存活）。
 *
 * 从 [DividendRepository] 外提：这里只管「抓过 / 刚失败 / 抓到了非空数据」三类时间戳的读写，
 * 不参与缓存、解析或编排。键名与前缀保持原样，旧安装升级后状态可直接沿用。
 *
 * 为什么「抓过，且确实没有分红」必须被记住：实测一次全量抓取里 87% 的标的没有分红，
 * 空结果从不落库；进程被回收后若不留时间戳，每次冷启动都会重抓全部标的，
 * 接口被打到 HTTP 514 限流，进而放大「闪退」。
 */
internal class DividendFetchStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(FETCH_STATE_PREFS, Context.MODE_PRIVATE)

    /** 清空全部簿记（「清除所有数据」时调用）。同步 commit：调用点都在 IO 协程，避免异步落盘来不及。 */
    fun clear() {
        runCatching { prefs.edit().clear().commit() }
    }

    /** 记录一次抓取完成（不管抓没抓到）。必须在抓取**成功**后写，失败时写会把错误固化 24 小时 */
    fun markFetched(symbol: String) {
        runCatching { prefs.edit().putLong(symbol, System.currentTimeMillis()).apply() }
    }

    /**
     * 记一次抓取失败（所有数据源都没应答）。
     *
     * 与 [markFetched] 是两个独立状态：这里按 [computeFailureBackoff] 指数退避，
     * 混在一起会把错误固化成结论。读-改-写必须原子：两个页面可能同时抓同一只标的。
     */
    fun markFailed(symbol: String) {
        runCatching {
            synchronized(prefs) {
                val countKey = FAILURE_COUNT_PREFIX + symbol
                val newCount = prefs.getInt(countKey, 0) + 1
                prefs.edit()
                    .putLong(FAILURE_KEY_PREFIX + symbol, System.currentTimeMillis())
                    .putInt(countKey, newCount)
                    .apply()
            }
        }
    }

    /** 抓取成功即清除失败标记与连续失败计数，让它立刻回到正常的 TTL 节奏 */
    fun clearFailure(symbol: String) {
        runCatching {
            prefs.edit()
                .remove(FAILURE_KEY_PREFIX + symbol)
                .remove(FAILURE_COUNT_PREFIX + symbol)
                .apply()
        }
    }

    fun lastFetchedAt(symbol: String): Long? {
        val value = runCatching { prefs.getLong(symbol, 0L) }.getOrDefault(0L)
        return value.takeIf { it > 0L }
    }

    fun lastFailedAt(symbol: String): Long? {
        val value = runCatching { prefs.getLong(FAILURE_KEY_PREFIX + symbol, 0L) }.getOrDefault(0L)
        return value.takeIf { it > 0L }
    }

    /** 该 symbol 连续抓取失败的次数（成功一次即清零） */
    fun failureCount(symbol: String): Int =
        runCatching { prefs.getInt(FAILURE_COUNT_PREFIX + symbol, 0) }.getOrDefault(0)

    /** 记录一次**真正取到非空分红**的时间（与 [markFetched] 的「抓过」区分） */
    fun markDataFetched(symbol: String) {
        runCatching { prefs.edit().putLong(DATA_AT_PREFIX + symbol, System.currentTimeMillis()).apply() }
    }

    /** 记录一次「源返回空结果、沿用库内旧记录」。只做展示簿记，不参与清库 / 负缓存 / 退避判定 */
    fun markEmptyResult(symbol: String) {
        runCatching { prefs.edit().putLong(MISS_AT_PREFIX + symbol, System.currentTimeMillis()).apply() }
    }

    /**
     * 某标的分红数据的新鲜度（只供 UI 提示，不参与抓取 / 缓存 / 退避）。
     *
     * 老用户第一次命中「空结果保留旧值」时 `lastDataAt` 还是 null，
     * 此时提示退化为不带天数的文案，不会编造天数。
     */
    fun freshness(symbol: String): DividendDataFreshness {
        val normalized = StockCodeManager.normalizeSymbol(symbol)
        fun read(prefix: String): Long? = runCatching {
            prefs.getLong(prefix + normalized, 0L)
        }.getOrDefault(0L).takeIf { it > 0L }
        return DividendDataFreshness(
            lastDataAt = read(DATA_AT_PREFIX),
            lastMissedAt = read(MISS_AT_PREFIX)
        )
    }

    /**
     * 一次读出所有 Long 值（批量缓存判定用），取代逐标的 N 次同步 SP 读。
     *
     * 里面混着失败标记（前缀键），统一按 Long 过滤即可，不影响按 symbol 的查表。
     */
    fun allLongEntries(): Map<String, Long> = runCatching {
        prefs.all.mapNotNull { (key, value) ->
            (value as? Long)?.takeIf { it > 0L }?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private companion object {
        /** 「上次抓取时间」存放的 preferences 名 */
        const val FETCH_STATE_PREFS = "dividend_fetch_state"

        /** 失败时间戳与「抓过」时间戳共用一份 SP，靠前缀区分，避免多一个文件 */
        const val FAILURE_KEY_PREFIX = "failed_"
        const val FAILURE_COUNT_PREFIX = "failedcnt_"

        /**
         * 「最近一次真正抓到**非空**分红」的时间（UI 陈旧提示用）。
         * 与「抓过时间」刻意分开：后者空结果也会写，无法回答「当前展示的记录是哪天抓回来的」。
         */
        const val DATA_AT_PREFIX = "dataAt_"

        /** 「最近一次源返回空结果、沿用库内旧记录」的时间（UI 陈旧提示用） */
        const val MISS_AT_PREFIX = "missAt_"
    }
}
