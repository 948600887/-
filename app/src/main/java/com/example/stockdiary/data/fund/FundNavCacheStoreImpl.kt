package com.example.stockdiary.data.fund

import com.example.stockdiary.database.FundNavCache
import com.example.stockdiary.database.FundNavCacheDao
import com.example.stockdiary.network.FundNavCacheStore

/**
 * [FundNavCacheStore] 的 Room 实现：只做 DAO 转发，不做任何业务判断。
 *
 * 放在 data 层，是为了让 Room（[FundNavCacheDao]）这个具体存储实现留在数据层，
 * 网络层的 [com.example.stockdiary.network.FundAPI] 只依赖端口接口。
 */
class FundNavCacheStoreImpl(
    private val dao: FundNavCacheDao
) : FundNavCacheStore {

    override suspend fun get(code: String): FundNavCache? = dao.get(code)

    override suspend fun upsert(row: FundNavCache) = dao.upsert(row)

    override suspend fun clear() = dao.clear()
}
