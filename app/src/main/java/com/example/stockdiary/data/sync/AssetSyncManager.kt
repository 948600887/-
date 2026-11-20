package com.example.stockdiary.data.sync

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.contract.PendingPriceWrite
import com.example.stockdiary.data.contract.SyncErrorType
import com.example.stockdiary.data.contract.SyncStrategy
import com.example.stockdiary.data.contract.withHeartbeat
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.price.ApiManager
import com.example.stockdiary.data.price.LineageOrigin
import com.example.stockdiary.data.price.PriceLineage
import com.example.stockdiary.data.price.PriceLineageRecorder
import com.example.stockdiary.data.price.RoundSourceFailureScope
import com.example.stockdiary.data.price.SourceIds
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.common.SyncLimits
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.EastmoneyDelistedQuoteApi
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asset 同步管理器
 * 负责资产价格同步，使用并发控制避免过载
 */
object AssetSyncManager {

    private const val TAG = "AssetSyncManager"

    /**
     * 同步并发上限。
     *
     * 从 8 降到 3 是这轮「点刷新闪退」修复的一部分：场外基金每只需要下载
     * 天天基金 `pingzhongdata/{code}.js`，实测响应 **0.5~2MB**
     * （文件里是这只基金的完整历史净值，而我们要的只是今天一个数）。
     * 8 并发 = 最多 16MB 响应体同时驻留，再叠加解析对象图与界面状态，
     * 106 个持仓足以把进程压到被系统回收 —— 用户看到的是黑屏后闪退，
     * 而且零崩溃报告（不是 Java 异常，是 LMK）。
     *
     * 降到 3 后峰值降到约 6MB。速度代价可接受：106 个标的约 36 批，
     * 每批 100~500ms；而且 [FundAPI] 已会在「缓存里已是今日净值」时
     * 跳过下载，同一天的第二刷及以后基本不下载，快得多。
     *
     * ## 2026-10-24 从 3 提到 6（只放总闸，不动基金专属闸）
     *
     * 真机诊断（goldvault_diagnostics_20261023_200928.txt）：240 个标的的全量同步
     * 实测 38.6s / 61.0s，其中逐标的价格同步阶段就占 24~28s。240 个标的按 3 并发
     * 要跑 80 批，是这段耗时的大头。
     *
     * 为什么提到 6 不会重蹈「闪退」：当初压到 3 是为了压**基金大文件**的峰值内存，
     * 而现在真正管住大文件的是基金专属闸的**回退档** [FUND_MAX_CONCURRENT_LEGACY]
     * （=2，与总闸叠加）。也就是说同时下载的 0.5~2MB 文件**最多仍是 2 个**，
     * 峰值内存不变；多出来的名额只会被股票 / 加密行情这类**几十字节**的轻量请求占用。
     * 网络侧由 [com.example.stockdiary.network.NetworkClient] 的 per-host 上限兜底。
     */
    // 值收口到 common/SyncLimits.kt：改并发度只需改一处，且 A1 静态校验会
    // 同时检查「熔断阈值仍严格大于并发度」。保留同名 const 引用，下游引用点一行都不用动。
    private const val MAX_CONCURRENT_SYNC = SyncLimits.MAX_CONCURRENT_SYNC

    /**
     * 手动刷新之间的进程内互斥。
     *
     * 资产页与持仓页各有一个 ViewModel，各自持有一份 [syncAllAssets] 调用
     * （分红收入计算有 [DividendIncomeUseCase] 的单飞，价格同步从前没有）。
     * 用户在两个页面之间切换、各点一次刷新，就是两份并发扇出 + 双倍请求：
     * 主线程与 OkHttp 队列被双双压爆，正是这次卡死的放大器之一。
     *
     * 只锁**手动**刷新：后台 [PriceSyncWorker] 不传 startTime 之外还带着
     * 自己的 9 分钟预算，让用户手动点下的刷新排在它后面（最多 9 分钟）
     * 比叠加更糟。Worker 与手动刷新不幸撞车时，最坏约 16 个并发请求，
     * 由 [com.example.stockdiary.network.NetworkClient] 的 per-host 上限
     * 与这里的 Semaphore 共同兜底。
     */
    private val manualSyncMutex = Mutex()

    /**
     * 同步轮次序号（进程内自增，带进程前缀）。
     *
     * 一轮同步会在日志里留下上百行（逐标的、每源、每个 OkHttp 请求）。没有轮次 ID 时，
     * 几轮混在同一份日志里根本分不清哪行属于哪一轮，用户报「刚才刷新不对」也无从对起。
     *
     * 前缀 = 进程 pid × 4096：日志文件跨进程存活（8MB 轮转、多份归档），若每轮都从 1 起，
     * 进程重启后的「轮次#1」和上一份归档里的「轮次#1」会撞在一起，按轮次 grep 就串了。
     * 单测环境 pid 读不到时前缀为 0（退回纯自增，不影响断言）。
     */
    private val syncRoundSeq = AtomicInteger(
        runCatching { android.os.Process.myPid() shl 12 }.getOrDefault(0)
    )

    /**
     * 最近一轮同步的汇总文本，供诊断报告头部直接引用。
     *
     * 排障时用户分享一份诊断报告过来，第一秒就该看到「最近一轮同步成了多少、
     * 哪个源在掉链子」，而不是先去几百行日志里 grep。进程内单份，重启即失——
     * 诊断报告是当下快照，不需要持久化。
     */
    @Volatile
    private var lastRoundSummary: String? = null

    /** 诊断报告用：最近一轮同步汇总；从没同步过则 null */
    fun lastRoundSummaryForDiagnostics(): String? = lastRoundSummary

    /**
     * 当前进行中的**手动**同步 job（startTime<=0 的那条），供退后台取消。
     *
     * 手动同步一路在 [manualSyncMutex] 锁内登记自己、finally 里清空；后台 Worker 传
     * startTime>0，**不**登记，因此退后台取消波及不到它（Worker 有自己的时间预算，
     * 且本该跑完）。
     *
     * 为什么需要它：手动同步若卡在一个挂死的请求上（app 退后台被系统冻结，醒来才恢复），
     * 会一直占着信号量与锁。用户在后台里并不会盯着转圈，但切回来时面对的是「恢复后立刻
     * 超时截断、只零星更新几个标的」的僵尸轮。退后台时主动取消在途手动同步，换来的是
     * 不再有这种僵尸轮——用户下次下拉刷新即可重新同步。
     */
    /**
     * 在途的**手动**同步 job 集合（不是单字段：连点「刷新」会同时有「正在跑 + 排队」多个
     * 在途 job，退后台要把它们**全部**取消，单字段会漏掉排队那一轮，见 [cancelActiveManualSync]）。
     *
     * Worker 路径(startTime>0)从不登记，所以不受影响。
     */
    private val manualSyncJobs = ConcurrentHashMap.newKeySet<Job>()

