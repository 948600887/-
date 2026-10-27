package com.example.stockdiary.data.sync

import com.example.stockdiary.common.DateFormats
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 同步成功后「要不要存当日快照」的判定 + 落盘动作。
 *
 * 后台 [PriceSyncWorker] 一直有「同步成功→覆盖当日快照」，手动刷新路径从前没有：
 * 用户点一次全量刷新，价格全更新了，但当日的快照曲线点还是旧的，要等下一轮后台
 * Worker 才补上。这里补齐**全量手动刷新**这条路径。
 */

/** 与 [PriceSyncWorker] 同一日期格式（yyyy-MM-dd），别自创 */
private val SNAPSHOT_DATE_FORMATTER: DateTimeFormatter = DateFormats.ISO_DATE_FORMATTER

/**
 * 纯判定：这次同步成功后该不该覆盖当日快照。
 *
 * 门槛与后台 [PriceSyncWorker] 对齐（`successCount > 0 || failedCount == 0`），
 * 再加用户拍板的「**截断不存**」：[SyncResult.truncatedByTimeLimit] 说明这一轮只同步了
 * 一部分标的（被时间预算砍断），此时存快照会把「部分新价 + 部分旧价」固化成当日快照，
 * 污染历史净值曲线——宁可这轮不存，等下次完整刷新或后台 Worker 补。
 *
 * 边界（逐条有单测钉死）：
 * - `truncatedByTimeLimit = true` → false（截断不存，优先级最高，即便全 0 也不存）；
 * - `successCount == 0 && failedCount > 0` → false（整轮全失败，没什么可存）；
 * - `successCount > 0` → true（有标的更新成功，值得固化）；
 * - `failedCount == 0` → true（含全 0 的极端：全退市组合一个都没同步、但也没失败，
 *   覆盖一次同日快照无害——本来就是同一批价）。
 */
fun shouldSaveSnapshotAfterSync(result: SyncResult): Boolean {
    if (result.truncatedByTimeLimit) return false
    return result.successCount > 0 || result.failedCount == 0
}

/**
 * 同步成功后按 [shouldSaveSnapshotAfterSync] 判定，合格则覆盖当日快照。
 *
 * 「同日覆盖、以最后一次为准」由 [AssetRepository.replaceSnapshotForDate] 内部事务保证
 * （先删同日旧快照、再插入），这里**不**自己造去重逻辑。
 *
 * 失败隔离：[AssetRepository.replaceSnapshotForDate] 抛异常只记一笔日志，绝不向上抛——
 * 快照是刷新的附属动作，不能搞崩刷新完成态 / UI / Snackbar。
 * CancellationException 照旧重抛（项目铁律：取消不吞）。
 *
 * @param repository 资产仓库（VM 里现成的 container.assetRepository）
 * @param result 本轮同步结果，仅用于 [shouldSaveSnapshotAfterSync] 判定
 * @param tag 日志 tag（调用方 VM 名）
 */
suspend fun saveTodaySnapshotIfEligible(
    repository: AssetRepository,
    result: SyncResult,
    tag: String
) {
    if (!shouldSaveSnapshotAfterSync(result)) return
    try {
        val today = LocalDate.now().format(SNAPSHOT_DATE_FORMATTER)
        val snapshot = repository.replaceSnapshotForDate(today)
        if (snapshot != null) {
            AppLogger.i("全量刷新后已刷新当日快照: ${snapshot.date}, 净资产: ¥${snapshot.netAssets}", tag)
        } else {
            AppLogger.w("全量刷新后刷新当日快照失败（不影响刷新结果）", tag)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.e("刷新当日快照失败（不影响刷新结果）: ${e.message}", e, tag)
    }
}

/**
 * 导入落库（+ 导入后行情同步）之后刷新当日快照。
 *
 * 为什么需要它：导入改变了资产集合，首页/持仓/日历都会随 Room 自动更新，但历史页只读快照，
 * 不写的话「今天」这个点还停在导入前。导入后的同步（[syncedCount] > 0）已经给新资产补了价，
 * 此刻库里的资产/价格与页面显示完全一致，是写快照最合适的时机。
 *
 * 与 [saveTodaySnapshotIfEligible] 的区别：那不是一轮完整同步（只同步了导入的标的），
 * 不能用 [SyncResult] 的截断口径判定；这里只以「同步确实有成功项」为门槛，
 * 同步失败则不动快照，避免把 0 价固化。
 */
suspend fun refreshTodaySnapshotAfterImport(
    repository: AssetRepository,
    syncedCount: Int,
    tag: String
) {
    if (syncedCount <= 0) return
    try {
        val today = LocalDate.now().format(SNAPSHOT_DATE_FORMATTER)
        val snapshot = repository.replaceSnapshotForDate(today)
        if (snapshot != null) {
            AppLogger.i("导入后已刷新当日快照: ${snapshot.date}, 净资产: ¥${snapshot.netAssets}", tag)
        } else {
            AppLogger.w("导入后刷新当日快照失败（不影响导入结果）", tag)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.e("导入后刷新当日快照失败（不影响导入结果）: ${e.message}", e, tag)
    }
}
