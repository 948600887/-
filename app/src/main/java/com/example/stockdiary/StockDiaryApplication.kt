package com.example.stockdiary

import android.app.Application
import com.example.stockdiary.data.sync.PriceSyncScheduler
import com.example.stockdiary.data.sync.SnapshotScheduler
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.UnifiedPriceService
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.CrashRecoveryManager
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.GlobalExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException

class StockDiaryApplication : Application() {

    lateinit var container: AppContainer
        private set

    // 应用级协程作用域：SupervisorJob 保证子协程失败不影响其他任务
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 日志尽早初始化：下面 GlobalExceptionHandler.initialize 内部、以及应用启动日志都要用它。
        // 原先 AppLogger.init 放在 MainActivity，导致 Application 阶段的日志早于初始化而被丢弃。
        AppLogger.init(this, debug = BuildConfig.DEBUG)
        container = AppContainer(this)
        // 行情门面初始化：STOCK/场内基金分支委托 data/price/StockDataManager
        // （多源容错策略），必须在第一次取价之前注入，否则该分支返回失败
        UnifiedPriceService.init(this)
        // 场外基金净值持久化缓存：把净值（含公布日）落进 Room，进程被系统回收后
        // 下次刷新直接读库复用，不再重下那份 0.5~2MB 的历史净值文件。
        // 未注入时 FundAPI 自动降级为纯内存缓存，不会 NPE。
        FundAPI.init(this)
        // 崩溃处理必须挂在 Application 级：只在 MainActivity 初始化时，启动早期崩溃无人记录。
        // GlobalExceptionHandler.initialize 有 isInitialized 幂等保护，MainActivity 重复调用无副作用。
        // 崩溃详情（含完整堆栈）由 handler 内部直接 recordCrash，这里不需要再传 onCrash。
        GlobalExceptionHandler.initialize(this)
        // 伴生 init 只设置 appContext；实例 init 才会真正打开 SharedPreferences 并加载崩溃状态，
        // 漏掉它 recordCrash 会因 prefs=null 静默空转（崩溃恢复功能等于不存在）。
        CrashRecoveryManager.init(this)
        CrashRecoveryManager.getInstance().init(this)

        // 读回上次持久化的 live 汇率（毫秒级 SharedPreferences 读，与
        // CrashRecoveryManager.init 同一模式）：此后 getRate 拿到的就是
        // 上次联网结果，离线 / 进程重启不退回静态默认值
        ExchangeRateManager.init(this)

        AppLogger.i("应用启动，DI容器已初始化", "StockDiaryApplication")

        // 汇率刷新独立协程：最坏要等完整网络超时（10s 连接 / 15s 读），
        // 不能排在快照补全与同步调度前面拖慢启动。失败静默降级——
        // 刷新不成功就继续用当前汇率，TTL（6 小时）兜底不会重复打接口
        applicationScope.launch {
            ExchangeRateManager.refresh()
        }

        applicationScope.launch {
            scheduleDailySnapshot()
            fillMissingSnapshots()
            scheduleAutoPriceSync()
            // 一次性修复已存的 LD 锁仓脏符号（旧版导入落库的 LDBCHUSDT 等），
            // 否则这些资产之后每次行情同步都向币安问不存在的交易对 → 永远 400。
            repairLdDirtySymbols()
            // 崩溃日志的磁盘裁剪挪到启动阶段：崩溃现场多一次目录遍历与排序，
            // 在 OOM/StackOverflow 时只会火上浇油。
            GlobalExceptionHandler.pruneCrashFiles()
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }

    private suspend fun scheduleDailySnapshot() {
        try {
            SnapshotScheduler.scheduleDailySnapshot(this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("调度快照失败: ${e.message}", e, "StockDiaryApplication")
        }
    }

    private fun scheduleAutoPriceSync() {
        try {
            if (container.priceSyncPreferencesManager.isAutoSyncEnabled()) {
                PriceSyncScheduler.scheduleDailySync(this)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("调度自动价格同步失败: ${e.message}", e, "StockDiaryApplication")
        }
    }

    /**
     * 一次性修复已存的 LD 锁仓脏符号（旧版币安导入落库的 LDBCHUSDT 等）。
     *
     * 见 [com.example.stockdiary.data.repository.AssetRepository.repairLdDirtySymbols]：
     * 用 SharedPreferences 守卫保证每个安装只跑一次，失败静默降级（下次启动可重试），
     * 绝不因为修复失败而拖垮启动。
     */
    private suspend fun repairLdDirtySymbols() {
        try {
            container.assetRepository.repairLdDirtySymbols()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("修复 LD 脏符号失败: ${e.message}", e, "StockDiaryApplication")
        }
    }

    private suspend fun fillMissingSnapshots() {        try {
            val repository = container.assetRepository
            val snapshots = repository.getAllSnapshots().first()

            if (snapshots.isEmpty()) {
                AppLogger.i("无历史快照，创建今日快照", "StockDiaryApplication")
                // LocalDate.toString() 本身就是 yyyy-MM-dd，不必再走 DateTimeFormatter
                repository.createSnapshot(LocalDate.now().toString())
                return
            }

            val latestDateStr = snapshots.lastOrNull()?.date ?: return
            val latestDate = LocalDate.parse(latestDateStr)
            val today = LocalDate.now()

            if (latestDate.isBefore(today)) {
                // 只补今天一条，绝不逐日回填缺口。
                //
                // 为什么不能逐日补：repository.createSnapshot(date) 内部取的是
                // 「当前资产」（assetDao.getAllAssets().first()），并不按 date
                // 回放历史价格。缺快照的日子如果也补，每天都会拿到同一个
                // 「今天」的净资产——历史净值曲线在缺口段变成一条直线
                // （诊断日志里连续四天 netAssets 一模一样即此症状），
                // 比缺口本身更有害：用户会误以为那几天真的没波动。
                val gapDays = ChronoUnit.DAYS.between(latestDate, today)
                AppLogger.i(
                    "跳过历史快照回填：最新为 $latestDateStr，距今 $gapDays 天（无历史价格，不伪造净值）；已创建今日快照",
                    "StockDiaryApplication"
                )
                try {
                    // LocalDate.toString() 本身就是 yyyy-MM-dd，不必再走 DateTimeFormatter
                    repository.createSnapshot(today.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("补全今日快照失败: ${e.message}", e, "StockDiaryApplication")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("补全快照失败: ${e.message}", e, "StockDiaryApplication")
        }
    }

    companion object {
        lateinit var instance: StockDiaryApplication
            private set
    }
}