package com.example.stockdiary.data.sync

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.repository.PendingPriceWrite
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.price.ApiManager
import com.example.stockdiary.data.price.SourceStats
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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
    private const val MAX_CONCURRENT_SYNC = 6

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
     * 同步轮次序号（进程内自增）。
     *
     * 一轮同步会在日志里留下上百行（逐标的、每源、每个 OkHttp 请求）。没有轮次 ID 时，
     * 几轮混在同一份日志里根本分不清哪行属于哪一轮，用户报「刚才刷新不对」也无从对起。
     */
    private val syncRoundSeq = AtomicInteger(0)

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
    @Volatile private var activeManualSyncJob: Job? = null

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
            manualSyncMutex.withLock {
                // 登记当前手动同步的 job，供 MainActivity.onStop 退后台取消（见 [cancelActiveManualSync]）。
                // withLock 块是 suspend 的，可安全读 currentCoroutineContext().job（= 调用方
                // viewModelScope 那个 job，取消它会一路取消到 runSync）。finally 清空，避免
                // 泄漏一个已结束的 job 引用、或让下次取消误伤。
                activeManualSyncJob = currentCoroutineContext()[Job]
                try {
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
                } finally {
                    activeManualSyncJob = null
                }
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
        val job = activeManualSyncJob ?: return
        AppLogger.i("取消进行中的手动同步：$reason", TAG)
        job.cancel()
    }

    /** 测试专用复位：清掉在途手动同步句柄（object 是进程内单例，用例之间必须清状态） */
    internal fun resetForTesting() {
        activeManualSyncJob = null
    }

    /** 测试专用读数：当前登记的在途手动同步句柄（无则 null），供取消用例断言登记/清空 */
    internal fun activeManualSyncJobForTesting(): Job? = activeManualSyncJob

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
        // 各数据源的成败统计只反映**本轮**：开头清零，末尾打快照
        val stockManagerForStats = StockDataManager.getInstance(context)
        stockManagerForStats.resetSourceStats()
        // 跳过原因分类计数：汇总里「跳过 127」必须能拆开，否则等于没统计
        val skipReasons = ConcurrentHashMap<String, AtomicInteger>()
        fun countSkip(reason: String) {
            skipReasons.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
        }

        val successCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val totalCount = assets.size
        val errorTypes = ConcurrentLinkedQueue<SyncErrorType>()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)

        val semaphore = Semaphore(MAX_CONCURRENT_SYNC)
        val dividendRepository = DividendRepository.getInstance(context)

        // 价格批量落库器：落价不再逐条 UPDATE，而是攒够一批走一个事务（见 [SyncPriceWriter]）
        val priceWriter = SyncPriceWriter(repository, PRICE_WRITE_BATCH_SIZE)

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
            "价格同步开始 [轮次#$roundId][$syncTrigger]：${totalCount} 个资产，" +
                "forceRefresh=$forceRefresh，" +
                "时间预算=${if (enforceTimeLimit) "${timeLimitMs}ms" else "无"}",
            TAG
        )

        val stocksAndFunds = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
        if (stocksAndFunds.isNotEmpty()) {
            val symbols = stocksAndFunds.mapNotNull { it.symbol }
            // 注意：notifyProgress 是函数类型变量，Kotlin 不允许对函数类型用命名实参，只能按位置传
            notifyProgress(SyncProgress(0, totalCount, "正在获取分红数据..."), true)
            // 分红预取是刷新的配角，只划一段子预算（见 SyncBudget），
            // 剩下的留给价格同步——用户点刷新真正要看的是行情
            val prefetchBudget = SyncBudget.prefetchPhaseBudget(timeLimitMs)
            dividendRepository.preFetchDividends(symbols, forceRefresh, prefetchBudget)
            AppLogger.i("分红预取阶段结束，开始逐标的价格同步", TAG)
        }

        withHeartbeat(
            tag = TAG,
            phase = "价格同步",
            status = {
                val doing = inFlightAssets.take(3).joinToString("、")
                "已完成 ${completedCount.get()}/$totalCount" +
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
            val batchPriceMap: Map<String, AssetPrice> = if (batchStockCodes.isNotEmpty()) {
                UnifiedPriceService.prewarmStockPrices(batchStockCodes, forceRefresh, networkAvailable)
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            AppLogger.i("批量预取 STOCK/场内基金 ${batchStockCodes.size} 个代码，命中 ${batchPriceMap.size} 个", TAG)
            // 整体 0 命中且批里有普通股票：不是「这些代码此源不支持」，而是源在返回空报价。
            // 真机日志坐实过：新浪 44 次请求全 41 字节、0 成功，却因为逐只被判成符号级
            // （代码不在此源支持范围）而熔断器永不开，导致每轮每个标的白打一次新浪。
            // 喂一次源级失败，让熔断器看见（阈值 5 次才禁，半开探测自动恢复）。
            if (batchPriceMap.isEmpty()) {
                stockManager.reportBatchTotalMiss(ApiManager.API_SINA, batchStockCodes)
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
            val cryptoPriceMap: Map<String, AssetPrice> =
                if (batchCryptoCodes.isNotEmpty() && networkAvailable) {
                    UnifiedPriceService.prewarmCryptoPrices(batchCryptoCodes, forceRefresh)
                        .getOrDefault(emptyMap())
                } else {
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
                            if (truncated.compareAndSet(false, true)) {
                                AppLogger.w(
                                    "已达时间预算（${timeLimitMs}ms），后续未开始的标的本次跳过",
                                    TAG
                                )
                            }
                            return@withPermit
                        }
                        val label = "${asset.name}（${asset.symbol ?: "无代码"}）".take(24)
                        AppLogger.d("开始同步 $label", TAG)
                        inFlightAssets.add(label)
                        try {
                        syncSingleAsset(
                            asset,
                            repository,
                            priceWriter,
                            successCount,
                            failedCount,
                            errorTypes,
                            forceRefresh,
                            correctNames,
                            batchResult = batchPriceMap[asset.symbol] ?: cryptoPriceMap[asset.symbol],
                            networkAvailable = networkAvailable,
                            onSkip = ::countSkip
                        )
                        } finally {
                            inFlightAssets.remove(label)
                        }
                        val completed = completedCount.incrementAndGet()
                        // 收尾那一次强制发出：否则标题栏会停在「103 / 106」这类中间态
                        notifyProgress(
                            SyncProgress(completed, totalCount, "同步 ${asset.name}..."),
                            completed >= totalCount
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
                            if (truncated.compareAndSet(false, true)) {
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
                }
            }
        }

        val networkErrorCount = errorTypes.count { it == SyncErrorType.NETWORK }
        val serverErrorCount = errorTypes.count { it == SyncErrorType.SERVER }
        val dataErrorCount = errorTypes.count { it == SyncErrorType.DATA }
        val unknownErrorCount = errorTypes.count { it == SyncErrorType.UNKNOWN }
        val dominantErrorType = errorTypes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        // ===== 轮次末的结构化汇总 =====
        // 排障时最先想知道的三件事——「这轮跑了多少、卡在哪、哪个数据源在掉链子」——
        // 从前全散落在上百行里，只能靠人肉 grep 才能拼出来（真机日志分析时为确认
        // "新浪是否全挂"连跑多次 grep -c）。现在一轮一段，打开日志尾部就能看完。
        logRoundSummary(
            roundId = roundId,
            trigger = syncTrigger,
            total = totalCount,
            success = successCount.get(),
            failed = failedCount.get(),
            truncated = truncated.get(),
            elapsedMs = System.currentTimeMillis() - startTime,
            skipReasons = skipReasons.mapValues { it.value.get() },
            sourceStats = stockManagerForStats.sourceStatsSnapshot()
        )

        SyncResult(
            successCount = successCount.get(),
            failedCount = failedCount.get(),
            networkErrorCount = networkErrorCount,
            serverErrorCount = serverErrorCount,
            dataErrorCount = dataErrorCount,
            unknownErrorCount = unknownErrorCount,
            dominantErrorType = dominantErrorType,
            truncatedByTimeLimit = truncated.get(),
            skippedByReason = skipReasons.mapValues { it.value.get() }
        ).also {
            AppLogger.i(
                "价格同步结束 [轮次#$roundId]：成功 ${it.successCount}，失败 ${it.failedCount}，" +
                    "超时截断=${it.truncatedByTimeLimit}",
                TAG
            )
        }
    }

    /**
     * 打印一轮同步的结构化汇总（单行，用 ` | ` 分段，便于按行检索）。
     *
     * 设计要点：
     * - **跳过必须拆原因**：只说"跳过 127"等于没统计。币安熔断 / 退市 / 停牌 /
     *   不可交易 / 非行情类型各自多少，决定了"该不该管"。
     * - **数据源按三个出口计数**：成功 / 源级失败 / 符号级失败。这一行直接回答
     *   "某个源是不是全挂了"——真机日志里新浪 44 次空报价、0 成功，就是靠人肉
     *   grep -c 才看出来的，现在轮次自己会说。
     */
    private fun logRoundSummary(
        roundId: Int,
        trigger: String,
        total: Int,
        success: Int,
        failed: Int,
        truncated: Boolean,
        elapsedMs: Long,
        skipReasons: Map<String, Int>,
        sourceStats: List<SourceStats>
    ) {
        val skipped = skipReasons.values.sum()
        val skipText = if (skipReasons.isEmpty()) {
            "无"
        } else {
            skipReasons.entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key} ${it.value}" }
        }
        val sourceText = if (sourceStats.isEmpty()) {
            "无"
        } else {
            sourceStats.joinToString(" · ") { s ->
                "${s.source} 成${s.success}/源败${s.sourceLevelFailure}/符败${s.symbolLevelFailure}"
            }
        }
        val summary = "=== 同步轮次 #$roundId 汇总 [$trigger] === | " +
            "资产 $total：成功 $success / 失败 $failed / 跳过 $skipped（$skipText） | " +
            "数据源：$sourceText | " +
            "超时截断=$truncated · 总耗时 ${elapsedMs / 1000.0}s"
        // 存一份给诊断报告头部用，再照常打日志
        lastRoundSummary = summary
        AppLogger.i(summary, TAG)
    }

    /**
     * 同步期的价格批量落库器。
     *
     * ## 为什么必须有它
     *
     * 见 [PRICE_WRITE_BATCH_SIZE]：Assets 表每写一行 Room 就整表重查重发一次，
     * 逐只标的单独 UPDATE 会让「一次刷新 = N 次整页重组」。这里把拿到的价先攒在缓冲区，
     * 攒够一批才用一个事务写下去，Room 的失效通知次数约为原来的 1/8。
     *
     * ## 失败语义（刻意与改造前对齐）
     *
     * 凑满一批触发的 flush 是**在调用方（[syncSingleAsset]）自己的协程里同步执行**的，
     * 落库异常照旧冒到 `syncSingleAsset` 的 catch —— 与改造前「写库失败算这个标的失败」
     * 的语义一致，不会悄悄吞掉一次写库故障。只有收尾那次 [flushAll] 无法归属到单个标的，
     * 才退化为记 WARN。
     *
     * ## 并发
     *
     * 三个并发标的都可能同时放价。缓冲区用无锁队列；flush 用 [Mutex] 串行：
     * 已在 flush 中的协程会把缓冲区**一次抽干**（含别的协程刚放进去的），
     * 后来的协程用 [Mutex.tryLock] 抢不到锁就直接返回——它不该在信号量名额里
     * 为一个已经在进行的写操作空等。
     */
    private class SyncPriceWriter(
        private val repository: AssetRepository,
        private val batchSize: Int
    ) {
        private val pending = ConcurrentLinkedQueue<PendingPriceWrite>()
        private val flushMutex = Mutex()

        /** 放入一条落价意图；缓冲区够大就顺手 flush 一批（可能抛，交由调用方的 catch 记账） */
        suspend fun write(write: PendingPriceWrite) {
            pending.offer(write)
            flushIfBatchFull()
        }

        /** 收尾：把缓冲区里剩下的全写掉。同步被取消时也要调用，否则会丢价 */
        suspend fun flushAll() {
            flushMutex.withLock { drainAllLocked() }
        }

        private suspend fun flushIfBatchFull() {
            if (pending.size < batchSize) return
            if (!flushMutex.tryLock()) return
            try {
                drainAllLocked()
            } finally {
                flushMutex.unlock()
            }
        }

        /** 调用方必须持有 [flushMutex]。循环抽干：别的协程可能在 flush 期间又放进了新的 */
        private suspend fun drainAllLocked() {
            while (true) {
                val batch = ArrayList<PendingPriceWrite>(batchSize)
                repeat(batchSize) { pending.poll()?.let(batch::add) }
                if (batch.isEmpty()) return
                repository.updatePricesInTransaction(batch)
            }
        }
    }

    private suspend fun syncSingleAsset(
        asset: Asset,
        repository: AssetRepository,
        /** 落价出口：拿到的价交给它攒批，不再逐条 UPDATE（见 [SyncPriceWriter]） */
        priceWriter: SyncPriceWriter,
        successCount: AtomicInteger,
        failedCount: AtomicInteger,
        errorTypes: ConcurrentLinkedQueue<SyncErrorType>,
        forceRefresh: Boolean,
        correctNames: Boolean,
        /** 批量预取回填的现成价；非 null 时 STOCK/场内基金分支直接采信，跳过逐标的网络请求（P3） */
        batchResult: AssetPrice? = null,
        /** 同步起点一次性算好的网络可用性（P4）：非 null 时不再逐标的读 ConnectivityManager */
        networkAvailable: Boolean? = null,
        /** 本轮轮次 ID，只用于日志归属 */
        roundId: Int = 0,
        /** 跳过原因回调（退市/停牌/币安熔断…），供轮次末汇总分类计数 */
        onSkip: (String) -> Unit = {}
    ) {
        val symbol = asset.symbol
        val startedAt = System.currentTimeMillis()
        var skipReason: String? = null

        var isSuccess = false
        var lastError: Throwable? = null

        try {
            // 退市标的（400xxx/420xxx 老三板）没有任何行情源可取：UI 已显示「退市」，
            // 同步侧再为它轮询三源只是白烧请求额度，还会把数据源失败计数推高
            // （累计到阈值会「跳过数据源（失败次数过多）」禁用 30 分钟，连累正常标的）。
            // 必须走 isDelistedForSync 而不是 isDelisted：后者的「6 位数字 + 400/420 前缀」
            // 和天天基金场外代码段是重叠的——400030（东方添益债券）、420102 都是用户
            // 持仓里的真基金。误杀后不发行情请求、不计失败，价格永久冻结且用户端零信号，
            // 比没有豁免更糟。isDelistedForSync 会放行 fund_ 前缀的场外份额，
            // 同时仍能认出老三板真退市股（bj400264 / sh400123）。
            if ((asset.type == AssetType.STOCK || asset.type == AssetType.FUND) &&
                StockCodeManager.isDelistedForSync(symbol)
            ) {
                AppLogger.i("跳过退市标的: ${asset.name}（$symbol，无行情源）", TAG)
                skipReason = "退市"; onSkip("退市")
                return
            }

            when (asset.type) {
                AssetType.FUND -> {
                    val fundCode = symbol?.removePrefix("fund_")
                    if (fundCode == null) {
                        failedCount.incrementAndGet()
                        errorTypes.offer(SyncErrorType.DATA)
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
                                    priceWriter.write(PendingPriceWrite(symbol, price.data.price))
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
                                        priceWriter.write(
                                            PendingPriceWrite(symbol, price.data.price, price.data.millionCopiesIncome)
                                        )
                                        isSuccess = true
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
                        failedCount.incrementAndGet()
                        errorTypes.offer(SyncErrorType.DATA)
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
                                priceWriter.write(PendingPriceWrite(stockSymbol, price.data.price))
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
                        failedCount.incrementAndGet()
                        errorTypes.offer(SyncErrorType.DATA)
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
                                priceWriter.write(PendingPriceWrite(cryptoSymbol, price.data.price))
                                isSuccess = true
                            }
                            else -> { lastError = IllegalStateException("返回类型不匹配") }
                        }
                    }.onFailure { lastError = it }
                }
                else -> {
                    // 银行存款/负债等没有行情可同步的类型：不算失败，也不该悄无声息
                    skipReason = "非行情类型"; onSkip("非行情类型")
                    return
                }
            }

            if (isSuccess) {
                successCount.incrementAndGet()
            } else if (com.example.stockdiary.network.QuoteSuspendedException.isSuspended(lastError)) {
                // 停牌不是同步失败：计进 failedCount 会让 PriceSyncWorker 走到
                // 「同步多次失败，请检查网络」的通知，用户被一条根本不是故障的事骚扰。
                // 价格保持上次的值不动（AssetRepository 也不会写入 0 价）。
                AppLogger.i("跳过停牌标的: ${asset.name}（${lastError?.message}）", TAG)
                skipReason = "停牌"; onSkip("停牌")
            } else if (com.example.stockdiary.network.NonTradableAssetException.isNonTradable(lastError)) {
                // 币安理财/锁仓产品剥不掉底层交易对（如稳定币壳 LDUSDT）：不是网络故障、
                // 也不是暂时停牌，而是本就不可取价。有底层的 LD 已改按底层 1:1 计价
                // （见 BinanceAPI.earnUnderlyingPair），走到这里只剩真正无行情的。
                // 手动刷新等没走上面 CRYPTO 分支短路的路径也会走到这里，同样不计失败，
                // 价格保持上次的值。
                AppLogger.i("跳过不可交易标的: ${asset.name}（${lastError?.message}）", TAG)
                skipReason = "不可交易"; onSkip("不可交易")
            } else if (com.example.stockdiary.network.BinanceHostUnavailableException.isUnavailable(lastError)) {
                // 币安主机级熔断（BinanceHostGate）：临时暂停访问、不是故障，也不是
                // 标的的问题。熔断期内每个加密货币都会走到这里，计进 failedCount
                // 会推高「同步多次失败，请检查网络」通知，用户被一条与真实原因
                // 无关的提示骚扰。价格保持上次的值，冷却过去后自然恢复。
                AppLogger.i("币安接口临时熔断，跳过: ${asset.name}（${lastError?.message}）", TAG)
                skipReason = "币安熔断"; onSkip("币安熔断")
            } else {
                failedCount.incrementAndGet()
                errorTypes.offer(SyncStrategy.classifyError(lastError))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("同步资产 ${asset.name} 失败: ${e.message}", TAG)
            failedCount.incrementAndGet()
            errorTypes.offer(SyncStrategy.classifyError(e))
        } finally {
            // 每个标的一行收尾。读日志时最缺的就是「这个标的最后怎么了」——
            // 从前只有"开始同步 XXX"，结果散在几百行之后的另一条日志里，中间夹着 OkHttp 噪音。
            val outcome = when {
                isSuccess -> "成功"
                skipReason != null -> "跳过($skipReason)"
                else -> "失败"
            }
            AppLogger.d(
                "[轮次#$roundId] $outcome ${asset.name}（${symbol ?: "无代码"}）" +
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

data class SyncProgress(
    val current: Int,
    val total: Int,
    val message: String
)

data class SyncResult(
    val successCount: Int,
    val failedCount: Int,
    val networkErrorCount: Int = 0,
    val serverErrorCount: Int = 0,
    val dataErrorCount: Int = 0,
    val unknownErrorCount: Int = 0,
    val dominantErrorType: SyncErrorType? = null,
    /** 是否因接近后台任务时间上限而提前结束（仍有未同步的资产） */
    val truncatedByTimeLimit: Boolean = false,
    /**
     * 本轮各类「跳过」的计数（键为原因，如「币安熔断」→ 130、「退市」→ 1）。
     *
     * 必须带回给 UI：只报「成功 107」的话，用户看不到还有 130 个加密货币因行情源
     * 不可达压根没更新（诊断日志里的典型一轮：成功 107 / 跳过 131（币安熔断 130）），
     * 会以为刷新成功却没生效、于是反复去点刷新——每点一次又白等一轮。
     */
    val skippedByReason: Map<String, Int> = emptyMap()
) {
    val hasRetryableErrors: Boolean
        get() = networkErrorCount > 0 || serverErrorCount > 0 || unknownErrorCount > 0

    /**
     * 把 [skippedByReason] 渲染成给用户看的一句补充（供刷新结果提示拼接）。
     *
     * 只对「用户会困惑」的两类说话：
     * - 行情源不可达（币安熔断）：这些标的价格**压根没更新**，不说明的话用户会以为
     *   刷新成功却没生效；
     * - 退市/停牌/不可交易：这些本来就没有新价，说明一下免得被当成失败。
     *
     * 其余原因（如「非行情类型」）用户不关心，不占用提示篇幅。
     */
    val skippedHint: String
        get() {
            if (skippedByReason.isEmpty()) return ""
            val gated = skippedByReason["币安熔断"] ?: 0
            val others = skippedByReason.filterKeys { it != "币安熔断" }.values.sum()
            return buildString {
                if (gated > 0) append("；$gated 个加密货币行情源不可达，本次未更新")
                if (others > 0) append("；$others 个无需更新（退市/停牌等）")
            }
        }
}

/**
 * 看门狗取消在途子任务后，收集方（父协程）该不该「吞掉这次 [CancellationException] 继续收尾」。
 *
 * 纯函数（只取决于入参），便于单测钉死判定，不碰协程/时钟：
 * - `parentActive = true`：只有子任务被看门狗砍了、父协程还活着 → 返回 true，调用方吞掉异常、
 *   继续收尾其余标的，最终照常返回带 `truncatedByTimeLimit=true` 的 [SyncResult]；
 * - `parentActive = false`：父协程也死了（退后台取消 / VM 销毁 / 看门狗自己被外部取消）→
 *   返回 false，调用方必须**原样重抛**（项目铁律：取消不吞），让取消一路传上去。
 */
internal fun shouldContinueAfterChildCancel(parentActive: Boolean): Boolean = parentActive
