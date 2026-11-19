package com.example.stockdiary.data.repository

import android.content.Context
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager

/**
 * 分红抓取状态簿记（SharedPreferences，跨进程存活）。
 *
 * 从 [DividendRepository] 外提：这里只管「抓过 / 刚失败 / 抓到了非空数据」三类时间戳的读写，
 * 不参与缓存、解析或编排。键名与前缀保持原样，旧安装升级后状态可直接沿用。
 *
 * ## 为什么支持批量落盘
 *
 * 一次预取会逐只写 [markFetched] / [markFailed]，110 只标的若逐只 `commit()` 就是 110 次
 * 同步磁盘写；而原来的逐只 `apply()` 又是异步的——进程在预取刚结束、落盘队列还没刷完时被杀，
 * 「今天抓过」的结论会全部丢失，下一轮冷启动重抓一遍。
 *
 * 所以预取轮次内改为：**内存立即可见 + 分段检查点 + 轮末收尾**。
 * [beginBatch] / [endBatch] 由编排层包住整轮（正常结束、预算用尽、取消、抛异常都会走到 `finally`）。
 * 轮内每累计 [CHECKPOINT_MAX_PENDING] 条待落盘记录，或距上次落盘超过
 * [CHECKPOINT_MAX_INTERVAL_MS]，就先合并落盘一次：进程被杀时最多丢一个检查点区间，
 * 而不是整轮。读取方法会先查批次内待落盘的值，因此同一轮里的 [isFetchedToday] 去重仍然即时准确。
 *
 * ## 落盘方式：检查点异步 apply，轮末同步 commit
 *
 * 检查点与轮末刻意用两种口径。原因：持批次的不只是后台预取——详情页的
 * `AssetDetailViewModel` 也在主线程（`Main.immediate`）里抓分红，抓成功就 [markFetched]，
 * 命中检查点时如果同步 `commit()`，就是**主线程上一次 fsync**（StrictMode DiskWriteViolation、
 * 慢 flash 上掉帧），而且主线程随后的读取还要排队等同一把 [lock]。
 *
 * 检查点的作用只是「别攒整轮」，本身不需要「已写进磁盘」这个强保证：
 * 「同轮立刻可见」由待落盘表保证，进程被杀最多丢一个区间——异步 `apply()` 就能满足。
 * 轮末 [endBatch] 则保持同步 `commit()`：它的调用点在 `NonCancellable + Dispatchers.IO` 上，
 * 且是「本轮结论」的最后一道保证，值得等磁盘。
 *
 * 不在批次内时保持原有 `apply()` 行为（单标的查询、详情页等零星写入）。
 */
