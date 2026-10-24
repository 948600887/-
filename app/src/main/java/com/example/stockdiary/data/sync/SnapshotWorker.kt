package com.example.stockdiary.data.sync

import com.example.stockdiary.data.repository.AssetRepository
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stockdiary.utils.AppLogger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 每日自动快照。
 *
 * 准入判定见 [shouldCreateAutoSnapshot]：自动**价格**同步默认关闭，用户几天才手动刷
 * 一次时，若无条件建快照，中间每天都是同一批旧价的假数据——月度收益被拉平、波动率
 * 样本被零收益污染。宁可这天没有自动快照（曲线留缺口），也不要假数据。
 *
 * 另外空组合直接跳过：createSnapshot 对空组合同样返回 null，但在这里先拦一道，
 * 免得把「没什么可存」当成失败去 retry（retry 会退避重试，纯属浪费）。
 */
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

            val assets = repository.getAllAssets().first()
            if (assets.none { !it.type.isLiability }) {
                AppLogger.i("没有任何持仓，跳过自动快照（不建 0 值快照）", "SnapshotWorker")
                return Result.success()
            }
            if (!shouldCreateAutoSnapshot(assets, System.currentTimeMillis())) {
                AppLogger.i("多数标的价格不新鲜（未开自动同步且近日无手动刷新），跳过自动快照", "SnapshotWorker")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("自动快照异常: ${e.message}", e, "SnapshotWorker")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "com.example.stockdiary.SnapshotWorker"
    }
}
