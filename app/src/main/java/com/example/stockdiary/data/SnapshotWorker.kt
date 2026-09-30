package com.example.stockdiary.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stockdiary.utils.AppLogger
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SnapshotWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            
            val repository = AssetRepository.getInstance(applicationContext)
            val latestSnapshot = repository.getLatestSnapshot()
            
            if (latestSnapshot?.date == today) {
                AppLogger.i("今天已经有快照，跳过", "SnapshotWorker")
                return Result.success()
            }
            
            val snapshot = repository.createSnapshot(today)
            if (snapshot != null) {
                AppLogger.i("自动快照成功: ${snapshot.date}, 净资产: ¥${snapshot.netAssets}", "SnapshotWorker")
                Result.success()
            } else {
                AppLogger.w("自动快照失败", "SnapshotWorker")
                Result.retry()
            }
        } catch (e: Exception) {
            AppLogger.e("自动快照异常: ${e.message}", e, "SnapshotWorker")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "com.example.stockdiary.SnapshotWorker"
    }
}