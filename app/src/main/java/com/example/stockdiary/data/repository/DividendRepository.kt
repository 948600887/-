package com.example.stockdiary.data.repository

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.data.contract.withHeartbeat
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.dividend.isFetchedToday
import com.example.stockdiary.data.dividend.shouldRefetchOtc
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.network.EastmoneyDividendApiService
import com.example.stockdiary.network.FundDividendApiService
import com.example.stockdiary.network.OtcBackoffStore
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// ==========================================================================
// 分红仓库（编排层）
//
// 拆分后的职责边界：
// - 抓取状态 / 新鲜度簿记 → DividendFetchStateStore
// - 内存缓存 + 三层读取判定 → DividendMemoryCache / DividendCacheReader
// - 东财合并请求批量预取 → DividendBatchPrefetcher
// - 三个数据源适配与解析映射 → DividendSourceFetcher / DividendRecordMapping
// - 写库与残缺解析保护 → DividendWriter
// - 策略纯函数（退避 / 负缓存 / 切块 / 汇总文案）→ DividendRepositoryPolicy
//
// 本类只保留：单例与公开契约、单标的查询编排、逐条预取的预算与互斥编排。
// ==========================================================================

class DividendRepository private constructor(
    private val context: Context
) {
    private val eastmoneyApi by lazy { EastmoneyDividendApiService(context) }
    private val dividendDao by lazy { StockDatabase.getInstance(context).dividendDao() }

    /** 抓取状态（SharedPreferences）簿记 */
    private val fetchStateStore by lazy { DividendFetchStateStore(context) }

    /** 内存缓存与命中统计 */
    private val memoryCache = DividendMemoryCache()

    /** 缓存三层判定（内存 / Room / 「抓过」时间戳） */
    private val cacheReader by lazy { DividendCacheReader(memoryCache, fetchStateStore, dividendDao) }

    /** 数据源适配（东财 / 新浪 / 天天基金） */
    private val sourceFetcher by lazy { DividendSourceFetcher(eastmoneyApi) }

    /** 批量预取（东财合并请求） */
    private val batchPrefetcher by lazy { DividendBatchPrefetcher(eastmoneyApi, cacheReader) }

    /** 写库与残缺解析保护 */
    private val writer by lazy { DividendWriter(context, dividendDao, memoryCache) }

    init {
        // 注入跨进程退避持久化：使天天基金限流(514/429)与主机不可达(DNS)退避窗口在冷启动后
        // 仍能记得，避免「进程被系统回收 → 内存退避全丢 → 冷启动又打一波 514」的放大器。
        // backoffStore 是进程级 companion 字段，所有 FundDividendApiService 实例共享同一份。
        FundDividendApiService.backoffStore = SharedPreferencesOtcBackoffStore(context)
    }

    /** 设置页计数/导出用：全部分红（流） */
    fun getAllDividendsFlow(): Flow<List<Dividend>> = dividendDao.getAllFlow()

    /** 设置页导出用：同步取全部分红（避免 ViewModel 直连 DAO） */
    suspend fun getAllDividendsSync(): List<Dividend> = dividendDao.getAllSync()

    /**
     * 本轮 [prefetchDividends] 是否撞过天天基金限流（514/429）。
     *
     * 进程级的 [FundDividendApiService.otcRateLimitSignal] 是只增计数，
     * 每轮预取在轮首/轮末各取一次做差，判「这一轮」是否新增了限流；
     * 这里存的是「本轮新增过」的结论，由 [consumeOtcRateLimitWarning] 消费一次（读即清零）。
     */
    @Volatile
    private var _otcRateLimitedThisCycle: Boolean = false

// ==========================================================================
// §1 抓取状态 / 新鲜度（委托 DividendFetchStateStore）
// ==========================================================================

    /**
     * 清空全部抓取状态簿记（"清除所有数据"时调用）。
     *
     * 否则库清空了，但每条标的仍带着「X 分钟/7 天内抓过且没分红」的时间戳，
     * [shouldReuseEmptyResult] 会把它们当成"确实没分红"复用，最长 7 天不再真正重抓，
     * 用户以为清干净了、分红却一直空白。见 [AssetRepository.deleteAllData]。
     */
    fun clearFetchState() {
        fetchStateStore.clear()
    }

    /**
     * 某标的分红数据的新鲜度（只供 UI 提示，不参与抓取 / 缓存 / 退避）。
     *
     * 读的是本次改动新写的两个键：正常升级上来的老用户第一次命中「空结果保留
     * 旧值」时 `lastDataAt` 还是 null，此时提示退化为不带天数的文案，不会编造天数。
     */
    fun dividendFreshness(symbol: String): DividendDataFreshness = fetchStateStore.freshness(symbol)

// ==========================================================================
// §2 批量预取（委托 DividendBatchPrefetcher）
// ==========================================================================

    /**
     * 一次性把一批标的的分红抓完，结果**按次返回**，由调用方在随后的逐条
     * [getDividendsBySymbol] 里作为 `batchResults` 传回来。
     *
     * 结果按次返回而不是放共享字段：资产页与持仓页同时刷新时，两个预取周期
     * 曾互相 clear / remove，整批退化成逐条级联，请求风暴照旧。
     */
    suspend fun prefetchDividendsBatch(
        symbols: List<String>,
        forceRefresh: Boolean = false
    ): Map<String, List<DividendRecord>> = batchPrefetcher.prefetch(symbols, forceRefresh)

// ==========================================================================
// §3 缓存读取（委托 DividendCacheReader）
// ==========================================================================

    /**
     * 冷启动专用的缓存读取：**TTL 过期也照用**，只留一个很硬的上限。
     *
     * 返回值可能是**空列表**：那表示「抓过，且确实没有分红」，是有效答案，不是没数据。
     * 只有 null 才代表调用方该去抓。
     */
    suspend fun getCachedAllowingStale(symbol: String): List<DividendRecord>? =
        cacheReader.getCachedAllowingStale(symbol)

    /**
     * 批量版 [getCachedAllowingStale]：一次读 N 个标的（2 次 IO 取代 2N 次）。
     *
     * @param primeCache true = 把宽口径（24h~30 天）读到的记录也写进内存缓存，
     *        供进程刚重启时预热；默认 false，既有调用方行为完全不变。
     * @return symbol（已规范化）→ 记录；值为 null 表示「连过期数据都没有，该去抓」。
     */
    suspend fun getCachedAllowingStaleBatch(
        symbols: List<String>,
        primeCache: Boolean = false
    ): Map<String, List<DividendRecord>?> = cacheReader.getCachedAllowingStaleBatch(symbols, primeCache)

    /**
     * 把库里有记录的标的预热进内存缓存，返回「仍然需要联网抓取」的 symbol 列表。
     *
     * 冷启动先用本方法做一次宽口径批量读（零网络、2 次 IO）把有库记录的标的
     * 预热进内存缓存，只让「连过期数据都没有」的标的去联网 —— 否则日历页会为
     * 每个标的都判 TTL 过期、全量重抓。
     *
     * @param forceRefresh true = 不预热、直接返回全部 symbol 让调用方全量重抓
     * @return 仍需抓取的 symbol（**原始 symbol**，未规范化）
     */
    suspend fun primeDividendCache(symbols: List<String>, forceRefresh: Boolean): List<String> =
        cacheReader.prime(symbols, forceRefresh)

// ==========================================================================
// §4 抓取并发与预取互斥
// ==========================================================================

    /**
     * 预取分红逐条抓取的并发上限。
     *
     * 从前是 8，在天天基金这一唯一场外基金主源上，8 并发首发会在一波内打出多个 514；
     * 现降到 2，与基金源 fhsp 健康档(2) 对齐、刻意低于同步总闸(6)，根治 514 首发浪涌。
     */
    private val maxConcurrentFetch = 2

    /**
     * 预取互斥闸：前台刷新后的后台预取、日历页预取、定期补齐 Worker 只能有一份在跑。
     *
     * 用 [Mutex.tryLock] 而不是 `withLock`：抢不到锁的一侧**立即放弃本轮**，
     * 不排队等待。前台刷新不能被 Worker 的 4 分钟预算拖住；被跳过的一侧下一个周期
     * 自然会续上，已成功的标的由 markFetched 的跨进程时间戳去重。
     */
    private val prefetchGate = Mutex()

// ==========================================================================
// §5 单例与缓存维护
// ==========================================================================

    companion object {
        /** 场外基金退避窗口持久化名（跨进程存活，见 [SharedPreferencesOtcBackoffStore]）。 */
        private const val OTC_BACKOFF_PREFS = "otc_backoff_state"

        /**
         * 分红预取轮次序号（进程内自增，仅供收尾汇总日志做归属）。
         *
         * [prefetchDividends] 不总在 [com.example.stockdiary.data.sync.AssetSyncManager]
         * 的同步轮次里（日历页也会直接调它），所以自带一套轻量序号。纯观测：只给一行 INFO 用。
         */
        private val prefetchRoundSeq = AtomicInteger(0)

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DividendRepository? = null

        fun getInstance(context: Context): DividendRepository {
            return instance ?: synchronized(this) {
                instance ?: DividendRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    fun clearCache() {
        memoryCache.clear()
    }

    fun getCacheStats(): Map<String, Any> = memoryCache.stats()

    fun close() {
        memoryCache.clear()
    }

    /**
     * 场外基金退避窗口的 SharedPreferences 持久化实现（跨进程存活）。
     *
     * 只存两个**确定性短退避窗口**（限流 514/429、主机不可达 DNS），不持久化 fhsp 整源熔断
     * —— 后者是动态状态机、已有自愈机制，跨进程持久化反而可能误判「已恢复的源为熔断」。
     */
    private class SharedPreferencesOtcBackoffStore(ctx: Context) : OtcBackoffStore {
        private val prefs = ctx.getSharedPreferences(OTC_BACKOFF_PREFS, Context.MODE_PRIVATE)
        override fun get(key: String): Long =
            runCatching { prefs.getLong(key, 0L) }.getOrDefault(0L)
        override fun set(key: String, value: Long) {
            runCatching { prefs.edit().putLong(key, value).apply() }
        }
    }

    /**
     * 消费本轮「天天基金限流」信号。
     *
     * 返回 true 表示这一轮手动刷新撞过 514/429（部分场外基金分红因限流没抓全），
     * 并**清零**，避免下一次刷新（或同一轮别处的入口）重复提示。
     */
    internal fun consumeOtcRateLimitWarning(): Boolean {
        val hit = _otcRateLimitedThisCycle
        _otcRateLimitedThisCycle = false
        return hit
    }

// ==========================================================================
// §6 单标的查询与抓取编排
// getDividendsBySymbol 总入口 → cacheReader 三层判定 → fetchSource 按标的分派
// 到东财 / 新浪 / 场外基金。失败抛 NoDividendAnswer 而不是返回 emptyList——
// 「没问到」与「没有分红」不能混。
// ==========================================================================

    /**
     * 判断 [symbol] 今天是否已经成功抓过一次分红。
     *
     * 基于「上次抓取时间戳」与**同一自然日**比较（按设备时区），跨零点刷新也要算「今天」。
     */
    private fun isFetchedToday(symbol: String): Boolean =
        isFetchedToday(fetchStateStore.lastFetchedAt(symbol), System.currentTimeMillis())

    /**
     * 取某个标的的分红记录。
     *
     * @param forceRefresh true 时跳过缓存强制重抓（用户点「刷新」走这条）
     * @param batchResults 同一批 [prefetchDividendsBatch] 的结果，key 是**标准化后**的 symbol。
     *        命中就跳过逐条级联；为 null 时行为与从前完全一致（单标的查询传 null）。
     */
    suspend fun getDividendsBySymbol(
        symbol: String,
        forceRefresh: Boolean = false,
        batchResults: Map<String, List<DividendRecord>>? = null
    ): List<DividendRecord> {
        val normalizedSymbol = StockCodeManager.normalizeSymbol(symbol)

        if (!forceRefresh) {
            cacheReader.tryGetFromCache(normalizedSymbol)?.let { return it }

            // 上一轮所有数据源都没应答，且还在退避窗内 → 这一轮一个请求都不发，
            // 只用已经有的（内存旧值 / 库里记录）。场外基金没有批量入口，
            // 上百只失败标的每轮各重打一次就是请求风暴
            if (shouldSkipAfterFailure(
                    lastFailedAt = fetchStateStore.lastFailedAt(normalizedSymbol),
                    now = System.currentTimeMillis(),
                    ttl = computeFailureBackoff(
                        count = fetchStateStore.failureCount(normalizedSymbol),
                        everSucceeded = fetchStateStore.lastFetchedAt(normalizedSymbol) != null
                    )
                )
            ) {
                return memoryCache.get(normalizedSymbol)?.data
                    ?: dividendRecordsFromDb(dividendDao.getBySymbol(normalizedSymbol))
            }
        }

        memoryCache.recordMiss()

        val existingEntry = memoryCache.get(normalizedSymbol)
        // forceRefresh=true 时强制全量刷新，避免旧错误数据通过增量合并残留
        val isIncremental = !forceRefresh && existingEntry != null && !existingEntry.needsFullFetch()

        return try {
            val data = fetchDividendData(normalizedSymbol, batchResults)

            if (data.isNotEmpty()) {
                writer.saveFromFetch(normalizedSymbol, data, isIncremental)
                // 展示簿记：这批记录确实是「今天抓回来的」，陈旧提示据此折算天数
                fetchStateStore.markDataFetched(normalizedSymbol)
            } else if (!isIncremental) {
                // 全量刷新拿到空结果：清库前先看库里有没有旧自动记录。
                //
                // 真机诊断：刷新时 forceRefresh=true 走全量路径，一批基金页面「有表头但解析 0 条」
                // 曾被当成「确实无分红」，这里 deleteNonManualBySymbol 把旧记录删光并写 7 天负缓存，
                // 年化收入当场缩水；另一批标的撞 514 限流又保留旧值，于是每轮分红集合都在洗牌。
                // 上游已把「解析可疑的 0 条」改成失败（不走到这里），此处再兜一道：
                // 库里只要还有自动记录，就不因一次空结论清空，mergedData 会读回旧记录。
                //
                // 取舍（R1）：真正停止分红的标的会多留一段旧值，等 7 天负缓存过期或手动
                // 修复；本轮不实现「连续 N 次可信空结论」计数，避免过度设计。手动记录始终保留。
                val existingAutoCount = dividendDao.getBySymbol(normalizedSymbol).count { !it.isManual }
                if (existingAutoCount > 0) {
                    // 展示簿记：这次确实没取到新数据，旧记录继续沿用 ——
                    // 详情页据此提示「最近一次未取到、来自 N 天前」
                    fetchStateStore.markEmptyResult(normalizedSymbol)
                    AppLogger.w(
                        "空结果但库里仍有 $existingAutoCount 条自动分红记录，保留旧值不清库: $normalizedSymbol",
                        "DividendRepository"
                    )
                } else {
                    AppLogger.w(
                        "空结果清理非手动分红记录: $normalizedSymbol，删除 0 条（库里本就无自动记录）",
                        "DividendRepository"
                    )
                    dividendDao.deleteNonManualBySymbol(normalizedSymbol)
                }
            }

            val mergedData = if (isIncremental && existingEntry != null) {
                mergeDividendRecords(existingEntry.data, data)
            } else {
                // 全量刷新后从数据库读取完整记录（含保留的手动记录），保证缓存与DB一致
                val dbRecords = dividendDao.getBySymbol(normalizedSymbol)
                if (dbRecords.isNotEmpty()) {
                    dividendRecordsFromDb(dbRecords)
                } else {
                    data
                }
            }

            // 抓成功了就记一笔，**不管抓没抓到**。空结果也是结论，不记下来
            // 下次冷启动会再抓一遍。「抓成功」≠「抓到了」：所有数据源都没应答时
            // fetchDividendData 抛 DividendSourceUnavailableException，直接落到下面的
            // catch —— 那时一个字都不写，否则一次网络抖动会被固化成「这只没有分红」最长 7 天
            fetchStateStore.markFetched(normalizedSymbol)
            // 抓通过了就清掉失败退避标记，让它立刻回到正常的 TTL 节奏
            fetchStateStore.clearFailure(normalizedSymbol)

            memoryCache.put(
                normalizedSymbol,
                DividendCacheEntry(
                    data = mergedData,
                    timestamp = System.currentTimeMillis(),
                    lastFullFetch = if (isIncremental) existingEntry?.lastFullFetch ?: System.currentTimeMillis() else System.currentTimeMillis()
                )
            )
            memoryCache.trimIfNeeded()
            mergedData
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            // 所有数据源都没应答：只记失败退避，**不写** markFetched —— 那会把一次
            // 网络抖动固化成「这只没有分红」最长 7 天，界面还一声不吭
            AppLogger.w(
                "分红数据源未应答[$normalizedSymbol]，退避中不再重打: ${e.message}",
                "DividendRepository"
            )
            fetchStateStore.markFailed(normalizedSymbol)
            memoryCache.get(normalizedSymbol)?.data
                ?: dividendRecordsFromDb(dividendDao.getBySymbol(normalizedSymbol))
        } catch (e: Exception) {
            AppLogger.e("获取分红数据失败: ${e.message}", e, "DividendRepository")
            // 抓不回来也不该让已入库的分红凭空消失：内存有旧值用旧值，没有就退回
            // 数据库记录（离线/限流时 24h TTL 刚过期的常见场景）
            memoryCache.get(normalizedSymbol)?.data
                ?: dividendRecordsFromDb(dividendDao.getBySymbol(normalizedSymbol))
        }
    }

    /**
     * 批量取多只标的的分红记录（日历/详情一次生成用）。
     *
     * 先用一次 `IN` 查询把内存/数据库能覆盖的标的一次拿齐，只有「连过期数据都没有」的
     * 标的才回落到逐符号抓取——回落路径就是 [getDividendsBySymbol] 本身，因此 TTL、失败
     * 退避、空结果 vs 无数据等语义与逐 symbol 完全一致。
     *
     * 返回的 key 是调用方传入的原始 symbol，方便直接按 `asset.symbol` 取值。
     */
    suspend fun getDividendRecordsBatch(symbols: List<String>): Map<String, List<DividendRecord>> {
        val distinct = symbols.distinct()
        if (distinct.isEmpty()) return emptyMap()
        // 走公开成员而不是 cacheReader：保留「调用方可整体替换批量读」的既有行为（含单测桩）
        val cached = getCachedAllowingStaleBatch(distinct, primeCache = false)
        val out = LinkedHashMap<String, List<DividendRecord>>(distinct.size)
        for (symbol in distinct) {
            val records = cached[StockCodeManager.normalizeSymbol(symbol)]
            if (records != null) {
                out[symbol] = records
                continue
            }
            try {
                out[symbol] = getDividendsBySymbol(symbol)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单只失败只影响它自己：与逐 symbol 循环里的 try/catch 行为一致
                AppLogger.w("批量读取分红失败[$symbol]，按无记录处理: ${e.message}", "DividendRepository")
            }
        }
        return out
    }

    /**
     * 调一个数据源：应答过就置位 [answered] 并返回数据（**可以是空列表**），
     * 没应答就记一条警告返回空。用累加器而不是返回值，是因为成员函数改不到调用方的
     * 局部变量，而「本轮有没有人应答过」是所有数据源的共同结论。
     */
    private suspend fun fetchSource(
        sourceName: String,
        code: String,
        answered: AtomicBoolean,
        block: suspend () -> List<DividendRecord>
    ): List<DividendRecord> = try {
        block().also { answered.set(true) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DividendSourceUnavailableException) {
        AppLogger.w("${sourceName}分红源未应答[$code]: ${e.message}", "DividendRepository")
        emptyList()
    }

    internal suspend fun fetchDividendData(
        normalizedSymbol: String,
        batchResults: Map<String, List<DividendRecord>>?
    ): List<DividendRecord> {
        // 批量预取优先。[prefetchDividendsBatch] 已经用少量请求把这批标的问完了，
        // 命中就直接用，省掉逐条的级联（ETF 空手还要顺序试三种代码格式，再 3 个请求）
        val prefetched = batchResults?.get(normalizedSymbol)
        if (prefetched != null && prefetched.isNotEmpty()) return prefetched

        // 有条目 ⇔ 这个标的参与过东财批量查询（场外基金与批量失败时无条目，行为不变）。
        // 批量说没有的标的，级联里跳过重复问东财那一步，但保留 Sina 兜底 ——
        // 那是独立的第二来源，不能因为合并了请求就把它一起省掉
        val eastmoneyAnswered = prefetched != null

        // fund_ 前缀必须在这里剥掉。extractPureCode 只认 sh/sz/bj，不认 fund_，
        // 不剥的话 stockCode 会带着 "fund_" 传下去，于是 isOTCFundCode / isETF 全部判错，
        // 最后拿着 "fund_004502" 去请求接口——场外基金的分红永远抓不到，而且失败是静默的
        val pureSymbol = StockCodeManager.stripFundPrefix(normalizedSymbol)
        val stockCode = StockCodeManager.extractPureCode(pureSymbol)
        // 剥完前缀还不够：fund_ 本身就是「场外份额」的显式标记，但 400030 / 420102
        // 落在 400/420 号段，isOTCFundCode 按号段认不出它们。不把前缀算进场外判定，
        // 这些真基金会被送进东财 + 新浪股票接口，而场外基金的分红只在天天基金有
        val isOTCFund = StockCodeManager.isOTCFund(normalizedSymbol)
        val isEtf = !StockCodeManager.hasFundPrefix(normalizedSymbol) &&
            StockCodeManager.isETF(pureSymbol)

        // 场内交易型基金：含 ETF 与「非 ETF 的场内基金」（REITs 508xxx、封闭式基金 500/501xxx），
        // 以及 LOF（16xxxx/18xxxx）。它们的分红/收益分配只在天天基金有，
        // 东财股票分红报告与 Sina 都不覆盖。从前只把 isEtf 当门槛，导致 REIT 永远到不了
        // 基金分红源、静默显示 0 并被 7 天负缓存固化（见 sh508085）。
        val category = StockCodeManager.classifyCode(pureSymbol)
        val isExchangeFund = isEtf || (!StockCodeManager.hasFundPrefix(normalizedSymbol) &&
            (category == StockCodeManager.CodeCategory.EXCHANGE_FUND ||
                category == StockCodeManager.CodeCategory.LOF))

        // 「本轮有没有任何一个数据源应答过」。只要有一个应答，空结果就是有效结论
        // （该写负缓存）；一个都没应答说明是网络/限流问题，上抛让调用方什么都不写。
        //
        // A19 修复（阻断）：东财批量只覆盖股票/场外基金的分红报告，场内基金（ETF/REIT/LOF）
        // 的分红**不在**东财股票分红报告里。因此标的「在批量里且返回空」对场内基金
        // **不代表**它没分红——批量预取本来就不问它的基金源。只有「东财确实是该标的的
        // 权威来源（股票）且批量已应答」才预置 answered=true；场内基金一律留空。
        val answered = AtomicBoolean(eastmoneyAnswered && !isExchangeFund)

        var data: List<DividendRecord> = when {
            isOTCFund -> fetchSource("天天基金", stockCode, answered) { fetchFromFundApi(stockCode) }
            eastmoneyAnswered -> emptyList()  // 批量已经问过东财了，答案是空，别再逐条重问
            else -> fetchSource("东方财富", stockCode, answered) { fetchFromEastmoney(stockCode) }
        }

        if (data.isEmpty() && isExchangeFund) {
            // 批量已经覆盖了东财的三种代码格式，别再问一遍；
            // 直接走天天基金这个独立数据源，只砍重复问东财
            // （fetchETFDividends 内部最后本来就会调 fetchFromFundApiForETF，不损失数据源）。
            // 非 ETF 的场内基金（REITs、封基）此前被 isEtf 这道门槛挡在基金源之外，
            // 改走天天基金后才能拿到收益分配记录——东财股票分红表与 Sina 都不覆盖它们。
            data = if (eastmoneyAnswered || !isEtf) {
                fetchSource("天天基金(场内)", stockCode, answered) { fetchFromFundApiForETF(stockCode) }
            } else {
                fetchSource("ETF", stockCode, answered) { fetchETFDividends(stockCode) }
            }
        }

        if (data.isEmpty() && !isOTCFund && !isExchangeFund) {
            val market = StockCodeManager.getMarketPrefix(pureSymbol)
            data = fetchSource("新浪", stockCode, answered) { fetchFromSina(stockCode, market) }
        }

        if (!answered.get()) {
            throw DividendSourceUnavailableException(
                "分红数据源全部未应答[$normalizedSymbol]（场外=$isOTCFund ETF=$isEtf 场内=$isExchangeFund 批量=$eastmoneyAnswered）"
            )
        }

        return data
    }

    private suspend fun fetchETFDividends(stockCode: String): List<DividendRecord> {
        val marketPre = if (stockCode.startsWith("5")) "SH" else "SZ"
        val formats = listOf(
            "${marketPre}.${stockCode}",
            "${marketPre}${stockCode}",
            "${stockCode}.${marketPre}"
        )
        var answered = false
        for (fmt in formats) {
            val data = try {
                fetchFromEastmoneyWithFormat(fmt).also { answered = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DividendSourceUnavailableException) {
                AppLogger.w("ETF东方财富格式[$fmt]未应答: ${e.message}", "DividendRepository")
                emptyList()
            }
            if (data.isNotEmpty()) {
                AppLogger.d("ETF东方财富格式[$fmt]成功，获取到${data.size}条记录", "DividendRepository")
                return data
            }
        }

        return try {
            fetchFromFundApiForETF(stockCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            // 三个格式里只要有一个应答过，空结果就是「确实没有」；全没应答才算抓取失败
            if (!answered) throw e
            AppLogger.w("ETF天天基金接口未应答: ${e.message}", "DividendRepository")
            emptyList()
        }
    }

    // 数据源访问保留为成员方法：既有单测用 spyk 桩掉 fetchFromSina / fetchFromFundApiForETF
    // 来验证路由，成员方法委托给 DividendSourceFetcher，公开与 internal 权限不变。
    private suspend fun fetchFromEastmoney(stockCode: String): List<DividendRecord> =
        sourceFetcher.fetchFromEastmoney(stockCode)

    private suspend fun fetchFromEastmoneyWithFormat(securityCode: String): List<DividendRecord> =
        sourceFetcher.fetchFromEastmoneyWithFormat(securityCode)

    internal suspend fun fetchFromFundApiForETF(fundCode: String): List<DividendRecord> =
        sourceFetcher.fetchFromFundApiForETF(fundCode)

    internal suspend fun fetchFromSina(stockCode: String, market: String): List<DividendRecord> =
        sourceFetcher.fetchFromSina(stockCode, market)

    private suspend fun fetchFromFundApi(fundCode: String): List<DividendRecord> =
        sourceFetcher.fetchFromFundApi(fundCode)

// ==========================================================================
// §7 写库（委托 DividendWriter）
// ==========================================================================

    suspend fun saveDividends(symbol: String, records: List<DividendRecord>) {
        writer.saveDividends(symbol, records)
    }

    /**
     * 手动添加或更新分红记录。用于用户纠错。
     * @return 保存后的 Dividend id
     */
    suspend fun saveManualDividend(dividend: Dividend): Long = writer.saveManualDividend(dividend)

    /** 手动删除分红记录 */
    suspend fun deleteManualDividend(id: Int, symbol: String) {
        writer.deleteManualDividend(id, symbol)
    }

    /** 获取数据库中某资产的手动/自动分红记录（原始 Dividend 实体） */
    suspend fun getDbDividendsBySymbol(symbol: String): List<Dividend> =
        dividendDao.getBySymbol(StockCodeManager.normalizeSymbol(symbol))

// ==========================================================================
// §8 prefetchDividends（按 SyncBudget 预算的预取编排）
// 与 §2 的批量预取并存：这版按调用方传入的预算跳过后续代码、超时不写缓存
// （「抓过且没有分红」的结论留空，之后按需抓取再补，不丢数据）。
// ==========================================================================

    /**
     * 老入口：逐条抓一批标的的分红（内部先跑一次 [prefetchDividendsBatch] 批量预取）。
     *
     * 先批量再逐条是这次修复的核心：刷新按钮（forceRefresh=true）从前完全绕开批量预取，
     * 每个标的各打一遍完整级联，59 个标的打出 250~400 个请求 —— 就是用户看到的
     * 「按了刷新就闪退」。
     *
     * 整段强制跑在 [Dispatchers.IO] 上：调用方可能是 `viewModelScope`
     * （`Dispatchers.Main.immediate`），52 个代码的协程启动与续体挤在主线程
     * Looper 里，就是「点刷新黑屏、日志还全静默」的直接原因之一。
     *
     * @param timeBudgetMs 本阶段的时间预算（毫秒），<=0 表示不设限。
     *        超时后跳过的代码不写缓存，「抓过且没有分红」的结论留空，之后按需抓取时还会补上。
     */
    suspend fun prefetchDividends(
        symbols: List<String>,
        forceRefresh: Boolean = false,
        timeBudgetMs: Long = 0L
    ) {
        if (!prefetchGate.tryLock()) {
            AppLogger.i("已有分红预取在进行，本轮跳过（避免与前台/定期任务重复请求）", "DividendRepository")
            return
        }
        // 整轮抓取状态改成「内存即时 + 轮末一次 commit」：逐只 apply 的异步落盘
        // 在进程刚被回收时会丢，导致下一轮冷启动重抓。批次内的读取仍即时准确。
        fetchStateStore.beginBatch()
        try {
            prefetchDividendsLocked(symbols, forceRefresh, timeBudgetMs)
        } finally {
            // 正常结束、预算用尽、取消、抛异常都要落盘；NonCancellable 保证取消后
            // 仍能切到 IO 完成这一次同步 commit（不能在主线程做磁盘写）
            withContext(NonCancellable + Dispatchers.IO) {
                fetchStateStore.endBatch()
            }
            prefetchGate.unlock()
        }
    }

    /** [prefetchDividends] 的加锁实现：互斥与去重只在上一层处理，抓取语义原样保留 */
    private suspend fun prefetchDividendsLocked(
        symbols: List<String>,
        forceRefresh: Boolean,
        timeBudgetMs: Long
    ) {
        withContext(Dispatchers.IO) {
            // 本轮观测标识与起点：prefetchDividends 也会被日历页直接调用（不经价格同步
            // 轮次），所以自带一个轻量序号；roundStartMs 用来判定 markFetched / markFailed
            // 写下的时间戳是否属于本轮。两者只服务于收尾那一行 INFO，不参与任何决策。
            val roundId = prefetchRoundSeq.incrementAndGet()
            val roundStartMs = System.currentTimeMillis()

            // 本轮「天天基金限流」信号：只记开始值，**不清零**共享计数。
            // 资产页刷新与日历预取并发时，任何一方清零都会抹掉对方的信号；
            // 轮末用前后差值判断「这一轮」是否新增了限流。
            val rateLimitSignalAtStart = FundDividendApiService.otcRateLimitSignal

            val normalizedSymbols = symbols.map { StockCodeManager.normalizeSymbol(it) }.distinct()

            val batchResults = prefetchDividendsBatch(normalizedSymbols, forceRefresh)

            // 逐条阶段按「最久没成功抓过」重排（批量阶段保持调用方原顺序，不受影响）。
            // 时间预算会自然截断一轮的抓取量，排序保证每轮推进的是**不同的一批**：
            // 上轮被 514/退避短路的仍留队首，本轮刚成功的因 markFetched 时间戳排到队尾。
            val orderedSymbols = prioritizeDividendSymbols(
                symbols = normalizedSymbols,
                lastFetchedAt = normalizedSymbols.associateWith { fetchStateStore.lastFetchedAt(it) }
            )

            // 预算截止时刻：超时后不再启动新代码的逐条抓取
            val deadline = if (timeBudgetMs > 0L) System.currentTimeMillis() + timeBudgetMs else 0L
            val semaphore = Semaphore(maxConcurrentFetch)
            val checkedCount = AtomicInteger(0)

            // 本轮收尾汇总所需的纯观测簿记。逐标的的 WARN 会被 app.log 的环形缓冲冲掉
            // 大半，事后只能靠正则从几 MB 日志里捞；这里在并发子任务里只做内存计数，
            // 最后统一打一行 INFO，不新增任何循环内日志、不改抓取 / 缓存 / 退避行为。
            val successCount = AtomicInteger(0)
            val unavailableCount = AtomicInteger(0)
            val skippedCodes = ConcurrentLinkedQueue<String>()

            // 心跳：这个阶段是刷新链路里第二长的静默期（批量之后是逐条级联），
            // 从前只有开始/结束各一行日志，卡住时完全看不出走到哪
            withHeartbeat(
                tag = "DividendRepository",
                phase = "分红预取",
                status = {
                    val leftSeconds =
                        if (deadline > 0L) ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)
                        else null
                    "已检查 ${checkedCount.get()}/${normalizedSymbols.size} 个代码" +
                        (leftSeconds?.let { "，预算剩余 ${it}s" } ?: "")
                }
            ) {
                // 本轮真正发起逐条抓取的标的数（含被时间预算拦下的）。
                var attempted = 0
                coroutineScope {
                    orderedSymbols.map { symbol ->
                        val entry = memoryCache.get(symbol)
                        val isOtc = StockCodeManager.isOTCFund(symbol)
                        val ttlExpired = entry == null || !entry.isValid(DIVIDEND_CACHE_TTL_MS)
                        val shouldFetch = if (isOtc) {
                            // 手动刷新(forceRefresh)时，场外基金只补抓「TTL 已过期 且 今天还没成功抓过」的，
                            // 避免每次刷新把所有场外基金打一遍再踩 514 请求风暴；非手动刷新维持原 TTL 语义。
                            shouldRefetchOtc(
                                ttlExpired = ttlExpired,
                                forceRefresh = forceRefresh,
                                fetchedToday = isFetchedToday(symbol)
                            )
                        } else {
                            // 非场外基金：forceRefresh 之外，还要求「今天没成功抓过」。
                            // 缓存是进程内的，进程被杀后必然为空；只看 ttlExpired 会让
                            // 定期任务在冷启动后把前台今天刚抓过的标的全量重拉一遍。
                            forceRefresh || (ttlExpired && !isFetchedToday(symbol))
                        }
                        if (shouldFetch) {
                            attempted++
                            async {
                                // 预算检查必须放在「拿到信号量之后」，不能放在 async 开头：
                                // 上面 N 个 async 是**一次性全部 launch** 的，若在 withPermit 之前检查，
                                // 它们全都在 t≈0 落空、之后在信号量上排队恢复时早已错过检查点，
                                // 预算形同虚设（真机日志坐实过：28s 子预算实际跑了 50.3s）。
                                semaphore.withPermit {
                                    if (deadline > 0L && System.currentTimeMillis() > deadline) {
                                        AppLogger.w(
                                            "分红预取达到时间预算，跳过 $symbol（留待按需抓取）",
                                            "DividendRepository"
                                        )
                                        // 预算跳过的标的本轮同样没取到数，只记进汇总清单，
                                        // 不额外打一行日志（原有 WARN 保持原样）。
                                        skippedCodes.add(symbol)
                                        return@withPermit
                                    }
                                    checkedCount.incrementAndGet()
                                    getDividendsBySymbol(symbol, forceRefresh, batchResults)

                                    // 纯观测分类：getDividendsBySymbol 内部把「源应答」
                                    // 写进 fetchState（markFetched），把「全源未应答」
                                    // 写进失败标记（markFailed），两者都在本类作用域可读。
                                    // 只比时间戳，不改变任何返回语义、不触发重试 / 退避。
                                    val fetchedAt = fetchStateStore.lastFetchedAt(symbol)
                                    if (fetchedAt != null && fetchedAt >= roundStartMs) {
                                        successCount.incrementAndGet()
                                    } else {
                                        skippedCodes.add(symbol)
                                        val failedAt = fetchStateStore.lastFailedAt(symbol)
                                        if (failedAt != null && failedAt >= roundStartMs) {
                                            unavailableCount.incrementAndGet()
                                        }
                                    }
                                }
                            }
                        } else null
                    }.filterNotNull().forEach { it.await() }
                }

                // 轮末做差：只有本轮新增的限流才计入提示；不消费也不清零共享计数，
                // 并发轮次各自判断、互不影响。仓库实例级结论由 consumeOtcRateLimitWarning
                // 读即清零，供 VM 每次刷新只提示一次。
                if (FundDividendApiService.otcRateLimitSignal > rateLimitSignalAtStart) {
                    _otcRateLimitedThisCycle = true
                }

                // ===== 分红预取阶段收尾：只打一行汇总 =====
                // 纯观测：不把 514 当错误处理、不触发任何重试 / 退避 / 缓存写入。
                val rateLimitDelta =
                    (FundDividendApiService.otcRateLimitSignal - rateLimitSignalAtStart)
                        .coerceAtLeast(0L).toInt()
                AppLogger.i(
                    formatDividendPrefetchSummary(
                        roundId = roundId,
                        totalSymbols = normalizedSymbols.size,
                        attempted = attempted,
                        success = successCount.get(),
                        unavailable = unavailableCount.get(),
                        rateLimitDelta = rateLimitDelta,
                        skippedCodes = skippedCodes.toList()
                    ),
                    "DividendRepository"
                )
            }
        }
    }
}

/**
 * 某标的分红数据的「新鲜度」快照（纯展示，不参与任何抓取 / 缓存 / 退避判定）。
 *
 * @param lastDataAt 最近一次真正抓到**非空**分红并写库的时间
 * @param lastMissedAt 最近一次「源返回空结果、沿用库内旧记录」的时间
 */
data class DividendDataFreshness(
    val lastDataAt: Long?,
    val lastMissedAt: Long?
)

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * 分红陈旧提示文案（纯函数，便于单测钉死措辞与边界）。
 *
 * 只在「最近一次抓取是空结果（[DividendDataFreshness.lastMissedAt] 晚于
 * 最近一次取到数据）」时返回文案：
 * - 有旧记录时间 → 「分红数据最近一次未取到，当前沿用 N 天前的记录」；
 * - 老用户升级上来还没写过该键 → 退化为「当前沿用更早的记录」，**不编造天数**。
 *
 * 之后若又成功取到非空数据（lastDataAt >= lastMissedAt），提示自动消失。
 */
internal fun staleDividendHint(freshness: DividendDataFreshness, now: Long): String? {
    val missedAt = freshness.lastMissedAt ?: return null
    val dataAt = freshness.lastDataAt
    if (dataAt == null) return "分红数据最近一次未取到，当前沿用更早的记录"
    if (dataAt >= missedAt) return null
    val days = ((now - dataAt).coerceAtLeast(0L) / DAY_MS).coerceAtLeast(1L)
    return "分红数据最近一次未取到，当前沿用 $days 天前的记录"
}
