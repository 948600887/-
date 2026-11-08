package com.example.stockdiary.data.fund

import com.example.stockdiary.database.FundNavCache
import com.example.stockdiary.database.FundNavCacheDao
import com.example.stockdiary.network.FundNavCacheStore
import com.example.stockdiary.network.FundNavSnapshot

/**
 * [FundNavCacheStore] 的 Room 实现：只做 DAO 转发与实体 ↔ 纯模型映射，不做业务判断。
 *
 * 放在 data 层，是为了让 Room（[FundNavCacheDao]）这个具体存储实现留在数据层，
 * 网络层的 [com.example.stockdiary.network.FundAPI] 只依赖端口接口与 [FundNavSnapshot]。
 */
class FundNavCacheStoreImpl(
    private val dao: FundNavCacheDao
) : FundNavCacheStore {

    override suspend fun get(code: String): FundNavSnapshot? =
        dao.get(code)?.let { row ->
            FundNavSnapshot(
                code = row.code,
                name = row.name,
                nav = row.nav,
                changePercent = row.changePercent,
                navDate = row.navDate,
                millionCopiesIncome = row.millionCopiesIncome,
                fetchedAt = row.fetchedAt
            )
        }

    override suspend fun upsert(row: FundNavSnapshot) {
        dao.upsert(
            FundNavCache(
                code = row.code,
                name = row.name,
                nav = row.nav,
                changePercent = row.changePercent,
                navDate = row.navDate,
                millionCopiesIncome = row.millionCopiesIncome,
                fetchedAt = row.fetchedAt
            )
        )
    }

    override suspend fun clear() = dao.clear()
}
