package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.data.price.BoundedTtlCache
import com.example.stockdiary.data.price.RoundSourceFailureScope
import com.example.stockdiary.data.price.SourceStats
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.FailureLogFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 基金数据 API：获取场外基金最新净值。
 *
 * ## 数据源
 *
 * - **轻量源（首选）**：`fundmobapi.eastmoney.com/FundMNewApi/FundMNBasicInformation`，
 *   只回最新一期净值，约 2.5KB，且带基金名称。解析见 [FundNavParser]。
 * - **legacy 源（回退）**：`fund.eastmoney.com/pingzhongdata/{code}.js`，
 *   内容是基金的**完整历史净值**（0.5~2MB），只为拿最后一个数却要把整份历史
 *   读进内存 —— 它是刷新时峰值内存的主要来源（原 fundgz.1234567.com.cn
 *   实时估值接口已于 2023 年因监管要求下线后改用的方案）。
 *
 * 历史净值数组在本项目里**没有任何消费方**（走势图、快照补洞、持仓盈亏、
 * 导出、分红都走 [com.example.stockdiary.database.AssetSnapshot] 或分红专用接口），
 * 所以首选轻量源不会损失任何功能。
 */
object FundAPI {
    private const val TAG = "FundAPI"
    private const val LEGACY_BASE_URL = "https://fund.eastmoney.com/pingzhongdata"
    private const val LIGHTWEIGHT_NAV_URL =
        "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNBasicInformation"
    /** 轻量源必须带 UA，否则东财回 ErrCode 61136「网络繁忙」。 */
    private const val USER_AGENT = "Mozilla/5.0"
    private const val LEGACY_REFERER = "https://fund.eastmoney.com/"
    private const val CACHE_DURATION_MS = 5 * 60 * 1000L

    /** 轻量源连续失败多少次后**整源熔断**（本进程内不再尝试）。 */
    private const val LIGHTWEIGHT_FAILURE_THRESHOLD = 3

    /**
     * 连续失败的判据时间窗：两次失败间隔超过 10 分钟就不算「同一轮持续故障」。
     *
     * 没有时间窗的话，「昨天挂机累计 2 次、今天再失败 1 次」也会把整源熔断，
     * 一次瞬时抖动要拖到进程重启才恢复。
     */
    private const val LIGHTWEIGHT_FAILURE_WINDOW_MS = 10 * 60 * 1000L

    /** 主机级短退避时长：与分红链路同口径（60s），覆盖限流窗口与一次网络切换恢复。 */
    private const val HOST_BACKOFF_MS = 60_000L

    /** 源统计里的稳定名字：三条链路共用同一个 SourceStats 模型 */
    private const val SOURCE_LIGHTWEIGHT = "基金-轻量源"
    private const val SOURCE_LEGACY = "基金-legacy源"
    private const val HOST_LIGHTWEIGHT = "fundmobapi.eastmoney.com"
    private const val HOST_LEGACY = "fund.eastmoney.com"

    private val client = NetworkClient.fundClient
    /**
     * 基金净值内存缓存。统一实现见 [BoundedTtlCache]，但这里保留原有两个「刻意口径」：
     * 1. **TTL 固定 5 分钟**（[CACHE_DURATION_MS]），且「已是今日净值」时 forceRefresh
     *    也复用（判定留在 [getFundInfoWithCache] 调用点，不搬进缓存层）；
     * 2. 历史上**没有容量上限**（基金数量远小于股票，且清缓存 / 进程回收是主要出口）。
     *    因此 maxEntries 取 [Int.MAX_VALUE]，不为「统一」而引入新的淘汰行为。
     */
    private val cache = BoundedTtlCache<String, FundInfo>(Int.MAX_VALUE)

    /**
     * 净值持久化端口（由 data 层实现）。
     *
     * 与 [UnifiedPriceService.init] 同一模式：在 Application.onCreate 注入一次。
     * **未注入时必须整体降级为空**（单测不注入；数据库初始化失败时也是 null）：
     * 拿不到持久化只是没有跨进程复用，退回原来的纯内存缓存即可，绝不能 NPE
     * 把整条同步链路打断。
     */
    @Volatile
    private var navCacheStore: FundNavCacheStore? = null

    /**
     * 注入净值持久化端口。在 Application.onCreate 调用一次。
     *
     * 网络层不再直接打开 Room：DAO 的构造在 data 层的
     * [com.example.stockdiary.data.fund.FundNavCacheStoreImpl]，本层只依赖接口。
     * 传 null 表示持久化不可用（降级为纯内存缓存）。
     */
    fun init(store: FundNavCacheStore?) {
        navCacheStore = store
    }

    /** 持久化缓存是否可用（未注入时 false）。单测用它钉住「未注入也不炸」。 */
    internal fun hasPersistentNavCache(): Boolean = navCacheStore != null

    // ==================== 轻量源 / legacy 源 ====================
    //
    // 两个数据源做成可替换的挂起函数，是为了让「回退链」与「整源熔断」
    // 能在 JVM 单测里用假源钉死（单测没有网络，打真实接口既慢又不可靠）。
    // 生产代码永远用下面这两个默认实现。

    /** 轻量源注入点，默认走真实网络（[fetchFundInfoLightweight]）。 */
    @Volatile
    internal var lightweightNavSource: suspend (String) -> FundInfo? = { code ->
        fetchFundInfoLightweight(code)
    }

    /** legacy 源注入点，默认走真实网络（[fetchFundInfoLegacy]）。 */
    @Volatile
    internal var legacyNavSource: suspend (String) -> FundInfo? = { code ->
        fetchFundInfoLegacy(code)
    }

    /** 轻量源连续失败计数（跨基金码、带时间窗累计，成功一次即清零）。 */
    private val lightweightFailureWindow = ConsecutiveFailureWindow(
        threshold = LIGHTWEIGHT_FAILURE_THRESHOLD,
        windowMs = LIGHTWEIGHT_FAILURE_WINDOW_MS
    )

    /** 轻量源是否已熔断。进程级：下次启动自动重置。 */
    @Volatile
    private var lightweightSourceDisabled = false

