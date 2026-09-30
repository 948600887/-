package com.example.stockdiary

import android.app.Application
import com.example.stockdiary.data.PriceSyncScheduler
import com.example.stockdiary.data.SnapshotScheduler
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.CrashRecoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

class StockDiaryApplication : Application() {

    lateinit var container: AppContainer
        private set

    // 应用级协程作用域：SupervisorJob 保证子协程失败不影响其他任务
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        CrashRecoveryManager.init(this)

        AppLogger.i("应用启动，DI容器已初始化", "StockDiaryApplication")

        applicationScope.launch {
            scheduleDailySnapshot()
            fillMissingSnapshots()
            scheduleAutoPriceSync()
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
                repository.createSnapshot(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                return
            }

            val latestDateStr = snapshots.lastOrNull()?.date ?: return
            val latestDate = LocalDate.parse(latestDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val today = LocalDate.now()

            if (latestDate.isBefore(today)) {
                var currentDate = latestDate.plusDays(1)
                while (!currentDate.isAfter(today)) {
                    val dateStr = currentDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
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