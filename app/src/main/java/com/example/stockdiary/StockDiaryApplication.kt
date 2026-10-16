package com.example.stockdiary

import android.app.Application
import com.example.stockdiary.data.PriceSyncScheduler
import com.example.stockdiary.data.SnapshotScheduler
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.CrashRecoveryManager
import com.example.stockdiary.utils.GlobalExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDate
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
        // 崩溃处理必须挂在 Application 级：只在 MainActivity 初始化时，启动早期崩溃无人记录。
        // GlobalExceptionHandler.initialize 有 isInitialized 幂等保护，MainActivity 重复调用无副作用。
        // 崩溃详情（含完整堆栈）由 handler 内部直接 recordCrash，这里不需要再传 onCrash。
        GlobalExceptionHandler.initialize(this)
        // 伴生 init 只设置 appContext；实例 init 才会真正打开 SharedPreferences 并加载崩溃状态，
        // 漏掉它 recordCrash 会因 prefs=null 静默空转（崩溃恢复功能等于不存在）。
        CrashRecoveryManager.init(this)
        CrashRecoveryManager.getInstance().init(this)

        AppLogger.i("应用启动，DI容器已初始化", "StockDiaryApplication")

        applicationScope.launch {
            scheduleDailySnapshot()
            fillMissingSnapshots()
            scheduleAutoPriceSync()
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

    private suspend fun fillMissingSnapshots() {
        try {
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
                var currentDate = latestDate.plusDays(1)
                while (!currentDate.isAfter(today)) {
                    val dateStr = currentDate.toString()
                    AppLogger.i("补全缺失快照: $dateStr", "StockDiaryApplication")
                    try {
                        repository.createSnapshot(dateStr)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("补全 $dateStr 快照失败: ${e.message}", e, "StockDiaryApplication")
                    }
                    currentDate = currentDate.plusDays(1)
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