    // 轮次级源统计（只观测，不影响回退/熔断判定）
    private val lightweightStats = SourceStatsCounter(SOURCE_LIGHTWEIGHT, host = HOST_LIGHTWEIGHT)
    private val legacyStats = SourceStatsCounter(SOURCE_LEGACY, host = HOST_LEGACY)

    /**
     * 主机级 60s 短退避（按主机隔离：轻量源与 legacy 互不影响）。
     *
     * 只在两种**确定性**故障后进入冷却：514/429 限流、DNS 主机不可达。
     * 冷却期内的请求直接跳过、不发网络请求；原因写进本轮源统计的 degradedReason，
     * 不额外增加失败计数（跳过的请求并没有真的失败）。
     *
     * 与分红链路的 rateLimitedUntil / hostUnreachableUntil 同口径（60s），
     * 但这里是**净值链路**自己的窗口，两者互不共享状态。
     */
    internal val lightweightHostCooldown = HostCooldown(HOST_BACKOFF_MS)
    internal val legacyHostCooldown = HostCooldown(HOST_BACKOFF_MS)

    /** 兼容签名：清掉 roundId=0 的统计（非同步路径 / 既有单测） */
    internal fun resetSourceStats() = resetSourceStats(0)

    /** 每轮同步开始时清零**本轮**统计，与股票链路同节奏（不碰并发中的其它轮次） */
    internal fun resetSourceStats(roundId: Int) {
        lightweightStats.reset(roundId)
        legacyStats.reset(roundId)
    }

    /** 轮末释放该轮的统计桶，避免轮次字典无限增长（快照之后调用） */
    internal fun discardSourceStats(roundId: Int) {
        lightweightStats.discard(roundId)
        legacyStats.discard(roundId)
    }

    /** 兼容签名：roundId=0 的统计快照 */
    internal fun sourceStatsSnapshot(): List<SourceStats> = sourceStatsSnapshot(0)

    /**
     * 指定轮次的基金两源统计快照，顺序固定为「轻量 → legacy」，与回退链一致。
     *
     * 只读该轮（`roundId` 各一份计数）：手动轮与后台 Worker 并发时，不会把对方的
     * 成功/失败数混进来，也不会因为对方 `reset` 而清空本轮。
     *
     * 轻量源已熔断且本轮确实走了 legacy 时，补一行 `degradedReason`；
     * 这时它没有真实请求，所以成功/失败计数保持 0（不用 0 假装「跑过」）。
     */
    internal fun sourceStatsSnapshot(roundId: Int): List<SourceStats> {
        val lightweight = lightweightStats.snapshot(roundId)
        val legacy = legacyStats.snapshot(roundId)
        return buildList {
            if (lightweightStats.hasActivity(roundId)) {
                add(lightweight)
            } else if (lightweightSourceDisabled && legacyStats.hasActivity(roundId)) {
                add(
                    lightweight.copy(
                        degradedReason = "连续失败已熔断，本轮直接走 legacy"
                    )
                )
            }
            if (legacyStats.hasActivity(roundId)) add(legacy)
        }
    }

    /**
     * 轻量源是否已被熔断。
     *
     * 两个用途：① 单测断言熔断状态；② **生产**——[com.example.stockdiary.data.sync.AssetSyncManager]
     * 靠它在下载前判断本次会走哪个源，据此选宽/窄并发闸（见那边的
     * `FUND_MAX_CONCURRENT_LIGHTWEIGHT` / `_LEGACY` 注释）。改语义或可见性前先看那边的依赖。
     */
    internal fun isLightweightSourceDisabled(): Boolean = lightweightSourceDisabled

    /** 轻量源当前连续失败次数（单测用）。 */
    internal fun lightweightFailureCount(): Int = lightweightFailureWindow.count()

    /**
     * 把数据源与熔断状态恢复成出厂值。
     *
     * 只给单测用：熔断状态是进程级全局的，一个用例改了不还原会污染同批
     * 其它用例（JVM 单测共用同一个 [FundAPI] 单例）。
     */
    internal fun resetNavSourcesForTest() {
        lightweightNavSource = { code -> fetchFundInfoLightweight(code) }
        legacyNavSource = { code -> fetchFundInfoLegacy(code) }
        lightweightFailureWindow.reset()
        lightweightSourceDisabled = false
        // 主机退避窗口也是进程级单例，测试之间必须清零
        lightweightHostCooldown.reset()
        legacyHostCooldown.reset()
        // 统计计数器同样是进程级单例，测试之间必须清零
        resetSourceStats()
        // 内存缓存也是进程级单例：测试之间必须清掉，否则上一个用例写入的
        // 基金净值会让下一个用例命中缓存、绕过它刚注入的假源。
        cache.clear()
    }

