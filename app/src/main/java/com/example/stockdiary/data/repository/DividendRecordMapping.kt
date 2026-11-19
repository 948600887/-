package com.example.stockdiary.data.repository

import com.example.stockdiary.data.dividend.DividendData
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.Dividend

/**
 * 分红记录映射与去重（纯函数，不碰 Context / Room / 网络）。
 *
 * 从 [DividendRepository] 外提：三处读取点（缓存恢复、抓取后合并、抓取失败兜底）
 * 必须用同一套口径，否则同一条记录在日历与进度条上会长出两个版本。
 */

/**
 * 数据库行 → [DividendRecord]。
 *
 * 三处读取点（缓存恢复、抓取后合并、抓取失败兜底）必须是同一套口径。
 */
internal fun dividendRecordsFromDb(dbRecords: List<Dividend>): List<DividendRecord> =
    dbRecords.map { dbDiv ->
        DividendRecord(
            exDividendDate = dbDiv.date,
            dividendPerShare = dbDiv.amount.toString(),
            announceDate = "",
            fiscalYear = dbDiv.date.take(4),
            paymentDate = dbDiv.payDate
        )
    }

/** 合并增量分红记录到现有数据中，按除息日去重 */
internal fun mergeDividendRecords(
    existing: List<DividendRecord>,
    newData: List<DividendRecord>
): List<DividendRecord> {
    val existingDates = existing.map { it.exDividendDate }.toSet()
    val uniqueNewRecords = newData.filter { it.exDividendDate !in existingDates }
    return existing + uniqueNewRecords
}

/**
 * 接口层 [DividendData] → 领域层 [DividendRecord]。
 *
 * 与逐条路径 `fetchFromEastmoneyWithFormat` 里的映射是同一套口径:
 * 东财的 `PRETAX_BONUS_RMB` 实际是「每 10 股」分红，与应用内 `dividendPerShare` 单位一致，
 * 不做换算。抽成扩展函数是为了让批量路径和逐条路径不可能各写一份而对不上 ——
 * 分红金额差一位就是一个数量级，而且是静默的。
 */
internal fun DividendData.toDividendRecord(): DividendRecord = DividendRecord(
    exDividendDate = date,
    dividendPerShare = dividendPerShare.toString(),
    announceDate = reportDate ?: "",
    // date 理论上不会为空（mapDividendItem 里已挡掉空除息日），
    // 但仍不能直接 substring(0, 4)：一旦为空会 StringIndexOutOfBounds，
    // 而这里在批量路径上，一次异常会把整批预取全废掉
    fiscalYear = date.take(4),
    paymentDate = paymentDate
)