internal class DividendFetchStateStore(
    context: Context,
    /** 时钟可注入，仅用于单测推进 30 秒检查点窗口；生产默认系统时间 */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val prefs = context.getSharedPreferences(FETCH_STATE_PREFS, Context.MODE_PRIVATE)

    /** 保护批次深度与待落盘表；所有读写都要拿它，保证批次内读取看到即时值 */
    private val lock = Any()

    private var batchDepth = 0

    /** 上一次批量落盘（检查点或轮末）的时刻；批次开始时重置 */
    private var lastFlushAtMs = 0L

    /** 待落盘：symbol → 抓取完成时间 */
    private val pendingFetched = HashMap<String, Long>()

    /** 待落盘：symbol → 最近失败时间 */
    private val pendingFailureAt = HashMap<String, Long>()

    /** 待落盘：symbol → 本轮累计后的连续失败次数 */
    private val pendingFailureCount = HashMap<String, Int>()

    /** 待落盘：本轮已 clearFailure 的 symbol（落盘时删除失败键） */
    private val pendingFailureCleared = HashSet<String>()

    /** 待落盘：symbol → 最近抓到非空数据 / 最近空结果的时间（UI 陈旧提示用） */
    private val pendingDataAt = HashMap<String, Long>()
    private val pendingMissAt = HashMap<String, Long>()

    /** 开始一个预取批次；可重入（深度归零时才真正收尾落盘） */
    fun beginBatch() {
        synchronized(lock) {
            if (batchDepth == 0) lastFlushAtMs = clock()
            batchDepth++
        }
    }

    /**
     * 结束批次：深度归零时把本轮所有待落盘值合并成**一次同步 commit**。
     *
     * 这里必须同步：调用点在 `NonCancellable + Dispatchers.IO`，且这是本轮结论的最后一道保证。
     * 失败只记日志不抛：本轮结论留在内存，最多导致下次冷启动多抓一轮，不能影响预取收尾。
     * 调用方必须在 `finally` 里执行（取消 / 异常路径同样要落盘）。
     */
    fun endBatch() {
        synchronized(lock) {
            if (batchDepth == 0) return
            batchDepth--
            if (batchDepth == 0) flushLocked(synchronous = true)
        }
    }

    /** 清空全部簿记（「清除所有数据」时调用）。同步 commit：调用点都在 IO 协程，避免异步落盘来不及。 */
    fun clear() {
        synchronized(lock) {
            clearPendingLocked()
            runCatching { prefs.edit().clear().commit() }
        }
    }

    /** 记录一次抓取完成（不管抓没抓到）。必须在抓取**成功**后写，失败时写会把错误固化 24 小时 */
    fun markFetched(symbol: String) {
        val now = clock()
        synchronized(lock) {
            if (batchDepth > 0) {
                pendingFetched[symbol] = now
                maybeCheckpointLocked()
                return
            }
        }
        runCatching { prefs.edit().putLong(symbol, now).apply() }
    }

    /**
     * 记一次抓取失败（所有数据源都没应答）。
     *
     * 与 [markFetched] 是两个独立状态：这里按 [computeFailureBackoff] 指数退避，
     * 混在一起会把错误固化成结论。读-改-写必须原子：两个页面可能同时抓同一只标的。
     *
     * 批次内失败次数先在内存累加（以持久化值为基数），读取立即反映新档位；轮末一次落盘。
     */
    fun markFailed(symbol: String) {
        val now = clock()
        synchronized(lock) {
            if (batchDepth > 0) {
                pendingFailureCleared.remove(symbol)
                pendingFailureAt[symbol] = now
                val countKey = FAILURE_COUNT_PREFIX + symbol
                val base = pendingFailureCount[symbol]
                    ?: runCatching { prefs.getInt(countKey, 0) }.getOrDefault(0)
                pendingFailureCount[symbol] = base + 1
                maybeCheckpointLocked()
                return
            }
        }
        runCatching {
            synchronized(prefs) {
                val countKey = FAILURE_COUNT_PREFIX + symbol
                val newCount = prefs.getInt(countKey, 0) + 1
                prefs.edit()
                    .putLong(FAILURE_KEY_PREFIX + symbol, now)
                    .putInt(countKey, newCount)
                    .apply()
            }
        }
    }

    /** 抓取成功即清除失败标记与连续失败计数，让它立刻回到正常的 TTL 节奏 */
    fun clearFailure(symbol: String) {
        synchronized(lock) {
            if (batchDepth > 0) {
                pendingFailureAt.remove(symbol)
                pendingFailureCount.remove(symbol)
                pendingFailureCleared.add(symbol)
                maybeCheckpointLocked()
                return
            }
        }
        runCatching {
            prefs.edit()
                .remove(FAILURE_KEY_PREFIX + symbol)
                .remove(FAILURE_COUNT_PREFIX + symbol)
                .apply()
        }
    }

    fun lastFetchedAt(symbol: String): Long? {
        synchronized(lock) {
            // 批次内待落盘的值优先：否则同一轮里刚抓过的标的会被判成「没抓过」而重抓
            pendingFetched[symbol]?.let { return it.takeIf { value -> value > 0L } }
        }
        val value = runCatching { prefs.getLong(symbol, 0L) }.getOrDefault(0L)
        return value.takeIf { it > 0L }
    }

    fun lastFailedAt(symbol: String): Long? {
        synchronized(lock) {
            pendingFailureAt[symbol]?.let { return it.takeIf { value -> value > 0L } }
            if (pendingFailureCleared.contains(symbol)) return null
        }
        val value = runCatching { prefs.getLong(FAILURE_KEY_PREFIX + symbol, 0L) }.getOrDefault(0L)
        return value.takeIf { it > 0L }
    }

    /** 该 symbol 连续抓取失败的次数（成功一次即清零） */
    fun failureCount(symbol: String): Int {
        synchronized(lock) {
            pendingFailureCount[symbol]?.let { return it }
            if (pendingFailureCleared.contains(symbol)) return 0
        }
        return runCatching { prefs.getInt(FAILURE_COUNT_PREFIX + symbol, 0) }.getOrDefault(0)
    }

    /** 记录一次**真正取到非空分红**的时间（与 [markFetched] 的「抓过」区分） */
    fun markDataFetched(symbol: String) {
        writeAuxTimestamp(symbol, DATA_AT_PREFIX, pendingDataAt)
    }

    /** 记录一次「源返回空结果、沿用库内旧记录」。只做展示簿记，不参与清库 / 负缓存 / 退避判定 */
    fun markEmptyResult(symbol: String) {
        writeAuxTimestamp(symbol, MISS_AT_PREFIX, pendingMissAt)
    }

    private fun writeAuxTimestamp(symbol: String, prefix: String, pending: MutableMap<String, Long>) {
        val now = clock()
        synchronized(lock) {
            if (batchDepth > 0) {
                pending[symbol] = now
                maybeCheckpointLocked()
                return
            }
        }
        runCatching { prefs.edit().putLong(prefix + symbol, now).apply() }
    }

    /**
     * 分段检查点：待落盘条目达到上限，或距上次落盘超过时间窗口，就提前落盘一次。
     *
     * 进程被杀是这台设备上的常态（日志里两次抓到一半就没结束行）。整轮只在轮末落盘，
     * 一旦被杀就是整轮重抓；检查点把丢失窗口压到一个区间（最多 N 条 / 30 秒）。
     *
     * **走异步 apply**：本方法会在持锁状态下被任意线程调用，包括主线程
     * （详情页抓分红成功后 [markFetched]）。同步 commit 会让主线程等一次 fsync。
     */
    private fun maybeCheckpointLocked() {
        if (pendingEntryCountLocked() >= CHECKPOINT_MAX_PENDING ||
            clock() - lastFlushAtMs >= CHECKPOINT_MAX_INTERVAL_MS
        ) {
            flushLocked(synchronous = false)
        }
    }

    /** 待落盘「条目」数：失败次数与失败时间按同一 symbol 计一条，不重复累加 */
    private fun pendingEntryCountLocked(): Int =
        pendingFetched.size + pendingFailureAt.size + pendingFailureCleared.size +
            pendingDataAt.size + pendingMissAt.size

    /**
     * 某标的分红数据的新鲜度（只供 UI 提示，不参与抓取 / 缓存 / 退避）。
     *
     * 老用户第一次命中「空结果保留旧值」时 `lastDataAt` 还是 null，
     * 此时提示退化为不带天数的文案，不会编造天数。
     */
    fun freshness(symbol: String): DividendDataFreshness {
        val normalized = StockCodeManager.normalizeSymbol(symbol)
        fun readPending(pending: Map<String, Long>): Long? =
            pending[normalized]?.takeIf { it > 0L }
        fun readPersisted(prefix: String): Long? = runCatching {
            prefs.getLong(prefix + normalized, 0L)
        }.getOrDefault(0L).takeIf { it > 0L }

        synchronized(lock) {
            return DividendDataFreshness(
                lastDataAt = readPending(pendingDataAt) ?: readPersisted(DATA_AT_PREFIX),
                lastMissedAt = readPending(pendingMissAt) ?: readPersisted(MISS_AT_PREFIX)
            )
        }
    }

    /**
     * 一次读出所有 Long 值（批量缓存判定用），取代逐标的 N 次同步 SP 读。
     *
     * 批次内尚未落盘的抓取 / 失败 / 新鲜度时间戳会覆盖到结果上，
     * 保证同一轮里后续的批量读取也看到即时结论。失败次数是 Int，与原来一样不在此列。
     */
    fun allLongEntries(): Map<String, Long> {
        synchronized(lock) {
            val result = runCatching {
                prefs.all.mapNotNull { (key, value) ->
                    (value as? Long)?.takeIf { it > 0L }?.let { key to it }
                }.toMap().toMutableMap()
            }.getOrDefault(mutableMapOf())

            pendingFetched.forEach { (symbol, at) -> result[symbol] = at }
            pendingFailureAt.forEach { (symbol, at) -> result[FAILURE_KEY_PREFIX + symbol] = at }
            pendingDataAt.forEach { (symbol, at) -> result[DATA_AT_PREFIX + symbol] = at }
            pendingMissAt.forEach { (symbol, at) -> result[MISS_AT_PREFIX + symbol] = at }
            pendingFailureCleared.forEach { symbol ->
                result.remove(FAILURE_KEY_PREFIX + symbol)
                result.remove(FAILURE_COUNT_PREFIX + symbol)
            }
            return result
        }
    }

    /**
     * 合并写出本轮全部待落盘值。
     *
     * @param synchronous true（轮末 [endBatch]）：`commit()` 同步落盘，返回 false 说明没写进磁盘，
     *   必须 WARN 留痕，否则「以为记住了、重启全忘」无从排查。
     *   false（分段检查点）：`apply()` 异步落盘。apply 没有返回值，**不能**谎报落盘结果，
     *   日志只说明「已提交」。这条路主要是为了避免主线程 fsync。
     *
     * 失败日志要能回答「丢了什么」：已抓只数 / 失败只数 / 辅助簿记条数。
     * 无论成功失败都清空待落盘表，避免跨轮无限累积；失败后下一轮冷启动重抓即可。
     *
     * 注意：待落盘表在 `finally` 里清空，与落盘是否真的完成无关——异步 apply 之后
     * 读取会直接命中 SharedPreferences 的内存副本（apply 同步改内存），所以清空是安全的。
     */
    private fun flushLocked(synchronous: Boolean) {
        val hasPending = pendingFetched.isNotEmpty() || pendingFailureAt.isNotEmpty() ||
            pendingFailureCount.isNotEmpty() || pendingFailureCleared.isNotEmpty() ||
            pendingDataAt.isNotEmpty() || pendingMissAt.isNotEmpty()
        if (!hasPending) return

        val fetchedCount = pendingFetched.size
        val failedCount = pendingFailureAt.size
        val auxCount = pendingDataAt.size + pendingMissAt.size
        try {
            val editor = prefs.edit()
            pendingFetched.forEach { (symbol, at) -> editor.putLong(symbol, at) }
            pendingFailureAt.forEach { (symbol, at) -> editor.putLong(FAILURE_KEY_PREFIX + symbol, at) }
            pendingFailureCount.forEach { (symbol, count) -> editor.putInt(FAILURE_COUNT_PREFIX + symbol, count) }
            pendingFailureCleared.forEach { symbol ->
                editor.remove(FAILURE_KEY_PREFIX + symbol)
                editor.remove(FAILURE_COUNT_PREFIX + symbol)
            }
            pendingDataAt.forEach { (symbol, at) -> editor.putLong(DATA_AT_PREFIX + symbol, at) }
            pendingMissAt.forEach { (symbol, at) -> editor.putLong(MISS_AT_PREFIX + symbol, at) }

            if (synchronous) {
                // 轮末：一次同步 commit。落盘失败必须留痕
                if (!editor.commit()) {
                    AppLogger.w(
                        "分红抓取状态批量落盘失败(commit=false)：已抓 $fetchedCount 只、失败 $failedCount 只、" +
                            "辅助簿记 $auxCount 条；本轮结论只在内存，重启后会重抓",
                        TAG
                    )
                }
            } else {
                // 分段检查点：异步落盘，不阻塞调用线程（可能是主线程）
                editor.apply()
                // apply 无返回值——这里只能说明「已提交」，不能说「已落盘成功」
                AppLogger.i(
                    "分红抓取状态批量落盘已提交(异步)：已抓 $fetchedCount 只、失败 $failedCount 只、" +
                        "辅助簿记 $auxCount 条",
                    TAG
                )
            }
        } catch (e: Exception) {
            AppLogger.w(
                "分红抓取状态批量落盘异常：已抓 $fetchedCount 只、失败 $failedCount 只、" +
                    "辅助簿记 $auxCount 条；重启后会重抓: ${e.message}",
                TAG
            )
        } finally {
            clearPendingLocked()
            // 无论落盘成功与否都重置窗口：失败后不应每次写入都重试一次磁盘
            lastFlushAtMs = clock()
        }
    }

    private fun clearPendingLocked() {
        pendingFetched.clear()
        pendingFailureAt.clear()
        pendingFailureCount.clear()
        pendingFailureCleared.clear()
        pendingDataAt.clear()
        pendingMissAt.clear()
    }

    private companion object {
        /** 日志 tag：沿用原 `DividendRepository` 口径，既有日志过滤规则不用改 */
        const val TAG = "DividendRepository"

        /** 分段检查点：累计待落盘条目达到该数量就提前落盘一次（异步 apply） */
        const val CHECKPOINT_MAX_PENDING = 10

        /** 分段检查点：距上次落盘超过该时长也提前落盘，避免低频写入一直攒到轮末（异步 apply） */
        const val CHECKPOINT_MAX_INTERVAL_MS = 30_000L

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
