package com.example.stockdiary.data.repository

import com.example.stockdiary.common.DateFormats
import com.example.stockdiary.data.dividend.DividendData
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.dividend.DividendService
import com.example.stockdiary.network.EastmoneyDividendApiService
import com.example.stockdiary.network.FundDividendApiService
import com.example.stockdiary.data.sync.withHeartbeat
import com.example.stockdiary.data.dividend.isFetchedToday
import com.example.stockdiary.data.dividend.shouldRefetchOtc
import android.annotation.SuppressLint
import android.content.Context
import androidx.room.withTransaction
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException
import java.io.IOException

// ==========================================================================
// §1 构造与依赖
// 三个上游（东财 / 新浪 / 场外基金）+ Room DAO + 内存缓存与容量上限 + 本轮限流标志。
// 全文件分区：§1 构造 §2 抓取状态 §3 预取 §4 缓存读 §5 单例/维护 §6 查询/抓取编排
//            §7 写入 §8 旧批量预取 §9 文件级纯函数辅助。
// ==========================================================================

/**
 * 分红抓取失败退避窗（指数退避，见 [computeFailureBackoff]）。
 * 定义为文件级 private，使类内成员与文件级纯函数 [shouldSkipAfterFailure]/[computeFailureBackoff] 均可访问。
 */
private const val FAILURE_BACKOFF_BASE_MS = 5L * 60 * 1000            // 首档 5 分钟
private const val FAILURE_BACKOFF_MAX_MS = 6L * 60 * 60 * 1000        // 封顶 6 小时
private const val FAILURE_BACKOFF_BASE_MS_NEVER = 1L * 60 * 1000      // 从未成功：首档 1 分钟
private const val FAILURE_BACKOFF_MAX_MS_NEVER = 1L * 60 * 60 * 1000   // 从未成功：封顶 1 小时