    /**
     * 读一条持久化净值。
     *
     * 端口未注入、或读库抛异常，一律返回 null —— 持久化只是**加速**手段，
     * 读不到就回到内存缓存 + 网络，不能让它成为新的失败点。
     */
    internal suspend fun readPersistentNav(code: String): FundNavSnapshot? {
        val store = navCacheStore ?: return null
        return try {
            store.get(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("读取持久化净值失败，降级为内存缓存: $code ${e.message}", TAG)
            null
        }
    }

    data class FundInfo(
        val name: String,
        val code: String,
        val price: Double,
        val netWorth: Double?,
        val changePercent: Double?,
        val date: String?,
        val millionCopiesIncome: Double? = null,
        val isFromCache: Boolean = false,
        /**
         * true = 本次刷新失败/被节流，返回的是**旧净值**而不是刚抓到的值。
         * 同步编排器据此走「缓存过期」跳过路径，不写库、不刷新 lastSyncTime。
         * 普通缓存命中（内存/持久化）不算 stale：它们本来就是当日的有效净值。
         */
        val isStale: Boolean = false
    )

    /** 获取基金净值（无缓存，始终请求最新） */
    suspend fun getFundPrice(fundCode: String): Double? {
        return getFundInfo(fundCode)?.price
    }

    /** 获取基金信息（无缓存，始终请求最新） */
    suspend fun getFundInfo(fundCode: String): FundInfo? {
        return fetchFundInfo(fundCode)
    }

    /** 获取基金信息（带缓存，forceRefresh=true 跳过缓存） */
    suspend fun getFundInfoWithCache(
        fundCode: String,
        forceRefresh: Boolean = false
    ): Result<FundInfo> = withContext(Dispatchers.IO) {
        try {
            val cachedEntry = cache.get(fundCode)
            val now = System.currentTimeMillis()

            // forceRefresh 也要分情况，不能一律绕过缓存。
            //
            // 场外基金净值**每个交易日只公布一次**（收盘后），缓存里已经是今天
            // 公布的净值时，重新下载那份 0.5~2MB 的完整历史净值文件也拿不到
            // 更新的数字。而这份文件恰恰是刷新时峰值内存的主要来源：
            // 106 个标的 × 每份最多 2MB，再乘上同步并发数，实测能把进程压到
            // 被系统回收（用户看到的就是「点刷新黑屏然后闪退」，零崩溃报告）。
            //
            // 所以「缓存里已是今日净值」时，即使调用方要求强制刷新也走缓存 ——
            // 这不是偷懒，是这份数据客观上没有更新的可能。第一个同步日全量下载，
            // 同日后续刷新几乎零下载。
            if (cachedEntry != null &&
                (now - cachedEntry.timestamp) < CACHE_DURATION_MS &&
                (!forceRefresh || isTodaysNav(
                    navDate = cachedEntry.value.date,
                    today = java.time.LocalDate.now().toString()
                ))
            ) {
                AppLogger.d(
                    if (forceRefresh) "已是今日净值，复用缓存: $fundCode" else "使用缓存数据: $fundCode",
                    TAG
                )
                return@withContext Result.success(
                    cachedEntry.value.copy(isFromCache = true)
                )
            }

            // ---- 第二层：Room 持久化缓存（跨进程复用） ----
            //
            // 内存缓存是进程内的，进程被系统回收就整张清空 —— 于是「刷新 → 峰值内存
            // 过高 → 进程被杀 → 缓存全丢 → 下次刷新全部重下」的死亡螺旋。
            // 库里那行带 navDate（净值公布日），足以判断「这是不是最新一期净值」，
            // 命中就零下载返回，同时回填内存缓存，5 分钟内连库都不用读。
            val today = LocalDate.now()
            val todayStr = today.toString()
            val dayOfWeek = today.dayOfWeek
            val hourOfDay = LocalTime.now().hour
            val persisted = readPersistentNav(fundCode)?.takeIf { isUsableNavRow(it) }

            if (persisted != null &&
                shouldReusePersistentNav(
                    navDate = persisted.navDate,
                    today = todayStr,
                    dayOfWeek = dayOfWeek,
                    hourOfDay = hourOfDay
                )
            ) {
                val reused = persisted.toFundInfo()
                cache.put(fundCode, reused)
                AppLogger.d(
                    "复用持久化净值，零下载: $fundCode navDate=${persisted.navDate}",
                    TAG
                )
                return@withContext Result.success(reused)
            }

            // ---- T3：工作日 + 库里不是今日净值，只有重下才可能拿到新净值 ----
            //
            // 这里**不做复用**（复用 = 认定「库里就是最新的，不用下」），
            // 只做**下载节流**：同一自然日 16:00 之前，同一只基金 30 分钟最多下
            // 一次那份 0.5~2MB 的历史净值文件。
            //
            // 两者的区别很关键：节流是「少下载几次」，返回的数据一律是库里最近
            // 一次拿到的净值，与刷新失败回退旧值是同一套语义（isFromCache=true）；
            // 复用是「认定它已经是最新的」。混为一谈就会把昨天的净值当成今天的展示。
            if (persisted != null &&
                shouldThrottleNavDownload(
                    today = todayStr,
                    dayOfWeek = dayOfWeek,
                    hourOfDay = hourOfDay,
                    fetchedAt = persisted.fetchedAt,
                    nowMillis = now
                )
            ) {
                val throttled = persisted.toFundInfo().copy(isStale = true)
                cache.put(fundCode, throttled)
                AppLogger.d(
                    "下载节流生效，先给库内最近一次净值: $fundCode navDate=${persisted.navDate}",
                    TAG
                )
                return@withContext Result.success(throttled)
            }

            val fundInfo = fetchFundInfo(fundCode)
            if (fundInfo != null) {
                // 用写入时刻而不是请求开始时刻：天天基金的响应常在 0.5~2MB，
                // 弱网下耗时可观，拿请求开始时刻会把 5 分钟 TTL 白白吃掉一段。
                cache.put(fundCode, fundInfo)
                persistNav(fundCode, fundInfo, persisted)
                AppLogger.d("获取基金数据成功: ${fundInfo.name}", TAG)
                Result.success(fundInfo)
            } else {
                // 刷新失败时宁可给上一轮的旧净值，也不要返回失败：
                // 一次网络抖动就让持仓显示"未找到基金数据"，还会被计成同步失败。
                // 回退顺序：内存旧值 → 库里旧值 → 才认失败。
                val stale = cachedEntry?.value ?: persisted?.toFundInfo()
                if (stale != null) {
                    AppLogger.w(
                        FailureLogFormat.failureLine(
                            FailureLogFormat.NO_ROUND, fundCode, "基金",
                            "两源均失败，回退旧缓存（标记 isStale）"
                        ),
                        TAG
                    )
                    // 关键：标记 isStale，让同步编排器按「缓存过期」跳过，
                    // 不把昨天的净值写回库并刷新 lastSyncTime（否则界面显示“刚刚更新”）。
                    return@withContext Result.success(
                        stale.copy(isFromCache = true, isStale = true)
                    )
                }
                Result.failure(Exception("基金数据获取失败"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(
                FailureLogFormat.failureLine(
                    FailureLogFormat.NO_ROUND, fundCode, "基金", "获取基金数据异常: ${e.message}"
                ),
                TAG
            )
            Result.failure(e)
        }
    }

    /** 批量获取多个基金净值（带缓存和并发控制） */
    suspend fun getMultipleFundPrices(
        fundCodes: List<String>,
        concurrency: Int = 5
    ): Map<String, Result<FundInfo>> = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, Result<FundInfo>>()

        fundCodes.chunked(concurrency).forEach { batch ->
            val deferredResults = batch.map { code ->
                async { code to getFundInfoWithCache(code) }
            }
            deferredResults.awaitAll().forEach { (code, result) ->
                results[code] = result
            }
        }

        results.toMap()
    }

    /**
     * 清除**内存**缓存（非 suspend，不动数据库）。
     *
     * 持久化缓存要单独清：见 [clearPersistentCache]。
     */
    fun clearCache() {
        cache.clear()
        AppLogger.d("缓存已清空", TAG)
    }

    /**
     * 清除**持久化**净值缓存。
     *
     * 不塞进 [clearCache] 是因为后者非 suspend，被
     * [UnifiedPriceService.clearCache] 直接调用；在里面切线程 / 开事务会改变
     * 调用方的线程语义。清空落盘数据是 IO，只能由 suspend 的调用方来做。
     * 端口未注入时静默空操作。
     */
    suspend fun clearPersistentCache() {
        val store = navCacheStore ?: return
        try {
            store.clear()
            AppLogger.d("持久化净值缓存已清空", TAG)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("清空持久化净值缓存失败: ${e.message}", TAG)
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 库里这行净值能不能拿出来用。
     *
     * name 为空会被行情门面当成「没找到基金数据」丢弃（UnifiedPriceService），
     * navDate 为空则无从判断新旧，nav 非正 / NaN 会把持仓市值算没 —— 三者
     * 都判定为不可用，宁可重新下载。
     */
    private fun isUsableNavRow(row: FundNavSnapshot): Boolean {
        return row.name.isNotBlank() &&
            row.navDate.isNotBlank() &&
            row.nav.isFinite() &&
            row.nav > 0.0
    }

    private fun FundNavSnapshot.toFundInfo(): FundInfo = FundInfo(
        name = name,
        code = code,
        price = nav,
        netWorth = nav,
        changePercent = changePercent,
        date = navDate,
        millionCopiesIncome = millionCopiesIncome,
        isFromCache = true
    )

    /**
     * 把刚下载到的净值写进 Room。
     *
     * 三条不写的红线，每条都对应一个真实的事故：
     * - 日期为空（货币基金等解析路径可能没有日期）→ 写了就变成「日期未知」的行，
     *   从此无法判断新旧，**永久失去复用能力**；
     * - nav 非正或 NaN → 市值归零 / 曲线污染（NaN 参与的比较全为 false，
     *   只判 `nav <= 0` 拦不住它，必须 `isFinite`）；
     * - 库里已有更新的日期 → 用更旧的净值盖掉更新的，等于把刚刚拿到的
     *   最新一期净值倒退回上一期（yyyy-MM-dd 字典序即时间序，可直接比字符串）。
     *
     * @param previous 本次流程里已经读过的旧行（可能为空），用来省一次查询
     */
    private suspend fun persistNav(code: String, info: FundInfo, previous: FundNavSnapshot?) {
        val store = navCacheStore ?: return
        val navDate = info.date?.trim().orEmpty()
        if (navDate.isEmpty()) {
            AppLogger.w("净值日期为空，不写持久化缓存（否则无法判断新旧）: $code", TAG)
            return
        }
        if (!info.price.isFinite() || info.price <= 0.0) {
            AppLogger.w("净值无效，不写持久化缓存: $code = ${info.price}", TAG)
            return
        }
        if (previous != null && previous.navDate > navDate) {
            AppLogger.d(
                "库内存量净值更新（${previous.navDate} > ${navDate}），跳过写入: $code",
                TAG
            )
            return
        }
        val row = FundNavSnapshot(
            code = code,
            name = info.name,
            nav = info.price,
            changePercent = info.changePercent,
            navDate = navDate,
            // 货币基金的收益只在这里：它的单位净值恒为 1.0，不落库就永远算不出收益
            millionCopiesIncome = info.millionCopiesIncome,
            fetchedAt = System.currentTimeMillis()
        )
        try {
            store.upsert(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("写入持久化净值失败（不影响本次结果）: $code ${e.message}", TAG)
        }
    }

    /**
     * 取一只基金的最新净值：先轻量源，失败才回退 legacy。
     *
     * 轻量源只回最新一期（~2.5KB），legacy 会拉回整份历史净值（0.5~2MB），
     * 所以任何一步不对都要**明确地**落回 legacy，绝不静默吞掉失败
     * （每条回退都打 [AppLogger.w]，带上基金码与原因，便于线上定位）。
     */
    internal suspend fun fetchFundInfo(fundCode: String): FundInfo? {
        // 一轮只取一次轮次上下文：下面每条统计/日志都用同一个 roundId，
        // 避免逐条记录各查一次上下文（也避免同一函数内出现两个不同的轮次号）
        val roundId = RoundSourceFailureScope.currentRoundId()
        val now = System.currentTimeMillis()
        when {
            lightweightSourceDisabled -> {
                // 没有真实请求，只记录「本轮为什么没走轻量源」；失败计数保持 0
                lightweightStats.markDegraded(roundId, "连续失败已熔断，本轮直接走 legacy")
            }
            lightweightHostCooldown.isCooling(now) -> {
                // 60s 主机退避：冷却期内直接跳过，不发请求，也不算一次失败
                lightweightStats.markDegraded(roundId, lightweightHostCooldown.degradedReason())
                lightweightStats.recordSkip(
                    roundId,
                    "主机退避中（${lightweightHostCooldown.degradedReason()}），跳过本次请求"
                )
                AppLogger.i(
                    FailureLogFormat.skipLine(
                        roundId, fundCode, SOURCE_LIGHTWEIGHT,
                        "主机退避中（${lightweightHostCooldown.degradedReason()}），跳过本次请求"
                    ),
                    TAG
                )
            }
            else -> {
                // 只测耗时，不改回退/熔断：源函数内部已按 HTTP 状态/解析结果分类计数
                val lightweightStartedAt = System.currentTimeMillis()
                // 本次调用的失败分类与「是否计入熔断」由源函数写入：
                // 符号级缺口（200 但解析不出净值）不该算作源级故障；
                // 同一批并发请求撞 514 限流时，只有真正开启冷却的那一路计数。
                val trace = NavFetchTrace()
                val lightweight = try {
                    withContext(trace) { lightweightNavSource(fundCode) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isHostUnreachable(e)) {
                        // DNS 不可达也是「一轮故障」：并发的其余请求不重复计数
                        trace.countsTowardBreaker =
                            lightweightHostCooldown.start("主机不可达（DNS 解析失败）")
                    }
                    lightweightStats.recordSourceFailure(
                        roundId,
                        FailureLogFormat.accountingReason(e)
                    )
                    AppLogger.w(
                        FailureLogFormat.failureLine(
                            roundId, fundCode, SOURCE_LIGHTWEIGHT,
                            "网络异常（回退旧数据源）: ${e.message}",
                            System.currentTimeMillis() - lightweightStartedAt
                        ),
                        TAG
                    )
                    null
                } finally {
                    lightweightStats.addDuration(roundId, System.currentTimeMillis() - lightweightStartedAt)
                }
                if (lightweight != null) {
                    lightweightStats.recordSuccess(roundId = roundId)
                    lightweightFailureWindow.recordSuccess()
                    return lightweight
                }
                // 源级故障才喂熔断；符号级缺口、冷却期内的重复失败都不计。
                if (trace.failureKind != NavFailureKind.SYMBOL && trace.countsTowardBreaker) {
                    noteLightweightFailure(fundCode)
                }
            }
        }

        val legacyNow = System.currentTimeMillis()
        if (legacyHostCooldown.isCooling(legacyNow)) {
            // legacy 主机与轻量源主机互不影响；这里跳过后由上层按「取价失败」处理
            legacyStats.markDegraded(roundId, legacyHostCooldown.degradedReason())
            legacyStats.recordSkip(
                roundId,
                "主机退避中（${legacyHostCooldown.degradedReason()}），跳过本次请求"
            )
            AppLogger.i(
                FailureLogFormat.skipLine(
                    roundId, fundCode, SOURCE_LEGACY,
                    "主机退避中（${legacyHostCooldown.degradedReason()}），跳过本次请求"
                ),
                TAG
            )
            return null
        }

        val legacyStartedAt = System.currentTimeMillis()
        val legacy = try {
            legacyNavSource(fundCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isHostUnreachable(e)) {
                legacyHostCooldown.start("主机不可达（DNS 解析失败）")
            }
            legacyStats.recordSourceFailure(
                roundId,
                FailureLogFormat.accountingReason(e)
            )
            AppLogger.w(
                FailureLogFormat.failureLine(
                    roundId, fundCode, SOURCE_LEGACY,
                    "网络异常: ${e.message}",
                    System.currentTimeMillis() - legacyStartedAt
                ),
                TAG
            )
            null
        } finally {
            legacyStats.addDuration(roundId, System.currentTimeMillis() - legacyStartedAt)
        }
        if (legacy != null) legacyStats.recordSuccess(roundId = roundId)
        return legacy
    }

    /**
     * 514/429 命中后让该主机进入 60s 冷却。
     *
     * @return true 表示**本次**真正开启了一个新的冷却窗口（这一路失败应计入熔断）；
     *   false 表示已经在同一冷却窗口内（并发撞限流的其余请求、或冷却期内的重复失败），
     *   这些失败不再重复计入，避免一路限流被打成「源级 3 连败」。
     * 其他状态码（5xx 等）不进冷却：那些是服务端瞬时故障，立即重试或回退更划算。
     */
    internal fun noteRateLimit(cooldown: HostCooldown, code: Int): Boolean {
        if (code == 514 || code == 429) {
            return cooldown.start(httpFailureReason(code))
        }
        return false
    }

    /** 514/429 是限流口径，原因里标出来，便于和普通 5xx 区分（internal 供单测钉死口径） */
    internal fun httpFailureReason(code: Int): String = when (code) {
        514, 429 -> "HTTP $code 限流"
        else -> "HTTP $code"
    }

    /**
     * 累计一次轻量源失败，达到阈值就整源熔断。
     *
     * 熔断的必要性：一旦轻量源整体不可用（接口下线 / 拦截），不熔断的话
     * 每只基金都要「先白跑一次轻量请求再回退 legacy」，刷新请求数直接翻倍，
     * 弱网下把本来能用的 legacy 链路一起拖垮。
     *
     * 计数**跨基金码**累计：轻量源故障必然是全局的，不会因为换一只基金就好。
     * 熔断只在进程内生效（进程重启即失效），不做持久化 —— 存到磁盘会让一次
     * 短暂故障在用户反复重启后仍然生效，反而更难自愈。
     */
    private fun noteLightweightFailure(fundCode: String) {
        val failures = lightweightFailureWindow.recordFailure()
        if (failures >= LIGHTWEIGHT_FAILURE_THRESHOLD && !lightweightSourceDisabled) {
            lightweightSourceDisabled = true
            AppLogger.w(
                "轻量净值源连续失败 $failures 次（最后一只 ${fundCode}），本进程后续基金直接走旧数据源",
                TAG
            )
        }
    }

    /**
     * 轻量源：只取最新一期净值（~2.5KB），不下载完整历史。
     *
     * 必须带 `User-Agent: Mozilla/5.0`，否则东财返回 ErrCode 61136「网络繁忙」。
     * 解析失败 / 非 200 / 解析出 null 一律返回 null，由 [fetchFundInfo] 回退。
     */
    private suspend fun fetchFundInfoLightweight(fundCode: String): FundInfo? {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val roundId = RoundSourceFailureScope.currentRoundId()
            try {
                val url = "$LIGHTWEIGHT_NAV_URL?FCODE=$fundCode" +
                    "&deviceid=Wap&plat=Wap&product=EFund&version=2.0.0"
                AppLogger.apiUrl("GET", url, TAG)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .build()

                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        lightweightStats.recordSourceFailure(roundId, httpFailureReason(it.code))
                        val trace = currentCoroutineContext()[NavFetchTrace]
                        if (it.code == 514 || it.code == 429) {
                            trace?.failureKind = NavFailureKind.RATE_LIMITED
                            // 并发撞限流时只有「真正开启冷却」的那一路计入熔断，
                            // 其余请求在冷却期内失败不重复计数（否则 4 路并发 = 直接熔断）
                            trace?.countsTowardBreaker = noteRateLimit(lightweightHostCooldown, it.code)
                        } else {
                            trace?.failureKind = NavFailureKind.SOURCE
                        }
                        AppLogger.w(
                            FailureLogFormat.failureLine(
                                roundId, fundCode, SOURCE_LIGHTWEIGHT,
                                httpFailureReason(it.code) + "，回退旧数据源",
                                System.currentTimeMillis() - startedAt
                            ),
                            TAG
                        )
                        return@withContext null
                    }

                    val body = it.body?.string().orEmpty()
                    val info = FundNavParser.parseBasicInfo(body, fundCode)
                    if (info == null) {
                        // HTTP 成功但这条代码没有可解析数据：算符号级缺口，不是源故障
                        lightweightStats.recordSymbolFailure(roundId = roundId)
                        lightweightStats.recordSkip(roundId, "返回无法解析的内容")
                        currentCoroutineContext()[NavFetchTrace]?.failureKind = NavFailureKind.SYMBOL
                        AppLogger.i(
                            FailureLogFormat.skipLine(
                                roundId, fundCode, SOURCE_LIGHTWEIGHT,
                                "返回无法解析的内容，回退旧数据源",
                                System.currentTimeMillis() - startedAt
                            ),
                            TAG
                        )
                    }
                    return@withContext info
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val trace = currentCoroutineContext()[NavFetchTrace]
                trace?.failureKind = NavFailureKind.SOURCE
                if (isHostUnreachable(e)) {
                    trace?.countsTowardBreaker =
                        lightweightHostCooldown.start("主机不可达（DNS 解析失败）")
                }
                lightweightStats.recordSourceFailure(
                    roundId,
                    "网络异常：${e.message?.take(40) ?: e.javaClass.simpleName}"
                )
                AppLogger.w(
                    FailureLogFormat.failureLine(
                        roundId, fundCode, SOURCE_LIGHTWEIGHT,
                        "网络异常（回退旧数据源）: ${e.message?.take(40) ?: e.javaClass.simpleName}",
                        System.currentTimeMillis() - startedAt
                    ),
                    TAG
                )
                null
            }
        }
    }

    /**
     * legacy 源：下载完整历史净值文件（0.5~2MB）后取最后一条。
     *
     * 只在轻量源拿不到数据时使用（含轻量源整源熔断后）。
     */
    private suspend fun fetchFundInfoLegacy(fundCode: String): FundInfo? {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val roundId = RoundSourceFailureScope.currentRoundId()
            try {
                val url = "$LEGACY_BASE_URL/${fundCode}.js?v=${System.currentTimeMillis()}"
                AppLogger.apiUrl("GET", url, TAG)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Referer", LEGACY_REFERER)
                    .build()

                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        legacyStats.recordSourceFailure(roundId, httpFailureReason(it.code))
                        // legacy 主机独立退避：不影响轻量源主机
                        noteRateLimit(legacyHostCooldown, it.code)
                        // legacy 与轻量源同级：单只基金取不到是「本轮降级、下次可自愈」，
                        // 不是需要人介入的 ERROR（旧实现这里用 E，与轻量源的 W 口径不一致）
                        AppLogger.w(
                            FailureLogFormat.failureLine(
                                roundId, fundCode, SOURCE_LEGACY,
                                httpFailureReason(it.code),
                                System.currentTimeMillis() - startedAt
                            ),
                            TAG
                        )
                        return@withContext null
                    }

                    val body = it.body?.string()
                    if (body == null || body.length < 100) {
                        // 200 但响应过短：通常是反爬/网关拦截，归为源级响应异常
                        legacyStats.recordSourceFailure(roundId, "响应过短")
                        AppLogger.w(
                            FailureLogFormat.failureLine(
                                roundId, fundCode, SOURCE_LEGACY, "响应过短（疑似反爬/网关拦截）",
                                System.currentTimeMillis() - startedAt
                            ),
                            TAG
                        )
                        return@withContext null
                    }

                    val name = FundDataParser.extractJsVar(body, "fS_name") ?: ""
                    val code = FundDataParser.extractJsVar(body, "fS_code") ?: fundCode

                    if (name.isEmpty()) {
                        // 文件正常但没这个代码：符号级缺口，属「跳过」而非降级
                        legacyStats.recordSymbolFailure(roundId = roundId)
                        legacyStats.recordSkip(roundId, "响应里没有该基金代码")
                        AppLogger.i(
                            FailureLogFormat.skipLine(
                                roundId, fundCode, SOURCE_LEGACY, "响应里没有该基金代码，换下一源",
                                System.currentTimeMillis() - startedAt
                            ),
                            TAG
                        )
                        return@withContext null
                    }

                    val nawData = FundDataParser.parseNawData(body)

                    // 净值解析失败时 parseNawData 会返回默认值 0.0。0 净值会让场外基金市值归零，
                    // 且因为没有明显报错用户很难察觉，所以这里宁可不返回这条数据（触发下一数据源）。
                    if (nawData.naw <= 0.0) {
                        // 解析出 0 是数据质量问题，不是「没有这个代码」
                        legacyStats.recordSourceFailure(roundId, "净值无效")
                        AppLogger.w(
                            FailureLogFormat.failureLine(
                                roundId, fundCode, SOURCE_LEGACY,
                                "净值无效（naw=${nawData.naw}），丢弃该条",
                                System.currentTimeMillis() - startedAt
                            ),
                            TAG
                        )
                        return@withContext null
                    }

                    return@withContext FundInfo(
                        name = name,
                        code = code,
                        price = nawData.naw,
                        netWorth = nawData.naw,
                        changePercent = nawData.equityReturn,
                        date = nawData.date,
                        millionCopiesIncome = nawData.millionCopiesIncome
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isHostUnreachable(e)) {
                    legacyHostCooldown.start("主机不可达（DNS 解析失败）")
                }
                legacyStats.recordSourceFailure(
                    roundId,
                    "网络异常：${e.message?.take(40) ?: e.javaClass.simpleName}"
                )
                AppLogger.w(
                    FailureLogFormat.failureLine(
                        roundId, fundCode, SOURCE_LEGACY, "网络异常: ${e.message}",
                        System.currentTimeMillis() - startedAt
                    ),
                    TAG
                )
                null
            }
        }
    }
}

/**
 * 这条缓存里的净值是不是**今天公布**的。
 *
 * 抽成纯函数是为了能单测：判断错了两个方向都有代价 ——
 * 该认而不认，每次刷新都重下 0.5~2MB 的历史文件，内存压力直接把进程压死；
 * 不该认而认，用户在同一天内拿到旧净值（但场外基金当日只公布一次，
 * 所以「已是今日净值」时客观上没有更新的可能，这个方向的代价为零）。
 *
 * 净值日期来自 [FundAPI.FundInfo.date]，格式 `yyyy-MM-dd`；
 * 为空或格式不对时一律返回 false（宁可重下，也不要把脏数据当今日净值）。
 *
 * @param navDate 缓存净值对应的公布日期
 * @param today 今天，做成入参而不是内部读时钟，才能单测
 */
internal fun isTodaysNav(navDate: String?, today: String): Boolean {
    if (navDate.isNullOrBlank()) return false
    return navDate.trim() == today
}

/** 周末复用窗口：净值在周末不可能变新，往回多少天内都认。 */
private const val WEEKEND_REUSE_MAX_AGE_DAYS = 4L

/** 同一自然日内的下载节流窗口（毫秒）。 */
private const val NAV_THROTTLE_WINDOW_MS = 30 * 60 * 1000L

/** 节流只在 16:00 之前生效：之后就是净值公布时段，该下就得下。 */
private const val NAV_THROTTLE_END_HOUR = 16

/**
 * 当日净值公布时点（约 20:00，场外基金 T+1、收盘后才出数）。
 *
 * 在此时点**之前**，「上一个交易日」的那份净值就是当前客观能拿到的**最新**值——
 * 今天的新净值还没产生，下载也只会拿回一模一样的东西。见 [shouldReusePersistentNav] 的 T2。
 */
private const val NAV_PUBLISH_HOUR = 20

/**
 * 库里这条**持久化**净值能不能直接复用（一次网络都不打）。
 *
 * 纯函数：`today` 与 `dayOfWeek` 都是入参，内部不读时钟、不碰 Context，
 * 所以三个分支都能被单测钉死。
 *
 * 三条规则（顺序即优先级）：
 *
 * - **T1 同日**：`navDate == today` → 复用。场外基金每个交易日只公布一次净值，
 *   当天已经拿到过就再下那份 0.5~2MB 的历史文件也拿不到更新的数。
 *   这是「进程被杀后重启」的主力分支。
 * - **T2 周末**：今天是周六 / 周日 → 净值不可能变新，4 天内的一律复用。
 *   依据同 [com.example.stockdiary.data.price.PriceCacheManager] 的
 *   `WEEKEND_TTL = Long.MAX_VALUE`：周末没有新行情，缓存等于永久有效。
 * - **T3 工作日且不是今天的净值** → **必须下载**（只有重下才可能拿到新净值）。
 *   这里绝不复用；要控制下载次数是 [shouldThrottleNavDownload] 的事。
 *
 * 空值 / 空白 / 非 `yyyy-MM-dd` / 未来日期一律返回 false：宁可重下，
 * 也不把脏数据当成「最新净值」给用户看一整天。
 *
 * @param navDate 库里那行净值的公布日，格式 `yyyy-MM-dd`
 * @param today 今天，格式 `yyyy-MM-dd`
 * @param dayOfWeek 今天星期几
 */
internal fun shouldReusePersistentNav(
    navDate: String?,
    today: String,
    dayOfWeek: DayOfWeek,
    hourOfDay: Int
): Boolean {
    val navDay = parseNavDateOrNull(navDate) ?: return false
    val todayDay = parseNavDateOrNull(today) ?: return false

    // T1：同日即复用，周末也一样（周末分支本就会命中，放前面语义更直白）
    if (navDay == todayDay) return true
    // 未来日期：解析出来的脏数据，不认
    if (navDay.isAfter(todayDay)) return false

    // T2：工作日但还没到净值公布时点 —— 今天的新净值客观上还没产生。
    //
    // 场外基金净值是 T+1：当日净值要等收盘后（约 [NAV_PUBLISH_HOUR]）才公布。所以在
    // 公布时点之前，「上一个交易日」那份就是当前能拿到的**最新**值，此时下载只会拿回
    // 一模一样的东西。
    //
    // 实测（诊断 20261026_230142）：周一 10:32 那轮把 93 只基金全部重下了一遍，拿回的
    // 仍是周五的 navDate；而同样「无新净值可拿」的周末却因为下面的 T3 分支被复用了 ——
    // **工作日上午反而成了唯一的重灾区**，这正是这条规则要补的洞。
    if (!isWeekend(dayOfWeek) &&
        hourOfDay < NAV_PUBLISH_HOUR &&
        navDay == latestTradingDayBefore(todayDay)
    ) {
        return true
    }

    // T3：周末净值不变新，4 天内都认
    if (isWeekend(dayOfWeek)) {
        return ChronoUnit.DAYS.between(navDay, todayDay) <= WEEKEND_REUSE_MAX_AGE_DAYS
    }

    // T4：工作日、已过公布时点、且不是今天的净值 → 可能有新净值，该下就下
    return false
}

/**
 * 主机级 60s 短退避窗口（线程安全、按实例隔离）。
 *
 * 生产上两个实例各管一个主机：[FundAPI.lightweightHostCooldown] 对应轻量源主机、
 * [FundAPI.legacyHostCooldown] 对应 legacy 主机——一个被限流/不可达不会影响另一个。
 *
 * 只做「冷却期内跳过」这一件事：不缓存、不改变回退顺序、不改变净值校验。
 * 窗口自然过期后自动恢复，不需要成功探测；`reason` 只用于统计展示，
 * 不参与是否冷却的判断。
 */
internal class HostCooldown(private val windowMs: Long) {
    private val until = AtomicLong(0L)
    private val reason = AtomicReference<String?>(null)

    /** 当前是否仍在冷却窗口内（`now` 显式传入，便于单测钉住到期边界）。 */
    fun isCooling(now: Long = System.currentTimeMillis()): Boolean = now < until.get()

    /**
     * 进入冷却：只有从「未冷却」进入「冷却」才返回 true。
     *
     * 同一窗口内的重复故障（例如 4 路并发同时撞 514）返回 false：既不再计一次失败，
     * 也不延长窗口 —— 否则一次限流会被记成多次故障并拖长退避。
     */
    fun start(reason: String, now: Long = System.currentTimeMillis()): Boolean {
        if (isCooling(now)) return false
        this.reason.set(reason)
        until.set(now + windowMs)
        return true
    }

    /** 冷却期跳过请求时写进源统计的降级原因；没有记录原因时用通用口径兜底。 */
    fun degradedReason(): String {
        val why = reason.get()?.takeIf { it.isNotBlank() } ?: "主机退避中"
        return "${why}（${windowMs / 1000}s 退避中），本轮跳过"
    }

    fun reset() {
        until.set(0L)
        reason.set(null)
    }
}

/**
 * 轻量源熔断的失败分类。
 *
 * - [SOURCE]：源级故障（HTTP 非 2xx、网络异常）→ 喂熔断；
 * - [SYMBOL]：符号级缺口（200 但解析不出净值/没有该代码）→ **不**喂熔断，
 *   与股票侧「停牌不喂熔断」同一约定：个别标的数据缺失不该连坐整个源；
 * - [RATE_LIMITED]：514/429 限流 → 只有真正开启冷却窗口的那一路才喂熔断。
 */
internal enum class NavFailureKind { SOURCE, SYMBOL, RATE_LIMITED }

/**
 * 单次取净值调用的失败分类信标。
 *
 * 挂在协程上下文上而不是进程级字段：净值是并发拉取的，进程级字段会被其他协程覆盖。
 * 注入的假源（单测）不写它 → 默认 SOURCE / 计数，保持原有「null 即源故障」的测试口径。
 */
internal class NavFetchTrace : AbstractCoroutineContextElement(NavFetchTrace) {
    companion object Key : CoroutineContext.Key<NavFetchTrace>

    @Volatile
    var failureKind: NavFailureKind = NavFailureKind.SOURCE

    @Volatile
    var countsTowardBreaker: Boolean = true
}

/**
 * 带时间窗的「连续失败」计数。
 *
 * 只在两次失败间隔不超过 [windowMs] 时累加；超过则从 1 重新开始。
 * 目的：一次瞬时抖动不该和几十分钟后的失败拼成「连续 3 次」把整源熔断。
 */
internal class ConsecutiveFailureWindow(
    private val threshold: Int,
    private val windowMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private var count = 0
    private var lastFailureAt = 0L

    @Synchronized
    fun recordFailure(): Int {
        val at = now()
        if (count > 0 && at - lastFailureAt > windowMs) count = 0
        count++
        lastFailureAt = at
        return count
    }

    @Synchronized
    fun recordSuccess() {
        count = 0
        lastFailureAt = 0L
    }

    @Synchronized
    fun count(): Int = count

    @Synchronized
    fun thresholdReached(): Boolean = count >= threshold

    @Synchronized
    fun reset() {
        count = 0
        lastFailureAt = 0L
    }
}

/**
 * 今天之前最近的**交易日**（不含节假日）：周一 → 上周五，周二~周五 → 昨天。
 *
 * 只按周末回退，**不认法定节假日**：长假期间实际最新净值可能停更一周以上，此时本函数
 * 算出的日期会偏新，[shouldReusePersistentNav] 的 T2 因此不命中、退化成「每轮下载一次」
 * —— 这与修复前的行为一致，不引入新问题；真要覆盖节假日需要引入交易日历，收益不划算。
 */
private fun latestTradingDayBefore(day: LocalDate): LocalDate {
    var d = day.minusDays(1)
    while (isWeekend(d.dayOfWeek)) d = d.minusDays(1)
    return d
}

/**
 * 这次刷新要不要**跳过下载**（节流，不是复用）。
 *
 * 只在工作日的 16:00 之前生效，且要求「同一自然日内、上次下载距今不到 30 分钟」。
 * 节流命中时返回的是库里最近一次拿到的净值（isFromCache=true），语义等同
 * 「刷新失败回退旧值」—— 少下载几次，但不会把旧值伪装成刚拿到的新值。
 *
 * @param today 今天，格式 `yyyy-MM-dd`
 * @param dayOfWeek 今天星期几
 * @param hourOfDay 当前小时（0~23）
 * @param fetchedAt 库里那行净值的写入时间戳（毫秒）；<= 0 表示没下载过
 * @param nowMillis 当前时间戳（毫秒）
 */
internal fun shouldThrottleNavDownload(
    today: String,
    dayOfWeek: DayOfWeek,
    hourOfDay: Int,
    fetchedAt: Long,
    nowMillis: Long
): Boolean {
    if (isWeekend(dayOfWeek)) return false
    if (hourOfDay >= NAV_THROTTLE_END_HOUR) return false
    if (fetchedAt <= 0L) return false

    val elapsed = nowMillis - fetchedAt
    if (elapsed < 0L) return false // 时钟被回拨过，别拿它当节流依据
    if (elapsed >= NAV_THROTTLE_WINDOW_MS) return false

    // 只在同一个自然日内节流：跨了天就是新的一天，该下载还得下载
    val fetchedDay = dayOfEpochMillis(fetchedAt) ?: return false
    return fetchedDay == today
}

/**
 * 时间戳 → `yyyy-MM-dd`（系统时区）。任何异常都返回 null，交给上层当「不可判定」。
 *
 * 用 `Instant.atZone` 而不是 `LocalDate.ofInstant`：后者是 Java 9 才加的静态方法，
 * 在低版本 desugar 的 java.time 上不一定有。
 */
private fun dayOfEpochMillis(epochMillis: Long): String? {
    return try {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    } catch (e: Exception) {
        null
    }
}

/** `yyyy-MM-dd` → [LocalDate]；空值 / 空白 / 格式不符一律 null。 */
private fun parseNavDateOrNull(value: String?): LocalDate? {
    val raw = value?.trim() ?: return null
    if (raw.isEmpty()) return null
    return try {
        LocalDate.parse(raw)
    } catch (e: Exception) {
        null
    }
}

private fun isWeekend(dayOfWeek: DayOfWeek): Boolean {
    return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
}