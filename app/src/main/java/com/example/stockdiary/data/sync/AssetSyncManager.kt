package com.example.stockdiary.data.sync

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.DividendRepository
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.price.ApiManager
import com.example.stockdiary.data.price.StockDataManager
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.network.model.AssetPrice
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

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
     */
    private const val MAX_CONCURRENT_SYNC = 3

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
     * 场外基金下载的专属并发上限。
     *
     * 场外基金走天天基金 `pingzhongdata/{code}.js`，每只响应 0.5~2MB（完整历史净值）。
     * 这道闸把**同时在途的大文件数**压到最多 [FUND_MAX_CONCURRENT_SYNC] 只，避免峰值内存
     * 把进程顶到被系统回收（静默闪退、零崩溃报告——本项目历史老毛病）。
     *
     * 与 [manualSyncMutex]、总 [Semaphore] 都是**叠加**关系：一个基金任务同时占
     * 一个总信号量名额 + 一个基金专属名额。总并发仍是 3，但其中最多 2 个能是基金下载，
     * 其余名额留给股票/加密，确保 2MB 大文件不会三个一起挤在内存里。
     */
    private const val FUND_MAX_CONCURRENT_SYNC = 2
    private val fundSemaphore = Semaphore(FUND_MAX_CONCURRENT_SYNC)

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
                    // 只关心「快被杀了」的等级，避免后台周期回调刷屏
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        AppLogger.w(
                            "onTrimMemory(level=$level) — 进程面临被系统回收风险，" +
                                "level≥${ComponentCallbacks2.TRIM_MEMORY_COMPLETE} 即临死前通牒",
                            TAG
                        )
                    }
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
        correctNames: Boolean = false
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
                    correctNames = correctNames
                )
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
                correctNames = correctNames
            )
        }
    }

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
        correctNames: Boolean
    ): SyncResult = withContext(Dispatchers.IO) {
        val enforceTimeLimit = timeLimitMs > 0L

        val successCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val totalCount = assets.size
        val errorTypes = ConcurrentLinkedQueue<SyncErrorType>()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)

        val semaphore = Semaphore(MAX_CONCURRENT_SYNC)
        val dividendRepository = DividendRepository.getInstance(context)

        // 进度回调回主线程更新 StateFlow；扇出与续体留在 IO
        val notifyProgress: suspend (SyncProgress) -> Unit = { progress ->
            withContext(Dispatchers.Main) { onProgress(progress) }
        }

        // 心跳要能读出「卡在哪个标的」：进行中的标的在这里登记/注销
        val inFlightAssets = ConcurrentHashMap.newKeySet<String>()

        // 内存水位取证：刷新起点打一帧，并挂上进程级回收回调（只挂一次）。
        // 万一将来仍偶发「点刷新闪退、零崩溃报告」，log 末的 onTrimMemory / 内存水位
        // 就能坐实是 LMK 而非逻辑 bug，省去又一轮瞎猜。
        ensureMemoryTrimLogging(context)
        logMemoryState(context, "同步起点")

        AppLogger.i(
            "价格同步开始：${totalCount} 个资产，forceRefresh=$forceRefresh，" +
                "时间预算=${if (enforceTimeLimit) "${timeLimitMs}ms" else "无"}",
            TAG
        )

        val stocksAndFunds = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }
        if (stocksAndFunds.isNotEmpty()) {
            val symbols = stocksAndFunds.mapNotNull { it.symbol }
            notifyProgress(SyncProgress(0, totalCount, "正在获取分红数据..."))
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
            val batchStockCodes = orderedAssets.mapNotNull { batchStockCode(it) }.distinct()
            val stockManager = StockDataManager.getInstance(context)
            val networkAvailable = stockManager.isNetworkAvailableNow()
            val preferredApi = ApiManager.getPriceApi(context)
            val batchPriceMap: Map<String, AssetPrice> = if (batchStockCodes.isNotEmpty()) {
                UnifiedPriceService.prewarmStockPrices(batchStockCodes, forceRefresh, networkAvailable)
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            AppLogger.i("批量预取 STOCK/场内基金 ${batchStockCodes.size} 个代码，命中 ${batchPriceMap.size} 个", TAG)

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
                            successCount,
                            failedCount,
                            errorTypes,
                            forceRefresh,
                            correctNames,
                            batchResult = batchPriceMap[asset.symbol],
                            networkAvailable = networkAvailable,
                            preferredApi = preferredApi
                        )
                        } finally {
                            inFlightAssets.remove(label)
                        }
                        val completed = completedCount.incrementAndGet()
                        notifyProgress(SyncProgress(completed, totalCount, "同步 ${asset.name}..."))
                    }
                }
            }
            // 等待所有任务完成
            jobs.forEach { it.await() }
        }

        val networkErrorCount = errorTypes.count { it == SyncErrorType.NETWORK }
        val serverErrorCount = errorTypes.count { it == SyncErrorType.SERVER }
        val dataErrorCount = errorTypes.count { it == SyncErrorType.DATA }
        val unknownErrorCount = errorTypes.count { it == SyncErrorType.UNKNOWN }
        val dominantErrorType = errorTypes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        SyncResult(
            successCount = successCount.get(),
            failedCount = failedCount.get(),
            networkErrorCount = networkErrorCount,
            serverErrorCount = serverErrorCount,
            dataErrorCount = dataErrorCount,
            unknownErrorCount = unknownErrorCount,
            dominantErrorType = dominantErrorType,
            truncatedByTimeLimit = truncated.get()
        ).also {
            AppLogger.i(
                "价格同步结束：成功 ${it.successCount}，失败 ${it.failedCount}，" +
                    "超时截断=${it.truncatedByTimeLimit}",
                TAG
            )
        }
    }

    private suspend fun syncSingleAsset(
        asset: Asset,
        repository: AssetRepository,
        successCount: AtomicInteger,
        failedCount: AtomicInteger,
        errorTypes: ConcurrentLinkedQueue<SyncErrorType>,
        forceRefresh: Boolean,
        correctNames: Boolean,
        /** 批量预取回填的现成价；非 null 时 STOCK/场内基金分支直接采信，跳过逐标的网络请求（P3） */
        batchResult: AssetPrice? = null,
        /** 同步起点一次性算好的网络可用性（P4）：非 null 时不再逐标的读 ConnectivityManager */
        networkAvailable: Boolean? = null,
        /** 同步起点一次性读好的偏好数据源（P4）：非 null 时不再逐标的读 SharedPreferences */
        preferredApi: String? = null
    ) {
        val symbol = asset.symbol

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
                            networkAvailable = networkAvailable,
                            preferredApi = preferredApi
                        )
                        result.onSuccess { price ->
                            when (price) {
                                is AssetPrice.Stock -> {
                                    repository.updatePriceBySymbol(symbol, price.data.price)
                                    if (correctNames) correctAssetName(asset, price.data.name, repository)
                                    isSuccess = true
                                }
                                else -> { lastError = IllegalStateException("返回类型不匹配") }
                            }
                        }.onFailure { lastError = it }
                    } else {
                        // 场外基金：使用基金API（天天基金），传纯数字代码。
                        // forceRefresh 强制为 false：场外净值是**日更**的，同一天再刷也拿不到
                        // 更新的数；传 true 会绕过 FundAPI 的 5 分钟缓存 + 今日净值闸，每次手动
                        // 刷新都把 0.5~2MB 历史文件重新下载一遍，正是「点刷新偶发闪退」的残因。
                        // 改 false 让两道缓存都生效，只在该代码当日还没缓存时才真去下载。
                        // 额外的 fundSemaphore 再压一道在途大文件数（见对象级注释）。
                        fundSemaphore.withPermit {
                            val fundResult = UnifiedPriceService.getPrice(
                                pureCode,
                                AssetType.FUND,
                                forceRefresh = false
                            )
                            fundResult.onSuccess { price ->
                                when (price) {
                                    is AssetPrice.Fund -> {
                                        repository.updatePriceBySymbol(symbol, price.data.price, price.data.millionCopiesIncome)
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
                        networkAvailable = networkAvailable,
                        preferredApi = preferredApi
                    )

                    result.onSuccess { price ->
                        when (price) {
                            is AssetPrice.Stock -> {
                                repository.updatePriceBySymbol(stockSymbol, price.data.price)
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
                        forceRefresh = forceRefresh
                    )

                    cryptoResult.onSuccess { price ->
                        when (price) {
                            is com.example.stockdiary.network.model.AssetPrice.Crypto -> {
                                repository.updatePriceBySymbol(cryptoSymbol, price.data.price)
                                isSuccess = true
                            }
                            else -> { lastError = IllegalStateException("返回类型不匹配") }
                        }
                    }.onFailure { lastError = it }
                }
                else -> {
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
            } else if (com.example.stockdiary.network.NonTradableAssetException.isNonTradable(lastError)) {
                // 币安理财/锁仓产品剥不掉底层交易对（如稳定币壳 LDUSDT）：不是网络故障、
                // 也不是暂时停牌，而是本就不可取价。有底层的 LD 已改按底层 1:1 计价
                // （见 BinanceAPI.earnUnderlyingPair），走到这里只剩真正无行情的。
                // 手动刷新等没走上面 CRYPTO 分支短路的路径也会走到这里，同样不计失败，
                // 价格保持上次的值。
                AppLogger.i("跳过不可交易标的: ${asset.name}（${lastError?.message}）", TAG)
            } else if (com.example.stockdiary.network.BinanceHostUnavailableException.isUnavailable(lastError)) {
                // 币安主机级熔断（BinanceHostGate）：临时暂停访问、不是故障，也不是
                // 标的的问题。熔断期内每个加密货币都会走到这里，计进 failedCount
                // 会推高「同步多次失败，请检查网络」通知，用户被一条与真实原因
                // 无关的提示骚扰。价格保持上次的值，冷却过去后自然恢复。
                AppLogger.i("币安接口临时熔断，跳过: ${asset.name}（${lastError?.message}）", TAG)
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
    val truncatedByTimeLimit: Boolean = false
) {
    val hasRetryableErrors: Boolean
        get() = networkErrorCount > 0 || serverErrorCount > 0 || unknownErrorCount > 0
}