class DividendRepository private constructor(
    private val context: Context
) {
    private val eastmoneyApi by lazy { EastmoneyDividendApiService(context) }
    private val sinaService = DividendService()
    private val fundDividendApi = FundDividendApiService()
    private val dividendDao by lazy { StockDatabase.getInstance(context).dividendDao() }
    
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTTL = 24 * 60 * 60 * 1000L

    /** 分红缓存上限：与行情侧各缓存同量级（~200），避免长会话里无限增长 */
    private val MAX_CACHE_ENTRIES = 200

    /**
     * 本轮 [preFetchDividends] 是否撞过天天基金限流（514/429）。
     *
     * 进程级的 [FundDividendApiService.otcRateLimitHit] 在每轮预取开头清零、结尾读走，
     * 这里存的是「读走那一瞬间的状态」，由 [consumeOtcRateLimitWarning] 消费一次（读即清零），
     * 这样 ViewModel 在刷新收尾弹过一次提示后，不会在下一轮 / 同一轮别处入口重复弹。
     */
    @Volatile
    private var _otcRateLimitedThisCycle: Boolean = false

// ==========================================================================
// §2 抓取状态簿记（SharedPreferences，跨进程存活）
// markFetched / markFailed / lastFetchedAt 等：记住「抓过」与「刚失败」。空结果（抓过且确实没分红）必须被记住，否则每次冷启动重抓全部标的 → 接口 514 限流（见 fetchState 的 KDoc）。
// ==========================================================================

    /**
     * 每个代码「上次抓过的时间」，跨进程存活。
     *
     * ## 为什么非有它不可
     *
     * 实测（2026-10-14 的诊断报告）：一次全量抓取里 **525 次抓回 0 条、只有 77 次有数据** ——
     * 87% 的标的确实没有分红。而 [getDividendsBySymbol] 里写库那句是
     * `if (data.isNotEmpty())`，**空结果从不落库**；[tryGetFromCache] 又只在库里有记录时
     * 才算命中。两边一凑：空结果永远记不住。
     *
     * 于是进程一死（Android 后台进程被系统回收是常态），内存缓存全没，
     * 库里又查不到 → 全部重新抓一遍。59 个代码 × 3 个数据源 ≈ 250 个请求，
     * 每次冷启动来一轮，一天好几轮。后果是接口被打到返回 **HTTP 514（反爬限流）**
     * （实测 106 次），请求排队到 60 秒才返回，进程长时间占着大量内存做 Jsoup 解析
     * —— 然后更容易被系统回收，进入下一轮。这就是「闪退」反复出现的放大器。
     *
     * 所以：**「抓过，且确实没有分红」是一个必须记住的结论**，不是「没数据」。
     */
    private val fetchState by lazy {
        context.getSharedPreferences(FETCH_STATE_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * 清空全部抓取状态簿记（"清除所有数据"时调用）。
     *
     * 否则库清空了，但每条标的仍带着「X 分钟/7 天内抓过且没分红」的时间戳，
     * [shouldReuseEmptyResult] 会把它们当成"确实没分红"复用，最长 7 天不再真正重抓，
     * 用户以为清干净了、分红却一直空白。见 [AssetRepository.deleteAllData]。
     */
    fun clearFetchState() {
        runCatching { fetchState.edit().clear().apply() }
    }

    /** 记录一次抓取完成（不管抓没抓到）。必须在抓取**成功**后写，失败时写会把错误固化 24 小时 */
    private fun markFetched(symbol: String) {
        runCatching { fetchState.edit().putLong(symbol, System.currentTimeMillis()).apply() }
    }

    private fun lastFetchedAt(symbol: String): Long? {
        val value = runCatching { fetchState.getLong(symbol, 0L) }.getOrDefault(0L)
        return value.takeIf { it > 0L }
    }

    /**
     * 记一次抓取失败（所有数据源都没应答）。
     *
     * 与 [markFetched] 是**两个独立状态**：[markFetched] 记「源应答了，结论是没有
     * 分红」（7 天有效），这里记「源没答上」（按 [computeFailureBackoff] 指数退避：
     * 首档 5 分钟、封顶 6 小时；从未成功过的标的 1 分钟起、封顶 1 小时）。
     * 混在一起就会把错误固化成结论 —— 正是本次要修的东西。
     */
    private fun markFailed(symbol: String) {
        runCatching {
            // 读-改-写必须原子：资产页与持仓页可能同时抓同一只标的，
            // 两个线程各自读到旧值再各自 +1，会把失败次数少记一次、退避档位涨不上去。
            // 锁在 SharedPreferences 实例上（同进程同 name 只有一个实例），不引入额外字段。
            synchronized(fetchState) {
                val countKey = FAILURE_COUNT_PREFIX + symbol
                val newCount = fetchState.getInt(countKey, 0) + 1
                fetchState.edit()
                    .putLong(FAILURE_KEY_PREFIX + symbol, System.currentTimeMillis())
                    .putInt(countKey, newCount)
                    .apply()
            }
        }
    }

    /** 抓取成功即清除失败标记与连续失败计数，让它立刻回到正常的 TTL 节奏 */
    private fun clearFailure(symbol: String) {
        runCatching {
            fetchState.edit()
                .remove(FAILURE_KEY_PREFIX + symbol)
                .remove(FAILURE_COUNT_PREFIX + symbol)
                .apply()
        }
    }

    private fun lastFailedAt(symbol: String): Long? {
        val value = runCatching { fetchState.getLong(FAILURE_KEY_PREFIX + symbol, 0L) }.getOrDefault(0L)
        return value.takeIf { it > 0L }
    }

    /** 该 symbol 连续抓取失败的次数（成功一次即清零） */
    private fun failureCount(symbol: String): Int =
        runCatching { fetchState.getInt(FAILURE_COUNT_PREFIX + symbol, 0) }.getOrDefault(0)

// ==========================================================================
// §3 预取入口 prefetchDividends（批量抓取，冷启动/后台主力路径）
// 东财合并请求 + Semaphore 限流 + 超时预算判定在 withPermit 内部。Semaphore 用法是踩过的坑：判定写在外层等于没判定，改法照抄 AssetSyncManager。
// ==========================================================================

    /**
     * 一次性把一批标的的分红抓完，结果**按次返回**，由调用方在随后的逐条
     * [getDividendsBySymbol] 里作为 `batchResults` 传回来。
     *
     * ## 为什么按次返回而不是放共享字段
     *
     * 结果曾经放在单例的 `batchPrefetch` 字段里。资产页与持仓页同时刷新
     * （或后台 PriceSyncWorker 撞上手动刷新）时，两个预取周期会互相
     * clear / remove：后启动的清掉前一个的结果，整批退化成逐条级联，
     * 请求风暴照旧。按次返回后每个周期只读自己那份不可变结果，并发互不干扰。
     *
     * ## 为什么值得单独做一次
     *
     * 东财的 filter 支持 `SECURITY_CODE in ("a","b",…)`，**一个请求就能问全部代码**。
     * 逐条抓的话：59 个代码一个请求，ETF 空手后还要顺序试三种代码格式（再 3 个），
     * 一次全量 100 多个请求。合并后 1~N 个。
     *
     * ## 只走东财这条路
     *
     * 天天基金的分红页是**每个基金一个 HTML 页面**，没有可用的批量入口
     * （实测 `FundArchivesDatas.aspx?type=jjfh&code=a,b` 返回空，单代码也返回空），
     * 所以场外基金仍逐个抓 —— 那部分请求量省不掉，交给 TTL 分层去治。
     *
     * ## 不改变兜底语义
     *
     * 批量拿到记录的标的，后续不再级联（数据已经有了，没必要再问）；
     * 批量说没有的标的，级联照旧走完，只是**跳过已经问过的东财那一步**，
     * 让 Sina 兜底有机会发言。这样不因为合并请求而丢任何数据来源。
     */
    suspend fun prefetchDividends(
        symbols: List<String>,
        forceRefresh: Boolean = false
    ): Map<String, List<DividendRecord>> {
        if (symbols.isEmpty()) return emptyMap()

        val normalized = symbols.map { StockCodeManager.normalizeSymbol(it) }.distinct()

        // 已经有缓存的不用再问。forceRefresh 时全问（调用方明确要刷新）
        val pending = normalized.filter { symbol ->
            forceRefresh || tryGetFromCache(symbol) == null
        }
        if (pending.isEmpty()) return emptyMap()

        // 每个标的候选的 SECURITY_CODE：直接码 + （ETF 才有的）三种格式变体。
        // ETF 的格式历史上换过，接口三种都认，一起塞进批量查询比顺序重试省两个请求
        val codesBySymbol = mutableMapOf<String, MutableList<String>>()
        val codeToSymbol = mutableMapOf<String, String>()
        val eastmoneySymbols = mutableListOf<String>()

        for (symbol in pending) {
            // fund_ 前缀（场外份额的显式标记）必须比号段推断先判：
            // 400030（东方添益债券）/ 420102 落在 400/420 号段，isOTCFundCode 认不出来
            // （它只认 01/02 开头与 004xxx-009xxx），于是这些真基金会被塞进东财批量查询、
            // 拿不到结果后再级联到新浪股票接口，一路问的都是不存在的证券代码。
            // 场外基金走天天基金，没有批量入口 —— 与下面逐条抓取用的是同一个判据。
            //
            // 数据影响（已知且已认可）：修完之后 fund_400030 / fund_420102 的分红来源
            // 由「东财批量 + 新浪」切到天天基金，已入库的分红记录可能随之变化。
            if (StockCodeManager.isOTCFund(symbol)) continue

            val pure = StockCodeManager.stripFundPrefix(symbol)
            val code = StockCodeManager.extractPureCode(pure)

            eastmoneySymbols += symbol
            val candidates = codesBySymbol.getOrPut(symbol) { mutableListOf() }
            candidates += code
            codeToSymbol.putIfAbsent(code, symbol)

            if (StockCodeManager.isETF(pure)) {
                val marketPre = if (code.startsWith("5")) "SH" else "SZ"
                for (fmt in listOf("${marketPre}.$code", "${marketPre}$code", "$code.$marketPre")) {
                    candidates += fmt
                    codeToSymbol.putIfAbsent(fmt, symbol)
                }
            }
        }

        if (eastmoneySymbols.isEmpty()) return emptyMap()

        // 分块发：每块一个请求，URL 长度与代码数双保险（见 [chunkSecurityCodes]）。
        // 不切块的后果不是慢，是整批失败后退回逐条级联 —— 59 个标的打出 250~400 个请求，
        // 那就是这次要修掉的「点刷新闪退」
        val chunks = chunkSecurityCodes(codeToSymbol.keys.toList())
        // 被**成功**块覆盖的标的才会写进结果；块失败的标的不写 ——
        // 没有条目 ⇔ 级联里照常逐条重问，不能用「批量失败」冒充「批量问过且没有」
        val answeredSymbols = ConcurrentHashMap.newKeySet<String>()
        val recordsByCode = ConcurrentHashMap<String, MutableList<DividendData>>()

        coroutineScope {
            val chunkSemaphore = Semaphore(MAX_CONCURRENT_BATCH_CHUNKS)
            chunks.map { chunk ->
                async {
                    chunkSemaphore.withPermit {
                        val result = try {
                            eastmoneyApi.getDividendHistoryBySecurityCodes(chunk)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 只废这一块：块内标的不写结果条目，随后会走逐条级联，
                            // 其他块的结果照常生效 —— 一块失败不能把整批拖垮
                            AppLogger.w(
                                "批量预取分块失败（${chunk.size} 个代码）: ${e.message}",
                                "DividendRepository"
                            )
                            return@withPermit
                        }
                        if (!result.isSuccess) {
                            AppLogger.w(
                                "批量预取分块未成功（${chunk.size} 个代码）",
                                "DividendRepository"
                            )
                            return@withPermit
                        }
                        result.getOrNull().orEmpty().forEach { record ->
                            recordsByCode.computeIfAbsent(record.ticker) { mutableListOf() }
                                .add(record)
                        }
                        chunk.forEach { code -> codeToSymbol[code]?.let { answeredSymbols += it } }
                    }
                }
            }.forEach { it.await() }
        }

        if (answeredSymbols.isEmpty()) {
            // 全块失败：一个条目都不写，全部标的走逐条级联（与改造前批量失败的行为一致）
            AppLogger.w("批量预取所有分块均失败，退回逐条路径", "DividendRepository")
            return emptyMap()
        }

        // 按接口返回的 SECURITY_CODE 归组，再发回给各个标的。
        // 同一标的可能靠多种格式命中，取并集并按除息日去重。
        // 这里要把 [DividendData]（接口层）转成 [DividendRecord]（领域层）——
        // 口径与逐条路径 [fetchFromEastmoneyWithFormat] 保持一致，否则同一天的分红
        // 会因为走了批量还是逐条而算出不同的金额
        val batchResults = HashMap<String, List<DividendRecord>>(eastmoneySymbols.size)
        for (symbol in eastmoneySymbols) {
            if (symbol !in answeredSymbols) continue
            val codes = codesBySymbol[symbol].orEmpty()
            val merged = codes.flatMap { recordsByCode[it].orEmpty() }
                .map { it.toDividendRecord() }
                .distinctBy { it.exDividendDate }
                .sortedByDescending { it.exDividendDate }
            batchResults[symbol] = merged
        }

        AppLogger.i(
            "批量预取：${eastmoneySymbols.size} 只走东财的标的分 ${chunks.size} 个请求，" +
                "${batchResults.size} 只拿到批量答案，其中 " +
                "${batchResults.values.count { it.isNotEmpty() }} 只有分红记录",
            "DividendRepository"
        )
        return batchResults
    }

// ==========================================================================
// §4 缓存读取（内存 Map + Room + 「抓过」时间戳三层判定）
// getCachedAllowingStale(Batch) / primeDividendCache / CacheEntry。返回值可能是空列表（= 抓过且没有分红，有效答案）；只有 null 才代表调用方该去抓。
// ==========================================================================

    /**
     * 冷启动专用的缓存读取：**TTL 过期也照用**，只留一个很硬的上限。
     *
     * 打开 App 不该为了刷新「预计收入」去敲几十个接口 —— 那是用户点「刷新」时才该做的事。
     * 所以这里故意比 [cacheTTL] / [NEGATIVE_RESULT_TTL] 宽松：过期几天照样用，
     * 只有超过 [STALE_CEILING_MS]（30 天）才判定「连过期数据都没有」，让调用方去抓。
     *
     * 返回值可能是**空列表**：那表示「抓过，且确实没有分红」，是有效答案，不是没数据。
     * 只有 null 才代表调用方该去抓。
     */
    suspend fun getCachedAllowingStale(symbol: String): List<DividendRecord>? {
        val normalized = StockCodeManager.normalizeSymbol(symbol)

        // 先按正常 TTL 走一遍（命中就直接返回，顺带把内存缓存填上）
        tryGetFromCache(normalized)?.let { return it }

        // TTL 过期了：只要还没超过硬上限，就承认这份数据。
        // 只认抓取时间戳（库里记录本身不带时间戳，没法判断它有多旧）
        val stamp = lastFetchedAt(normalized)
        val freshEnough = stamp != null && System.currentTimeMillis() - stamp < STALE_CEILING_MS
        if (!freshEnough) return null

        val dbRecords = dividendDao.getBySymbol(normalized)
        return dbRecords.map { dbDiv ->
            DividendRecord(
                exDividendDate = dbDiv.date,
                dividendPerShare = dbDiv.amount.toString(),
                announceDate = "",
                fiscalYear = dbDiv.date.take(4),
                paymentDate = dbDiv.payDate
            )
        }
    }

    /**
     * 批量版 [getCachedAllowingStale]：一次读 N 个标的。
     *
     * ## 为什么要有这个方法
     *
     * 冷启动时 [DividendIncomeLoader.loadIncome] 的「同日短路」要先把每个标的的
     * 缓存记录取出来算 dataHash。逐标的走 [getCachedAllowingStale] 的代价是
     * **2N 次 IO**：每个符号一次 `dividendDao.getBySymbol`（Room 查询，最坏还会
     * 因库里已有记录而再查一次）+ 两次 `fetchState.getLong`（同步 SP 读）。
     * 用户有 240 个标的 → 约 480 次 IO，实测冷启动这一段就占 3~4 秒
     * （诊断日志 19:53:36 起、19:53:38 已跑到首页那遍）。
     *
     * 批量后：内存 Map 命中零 IO；未命中的一次 `dividendDao.getBySymbols`
     * （单条 IN 查询）+ 一次 `fetchState.getAll()`。
     *
     * ## 语义必须与逐标的版本逐分支一致
     *
     * 这不是「顺手优化」，判据错一个分支的代价都是静默的：
     * - 有分红记录 + 24h 内抓过 → 用记录（并回填内存缓存）
     * - 有分红记录 + 24h 外但 30 天内抓过 → 仍用旧记录（[STALE_CEILING_MS] 宽口径）
     * - 有分红记录 + 超过 30 天 → null（让调用方去抓）
     * - 无分红记录 + 7 天内抓过 → 「没有分红」是结论，返回**空列表**而非 null
     *   （[shouldReuseEmptyResult]；返回 null 会让调用方以为没抓过而反复重抓）
     * - 无分红记录 + 7 天外但 30 天内 → 同样是空列表（逐标的路径会重查 DB 后返回空）
     * - 无分红记录 + 超过 30 天 → null
     *
     * 内存缓存回填的时机也照抄逐标的版本：只有「24h 内抓过」与「空结果可复用」
     * 两条路回填，30 天宽口径那条不回填（它本就该在下次抓取时被刷新）——
     * **除非调用方显式要 [primeCache]**，见下。
     *
     * @param primeCache true = 把宽口径（24h~30 天）读到的记录也写进内存缓存。
     *        用途只有一个：**进程刚重启、内存缓存全丢**时，让随后按 symbol 的
     *        [getDividendsBySymbol] 命中内存而不再联网。此时把过期记录的
     *        timestamp 记成 now，等价于「这一轮先认旧数据，权威刷新交给同步链路」。
     *        默认 false，[DividendIncomeLoader] 等既有调用方行为完全不变。
     * @return symbol（已规范化）→ 记录；值为 null 表示「连过期数据都没有，该去抓」。
     *          **值可以是空列表**（表示抓过且确实没有分红），与 null 是两回事。
     */
    suspend fun getCachedAllowingStaleBatch(
        symbols: List<String>,
        primeCache: Boolean = false
    ): Map<String, List<DividendRecord>?> = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) return@withContext emptyMap()
        val normalized = symbols.map { StockCodeManager.normalizeSymbol(it) }.distinct()
        val now = System.currentTimeMillis()

        // 抓取时间戳一次全读，取代逐标的 N 次同步 SP 读。
        // fetchState 里混着失败标记（FAILURE_KEY_PREFIX 前缀），那些不是 Long 之外的类型，
        // 统一按 Long 过滤掉即可，不影响时间戳判定。
        val stamps: Map<String, Long> = runCatching {
            fetchState.all.mapNotNull { (key, value) ->
                (value as? Long)?.takeIf { it > 0L }?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())

        val out = HashMap<String, List<DividendRecord>?>(normalized.size)
        val needDb = ArrayList<String>()
        for (symbol in normalized) {
            val cached = cache[symbol]
            if (cached != null && cached.isValid(cacheTTL)) {
                cacheHits.incrementAndGet()
                out[symbol] = cached.data
            } else {
                needDb.add(symbol)
            }
        }
        if (needDb.isEmpty()) return@withContext out

        // 一次 IN 查询取代 N 次按 symbol 查（240 个远低于 SQLite 的 999 变量上限）
        val rowsBySymbol = dividendDao.getBySymbols(needDb)
            .groupBy { StockCodeManager.normalizeSymbol(it.symbol) }

        for (symbol in needDb) {
            val dbRecords = rowsBySymbol[symbol].orEmpty()
            val stamp = stamps[symbol]
            val fetchedRecently = stamp != null && now - stamp < cacheTTL
            val withinStaleCeiling = stamp != null && now - stamp < STALE_CEILING_MS

            if (dbRecords.isNotEmpty()) {
                if (fetchedRecently) {
                    val records = recordsFromDb(dbRecords)
                    // 与逐标的路径一致：数据库恢复的缓存不标记为全量拉取
                    cache[symbol] = CacheEntry(records, now, lastFullFetch = 0L)
                    out[symbol] = records
                } else if (withinStaleCeiling) {
                    // 宽口径：过期几天的记录照样用。默认不回填内存缓存（照抄逐标的版本）；
                    // 调用方显式要预热时才回填，见 [primeCache] 参数说明
                    if (primeCache) {
                        cache[symbol] = CacheEntry(
                            recordsFromDb(dbRecords),
                            now,
                            lastFullFetch = stamp ?: 0L
                        )
                    }
                    out[symbol] = recordsFromDb(dbRecords)
                } else {
                    out[symbol] = null
                }
                continue
            }

            // 库里一条都没有：先按 [shouldReuseEmptyResult] 判「抓过且没有分红」
            if (shouldReuseEmptyResult(
                    dbRecordCount = 0,
                    lastFetchedAt = stamp,
                    now = now,
                    ttl = NEGATIVE_RESULT_TTL
                )
            ) {
                cacheHits.incrementAndGet()
                cache[symbol] = CacheEntry(emptyList(), now, lastFullFetch = stamp ?: 0L)
                out[symbol] = emptyList()
            } else if (withinStaleCeiling) {
                // 逐标的路径这里会再查一次 DB（空）后返回空列表；批量版 DB 已查过，直接给空
                out[symbol] = emptyList()
            } else {
                out[symbol] = null
            }
        }
        out
    }

    /**
     * 把库里有记录的标的预热进内存缓存，返回「仍然需要联网抓取」的 symbol 列表。
     *
     * ## 为什么需要它（日历页冷启动转圈的根因）
     *
     * [CalendarViewModel] 生成日历时无条件调一次 `preFetchDividends(全部标的)`。
     * 而预取的判据是 `forceRefresh || ttlExpired`，其中 `cache` 是**进程内内存 Map**、
     * 一重启就全丢 —— 于是每个标的都算 `ttlExpired`，全部进抓取队列。
     * 更糟的是 [getDividendsBySymbol] 里「DB 有记录但 `lastFetchedAt` 超过
     * [cacheTTL]（24h）」会 **return null 转去联网**：用户只要超过一天没同步，
     * 200 个标的就得重抓一遍，日历页在 `isLoading` 里转圈到预算耗尽
     * （`generateCalendarData` 是 suspend 且被 collect 直接调用，转圈期间整条链阻塞）。
     *
     * 修法：冷启动先用本方法做一次宽口径批量读（零网络、2 次 IO）把有库记录的标的
     * 预热进内存缓存，只让「连过期数据都没有」的标的去联网。
     *
     * ## 取舍（明确记下）
     *
     * 预热会把过期数据的 timestamp 记成 now，意味着接下来 24h 内这些标的不会因
     * 「有新公告」而重抓。这是**有意**的：冷启动先用旧数据把界面填上，远比转圈几十秒
     * 好；权威刷新由 [AssetSyncManager] 的同步链路（forceRefresh）与用户手动刷新负责，
     * 两条路都不经过这里。首页/持仓页的预计收入冷启动也早就是同一套宽口径语义
     * （[getCachedAllowingStale] 的 [STALE_CEILING_MS] = 30 天）。
     *
     * @param forceRefresh true = 不预热、直接返回全部 symbol 让调用方全量重抓
     * @return 仍需抓取的 symbol（**原始 symbol**，未规范化；[preFetchDividends]
     *         内部会自己 normalize + distinct）
     */
    suspend fun primeDividendCache(symbols: List<String>, forceRefresh: Boolean): List<String> {
        if (symbols.isEmpty()) return emptyList()
        if (forceRefresh) return symbols
        val cached = getCachedAllowingStaleBatch(symbols, primeCache = true)
        return symbols.filter { cached[StockCodeManager.normalizeSymbol(it)] == null }
            .distinct()
    }

    /** 预取分红时的并发上限：与 AssetSyncManager.MAX_CONCURRENT_SYNC 一致，别把接口打爆 */
    private val maxConcurrentFetch = 8
    
    private val cacheHits = AtomicInteger(0)
    private val cacheMisses = AtomicInteger(0)
    
    data class CacheEntry(
        val data: List<DividendRecord>,
        val timestamp: Long,
        val lastFullFetch: Long = System.currentTimeMillis()
    ) {
        fun isValid(ttl: Long): Boolean = System.currentTimeMillis() - timestamp < ttl
        fun needsFullFetch(): Boolean = System.currentTimeMillis() - lastFullFetch > 30L * 24 * 60 * 60 * 1000
    }
    
// ==========================================================================
// §5 单例与缓存维护
// getInstance（双检锁单例）+ clearCache / trimCacheIfNeeded / getCacheStats / close / consumeOtcRateLimitWarning（限流信号读即清零，供 VM 弹一次 Snackbar）。
// ==========================================================================

    companion object {
        /** 「上次抓取时间」存放的 preferences 名，见 [fetchState] 的说明 */
        private const val FETCH_STATE_PREFS = "dividend_fetch_state"

        /**
         * 「抓过，且确实没有分红」这个结论的有效期。
         *
         * 比 [cacheTTL] 长得多是刻意的：实测一次全量抓取里 87% 的标的没有分红，
         * 它们是绝大多数请求量的来源，而「没有分红」这个结论又极不容易变。
         * 仍保留上限（而不是永久）是因为万一第一次抓取正好撞上限流/反爬拿到空结果，
         * 过期后还有机会重试；而且用户点「刷新」本来就会 forceRefresh 绕开缓存。
         */
        private const val NEGATIVE_RESULT_TTL = 7L * 24 * 60 * 60 * 1000

        /**
         * 抓取**失败**后不再重打同一标的的退避窗。
         *
         * 失败既不能写进 [fetchState]（那等于把网络抖动固化成「这只没有分红」最长
         * 7 天，界面还一声不吭），也不能什么都不记 —— 场外基金没有批量入口
         * （天天基金是每基金一个页面，见 [prefetchDividends] 的说明），上百只标的
         * 每轮各重打一次失败的接口就是请求风暴。所以给失败单独一个短窗：
         * 窗内不重打，过期自动重试；用户点刷新（forceRefresh）随时可以绕过。
         */
        /** 失败时间戳与「抓过」时间戳共用一份 SP，靠前缀区分，避免多一个文件 */
        private const val FAILURE_KEY_PREFIX = "failed_"
        private const val FAILURE_COUNT_PREFIX = "failedcnt_"

    /**
     * 冷启动允许使用的过期数据上限。
     *
     * 比 [NEGATIVE_RESULT_TTL] 长得多是刻意的：打开 App 不该为了刷新「预计收入」
     * 去敲几十个接口。超过这个天数才判定「连过期数据都没有」，让调用方去抓 ——
     * 那是为了防止用户换了手机/清了数据之后，界面上永远显示一份很旧的数。
     */
    private const val STALE_CEILING_MS = 30L * 24 * 60 * 60 * 1000

    // 基金/ETF 每10份分红超过100元视为异常（基金分红来自净值拆分，不可能达到）
    private const val MAX_DIVIDEND_PER_TEN_SHARES_FUND = 100.0
        // 股票每10股分红上限放宽：A股极端高分红如茅台约300元/10股（含特别分红更高），留足余量
        private const val MAX_DIVIDEND_PER_TEN_SHARES_STOCK = 1000.0

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
        cache.clear()
    }

    /**
     * 分红缓存超过上限时淘汰最旧条目（按写入时间戳）。避免长会话里缓存无限增长。
     * 最近写入的（本次同步/刷新窗口内）时间戳最新，不会被误删。
     */
    private fun trimCacheIfNeeded() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        val entries = cache.entries.sortedBy { it.value.timestamp }
        val removeCount = cache.size - MAX_CACHE_ENTRIES
        entries.take(removeCount).forEach { cache.remove(it.key) }
    }
    
    fun getCacheStats(): Map<String, Any> = mapOf(
        "size" to cache.size,
        "hits" to cacheHits.get(),
        "misses" to cacheMisses.get()
    )
    
    fun close() {
        cache.clear()
    }

    /**
     * 消费本轮「天天基金限流」信号。
     *
     * 返回 true 表示这一轮手动刷新撞过 514/429（部分场外基金分红因限流没抓全），
     * 并**清零**，避免下一次刷新（或同一轮别处的入口）重复提示。
     * 不消费 [FundDividendApiService.otcRateLimitHit] 本身（那是进程级退避标志，
     * 由 [preFetchDividends] 结尾读走后清零）；这里只回仓库实例级累积的结论。
     */
    internal fun consumeOtcRateLimitWarning(): Boolean {
        val hit = _otcRateLimitedThisCycle
        _otcRateLimitedThisCycle = false
        return hit
    }

// ==========================================================================
// §6 单标的查询与抓取编排
// getDividendsBySymbol 总入口 → tryGetFromCache（库/「抓过」复用）→ fetchSource 按标的分派到 东财 / 新浪 / 场外基金。失败抛 NoDividendAnswer 而不是返回 emptyList——「没问到」与「没有分红」不能混。
// ==========================================================================

    /**
     * 判断 [symbol] 今天是否已经成功抓过一次分红。
     *
     * 基于 [lastFetchedAt]（markFetched 写入的时间戳）与**同一自然日**比较
     * （按设备时区的 LocalDate，而非只比毫秒差 24h——跨零点刷新也要算「今天」）。
     * 抽成成员 + 顶部 [isFetchedToday] 纯函数，便于单测跨日 / 同日边界。
     */
    private fun isFetchedToday(symbol: String): Boolean =
        isFetchedToday(lastFetchedAt(symbol), System.currentTimeMillis())
    
    /**
     * 取某个标的的分红记录。
     *
     * @param forceRefresh true 时跳过缓存强制重抓（用户点「刷新」走这条）
     * @param batchResults 同一批 [prefetchDividends] 的结果，key 是**标准化后**的 symbol。
     *        命中就跳过逐条级联；为 null 时行为与从前完全一致（单标的查询传 null）。
     *        按次传入而不是读共享字段，是为了让并发的两个预取周期互不干扰 ——
     *        共享字段会互相清空，导致整批退化成逐条级联
     */
    suspend fun getDividendsBySymbol(
        symbol: String,
        forceRefresh: Boolean = false,
        batchResults: Map<String, List<DividendRecord>>? = null
    ): List<DividendRecord> {
        val normalizedSymbol = StockCodeManager.normalizeSymbol(symbol)

        if (!forceRefresh) {
            tryGetFromCache(normalizedSymbol)?.let { return it }

            // 上一轮所有数据源都没应答，且还在退避窗内 → 这一轮一个请求都不发，
            // 只用已经有的（内存旧值 / 库里记录）。场外基金没有批量入口，
            // 上百只失败标的每轮各重打一次就是请求风暴
            if (shouldSkipAfterFailure(
                    lastFailedAt = lastFailedAt(normalizedSymbol),
                    now = System.currentTimeMillis(),
                    ttl = computeFailureBackoff(
                        count = failureCount(normalizedSymbol),
                        everSucceeded = lastFetchedAt(normalizedSymbol) != null
                    )
                )
            ) {
                return cache[normalizedSymbol]?.data
                    ?: recordsFromDb(dividendDao.getBySymbol(normalizedSymbol))
            }
        }

        cacheMisses.incrementAndGet()

        val existingEntry = cache[normalizedSymbol]
        // forceRefresh=true 时强制全量刷新，避免旧错误数据通过增量合并残留
        val isIncremental = !forceRefresh && existingEntry != null && !existingEntry.needsFullFetch()

        return try {
            val data = fetchDividendData(normalizedSymbol, batchResults)

            if (data.isNotEmpty()) {
                saveDividendsToDb(normalizedSymbol, data, isIncremental)
            } else if (!isIncremental) {
                // 全量刷新且数据源应答了空（确实没分红 / 这次没返回）：清掉旧自动分红记录，
                // 否则库里陈旧自动分红会被读回并固化 TTL，用户点"刷新"却看到旧数据。
                // 增量刷新不在此清，避免源临时缺数据误删历史自动分红；手动记录始终保留。
                dividendDao.deleteNonManualBySymbol(normalizedSymbol)
            }

            val mergedData = if (isIncremental && existingEntry != null) {
                mergeDividendRecords(existingEntry.data, data)
            } else {
                // 全量刷新后从数据库读取完整记录（含保留的手动记录），保证缓存与DB一致
                val dbRecords = dividendDao.getBySymbol(normalizedSymbol)
                if (dbRecords.isNotEmpty()) {
                    recordsFromDb(dbRecords)
                } else {
                    data
                }
            }

            // 抓成功了就记一笔，**不管抓没抓到**。空结果也是结论，不记下来
            // 下次冷启动会再抓一遍（见 [fetchState] 的说明）。
            // 「抓成功」≠「抓到了」：所有数据源都没应答时 [fetchDividendData] 抛
            // [DividendSourceUnavailableException]，直接落到下面的 catch —— 那时
            // 一个字都不写，否则一次网络抖动会被固化成「这只没有分红」最长 7 天
            markFetched(normalizedSymbol)
            // 抓通过了就清掉失败退避标记，让它立刻回到正常的 TTL 节奏
            clearFailure(normalizedSymbol)

            cache[normalizedSymbol] = CacheEntry(
                data = mergedData,
                timestamp = System.currentTimeMillis(),
                lastFullFetch = if (isIncremental) existingEntry?.lastFullFetch ?: System.currentTimeMillis() else System.currentTimeMillis()
            )
            trimCacheIfNeeded()
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
            markFailed(normalizedSymbol)
            cache[normalizedSymbol]?.data
                ?: recordsFromDb(dividendDao.getBySymbol(normalizedSymbol))
        } catch (e: Exception) {
            AppLogger.e("获取分红数据失败: ${e.message}", e, "DividendRepository")
            // 抓不回来也不该让已入库的分红凭空消失：内存有旧值用旧值，没有就退回
            // 数据库记录（离线/限流时 24h TTL 刚过期的常见场景，见 [tryGetFromCache]）
            cache[normalizedSymbol]?.data
                ?: recordsFromDb(dividendDao.getBySymbol(normalizedSymbol))
        }
    }

    private suspend fun tryGetFromCache(normalizedSymbol: String): List<DividendRecord>? {
        val cached = cache[normalizedSymbol]
        if (cached != null && cached.isValid(cacheTTL)) {
            cacheHits.incrementAndGet()
            return cached.data
        }

        val dbRecords = dividendDao.getBySymbol(normalizedSymbol)
        if (dbRecords.isNotEmpty()) {
            // 有分红记录的标的必须受 24h 短 TTL 约束（到账日/金额会随新公告变）。
            // 从前这里不看抓取时间直接返回 DB 记录 → prefetch 的 pending 判空
            // （tryGetFromCache(symbol) == null）永远不为真 → 抓过一次后永远不再抓，
            // 新公告进不了「今年预计」/下一笔倒计时/月度柱图（用户实测：4/17 除息、
            // 4/24 到账的记录在日历里一直不显示）。stamp 为 null（从未抓过，如纯手动
            // 录入）也放行重抓——抓取过程会合并保留手动记录，不会丢
            val stamp = lastFetchedAt(normalizedSymbol)
            if (stamp == null || System.currentTimeMillis() - stamp >= cacheTTL) {
                return null
            }
            val records = recordsFromDb(dbRecords)
            cache[normalizedSymbol] = CacheEntry(records, System.currentTimeMillis(), lastFullFetch = 0L)  // 数据库恢复的缓存不标记为全量拉取
            return records
        }

        // 库里一条都没有，但**近期确实抓过** → 结论是「这只没有分红」，不是「没抓过」。
        // 判据抽成纯函数 [shouldReuseEmptyResult] 是为了能单测：这条判断错了要么
        // 每次冷启动重抓几百个请求，要么把「真的有分红」误判成没有并压 24 小时
        //
        // 空结果用**长得多的 TTL**：走到这里说明这只标的没有分红记录，
        // 而一只从没分过红的基金/股票不会明天突然开始分（要分也是先出公告，
        // 那时 lastFetchedAt 也会被刷新）。有分红的标的仍走 24 小时短 TTL ——
        // 它们的到账日、金额会随新公告变化，得勤着点。
        val stamp = lastFetchedAt(normalizedSymbol)
        if (shouldReuseEmptyResult(
                dbRecordCount = dbRecords.size,
                lastFetchedAt = stamp,
                now = System.currentTimeMillis(),
                ttl = NEGATIVE_RESULT_TTL
            )
        ) {
            cacheHits.incrementAndGet()
            cache[normalizedSymbol] = CacheEntry(
                emptyList(),
                System.currentTimeMillis(),
                lastFullFetch = stamp ?: 0L
            )
            return emptyList()
        }

        return null
    }

    /**
     * 「数据源没应答」的信号 —— 与「问过了，确实没有分红」是两回事，不能混。
     *
     * 分红抓取的失败与空结果在 List 上完全同形（都是 emptyList），而调用方
     * [getDividendsBySymbol] 抓完就 [markFetched]，一旦把「没问到」记成「没有」，
     * 冷启动的只读缓存路径会把它当有效结论复用 7 天：预计分红 / 收息进度 / 日历
     * 全显示 0，**且界面没有任何错误提示**。所以每个数据源失败时抛本异常而不是
     * 返回 emptyList，由 [fetchDividendData] 汇总后决定这是不是真的「没有分红」。
     */
    private class DividendSourceUnavailableException(message: String) : IOException(message)

    /**
     * 数据库行 → [DividendRecord]。三处读取点（缓存恢复、抓取后合并、抓取失败兜底）
     * 必须是同一套口径，否则同一条记录在日历与进度条上会长出两个版本。
     */
    private fun recordsFromDb(dbRecords: List<Dividend>): List<DividendRecord> =
        dbRecords.map { dbDiv ->
            DividendRecord(
                exDividendDate = dbDiv.date,
                dividendPerShare = dbDiv.amount.toString(),
                announceDate = "",
                fiscalYear = dbDiv.date.take(4),
                paymentDate = dbDiv.payDate
            )
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
        // 批量预取优先。[prefetchDividends] 已经用少量请求把这批标的问完了，
        // 命中就直接用，省掉逐条的级联（ETF 空手还要顺序试三种代码格式，再 3 个请求）
        //
        // 从入参读而不是从共享字段 remove：共享字段在资产页与持仓页同时刷新时
        // 会被另一个预取周期清空，整批退化成逐条级联 —— 请求风暴照旧、闪退照旧
        val prefetched = batchResults?.get(normalizedSymbol)
        if (prefetched != null && prefetched.isNotEmpty()) return prefetched

        // 有条目 ⇔ 这个标的参与过东财批量查询（场外基金与批量失败时无条目，行为不变）。
        // 从预取结果派生而不是收形参：唯一的调用点从来传不过来，形参是死代码，
        // 批量只让「有数据」的标的省了请求，空标的首次 sweep 仍付全级联
        // （逐条重问东财 + ETF 重问 3 种格式 + 非 ETF 空股票多打一次新浪）。
        // 批量说没有的标的，级联里跳过重复问东财那一步，但保留 Sina 兜底 ——
        // 那是独立的第二来源，不能因为合并了请求就把它一起省掉
        val eastmoneyAnswered = prefetched != null

        // fund_ 前缀必须在这里剥掉。extractPureCode 只认 sh/sz/bj，不认 fund_，
        // 不剥的话 stockCode 会带着 "fund_" 传下去，于是 isOTCFundCode / isETF 全部判错，
        // 最后拿着 "fund_004502" 去请求接口——场外基金的分红永远抓不到，
        // 而且失败是静默的（接口返回空 → 不写库 → 分红弹窗显示「暂无」）
        val pureSymbol = StockCodeManager.stripFundPrefix(normalizedSymbol)
        val stockCode = StockCodeManager.extractPureCode(pureSymbol)
        // 剥完前缀还不够：fund_ 本身就是「场外份额」的显式标记，但 400030 / 420102
        // 落在 400/420 号段，isOTCFundCode 按号段认不出它们（它只认 01/02 开头与
        // 004xxx-009xxx）。不把前缀算进场外判定，这些真基金会被送进东财 + 新浪股票接口，
        // 而场外基金的分红只在天天基金有 —— 请求全打出去，结果还是空的，而且不报错。
        val isOTCFund = StockCodeManager.isOTCFund(normalizedSymbol)
        val isEtf = !StockCodeManager.hasFundPrefix(normalizedSymbol) &&
            StockCodeManager.isETF(pureSymbol)

        // 场内交易型基金：含 ETF 与「非 ETF 的场内基金」（REITs 508xxx、封闭式基金 500/501xxx）。
        // 它们都在交易所挂牌、却不是股票也不是场外基金，分红/收益分配只在天天基金有，
        // 东财股票分红报告(RPT_SHAREBONUS_DET)与 Sina 都不覆盖。从前只把 isEtf 当门槛，
        // 导致 REIT 永远到不了基金分红源、静默显示 0 并被 7 天负缓存固化（见 sh508085）。
        // LOF（16xxxx/18xxxx 深交所上市开放式基金）同样在交易所挂牌、分红/收益分配
        // 只在天天基金有，东财股票分红表与 Sina 都不覆盖。classifyCode 把它们单列为
        // CodeCategory.LOF（不是 EXCHANGE_FUND），只认 EXCHANGE_FUND 会让 LOF 掉到下面的
        // 新浪兜底分支 → 恒为空 → 被 7 天负缓存固化，与上面 REIT 那次是同一个漏网。
        val category = StockCodeManager.classifyCode(pureSymbol)
        val isExchangeFund = isEtf || (!StockCodeManager.hasFundPrefix(normalizedSymbol) &&
            (category == StockCodeManager.CodeCategory.EXCHANGE_FUND ||
                category == StockCodeManager.CodeCategory.LOF))

        // 「本轮有没有任何一个数据源应答过」。只要有一个应答，空结果就是有效结论
        // （该写负缓存）；一个都没应答说明是网络/限流问题，上抛让调用方什么都不写
        val answered = AtomicBoolean(eastmoneyAnswered)

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

    private suspend fun saveDividendsToDb(
        symbol: String,
        data: List<DividendRecord>,
        isIncremental: Boolean
    ) {
        if (isIncremental) {
            val oneYearAgo = java.time.LocalDate.now().minusYears(1)
            val recentData = data.filter { record ->
                try {
                    val date = java.time.LocalDate.parse(
                        record.exDividendDate,
                        DateFormats.ISO_DATE_FORMATTER
                    )
                    date.isAfter(oneYearAgo)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            saveDividendsToDbInternal(symbol = symbol, records = recentData, isIncremental = true)
        } else {
            saveDividendsToDbInternal(symbol = symbol, records = data, isIncremental = false)
        }
    }
    
    /**
     * 合并增量分红记录到现有数据中，去重
     */
    private fun mergeDividendRecords(existing: List<DividendRecord>, newData: List<DividendRecord>): List<DividendRecord> {
        val existingDates = existing.map { it.exDividendDate }.toSet()
        val uniqueNewRecords = newData.filter { it.exDividendDate !in existingDates }
        return existing + uniqueNewRecords
    }
    
    private suspend fun fetchFromEastmoney(stockCode: String): List<DividendRecord> {
        return try {
            val result = eastmoneyApi.getDividendHistory(stockCode)
            if (result.isSuccess) {
                val dividendDataList = result.getOrNull() ?: emptyList()
                dividendDataList.map { divData ->
                    DividendRecord(
                        exDividendDate = divData.date,
                        dividendPerShare = divData.dividendPerShare.toString(),
                        announceDate = divData.reportDate ?: "",
                        fiscalYear = divData.date.substring(0, 4),
                        paymentDate = divData.paymentDate
                    )
                }
            } else {
                throw DividendSourceUnavailableException("东方财富分红接口未应答[$stockCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("东方财富分红数据获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("东方财富分红数据获取失败[$stockCode]")
        }
    }
    
    private suspend fun fetchFromEastmoneyWithFormat(securityCode: String): List<DividendRecord> {
        return try {
            val result = eastmoneyApi.getDividendHistoryBySecurityCode(securityCode)
            if (result.isSuccess) {
                val dividendDataList = result.getOrNull() ?: emptyList()
                dividendDataList.map { divData ->
                    DividendRecord(
                        exDividendDate = divData.date,
                        dividendPerShare = divData.dividendPerShare.toString(),
                        announceDate = divData.reportDate ?: "",
                        fiscalYear = divData.date.substring(0, 4),
                        paymentDate = divData.paymentDate
                    )
                }
            } else {
                throw DividendSourceUnavailableException("东方财富带格式分红接口未应答[$securityCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ETF东方财富带格式[$securityCode]获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("东方财富带格式分红获取失败[$securityCode]")
        }
    }
    
    internal suspend fun fetchFromFundApiForETF(fundCode: String): List<DividendRecord> {
        return try {
            val result = fundDividendApi.getFundDividendHistory(fundCode)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                throw DividendSourceUnavailableException("天天基金分红接口未应答(ETF)[$fundCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ETF天天基金接口获取失败[${fundCode}]: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("天天基金分红获取失败(ETF)[$fundCode]")
        }
    }
    
    internal suspend fun fetchFromSina(stockCode: String, market: String): List<DividendRecord> {
        return try {
            val result = sinaService.fetchDividendData(stockCode, market)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                throw DividendSourceUnavailableException("新浪分红接口未应答[$stockCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("新浪分红数据获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("新浪分红数据获取失败[$stockCode]")
        }
    }
    
    private suspend fun fetchFromFundApi(fundCode: String): List<DividendRecord> {
        return try {
            AppLogger.d("天天基金分红数据获取: $fundCode", "DividendRepository")
            val result = fundDividendApi.getFundDividendHistory(fundCode)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                throw DividendSourceUnavailableException("天天基金分红接口未应答[$fundCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("天天基金分红数据获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("天天基金分红数据获取失败[$fundCode]")
        }
    }
    
// ==========================================================================
// §7 写入（save / delete / getDb）
// saveDividends / saveManualDividend / deleteManualDividend / getDbDividendsBySymbol / saveDividendsToDbInternal（增量与全量两条路径，合并规则见 mergeDividendRecords）。
// ==========================================================================

    suspend fun saveDividends(symbol: String, records: List<DividendRecord>) {
        if (records.isEmpty()) return
        val normalized = StockCodeManager.normalizeSymbol(symbol)
        saveDividendsToDbInternal(normalized, records)
    }

    /**
     * 手动添加或更新分红记录。用于用户纠错。
     * @return 保存后的 Dividend id
     */
    suspend fun saveManualDividend(dividend: Dividend): Long {
        val normalized = StockCodeManager.normalizeSymbol(dividend.symbol)
        val toSave = dividend.copy(symbol = normalized, isManual = true)
        val id = if (toSave.id != 0) {
            dividendDao.update(toSave)
            toSave.id.toLong()
        } else {
            // 如果同一天已存在，则覆盖
            val existing = dividendDao.getBySymbolAndDate(normalized, toSave.date)
            if (existing != null) {
                dividendDao.update(toSave.copy(id = existing.id))
                existing.id.toLong()
            } else {
                dividendDao.insert(toSave)
            }
        }
        // 清除缓存，强制下次从数据库重新加载
        cache.remove(normalized)
        return id
    }

    /**
     * 手动删除分红记录。
     */
    suspend fun deleteManualDividend(id: Int, symbol: String) {
        dividendDao.deleteById(id.toLong())
        cache.remove(StockCodeManager.normalizeSymbol(symbol))
    }

    /**
     * 获取数据库中某资产的手动/自动分红记录（原始 Dividend 实体）。
     */
    suspend fun getDbDividendsBySymbol(symbol: String): List<Dividend> {
        return dividendDao.getBySymbol(StockCodeManager.normalizeSymbol(symbol))
    }

    private suspend fun saveDividendsToDbInternal(symbol: String, records: List<DividendRecord>, isIncremental: Boolean = false) {
        if (records.isEmpty()) return

        // 过滤异常分红数据：基金/ETF 上限 100 元/10份，股票上限 1000 元/10股（茅台类高分红不误杀）
        // symbol 是规范化后的（fund_ 前缀原样保留）。isStock 对带该前缀的场外份额返回 false，
        // 所以 fund_400030 这类**必须**拿基金的 100 上限——此前它误判成股票，用的是 1000。
        val maxAmount = if (StockCodeManager.isStock(symbol)) {
            MAX_DIVIDEND_PER_TEN_SHARES_STOCK
        } else {
            MAX_DIVIDEND_PER_TEN_SHARES_FUND
        }
        val validRecords = records.filter { record ->
            val amount = record.dividendPerShare.toDoubleOrNull() ?: 0.0
            if (amount <= 0) {
                AppLogger.w("跳过非正分红记录: $symbol, 日期=${record.exDividendDate}, 金额=$amount", "DividendRepository")
                false
            } else if (amount > maxAmount) {
                AppLogger.w("跳过异常高分红记录: $symbol, 日期=${record.exDividendDate}, 金额=$amount", "DividendRepository")
                false
            } else {
                true
            }
        }

        if (validRecords.isEmpty()) {
            AppLogger.w("无有效分红记录可保存: $symbol", "DividendRepository")
            return
        }

        StockDatabase.getInstance(context).withTransaction {
            if (!isIncremental) {
                // 全量更新：只删除自动抓取的记录，保留用户手动修正的记录(isManual=1)
                dividendDao.deleteNonManualBySymbol(symbol)
            }

            // 增量与全量统一逻辑：跳过已存在记录（含手动记录），避免覆盖用户修正
            val dividends = validRecords.mapNotNull { record ->
                val existing = dividendDao.getBySymbolAndDate(symbol, record.exDividendDate)
                if (existing == null) {
                    Dividend(
                        symbol = symbol,
                        name = "",
                        amount = record.dividendPerShare.toDoubleOrNull() ?: 0.0,
                        date = record.exDividendDate,
                        payDate = record.paymentDate,
                        isManual = false
                    )
                } else {
                    null
                }
            }

            if (dividends.isNotEmpty()) {
                dividendDao.insertAll(dividends)
            }
        }
        cache[symbol] = CacheEntry(validRecords, System.currentTimeMillis())
        trimCacheIfNeeded()
    }
    
// ==========================================================================
// §8 preFetchDividends（旧批量入口，按 SyncBudget 预算）
// 与 §3 的 prefetchDividends 并存：这版按调用方传入的预算跳过后续代码、超时不写缓存（「抓过且没有分红」的结论留空，之后按需抓取再补，不丢数据）。
// ==========================================================================

    /**
     * 老入口：逐条抓一批标的的分红（内部先跑一次 [prefetchDividends] 批量预取）。
     *
     * 先批量再逐条是这次修复的核心：刷新按钮（forceRefresh=true）是全 App
     * 唯一还在走这条老入口的路径，而它从前**完全绕开批量预取** —— 每个标的
     * 各打一遍完整级联（东财逐条 + ETF 三种代码格式 + 新浪兜底 + 场外基金
     * 逐个抓 HTML），59 个标的打出 250~400 个请求：OkHttp 队列排长龙、
     * 多个协程同时做 Jsoup 解析，内存压力直接让系统回收进程 ——
     * 就是用户看到的「按了刷新就闪退」。
     * forceRefresh 时 [prefetchDividends] 内部全量 pending，不存在「刷新拿到旧数据」。
     *
     * 整段强制跑在 [Dispatchers.IO] 上：调用方可能是 `viewModelScope`
     * （`Dispatchers.Main.immediate`），52 个代码的协程启动与续体挤在主线程
     * Looper 里，就是「点刷新黑屏、日志还全静默」的直接原因之一。
     *
     * @param timeBudgetMs 本阶段的时间预算（毫秒），<=0 表示不设限。
     *        [AssetSyncManager] 的手动刷新会传入子预算（见
     *        [SyncBudget.prefetchPhaseBudget]）：超时后跳过的代码不写缓存，
     *        「抓过且没有分红」的结论留空，之后按需抓取时还会补上，不丢数据。
     */
    suspend fun preFetchDividends(
        symbols: List<String>,
        forceRefresh: Boolean = false,
        timeBudgetMs: Long = 0L
    ) {
        withContext(Dispatchers.IO) {
            // 本轮「天天基金限流」信号清零：otcRateLimitHit 是进程级的，每轮预取开头清一次、
            // 结尾读走，避免上一轮撞的 514 误带到这一轮的提示里。
            FundDividendApiService.otcRateLimitHit = false

            val normalizedSymbols = symbols.map { StockCodeManager.normalizeSymbol(it) }.distinct()

            val batchResults = prefetchDividends(normalizedSymbols, forceRefresh)

            // 预算截止时刻：超时后不再启动新代码的逐条抓取
            val deadline = if (timeBudgetMs > 0L) System.currentTimeMillis() + timeBudgetMs else 0L
            val semaphore = Semaphore(maxConcurrentFetch)
            val checkedCount = AtomicInteger(0)

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
                coroutineScope {
                    normalizedSymbols.map { symbol ->
                        val entry = cache[symbol]
                        val isOtc = StockCodeManager.isOTCFund(symbol)
                        val ttlExpired = entry == null || !entry.isValid(cacheTTL)
                        val shouldFetch = if (isOtc) {
                            // 手动刷新(forceRefresh)时，场外基金只补抓「TTL 已过期 且 今天还没成功抓过」的，
                            // 避免每次刷新把所有场外基金打一遍再踩 514 请求风暴；非手动刷新维持原 TTL 语义。
                            shouldRefetchOtc(
                                ttlExpired = ttlExpired,
                                forceRefresh = forceRefresh,
                                fetchedToday = isFetchedToday(symbol)
                            )
                        } else {
                            forceRefresh || ttlExpired
                        }
                        if (shouldFetch) {
                            async {
                                // 预算检查必须放在「拿到信号量之后」，不能放在 async 开头：
                                // 上面 N 个 async 是**一次性全部 launch** 的，若在 withPermit 之前检查，
                                // 它们全都在 t≈0 落空、之后在信号量上排队恢复时早已错过检查点，
                                // 预算形同虚设。真机日志坐实过这个坑：手动刷新 70s 预算里
                                // 预取子预算明明只有 28s（SyncBudget.prefetchPhaseBudget），
                                // 实测却跑了 50.3s（轮次#3 19:53:45→19:54:35），
                                // 把价格同步的时间挤掉大半。价格同步那一侧同样的问题早已修掉
                                // （见 AssetSyncManager 中 withPermit 内的 SyncBudget.isExceeded）。
                                semaphore.withPermit {
                                    if (deadline > 0L && System.currentTimeMillis() > deadline) {
                                        AppLogger.w(
                                            "分红预取达到时间预算，跳过 $symbol（留待按需抓取）",
                                            "DividendRepository"
                                        )
                                        return@withPermit
                                    }
                                    checkedCount.incrementAndGet()
                                    getDividendsBySymbol(symbol, forceRefresh, batchResults)
                                }
                            }
                        } else null
                    }.filterNotNull().forEach { it.await() }
                }

                // 把本轮天天基金限流信号读走（非消费读）：供 ViewModel 在刷新收尾时弹 Snackbar。
                // 读到的是进程级 otcRateLimitHit 这一瞬间的状态，读完即清零；
                // 真正给 UI 的「这一轮撞过限流」结论存在仓库实例级的 _otcRateLimitedThisCycle，
                // 由 consumeOtcRateLimitWarning 消费一次（读即清零），下次刷新不会重复提示。
                _otcRateLimitedThisCycle = FundDividendApiService.otcRateLimitHit
                FundDividendApiService.otcRateLimitHit = false
            }
        }
    }
}

// ==========================================================================
// §9 文件级私有辅助（纯函数，可直接 JVM 单测）
// shouldReuseEmptyResult（空结果复用判定）/ isFailedWithin（失败退避窗）/ chunkSecurityCodes（东财合并请求的代码分块，按编码后长度估算成本）。
// ==========================================================================

/**
 * 库里一条分红记录都没有时，该不该把它当成「缓存命中」而不是「没抓过」。
 *
 * **纯函数**，不碰 Context / Room / 网络，所以能直接 JVM 单测。
 * 抽出来是因为这条判断错了代价都不小，而且两个方向的错都很隐蔽：
 *
 * - 该认而不认 → 87% 的标的（实测）每次冷启动重抓一遍，接口被打到 514 限流，
 *   进程长时间占着内存做解析，于是更容易被系统回收 → 又一轮重抓
 * - 不该认而认 → 一只真有分红的标的被压 24 小时不抓，界面上分红一直是空的
 *
 * @param dbRecordCount 库里该代码的记录条数
 * @param lastFetchedAt 上次抓取完成的时间戳；null 表示从没抓过
 * @param now 当前时间戳
 * @param ttl 认定「抓过」的有效期
 */
internal fun shouldReuseEmptyResult(
    dbRecordCount: Int,
    lastFetchedAt: Long?,
    now: Long,
    ttl: Long
): Boolean {
    // 库里有记录就走正常的库恢复路径，轮不到这里
    if (dbRecordCount > 0) return false
    // 从没抓过 → 真的没数据，必须去抓
    val fetched = lastFetchedAt ?: return false
    if (fetched <= 0L) return false
    // 抓过，但太久之前 → 结论可能已经过期（一只基金可能刚宣布首次分红）
    if (now - fetched >= ttl) return false
    // 时间戳来自未来（改过系统时间）→ 不认，宁可重抓也别无限期压住
    if (fetched > now) return false
    return true
}

/**
 * 失败退避窗长度（指数退避）。
 *
 * 按**连续失败次数** `count` 指数增长：`base × 2^(count-1)`，封顶 `max`。
 * 偶发抖动（count 小）很快自愈，持续失败的源不会无限放大请求量。
 * `everSucceeded = false`（历史上从未成功过，可能是刚加的、其实有效的标的）
 * 用更短的档位，让它更快重试，避免被当成「永久失败」长时间屏蔽。
 *
 * 用 `Long` 运算 + `shift` 上限 30，防止 `2^shift` 溢出 `Int`；`coerceAtMost(max)`
 * 封顶，`base > 0` 保证结果非负。
 *
 * @param count 连续失败次数（≥1）
 * @param everSucceeded 该 symbol 历史上是否成功抓取过
 */
internal fun computeFailureBackoff(count: Int, everSucceeded: Boolean): Long {
    val base = if (everSucceeded) FAILURE_BACKOFF_BASE_MS else FAILURE_BACKOFF_BASE_MS_NEVER
    val max = if (everSucceeded) FAILURE_BACKOFF_MAX_MS else FAILURE_BACKOFF_MAX_MS_NEVER
    val shift = (count - 1).coerceAtLeast(0).coerceAtMost(30)
    return (base.toLong() shl shift).coerceAtMost(max.toLong())
}

/**
 * 上一轮抓取**没问到**（网络失败 / 限流）之后，这一轮该不该先歇一会儿。
 *
 * **纯函数**，不碰 Context / Room / 网络，便于单测。
 *
 * 「失败」与「确实没有分红」必须分开记。写成 [shouldReuseEmptyResult] 那种结论，
 * 一次网络抖动就被固化成「这只没有分红」最长 7 天且界面静默；但干脆不记也不行 ——
 * 场外基金没有批量入口（天天基金每基金一个页面），上百只标的每轮各重打一次失败
 * 的接口就是请求风暴。所以失败只压一个指数退避窗（见 [computeFailureBackoff]）
 * ，过期自动重试，用户手动刷新（forceRefresh）随时可以绕过。
 *
 * @param lastFailedAt 上次失败的时间戳；null 表示没失败过
 * @param now 当前时间戳
 * @param ttl 退避窗长度
 */
internal fun shouldSkipAfterFailure(lastFailedAt: Long?, now: Long, ttl: Long): Boolean {
    val failed = lastFailedAt ?: return false
    if (failed <= 0L) return false
    // 时间戳来自未来（改过系统时间 / 时钟回拨）也算在窗内：
    // 宁可多等一窗不重打，也不要把请求量放大回去
    return now - failed < ttl
}

/**
 * 接口层 [DividendData] → 领域层 [DividendRecord]。
 *
 * 与逐条路径 `fetchFromEastmoneyWithFormat` 里的映射是同一套口径：
 * 东财的 `PRETAX_BONUS_RMB` 实际是「每 10 股」分红，与应用内 `dividendPerShare` 单位一致，
 * 不做换算。抽成扩展函数是为了让批量路径和逐条路径不可能各写一份而对不上 ——
 * 分红金额差一位就是一个数量级，而且是静默的。
 */
private fun DividendData.toDividendRecord(): DividendRecord = DividendRecord(
    exDividendDate = date,
    dividendPerShare = dividendPerShare.toString(),
    announceDate = reportDate ?: "",
    // date 理论上不会为空（mapDividendItem 里已挡掉空除息日），
    // 但仍不能直接 substring(0, 4)：一旦为空会 StringIndexOutOfBounds，
    // 而这里在批量路径上，一次异常会把整批预取全废掉
    fiscalYear = date.take(4),
    paymentDate = paymentDate
)

/**
 * 单个批量请求最多带多少个 SECURITY_CODE。
 *
 * 50 个代码的 filter 编码后约 2KB，离服务端/代理常见的 8KB 上限有足够余量；
 * 再大一是没有额外收益，二是把「414 或 OkHttp 拒绝」的概率推高 ——
 * 而批量失败意味着整批预取退化成逐条级联，正是这次要修掉的请求风暴。
 */
internal const val MAX_SECURITY_CODES_PER_REQUEST = 50

/**
 * 单个批量请求 URL 的长度预算（字符）。
 *
 * 按最坏情况估算：URLEncoder 会把引号、括号、逗号全部转义成 %XX，
 * 一个代码最坏占 3 倍长度。4KB 预算下即使代码全是 `SH.510050` 这种
 * 9 字符长码，也只会先撞数量上限，URL 永远不会接近 8KB。
 */
internal const val MAX_BATCH_URL_LENGTH = 4000

/**
 * 批量请求 URL 的固定部分基数：域名 + reportName / columns / pageSize /
 * sortColumns 等参数，再加编码放大的经验估计。切块时把它算进每一块的预算。
 */
internal const val BATCH_URL_FIXED_OVERHEAD = 600

/** 批量分块请求的并发上限：块数本来就少（百个代码 ≈ 2~4 块），别把东财当成压测对象 */
internal const val MAX_CONCURRENT_BATCH_CHUNKS = 3

/**
 * 把一批 SECURITY_CODE 切成分块，让每块的批量请求 URL 都控制在安全长度内。
 *
 * ## 为什么必须切
 *
 * 批量查询要把所有代码拼进 `filter=(SECURITY_CODE in ("a","b",…))`。
 * 59 个标的加上 ETF 的三种格式变体就是 100+ 个代码，URL 有好几 KB：
 * 服务端可能返回 414，OkHttp/代理也可能直接拒绝 —— 而批量失败意味着
 * 整批预取退化成逐条级联（那就是这次要修的请求风暴），所以必须在发出去之前切好。
 *
 * 双保险，先撞哪个算哪个：数量上限 [MAX_SECURITY_CODES_PER_REQUEST] 与
 * 长度预算 [MAX_BATCH_URL_LENGTH]（含固定基数 [BATCH_URL_FIXED_OVERHEAD]，
 * 每个代码按最坏 3 倍编码长度估）。
 *
 * **纯函数**：不碰网络与 Context，切块规则错了（丢代码 / 超长 URL）能直接单测。
 *
 * @param codes 一批 SECURITY_CODE，结果保持顺序、不重不漏
 * @return 分块结果；单个代码就超预算时独占一块 —— 宁可发一个可能被拒的长请求，
 *         也不能把它切丢了（丢了它就会走逐条级联，正是要避免的）
 */
internal fun chunkSecurityCodes(
    codes: List<String>,
    maxCodesPerChunk: Int = MAX_SECURITY_CODES_PER_REQUEST,
    maxUrlLength: Int = MAX_BATCH_URL_LENGTH,
    fixedOverhead: Int = BATCH_URL_FIXED_OVERHEAD
): List<List<String>> {
    if (codes.isEmpty()) return emptyList()
    val safeCount = maxCodesPerChunk.coerceAtLeast(1)
    val chunks = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var usedLength = fixedOverhead
    for (code in codes) {
        // 引号 + 逗号 + 括号开销，最坏情况每个字符都被百分号编码
        val encodedCost = (code.length + 3) * 3
        val wouldExceedCount = current.size >= safeCount
        val wouldExceedLength = usedLength + encodedCost > maxUrlLength
        if (current.isNotEmpty() && (wouldExceedCount || wouldExceedLength)) {
            chunks += current
            current = mutableListOf()
            usedLength = fixedOverhead
        }
        current += code
        usedLength += encodedCost
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}