    /**
     * 分红预取的**后台**作用域：预取不再占住价格同步的前台时间。
     *
     * 诊断（2026-11-15）实锤：手动刷新 31~33s 里，分红预取独占 29.4s（吃满
     * [SyncBudget.PREFETCH_PHASE_MAX_MS] 的 28s 子预算），价格同步只剩 2.0s。
     * 用户点刷新要看的是行情，分红只是配角，因此把预取移出 [runSync] 的等待链：
     * 同一份 symbols / forceRefresh / [SyncBudget.prefetchPhaseBudget] 预算原样交给
     * 这个作用域推进，[runSync] 立刻开始取价。预算、天天基金 1 秒匀速闸门与退避
     * 策略**全部不动**，只是不再阻塞前台。
     *
     * 用独立的进程级作用域（SupervisorJob）而不是调用方的 viewModelScope：
     * 前者不会因为页面关闭/刷新结束就中途夭折，预取可以跑完自己的子预算
     * （「分红宁可晚几轮补齐」）；进程内只有一份，不随页面重建而翻倍。
     * 必须带 [CoroutineExceptionHandler]：没有父协程的作用域不会把异常交给
     * Android 默认处理器，未捕获异常会直接崩进程。
     */
    private val dividendPrefetchScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            AppLogger.e("分红预取后台任务异常: ${e.message}", e, TAG)
        }
    )

    /**
     * 在途的后台分红预取 job（进程内单份，只保留最新一轮）。
     *
     * 新轮启动前先取消旧轮：`prefetchDividends` 里没有跨轮互斥，连点刷新会同时
     * 跑两份逐条级联，把天天基金的匀速闸门与退避计数放大成双倍请求量。
     * 被取消的旧轮不写缓存、不写「抓过」时间戳（见 [DividendRepository] 的预算
     * 语义），下一轮按 `prioritizeDividendSymbols` 从没抓到的标的继续，不丢数据。
     */
    @Volatile
    private var dividendPrefetchJob: Job? = null

    /**
     * 最近一次**被取消**的同步轮次收尾文本，供诊断与单测读取。
     *
     * 取消（退后台 / VM 销毁）此前只打「同步开始」一行，日志上像丢了轮次。
     * 完成回调里写一行取消收尾，同时留一份在这里：单测能直接断言，
     * 诊断报告将来也能取到「最后一轮是取消而非静默消失」。
     */
    @Volatile
    private var lastRoundCancelledSummary: String? = null

    /** 测试专用读数：最近一次取消收尾文本；没有取消过则为 null */
    internal fun lastRoundCancelledSummaryForTesting(): String? = lastRoundCancelledSummary

    /**
     * 场外基金下载的专属并发上限 —— **按数据源分两档**。
     *
     * ## 为什么要分档（本段是 2026-10-24 修正的过时前提）
     *
     * 从前这里只有一个 `=2`，理由是「场外基金走 `pingzhongdata/{code}.js`，每只
     * 0.5~2MB」。这个前提**已经不成立**：[com.example.stockdiary.network.FundAPI]
     * 首选源早已换成 `fundmobapi.../FundMNBasicInformation`，**约 2.5KB**（且带基金
     * 名称），`pingzhongdata` 降级为**回退源**（仅轻量源拿不到数据时才用）。
     *
     * 也就是说：正常路径下每只只有 2.5KB，却仍被当成 2MB 大文件压成 2 路串行——
     * 92 只场外基金要跑 46 批，白白慢一倍。故按源分档：
     *
     * - [FUND_MAX_CONCURRENT_LIGHTWEIGHT] = 4：轻量源。4 路 × 2.5KB = 10KB，
     *   内存上毫无压力。
     * - [FUND_MAX_CONCURRENT_LEGACY] = 2：回退源（0.5~2MB 完整历史净值）。**保持
     *   原来的 2 不动**，它就是为压这个大文件而设的。
     *
     * ## 为什么轻量档不直接开到 6（与总闸齐平）
     *
     * 分档靠的是**下载前预检** [FundAPI.isLightweightSourceDisabled]，存在一种竞态：
     * 预检时轻量源还活着，真正请求时它刚好熔断、本次回退到 legacy。此时这 4 个名额
     * 会同时装 4 个大文件 ≈ 8MB。对照历史实测：3 并发 ≈ 6MB 安全通过，8 并发 ≈ 16MB
     * 被 LMK 静默击毙（「点刷新闪退、零崩溃报告」）。8MB 贴近安全档且远低于击毙档，
     * 可接受；开到 6 则是 12MB，越过安全区太远，不冒这个险。
     *
     * ## 叠加关系
     *
     * 与 [manualSyncMutex]、总 [Semaphore] 都是**叠加**的：一个基金任务同时占一个
     * 总信号量名额 + 一个基金专属名额。改总闸时这两个数不要跟着动。
     */
    private const val FUND_MAX_CONCURRENT_LIGHTWEIGHT = 4

    private const val FUND_MAX_CONCURRENT_LEGACY = 2

    /** 轻量源专用闸（2.5KB/只）。正常路径走这条。 */
    private val fundLightSemaphore = Semaphore(FUND_MAX_CONCURRENT_LIGHTWEIGHT)

    /** 回退源专用闸（0.5~2MB/只）。只在轻量源熔断/拿不到数据时走这条。 */
    private val fundLegacySemaphore = Semaphore(FUND_MAX_CONCURRENT_LEGACY)

    /**
     * 价格**批量落库**的批大小。
     *
     * 每写一行 assets，Room 就让 `SELECT * FROM assets` 这个观察查询整表重查重发一次
     * —— 同步一轮写上百个标的，就是上百次整表重查 + 上百次整页重组（「点刷新就卡」的
     * 引擎，日历页同类问题已在 CalendarViewModel 实测到「一次全量刷新白跑 20+ 次」）。
     * 把连续若干条 UPDATE 收进**一个事务**，Room 只在事务提交时失效一次，发射次数约为原来的 1/8。
     *
     * 取 8 而不是「整轮一个事务」：后者一旦进程中途被杀（看门狗截断 / 退后台取消 / LMK），
     * 本轮已经抓到的价格会全部丢失；分批在中途落盘，最坏只丢最后不足一批的那几个。
     * 8 也大于并发上限 [MAX_CONCURRENT_SYNC]，保证并发扇出时仍会攒够批、
     * 不会因为收不齐而退化成逐条写。
     */
    private const val PRICE_WRITE_BATCH_SIZE = 8

    /**
     * 进度回调的最小间隔（毫秒）。
     *
     * `onProgress` 每完成一个标的就**回主线程**写一次 StateFlow（见 :notifyProgress），
     * 一次 StateFlow 赋值 = 一次整屏重组。106 个标的 = 106 次全屏重组，而标题栏上
     * 真正变化的只是一个「已完成 N / 总数」的数字。
     *
     * 节流到 200ms：视觉上仍是「走动的进度」（200ms 一跳人眼看是连续的），
     * 主线程重组次数则从 N 降到约（刷新时长 ÷ 200ms）。**收尾那一次强制发出**，
     * 保证标题栏不会停在「103 / 106」这种中间态。
     */
    private const val PROGRESS_NOTIFY_INTERVAL_MS = 200L

    /**
     * 看门狗轮询间隔（毫秒）。
     *
     * 取 1 秒：时间预算的判定粒度。太小（如 10ms）会在每轮同步里空转烧 CPU，
     * 太大（如 10s）会让超预算的同步多跑近 10 秒才被砍——70 秒预算下多跑 10 秒
     * 就逼近实测 ~83 秒的 LMK 阈值。1 秒是「及时收手」与「不空转」的折中。
     */
    private const val WATCHDOG_INTERVAL_MS = 1_000L

    /**
     * 进程级内存回收回调（整个进程只注册一次）。
     *
     * LMK 杀进程前系统一定会先发 [ComponentCallbacks2.onTrimMemory]，
     * 等级到 [ComponentCallbacks2.TRIM_MEMORY_COMPLETE]（80）基本就是临死前的最后通牒。
     * 把它记进 app.log：将来若仍出现「点刷新闪退、零崩溃报告」，
     * 只要 log 末尾能看到这条 onTrimMemory，就能坐实是内存压力而非逻辑 bug，
     * 省去又一轮瞎猜。只挂一次，避免重复注册导致回调翻倍。
     */
    @Volatile private var memoryTrimCallback: ComponentCallbacks2? = null

    private fun ensureMemoryTrimLogging(context: Context) {
        if (memoryTrimCallback != null) return
        synchronized(this) {
            if (memoryTrimCallback != null) return
            val cb = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    // 不同 level 含义差很多，措辞必须分级，不能把所有回调都叫「临死前通牒」——
                    // 那样低级别（切后台、进程入后台 LRU）会被误判成快被杀，反而误导排查。
                    // 常量定义见 android.content.ComponentCallbacks2（数值是 API 定死的）：
                    //   5  RUNNING_MODERATE      运行中内存偏低，系统建议释放
                    //   10 RUNNING_LOW           运行中内存较低
                    //   15 RUNNING_CRITICAL      运行中内存吃紧，随时可能杀 ← 这才是真压力
                    //   20 UI_HIDDEN             切后台（最频繁，纯正常事件）
                    //   40 BACKGROUND            进程进入后台 LRU 列表（正常事件，不是压力）
                    //   60 MODERATE_BACKGROUND   后台且内存较紧
                    //   80 COMPLETE              进程即将被回收——最后通牒
                    //
                    // ⚠️ 这里曾写成 `in TRIM_MEMORY_BACKGROUND..TRIM_MEMORY_RUNNING_CRITICAL`
                    // （即 40..15）——**上界小于下界的 IntRange 是空区间**，恒为 false，
                    // 于是 level=40 掉到 else 被叫成「内存压力通知」，把切后台误报成压力。
                    // 现已改为逐个常量显式匹配，不再用区间。
                    val desc = when (level) {
                        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                            "UI 不可见（切后台），正常事件"
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
                            "运行中内存偏低，系统建议释放资源"
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                            "运行中内存较低，系统建议释放资源"
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                            "运行中内存吃紧，随时可能被杀——真压力"
                        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                            "进程进入后台 LRU 列表，正常事件（非内存压力）"
                        // 60 = TRIM_MEMORY_MODERATE_BACKGROUND（API 34 才引入该常量，
                        // 但编译环境的 android.jar 里取不到，故用数值 + 注释）
                        60 -> "后台且内存较紧，可能被回收"
                        ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                            "进程即将被回收——最后通牒"
                        else -> "onTrimMemory(level=$level)"
                    }
                    AppLogger.w("onTrimMemory — $desc", TAG)
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    AppLogger.w("onLowMemory — 系统全局内存紧张", TAG)
                }
            }
            // 挂在 Application 上，避免持有 Activity 造成泄漏
            context.applicationContext.registerComponentCallbacks(cb)
            memoryTrimCallback = cb
        }
    }

    /**
     * 在关键时机（刷新起点）打一帧内存水位，作为 LMK 归因的物证。
     * 读数来自两部分：本进程堆（[Runtime]）与系统全局（[ActivityManager.MemoryInfo]）。
     */
    private fun logMemoryState(context: Context, phase: String) {
        val rt = Runtime.getRuntime()
        val maxHeapMb = rt.maxMemory() / (1024 * 1024)
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val availSysMb = mi.availMem / (1024 * 1024)
        val thresholdMb = mi.threshold / (1024 * 1024)
        AppLogger.i(
            "内存水位[$phase]：堆 已用=${usedHeapMb}MB/上限=${maxHeapMb}MB，" +
                "系统 availMem=${availSysMb}MB/回收阈值=${thresholdMb}MB lowMemory=${mi.lowMemory}",
            TAG
        )
    }

    /**
     * 同步所有资产的价格（并行版本，限流控制）
     *
     * @param startTime 同步开始时间戳（毫秒）。传入 >0 时启用时间上限检查，
     *        避免后台任务被系统强制终止；**不传（0）的视为手动刷新**，自动获得
     *        默认时间预算（[SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS]）与进程内互斥
     * @param timeLimitMs 时间上限（毫秒），接近上限时跳过后续未开始的资产并提前返回
     */
    suspend fun syncAllAssets(
        context: Context,
        repository: AssetRepository,
        assets: List<Asset>,
        onProgress: (SyncProgress) -> Unit,
        forceRefresh: Boolean = false,
        startTime: Long = 0L,
        timeLimitMs: Long = 0L,
        correctNames: Boolean = false,
        /**
         * 本次同步是谁触发的（"仪表盘下拉刷新" / "后台周期同步" / "导入后同步" …）。
         *
         * 只影响日志：用户报「刚才刷新不对」时，靠它把时刻与日志轮次对上，
         * 否则几轮同步混在同一份日志里无法区分是谁跑的。默认值给内部/测试调用兜底。
         */
        syncTrigger: String = "未知来源"
    ): SyncResult {
        if (assets.isEmpty()) {
            return SyncResult(0, 0)
        }

        // 不传 startTime 的都是 UI 触发的刷新（资产页/持仓页的刷新按钮、
        // 资产详情页、导入持仓后的同步），走「互斥 + 默认预算」保护；
        // 后台 Worker 一定传 startTime，用自己的 9 分钟上限，两项都不套
        val isManualSync = startTime <= 0L

        return if (isManualSync) {
            if (manualSyncMutex.isLocked) {
                AppLogger.i("另一次刷新正在进行，本次刷新排队等待", TAG)
            }
            // 进入 withLock 之前就登记本次手动同步的 job（用集合而非单字段：连点时会有
            // 「正在跑 + 排队」多个 job 同时在途，onStop 退后台要把它们全部取消）。
            // 关键点：登记在 withLock 之前，排队等待期间本 job 也处于「在途」状态——
            // 这样退后台取消才能打断等待中的那一轮，不会再等到它拿到锁后在后台跑完整轮
            // （最多 70s 网络请求），与「退后台即取消」的约定相反。
            val thisJob = currentCoroutineContext()[Job]
            thisJob?.let { manualSyncJobs.add(it) }
            try {
                manualSyncMutex.withLock {
                    // 预算从真正开跑那一刻起算：在锁上排队的时间不能算进本次刷新，
                    // 否则第二次刷新拿到的是被第一次吃掉大半的残预算
                    runSync(
                        context = context,
                        repository = repository,
                        assets = assets,
                        onProgress = onProgress,
                        forceRefresh = forceRefresh,
                        startTime = System.currentTimeMillis(),
                        timeLimitMs = SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS,
                        correctNames = correctNames,
                        syncTrigger = syncTrigger
                    )
                }
            } finally {
                thisJob?.let { manualSyncJobs.remove(it) }
            }
        } else {
            runSync(
                context = context,
                repository = repository,
                assets = assets,
                onProgress = onProgress,
                forceRefresh = forceRefresh,
                startTime = startTime,
                timeLimitMs = timeLimitMs,
                correctNames = correctNames,
                syncTrigger = syncTrigger
            )
        }
    }

    /**
     * 取消当前进行中的**手动**同步（若有）。
     *
     * 由 MainActivity.onStop 调用：用户切到后台（分屏/权限弹窗只触发 onPause，不触发 onStop，
     * 不会误伤）。没有在途手动同步时是 no-op；后台 Worker（startTime>0）从不登记，
     * 因此不受影响。
     *
     * 取消会经协程树一路传到 [runSync]，在途请求的 await 立即返回、同步立即收尾，
     * 残余阻塞在 OkHttp execute() 里的线程由 callTimeout（见 [com.example.stockdiary.network.NetworkClient]）兜底回收。
     * 各调用方的 catch 已按铁律 `catch (CancellationException) { throw e }` 透传，刷新态在
     *  finally 里复位，不会弹错误提示也不会永久转圈。
     */
    fun cancelActiveManualSync(reason: String) {
        // 后台分红预取独立于手动轮次存活：轮次可能已经结束、预取还在推进，
        // 退后台时也要一起停掉（改造前它是同步链的一部分，本就会随轮次取消）。
        dividendPrefetchJob?.takeIf { it.isActive }?.let { job ->
            AppLogger.i("取消后台分红预取：$reason", TAG)
            job.cancel()
        }
        if (manualSyncJobs.isEmpty()) return
        AppLogger.i("取消进行中的手动同步：$reason（${manualSyncJobs.size} 个在途）", TAG)
        // 取消集合里的每一个：正在跑的那一轮立即收尾，排队等待的那一轮在 withLock 挂起处
        // 抛 CancellationException、不会进入 runSync。toList 先快照，避免 cancel 触发
        // 协程树收尾时 finally 里的 remove 在遍历途中改集合。
        manualSyncJobs.toList().forEach { it.cancel() }
    }

    /** 测试专用复位：清掉在途手动同步句柄（object 是进程内单例，用例之间必须清状态） */
    internal fun resetForTesting() {
        manualSyncJobs.clear()
        // 后台预取也是进程级单例：不复位会让上一条用例的 job 泄漏到下一条
        dividendPrefetchJob?.cancel()
        dividendPrefetchJob = null
        lastRoundCancelledSummary = null
    }

    /** 测试专用读数：当前后台分红预取 job（供「预取不阻塞」用例断言它确实在跑） */
    internal fun backgroundDividendPrefetchJobForTesting(): Job? = dividendPrefetchJob

    /** 测试专用读数：当前是否还有登记的在途手动同步 job（供取消用例断言登记/清空） */
    internal fun activeManualSyncJobForTesting(): Job? = manualSyncJobs.firstOrNull()

    /**
     * 该资产是否走 STOCK/场内基金行情批量取价路径，返回用于批量取价的代码；
     * 不在此列（场外基金走 FundAPI、加密走币安、退市无源）返回 null。
     *
     * 口径必须与 [syncSingleAsset] 完全一致：场内基金 = 非 `fund_` 前缀 +
     * ETF/LOF/场内基金类别（与其中「`isExchangeTraded`」判断同源），
     * 这样批量回填的代码与逐标的取价用的代码一一对应，命中 `batchResult` 短路时不会张冠李戴。
     */
    private fun batchStockCode(asset: Asset): String? {
        val symbol = asset.symbol ?: return null
        return when (asset.type) {
            AssetType.STOCK -> if (StockCodeManager.isDelistedForSync(symbol)) null else symbol
            AssetType.FUND -> {
                // fund_ 前缀 = 场外份额，走 FundAPI，不参与股票批量
                if (symbol.startsWith("fund_", ignoreCase = true)) return null
                val pureCode = StockCodeManager.extractDigits(symbol)
                val category = StockCodeManager.classifyCode(pureCode)
                val isExchangeTraded = category == StockCodeManager.CodeCategory.ETF ||
                    category == StockCodeManager.CodeCategory.LOF ||
                    category == StockCodeManager.CodeCategory.EXCHANGE_FUND
                if (isExchangeTraded && !StockCodeManager.isDelistedForSync(symbol)) symbol else null
            }
            else -> null
        }
    }

    /**
     * 真正干活的同步。整段强制跑在 [Dispatchers.IO] 上——
     * 这是这次卡死修复的核心。
     *
     * ## 从前的问题
     *
     * 调用方是 `viewModelScope`，调度器是 `Dispatchers.Main.immediate`，
     * 而这个函数从前**不切换调度器**：60 个持仓 = 60 个协程的启动加上几百个
     * 网络续体，全部挤在主线程 Looper 里，每个续体还夹带缓存写入、Room 调用、
     * 日志与进度回调（进度回调又触发 Compose 重组）。主线程饱和的直接表现
     * 就是用户看到的「黑屏」，而且刷新链路自己的日志也发不出去——
     * 排查时那一百多秒的日志全静默就是这么来的。
     *
     * 注意**只把扇出搬下来，不把进度回调搬走**：进度必须回主线程更新
     * StateFlow 才能触发重组，但每一次只是一次轻量分发，与几百个续体
     * 挤在主线程有本质区别。
     */
    private suspend fun runSync(
        context: Context,
        repository: AssetRepository,
        assets: List<Asset>,
        onProgress: (SyncProgress) -> Unit,
        forceRefresh: Boolean,
        startTime: Long,
        timeLimitMs: Long,
        correctNames: Boolean,
        syncTrigger: String
    ): SyncResult = withContext(Dispatchers.IO) {
        val enforceTimeLimit = timeLimitMs > 0L

        // 轮次 ID：进程内自增。几轮同步混在同一份日志里时，靠它把散落各处的行归属到同一轮
        val roundId = syncRoundSeq.incrementAndGet()
        // G5 血缘：开轮清掉上一轮残留，后续各取价层的 report 才会归属到本轮而非旧数据
        PriceLineageRecorder.beginRound(roundId, syncTrigger)

        // 本轮「源级失败记账」上下文：显式轮次 ID + 每轮独立的「已记账源」集合。
        // 在批量预取之前开启；批量 0 命中确实记过账的源写入 reportedSources（扇出前写完，
        // 扇出期间只读）；逐标的扇出用 withContext 把它沿回落路径传给门面 / StockDataManager。
        // 作用域结束即自动失效：没有进程级全局状态，也没有 ThreadLocal 的清理路径。
        val roundFailureScope = RoundSourceFailureScope(roundId, ConcurrentHashMap.newKeySet())

        // 各数据源的成败统计只反映**本轮**：统计按 roundId 隔离（不再进程级清零），
        // 手动轮与后台 Worker 并发时互不覆盖；这里只防御性地清掉同号残留（理论上不会存在）
        val stockManagerForStats = StockDataManager.getInstance(context)
        stockManagerForStats.resetSourceStats(roundId)
        // 基金与加密链路共用同一套 SourceStats，轮次节奏也必须一致
        FundAPI.resetSourceStats(roundId)
        UnifiedPriceService.resetSourceStats(roundId)
        // 本轮统计账本：成功/失败/跳过原因、错误类型、截断标记、卡片提示集合。
        // 记账细节全部收在 SyncRoundStats，编排流程只负责在正确的时机调它的方法。
        val stats = SyncRoundStats(roundId, syncTrigger, assets.size)

        val semaphore = Semaphore(MAX_CONCURRENT_SYNC)
        val dividendRepository = DividendRepository.getInstance(context)

        // 价格批量落库器：落价不再逐条 UPDATE，而是攒够一批走一个事务（见 [SyncPriceWriter]）
        val priceWriter = SyncPriceWriter(repository, PRICE_WRITE_BATCH_SIZE)

        // 退市股兜底：每个自然日最多向东财确认一次最后收盘，避免逐轮重复请求。
        // 交易日只用于内部去重/日志，不进入界面。
        val delistedQuoteCache = DelistedQuoteCache(context)

        // 进度回调节流的时间戳闸门。AtomicLong + CAS 保证「同一时刻只有一个协程拿到发送权」，
        // 避免三个并发标的各发各的、把节流窗口冲掉
        val lastProgressNotifyAt = java.util.concurrent.atomic.AtomicLong(0L)

        /**
         * 进度回调回主线程更新 StateFlow；扇出与续体留在 IO。
         *
         * @param force true = 绕过节流立刻发（用于阶段起点的第一条与收尾的最后一条），
         *        保证标题栏不会停在「103 / 106」这类中间态
         */
        val notifyProgress: suspend (SyncProgress, Boolean) -> Unit = { progress, force ->
            val now = System.currentTimeMillis()
            val last = lastProgressNotifyAt.get()
            val withinInterval = now - last < PROGRESS_NOTIFY_INTERVAL_MS
            if (force) {
                lastProgressNotifyAt.set(now)
                withContext(Dispatchers.Main) { onProgress(progress) }
            } else if (!withinInterval && lastProgressNotifyAt.compareAndSet(last, now)) {
                // 节流：一次 StateFlow 赋值 = 一次整屏重组，而标题栏上变的只是一个数字
                withContext(Dispatchers.Main) { onProgress(progress) }
            }
        }

        // 心跳要能读出「卡在哪个标的」：进行中的标的在这里登记/注销
        val inFlightAssets = ConcurrentHashMap.newKeySet<String>()

        // 内存水位取证：刷新起点打一帧，并挂上进程级回收回调（只挂一次）。
        // 万一将来仍偶发「点刷新闪退、零崩溃报告」，log 末的 onTrimMemory / 内存水位
        // 就能坐实是 LMK 而非逻辑 bug，省去又一轮瞎猜。
        ensureMemoryTrimLogging(context)
        logMemoryState(context, "同步起点")

        AppLogger.i(
            "价格同步开始 [轮次#$roundId][$syncTrigger]：${stats.totalCount} 个资产，" +
                "forceRefresh=$forceRefresh，" +
                "时间预算=${if (enforceTimeLimit) "${timeLimitMs}ms" else "无"}",
            TAG
        )

        // 取消收尾（只加日志，数据行为不变）：退后台 / VM 销毁会让整轮在某个挂起点
        // 抛 CancellationException，此前只有上面那行「同步开始」，日志上像丢了一轮。
        // 用当前 job 的完成回调补一行，不包 try/catch 是为了不把 400 行主体整体缩进、
        // 让 diff 保持在几行内。回调在 job 状态落定后同步执行，AppLogger 只入队，
        // 不阻塞取消线程。
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                val summary = stats.cancellationSummary(cause)
                lastRoundCancelledSummary = summary
                AppLogger.i(summary, TAG)
            }
        }

        val stocksAndFunds = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
        if (stocksAndFunds.isNotEmpty()) {
            val symbols = stocksAndFunds.mapNotNull { it.symbol }
            // 注意：notifyProgress 是函数类型变量，Kotlin 不允许对函数类型用命名实参，只能按位置传
            notifyProgress(SyncProgress(0, stats.totalCount, "正在同步行情..."), true)
            // 分红预取是刷新的配角，只划一段子预算（见 SyncBudget），
            // 剩下的留给价格同步——用户点刷新真正要看的是行情。
            // 预算照旧，但**不再在这里 await**：转后台协程推进（见 [dividendPrefetchScope]），
            // 否则手动刷新会被预取吃满 28s 子预算（诊断实测 29.4s），价格只能等它跑完。
            val prefetchBudget = SyncBudget.prefetchPhaseBudget(timeLimitMs)
            startBackgroundDividendPrefetch(
                repository = dividendRepository,
                symbols = symbols,
                forceRefresh = forceRefresh,
                timeBudgetMs = prefetchBudget,
                roundId = roundId
            )
        }

        withHeartbeat(
            tag = TAG,
            phase = "价格同步",
            status = {
                val doing = inFlightAssets.take(3).joinToString("、")
                "已完成 ${stats.completedCount}/${stats.totalCount}" +
                    "，进行中 ${inFlightAssets.size} 个" +
                    if (doing.isNotEmpty()) "（$doing）" else ""
            }
        ) {
            // 顺序很重要：场外基金是日更净值，且每只都要下 0.5~2MB 历史文件，最重、最不紧急；
            // 把它排到最后，让股票 / 虚拟货币这些轻量、用户最关心的实时行情优先在预算内刷完，
            // 基金被截断也只影响日更数据、下次（或后台 Worker）补上即可。
            val orderedAssets = assets.sortedBy { if (it.type == AssetType.FUND) 1 else 0 }

            // P3：STOCK/场内基金批量预取——把原本「逐标的各打一次」的 N 次请求，
            // 合并成走新浪批量接口的 ~⌈N/20⌉ 次请求（请求量不增反降，安全说明见下）。
            // 在逐标的扇出之前只做一次：网络可用性 / 用户偏好数据源也只在此读一次（P4），
            // 随后每个标的的 getPrice 要么命中 batchResult 短路、要么命中门面 60s 缓存，
            // 完全不再逐标的打网络或读系统状态。批量失败 / 停牌 / 退市整理期的标的
            // batchResult 为 null，会落回逐标的的多源兜底，正确性不丢、也不会因此多烧请求。
            // 只把真正「股票」代码送进新浪批量：batchStockCode 会返回 STOCK 资产里
            // 装着 ETF/场内基金代码的标的（5xxxxx/159/16/18 开头），新浪不支持它们，
            // 送进去只会白占批槽位。滤掉后它们落到逐标的 getPrice → 腾讯路径，正确性不变，
            // 还省掉白占的批槽位。reportBatchTotalMiss 的 hasPlainStock 检查仍只看到纯股票
            // 代码，语义正确（不会把「整批都是场内基金」误判成源级故障）。
            val batchStockCodes = orderedAssets.mapNotNull { batchStockCode(it) }
                .filter { StockCodeManager.classifyCode(it) == StockCodeManager.CodeCategory.STOCK }
                .distinct()
            val stockManager = StockDataManager.getInstance(context)
            val networkAvailable = stockManager.isNetworkAvailableNow()
            // 批量阶段也必须受本轮预算管辖：它跑在逐标的扇出与看门狗**之前**，
            // 从前这里没有任何预算口径——调用方已经拖到预算边缘时，这一批仍会照发，
            // 单次请求最长吃满 callTimeout(20s)，把整轮推过 SyncBudget 记录的
            // 「~83s 被系统 LMK 静默击毙」线（真机形态：转圈很久然后闪退）。
            val batchDeadline = if (enforceTimeLimit) startTime + timeLimitMs else Long.MAX_VALUE
            val batchPriceMap: Map<String, AssetPrice> = if (batchStockCodes.isNotEmpty()) {
                UnifiedPriceService.prewarmStockPrices(
                    batchStockCodes,
                    forceRefresh,
                    networkAvailable,
                    deadlineMs = batchDeadline
                ).getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            AppLogger.i("批量预取 STOCK/场内基金 ${batchStockCodes.size} 个代码，命中 ${batchPriceMap.size} 个", TAG)
            // 整体 0 命中且批里有普通股票：不是「这些代码此源不支持」，而是源在返回空报价。
            // 真机日志坐实过：新浪 44 次请求全 41 字节、0 成功，却因为逐只被判成符号级
            // （代码不在此源支持范围）而熔断器永不开，导致每轮每个标的白打一次新浪。
            // 喂一次源级失败，让熔断器看见（阈值 8 次才禁，半开探测自动恢复）。
            // 若这次确实记了账，就把该源登记进本轮上下文：随后逐标的回落对同一源不再
            // 重复喂熔断（币安侧同款「整轮只记一次成败」），避免「1 次批量 + N 个标的」
            // 凑成 1+N 次、把单波并发抖动放大成误熔。返回 false（批内没有普通股票）时
            // 不登记，逐标的真实 IO 失败照常记账。
            if (batchPriceMap.isEmpty()) {
                val reported = stockManager.reportBatchTotalMiss(
                    ApiManager.API_SINA, batchStockCodes, roundId
                )
                if (reported) {
                    roundFailureScope.reportedSources.add(ApiManager.API_SINA)
                }
            }

            // 加密货币批量预取：与上面的 STOCK 批量同构——逐标的扇出**之前**只发
            // ⌈N/60⌉ 次币安批量请求（真机一轮约 129 次单标的请求 → 2~3 次），
            // 结果双写门面 60s 缓存并作为 batchResult 回填。
            //
            // getPrice 的 batchResult 短路位于 `when(type)` **之前**（UnifiedPriceService
            // 第 108 行 `if (batchResult != null) return Result.success(batchResult)`），
            // 已确认对 CRYPTO 分支同样生效，故无需改动 getPrice。
            // 批量失败/取不到的标的 cryptoPriceMap 里没有，回落逐标的 getCryptoPrice 原路径，
            // 正确性不丢、也不会因此多烧请求（见 prewarmCryptoPrices 的失败语义）。
            val batchCryptoCodes = orderedAssets
                .filter { it.type == AssetType.CRYPTO }
                .mapNotNull { it.symbol }
                .distinct()
            // 截止点必须传进去：预取阻塞在 OkHttp 里、看门狗要等它跑完才启动，
            // 不传预算它可以把整轮刷新（70s 手动 / 9min 后台）吃光在请求超时上。
            // 加密批量与股票批量共用同一个本轮截止点（batchDeadline）。
            // 预算已尽时**不再启动**：它的片间检查只在两片之间生效，第一片发出去
            // 至少再吃 20s，正好把整轮推过 LMK 线。
            val cryptoBudgetLeft = batchCryptoCodes.isNotEmpty() && networkAvailable &&
                System.currentTimeMillis() < batchDeadline
            val cryptoPriceMap: Map<String, AssetPrice> =
                if (cryptoBudgetLeft) {
                    // 必须带上本轮上下文：prewarmCryptoPrices 用 RoundSourceFailureScope
                    // 读轮次 ID 记源统计。批量预取在逐标的扇出的 withContext 之外，
                    // 从前没这层 → roundId 取默认 0 → 统计写进 0 号桶，轮次汇总按
                    // 本轮 ID 读不到，币安的源统计行整条消失（诊断 11-12 起）。
                    withContext(roundFailureScope) {
                        UnifiedPriceService.prewarmCryptoPrices(
                            batchCryptoCodes,
                            forceRefresh,
                            deadlineMs = batchDeadline
                        ).getOrDefault(emptyMap())
                    }
                } else {
                    if (batchCryptoCodes.isNotEmpty() && networkAvailable) {
                        AppLogger.w("批量预取加密货币已达时间预算，跳过本次预取（回落逐标的/下一轮）", TAG)
                    }
                    emptyMap()
                }
            AppLogger.i(
                "批量预取加密货币 ${batchCryptoCodes.size} 个代码，命中 ${cryptoPriceMap.size} 个",
                TAG
            )

            val jobs = orderedAssets.map { asset ->
                async {
                    semaphore.withPermit {
                        // 时间预算检查必须放在「拿到信号量之后」：上面的 112 个 async 是一次性
                        // 全部 launch 的，若在 withPermit 之前检查，全都在 t≈0 落空、之后在信号量
                        // 上排队恢复时早已错过检查点，预算形同虚设——这正是手动刷新曾跑满数分钟、
                        // 被系统 LMK 静默击毙、且币安行情永远排不到的根因。
                        if (SyncBudget.isExceeded(startTime, timeLimitMs, System.currentTimeMillis())) {
                            if (stats.markTruncated()) {
                                AppLogger.w(
                                    "已达时间预算（${timeLimitMs}ms），后续未开始的标的本次跳过",
                                    TAG
                                )
                            }
                            // 没来得及开始的标的也是「跳过」，必须进轮次摘要，
                            // 否则 total 与 成功/失败/跳过 三项之和对不上。
                            stats.markBudgetSkipped(asset.id)
                            stats.countSkip("超时截断")
                            return@withPermit
                        }
                        val label = "${asset.name}（${asset.symbol ?: "无代码"}）".take(24)
                        AppLogger.d("开始同步 $label", TAG)
                        inFlightAssets.add(label)
                        try {
                            // 把本轮「已记账源」上下文注入回落链路：门面 / StockDataManager
                            // 从 currentCoroutineContext 读取，同一源不再重复喂熔断。
                            // 子任务结束即失效，一轮一个 scope，不残留跨轮状态。
                            withContext(roundFailureScope) {
                                syncSingleAsset(
                                    asset,
                                    repository,
                                    priceWriter,
                                    delistedQuoteCache,
                                    stats,
                                    forceRefresh,
                                    correctNames,
                                    batchResult = batchPriceMap[asset.symbol] ?: cryptoPriceMap[asset.symbol],
                                    networkAvailable = networkAvailable
                                )
                            }
                        } finally {
                            inFlightAssets.remove(label)
                        }
                        // 同步流程正常走完（含显式跳过）：之后才登记为 settled。
                        // 被取消的标的走不到这一句，留给收尾统一计入「取消级联」。
                        stats.markSettled(asset.id)
                        val completed = stats.recordCompleted()
                        // 收尾那一次强制发出：否则标题栏会停在「103 / 106」这类中间态
                        notifyProgress(
                            SyncProgress(completed, stats.totalCount, "同步 ${asset.name}..."),
                            completed >= stats.totalCount
                        )
                    }
                }
            }

            // ===== 看门狗：强制时间预算 =====
            // 上面 withPermit 内的快检查只能拦「还没拿到信号量的标的」。可一旦某个在途请求挂死
            // （实测有请求挂 54 分钟：app 退后台被系统冻结、醒来才恢复），它会一直占着信号量名额，
            // 后面的标的连 withPermit 都进不去、快检查永远不执行——预算形同虚设，恢复后已耗时数十分钟
            // 才靠下一个拿到名额的标的触发截断。看门狗按 [WATCHDOG_INTERVAL_MS] 轮询总预算，超了就
            // **主动取消所有在途子任务**，不依赖它们是否还能拿到信号量。
            val watchdog = if (enforceTimeLimit) {
                launch {
                    while (isActive) {
                        delay(WATCHDOG_INTERVAL_MS)
                        if (SyncBudget.isExceeded(startTime, timeLimitMs, System.currentTimeMillis())) {
                            if (stats.markTruncated()) {
                                AppLogger.w(
                                    "看门狗触发：已达时间预算（${timeLimitMs}ms），取消在途请求并收尾",
                                    TAG
                                )
                            }
                            // 取消打不断同步阻塞在 OkHttp execute() 里的线程，但 await 会立即返回、
                            // 同步立即收尾；残余阻塞线程由 callTimeout（NetworkClient）兜底回收。
                            jobs.forEach { it.cancel() }
                            return@launch
                        }
                    }
                }
            } else {
                null
            }

            // 逐条 await + try/catch：区分「被看门狗取消的子任务」与「外部取消（退后台 / VM 销毁）」。
            // 前者（当前协程仍 active）吞掉、继续收尾其余，最终照常构建 SyncResult（truncatedByTimeLimit=true）；
            // 后者（当前协程已 inactive）原样重抛——项目铁律：取消不吞，否则退后台取消会被误当成「刷新失败」。
            try {
                jobs.forEach { job ->
                    try {
                        job.await()
                    } catch (e: CancellationException) {
                        if (!shouldContinueAfterChildCancel(currentCoroutineContext().isActive)) {
                            throw e
                        }
                        // 只是被看门狗取消的子任务：吞掉，继续收尾
                    }
                }
                // 取消级联如实计入 skipped：被看门狗取消的在途标的既不成功也不失败，
                // 不计的话轮次摘要会出现「总数 106，但成功+失败+跳过只有 50」的缺口。
                // 只加统计，不改取消传播（外部取消仍原样上抛）。
                if (stats.truncated) {
                    val cancelledCascade = stats.countCancelledCascade(orderedAssets.map { it.id })
                    stats.addSkipCount("取消级联", cancelledCascade)
                }
            } finally {
                // 无论正常收尾还是外部取消，都要把看门狗停掉，否则 withHeartbeat 会一直等它空转
                watchdog?.cancel()
                // ===== 收尾 flush：把缓冲区里最后不足一批的落价意图写掉 =====
                // 为什么必须放在这个 finally 里，而不是 withHeartbeat 之后：
                // 取消（退后台 / VM 销毁）会让整个块直接抛 CancellationException，
                // 正常收尾那句根本执行不到；而 SyncPriceWriter 的 KDoc 承诺
                // 「同步被取消时也要调用，否则会丢价」——缓冲区里不足一批（最多 7 个）
                // **已经抓到**的价格会白抓，且往往总是同一批标的，现价长期停在旧值。
                // NonCancellable 保证清理写库不被取消打断；只吞写库异常，
                // 取消信号本身交给外层继续正常传播。
                withContext(NonCancellable) {
                    try {
                        priceWriter.flushAll()
                    } catch (e: Exception) {
                        // 收尾这批无法归属到单个标的（不像逐标的写库能记进那个资产的失败计数），
                        // 所以只告警、不让整轮同步判失败——上面已成功的计数仍然有效。
                        AppLogger.e("收尾批量落价失败，本轮最后一批价格未写入: ${e.message}", e, TAG)
                    }
                    // 卡片提示的落盘也必须在 NonCancellable 里：取消时本轮已判定的
                    // 成功 / 失败结果不能丢，否则提示会停在上一轮的旧状态。
                    try {
                        AssetSyncFailureStore.getInstance(context)
                            .applyOutcomes(stats.succeededIds, stats.failedIds)
                    } catch (e: Exception) {
                        // 提示状态是纯展示信息，写失败绝不能让整轮同步判失败
                        AppLogger.e("落盘上次同步失败标记出错: ${e.message}", e, TAG)
                    }
                }
            }
        }

        // ===== 轮次末的结构化汇总 =====
        // 排障时最先想知道的三件事——「这轮跑了多少、卡在哪、哪个数据源在掉链子」——
        // 从前全散落在上百行里，只能靠人肉 grep 才能拼出来（真机日志分析时为确认
        // "新浪是否全挂"连跑多次 grep -c）。现在一轮一段，打开日志尾部就能看完。
        // 快照只取一次：日志汇总、返回给 UI 的 SyncResult 必须看到同一份数字，
        // 分两次调用会在并发收尾时产生「日志 8 次失败、提示 7 次」这种对不上。
        val elapsedMs = System.currentTimeMillis() - startTime
        // 三条链路（股票 / 基金 / 加密）的快照合成一份，顺序稳定：股票在前，基金次之，加密最后
        // 全部按 roundId 取：并发轮次各看各的，轮次汇总只含本轮
        val sourceStats = stockManagerForStats.sourceStatsSnapshot(roundId) +
            FundAPI.sourceStatsSnapshot(roundId) +
            UnifiedPriceService.cryptoSourceStatsSnapshot(roundId)

        lastRoundSummary = stats.logSummary(elapsedMs, sourceStats)
        val result = stats.toResult(elapsedMs, sourceStats)
        AppLogger.i(
            "价格同步结束 [轮次#$roundId][$syncTrigger]：成功 ${result.successCount}，失败 ${result.failedCount}，" +
                "跳过 ${result.skippedCount}，超时截断=${result.truncatedByTimeLimit}",
            TAG
        )
        // 轮末回收：统计按 roundId 隔离，快照已取完，及时释放避免轮次字典无限增长。
        // **必须在快照之后**：先删再取会让汇总变空
        stockManagerForStats.discardSourceStats(roundId)
        FundAPI.discardSourceStats(roundId)
        UnifiedPriceService.discardSourceStats(roundId)
        result
    }

    /**
     * 启动一轮**后台**分红预取，不阻塞价格同步。
     *
     * 与改造前逐项对齐的只有「调用谁、传什么」：symbols、forceRefresh 与
     * `SyncBudget.prefetchPhaseBudget` 的同一份子预算原样交给
     * [DividendRepository.prefetchDividends]，限速 / 退避 / 预算语义一处未改。
     * 变的是**归属**：不再挂在当前同步 job 下，轮次结束也不影响它跑完自己的子预算。
     *
     * 取消旧 job 再启动新的：连点刷新时两份逐条级联会同时压天天基金，
     * 把匀速闸门与退避计数放大成双倍请求量。被取消的旧轮不写缓存、不写
     * 「抓过」时间戳，下一轮按 `prioritizeDividendSymbols` 从没抓到的标的继续。
     */
    private fun startBackgroundDividendPrefetch(
        repository: DividendRepository,
        symbols: List<String>,
        forceRefresh: Boolean,
        timeBudgetMs: Long,
        roundId: Int
    ) {
        dividendPrefetchJob?.takeIf { it.isActive }?.let { previous ->
            AppLogger.i("上一轮分红预取仍在后台推进，先取消再启动本轮 [轮次#$roundId]", TAG)
            previous.cancel()
        }
        dividendPrefetchJob = dividendPrefetchScope.launch {
            AppLogger.i(
                "分红预取转入后台 [轮次#$roundId]：${symbols.size} 个代码，" +
                    "预算=${if (timeBudgetMs > 0) "${timeBudgetMs}ms" else "无"}",
                TAG
            )
            try {
                repository.prefetchDividends(symbols, forceRefresh, timeBudgetMs)
                AppLogger.i("分红预取阶段结束（后台）[轮次#$roundId]", TAG)
            } catch (e: CancellationException) {
                // 取消是预期路径（退后台 / 新一轮启动），只留一行收尾，不吞信号
                AppLogger.i("分红预取被取消（后台）[轮次#$roundId]", TAG)
                throw e
            } catch (e: Exception) {
                // 预取失败不该影响已经完成的价格同步；只记一笔，下一轮再补
                AppLogger.e("分红预取失败（后台）[轮次#$roundId]: ${e.message}", e, TAG)
            }
        }
    }

    private suspend fun syncSingleAsset(
        asset: Asset,
        repository: AssetRepository,
        /** 落价出口：拿到的价交给它攒批，不再逐条 UPDATE（见 [SyncPriceWriter]） */
        priceWriter: SyncPriceWriter,
        /** 退市股兜底收盘的按日缓存（同一自然日不重复请求东财） */
        delistedQuoteCache: DelistedQuoteCache,
        /** 本轮统计账本：成功/失败/跳过/错误类型与卡片提示集合都由它记账 */
        stats: SyncRoundStats,
        forceRefresh: Boolean,
        correctNames: Boolean,
        /** 批量预取回填的现成价；非 null 时 STOCK/场内基金分支直接采信，跳过逐标的网络请求（P3） */
        batchResult: AssetPrice? = null,
        /** 同步起点一次性算好的网络可用性（P4）：非 null 时不再逐标的读 ConnectivityManager */
        networkAvailable: Boolean? = null
    ) {
        val symbol = asset.symbol
        val startedAt = System.currentTimeMillis()
        var skipReason: String? = null

        var isSuccess = false
        var lastError: Throwable? = null
        // 只有真正计进 failedCount 才置 true；被取消（我们主动放弃）的异常不置位，
        // 避免把「退后台 / 看门狗截断」误标成「上次更新失败」。
        var failureRecorded = false

        try {
            // 退市标的（400xxx/420xxx 老三板）新浪 / 腾讯都没有报价，但东财 K 线仍保留
            // 最后一根周收盘（实测 R东方1 2026-11-06 收盘 0.22）。这里做一次**隔离的兜底**：
            // 只更新价格，不计成功、不计失败，也不喂任何数据源健康 / 熔断——东财这条链路
            // 与正常股票的取价源完全分开，失败只回落成原来的「跳过(退市)」，价格不动。
            //
            // 必须走 isDelistedForSync 而不是 isDelisted：后者的「6 位数字 + 400/420 前缀」
            // 和天天基金场外代码段是重叠的——400030（东方添益债券）、420102 都是用户
            // 持仓里的真基金。误杀后不发行情请求、不计失败，价格永久冻结且用户端零信号，
            // 比没有豁免更糟。isDelistedForSync 会放行 fund_ 前缀的场外份额，
            // 同时仍能认出老三板真退市股（bj400264 / sh400123）。
            //
            // 还要再加一道**类型闸**：只兜底 STOCK。FUND 且不带 fund_ 前缀的 400/420
            // （手动录入 400030/420102 后规范化成 bj400030/bj420102）仍是场外基金，
            // 东财股票 K 线里没有它的行情；放进兜底只会拿错价或白等一次请求。
            // 这类标的必须落到下面 FUND 分支，按纯数字代码走天天基金净值。
            if (asset.type == AssetType.STOCK && StockCodeManager.isDelistedForSync(symbol)) {
                val delistedSymbol = symbol ?: return
                // 同一自然日已经确认过：不再请求东财（失败也落检查日，避免每轮重试）
                if (delistedQuoteCache.isCheckedOn(delistedSymbol)) {
                    AppLogger.d("跳过退市标的（今日已确认无新收盘）: ${asset.name}（$delistedSymbol）", TAG)
                    skipReason = "退市"; stats.countSkip("退市")
                    return
                }
                if (networkAvailable == false) {
                    AppLogger.i("跳过退市标的（当前无网络，待恢复后再取历史收盘）: ${asset.name}（$delistedSymbol）", TAG)
                    skipReason = "退市"; stats.countSkip("退市")
                    return
                }

                val quote = EastmoneyDelistedQuoteApi.fetchLastClose(delistedSymbol)
                if (quote != null) {
                    // 顺序很重要：先写价、成功后再落检查日。
                    // 反过来的话，写价被打断/失败时检查日已经记下，当天不会再重试，
                    // 这只退市股的价格就会一直停在旧值。
                    val written = runCatching {
                        withContext(NonCancellable) {
                            priceWriter.write(PendingPriceWrite(delistedSymbol, quote.price))
                        }
                    }.isSuccess
                    if (written) {
                        delistedQuoteCache.record(delistedSymbol, quote)
                        // 兜底价也要有血缘：asOf 用行情交易日（老三板按周成交，可能滞后一周），
                        // 不能用抓取时刻冒充。
                        val asOf = runCatching {
                            LocalDate.parse(quote.tradeDate)
                                .atTime(15, 0)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        }.getOrDefault(System.currentTimeMillis())
                        PriceLineageRecorder.report(
                            delistedSymbol,
                            PriceLineage(
                                code = delistedSymbol,
                                sourceId = SourceIds.EASTMONEY_DELISTED,
                                origin = LineageOrigin.IN_MEMORY,
                                asOf = asOf,
                                fetchedAt = System.currentTimeMillis(),
                                roundId = stats.roundId
                            )
                        )
                        // 退市兜底也是**成功落到新价**：卡片上的「上次更新未成功」必须随之消失
                        stats.markSucceeded(asset.id)
                        // 交易日只进日志/缓存，界面按普通价格展示，不加「历史收盘」标注
                        AppLogger.i(
                            "退市标的按东财最后收盘更新: ${asset.name}（$delistedSymbol，" +
                                "交易日 ${quote.tradeDate}，收盘 ${quote.price}）",
                            TAG
                        )
                    } else {
                        AppLogger.w(
                            "退市兜底写价失败，保留原价、下一轮重试: ${asset.name}（$delistedSymbol）",
                            TAG
                        )
                    }
                } else {
                    // 没拿到价也要落检查日：同一交易日不重复打东财
                    delistedQuoteCache.record(delistedSymbol, null)
                    AppLogger.i("跳过退市标的: ${asset.name}（$delistedSymbol，东财无可用历史收盘）", TAG)
                }
                skipReason = "退市"; stats.countSkip("退市")
                return
            }

            when (asset.type) {
                AssetType.FUND -> {
                    val fundCode = symbol?.removePrefix("fund_")
                    if (fundCode == null) {
                        failureRecorded = true
                        stats.recordFailure(SyncErrorType.DATA)
                        return
                    }
                    // ETF/LOF/场内基金用股票API获取价格，场外基金用基金API
                    val pureCode = StockCodeManager.extractDigits(fundCode)
                    val category = StockCodeManager.classifyCode(pureCode)

                    // fund_ 前缀是「场外份额」的**显式标记**，优先级高于代码前缀。
                    // 基金E账户导入的 fund_163415 就是 LOF 的场外份额：
                    // 按代码前缀判会认为是场内、去调股票 API 拿场内价，
                    // 而场外申赎是按净值成交的，用场内价会有折溢价误差
                    val isOffExchange = symbol.startsWith("fund_", ignoreCase = true)
                    val isExchangeTraded = !isOffExchange && (
                        category == StockCodeManager.CodeCategory.ETF ||
                            category == StockCodeManager.CodeCategory.LOF ||
                            category == StockCodeManager.CodeCategory.EXCHANGE_FUND
                        )

                    if (isExchangeTraded) {
                        // 场内交易基金：与股票同源走行情门面（多源容错 + 60s TTL + 单飞）。
                        // 批量预取的现成价优先（batchResult 非 null 时门面内直接短路），
                        // 网络可用性与偏好数据源也复用同步起点一次性算好的值，不再逐标的重复读。
                        val result = UnifiedPriceService.getPrice(
                            fundCode,
                            AssetType.STOCK,
                            forceRefresh = forceRefresh,
                            batchResult = batchResult,
                            networkAvailable = networkAvailable
                        )
                        result.onSuccess { price ->
                            when (price) {
                                is AssetPrice.Stock -> {
                                    withContext(NonCancellable) {
                                        priceWriter.write(PendingPriceWrite(symbol, price.data.price))
                                    }
                                    if (correctNames) correctAssetName(asset, price.data.name, repository)
                                    isSuccess = true
                                }
                                else -> { lastError = IllegalStateException("返回类型不匹配") }
                            }
                        }.onFailure { lastError = it }
                    } else {
                        // 场外基金：使用基金API（天天基金），传纯数字代码。
                        // forceRefresh 强制为 false：场外净值是**日更**的，同一天再刷也拿不到
                        // 更新的数；传 true 会绕过 FundAPI 的 5 分钟缓存 + 今日净值闸，让每个
                        // 代码每次手动刷新都真的发一次请求，正是「点刷新偶发闪退」的残因。
                        // 改 false 让两道缓存都生效，只在该代码当日还没缓存时才真去下载。
                        //
                        // 专属闸**按源选**：轻量源（约 2.5KB）走宽档提速，轻量源已熔断、
                        // 本次会回退到 legacy（0.5~2MB）时收紧回 2 压峰值内存。
                        // 判据是下载前的预检，存在「预检后刚好熔断」的竞态，后果已在
                        // FUND_MAX_CONCURRENT_LIGHTWEIGHT 的注释里论证过（最坏 8MB，可控）。
                        val fundGate = if (FundAPI.isLightweightSourceDisabled()) {
                            fundLegacySemaphore
                        } else {
                            fundLightSemaphore
                        }
                        fundGate.withPermit {
                            val fundResult = UnifiedPriceService.getPrice(
                                pureCode,
                                AssetType.FUND,
                                forceRefresh = false
                            )
                            fundResult.onSuccess { price ->
                                when (price) {
                                    is AssetPrice.Fund -> {
                                        // FundAPI 刷新失败/被节流时会回退旧净值并带 isStale：
                                        // 不能把它当新价写回库（会刷新 lastSyncTime，旧净值伪装成刚更新），
                                        // 交给下面统一的「缓存过期」分支按跳过记账。
                                        if (price.data.isStale) {
                                            lastError = com.example.stockdiary.network.StalePriceCacheException(symbol)
                                        } else {
                                            withContext(NonCancellable) {
                                                priceWriter.write(
                                                    PendingPriceWrite(symbol, price.data.price, price.data.millionCopiesIncome)
                                                )
                                            }
                                            isSuccess = true
                                        }
                                    }
                                    else -> { lastError = IllegalStateException("返回类型不匹配") }
                                }
                            }.onFailure { lastError = it }
                        }
                    }
                }
                AssetType.STOCK -> {
                    val stockSymbol = symbol
                    if (stockSymbol == null) {
                        failureRecorded = true
                        stats.recordFailure(SyncErrorType.DATA)
                        return
                    }
                    // 股票与场内基金同源：统一走行情门面，多源容错在门面下游。
                    // 批量预取的现成价优先（batchResult 非 null 时门面内直接短路），
                    // 网络可用性与偏好数据源也复用同步起点一次性算好的值，不再逐标的重复读。
                    val result = UnifiedPriceService.getPrice(
                        stockSymbol,
                        AssetType.STOCK,
                        forceRefresh = forceRefresh,
                        batchResult = batchResult,
                        networkAvailable = networkAvailable
                    )

                    result.onSuccess { price ->
                        when (price) {
                            is AssetPrice.Stock -> {
                                withContext(NonCancellable) {
                                    priceWriter.write(PendingPriceWrite(stockSymbol, price.data.price))
                                }
                                if (correctNames) correctAssetName(asset, price.data.name, repository)
                                isSuccess = true
                            }
                            else -> { lastError = IllegalStateException("返回类型不匹配") }
                        }
                    }.onFailure { lastError = it }
                }
                AssetType.CRYPTO -> {
                    val cryptoSymbol = symbol
                    if (cryptoSymbol == null) {
                        failureRecorded = true
                        stats.recordFailure(SyncErrorType.DATA)
                        return
                    }
                    // 理财/锁仓产品（LD 前缀）不再在这里预跳过：LD 与底层资产 1:1 可赎回，
                    // 行情门面会剥掉 LD 按底层交易对取价（见 BinanceAPI.earnUnderlyingPair）——
                    // 从前在这里跳过 + 导入时 purchasePrice = 0，这些持仓的市值恒为 0，
                    // 总资产少算整整一块。剥不掉底层的（如稳定币壳 LDUSDT）由门面返回
                    // NonTradableAssetException，落到下面的记账分支同样不计失败——
                    // 「能不能取价」的判定只此一处（门面），同步侧别再复制第二份。
                    val cryptoResult = UnifiedPriceService.getPrice(
                        cryptoSymbol,
                        AssetType.CRYPTO,
                        forceRefresh = forceRefresh,
                        batchResult = batchResult,
                        networkAvailable = networkAvailable
                    )

                    cryptoResult.onSuccess { price ->
                        when (price) {
                            is com.example.stockdiary.network.model.AssetPrice.Crypto -> {
                                withContext(NonCancellable) {
                                    priceWriter.write(PendingPriceWrite(cryptoSymbol, price.data.price))
                                }
                                isSuccess = true
                            }
                            else -> { lastError = IllegalStateException("返回类型不匹配") }
                        }
                    }.onFailure { lastError = it }
                }
                else -> {
                    // 银行存款/负债等没有行情可同步的类型：不算失败，也不该悄无声息
                    skipReason = "非行情类型"; stats.countSkip("非行情类型")
                    return
                }
            }

            if (isSuccess) {
                stats.recordSuccess()
            } else if (com.example.stockdiary.network.QuoteSuspendedException.isSuspended(lastError)) {
                // 停牌不是同步失败：计进 failedCount 会让 PriceSyncWorker 走到
                // 「同步多次失败，请检查网络」的通知，用户被一条根本不是故障的事骚扰。
                // 价格保持上次的值不动（AssetRepository 也不会写入 0 价）。
                AppLogger.i("跳过停牌标的: ${asset.name}（${lastError?.message}）", TAG)
                skipReason = "停牌"; stats.countSkip("停牌")
            } else if (com.example.stockdiary.network.NonTradableAssetException.isNonTradable(lastError)) {
                // 币安理财/锁仓产品剥不掉底层交易对（如稳定币壳 LDUSDT）：不是网络故障、
                // 也不是暂时停牌，而是本就不可取价。有底层的 LD 已改按底层 1:1 计价
                // （见 BinanceAPI.earnUnderlyingPair），走到这里只剩真正无行情的。
                // 手动刷新等没走上面 CRYPTO 分支短路的路径也会走到这里，同样不计失败，
                // 价格保持上次的值。
                AppLogger.i("跳过不可交易标的: ${asset.name}（${lastError?.message}）", TAG)
                skipReason = "不可交易"; stats.countSkip("不可交易")
            } else if (com.example.stockdiary.network.BinanceHostUnavailableException.isUnavailable(lastError)) {
                // 币安主机级熔断（BinanceHostGate）：临时暂停访问、不是故障，也不是
                // 标的的问题。熔断期内每个加密货币都会走到这里，计进 failedCount
                // 会推高「同步多次失败，请检查网络」通知，用户被一条与真实原因
                // 无关的提示骚扰。价格保持上次的值，冷却过去后自然恢复。
                AppLogger.i("币安接口临时熔断，跳过: ${asset.name}（${lastError?.message}）", TAG)
                skipReason = "币安熔断"; stats.countSkip("币安熔断")
            } else if (com.example.stockdiary.network.StalePriceCacheException.isStaleCache(lastError)) {
                // 断网 / 行情源全挂且 PriceCacheManager 判定旧价已过 TTL：
                // 旧价**不能**重新落库——AssetDao.updatePriceBySymbol 会把 lastSyncTime
                // 刷成「刚刚」，三天前的收盘价就伪装成刚抓的新价。归到「跳过」（不算失败），
                // 库里的旧价原样保留（不清空、不写 0），等网络恢复后自然更新。
                AppLogger.i("过期缓存回落，跳过且不刷新 lastSyncTime: ${asset.name}（${lastError?.message}）", TAG)
                skipReason = "缓存过期"; stats.countSkip("缓存过期")
            } else {
                // 仅当协程仍活跃时才把这次记为同步失败。协程已被取消（切后台 / VM 销毁 /
                // 看门狗超预算）时，lastError 多半是「我们自己放弃」的产物（如币安抓取层把
                // 取消后的超时归为失败），不是真实同步故障——计入会「成功被静默判失败」
                // （真机日志 22:29 那批：2854ms 成功记录后紧接「判失败且无堆栈」）。
                // 已成功取得的价在上面的 isSuccess 分支已记成功，这里只跳过失败计数，避免误伤。
                if (currentCoroutineContext().isActive) {
                    failureRecorded = true
                    stats.recordFailure(SyncStrategy.classifyError(lastError))
                } else {
                    AppLogger.d("协程已取消，跳过失败记账: ${asset.name}", TAG)
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 仅当协程仍活跃时才计为同步失败。已取消说明是「我们放弃」而非真实故障，
            // 不计入失败数（与上面 else 分支同口径，避免取消被误判成失败）。
            // 例：取消后的取消类异常（InterruptedIOException 等）在最上面的 CancellationException
            // 分支之外漏进来的情形，这里同样不记账。
            if (currentCoroutineContext().isActive) {
                AppLogger.w("同步资产 ${asset.name} 失败: ${e.message}", TAG)
                failureRecorded = true
                stats.recordFailure(SyncStrategy.classifyError(e))
            } else {
                AppLogger.d(
                    "协程已取消，跳过异常记账: ${asset.name}（${e::class.simpleName}）",
                    TAG
                )
            }
        } finally {
            // 每个标的一行收尾。读日志时最缺的就是「这个标的最后怎么了」——
            // 从前只有"开始同步 XXX"，结果散在几百行之后的另一条日志里，中间夹着 OkHttp 噪音。
            val outcome = when {
                isSuccess -> "成功"
                skipReason != null -> "跳过($skipReason)"
                else -> "失败"
            }
            // 卡片提示口径：成功 → 清除；真失败 → 置位；跳过 / 被截断 / 取消 → 两边都不动。
            when {
                isSuccess -> stats.markSucceeded(asset.id)
                failureRecorded -> stats.markFailed(asset.id)
            }
            AppLogger.d(
                "[轮次#${stats.roundId}] $outcome ${asset.name}（${symbol ?: "无代码"}）" +
                    " ${System.currentTimeMillis() - startedAt}ms",
                TAG
            )
        }
    }

    /**
     * 导入后校正证券名：行情名非空、且与库存名不同才改成行情名；否则返回 null（不改）。
     *
     * 为什么普通同步不做：用户能在编辑弹窗自定义名称（EditAssetDialog 的 name 字段），
     * 无脑改名会把用户的命名冲掉。只有「导入后同步」显式传 correctNames 才走这里——
     * 那批名字来自 OCR，形近字误识高发（华→怯、科→料），值得按代码对应的行情标准名纠偏。
     */
    internal fun nameCorrectionOrNull(storedName: String, canonicalName: String?): String? =
        canonicalName?.takeIf { it.isNotBlank() && it != storedName }

    private suspend fun correctAssetName(asset: Asset, canonicalName: String?, repository: AssetRepository) {
        val corrected = nameCorrectionOrNull(asset.name, canonicalName) ?: return
        repository.updateNameById(asset.id, corrected)
        AppLogger.i("校正证券名：${asset.symbol} 「${asset.name}」→「$corrected」（按代码取行情标准名）", TAG)
    }

}

