package com.example.stockdiary.network

import com.example.stockdiary.database.FundNavCache

/**
 * 基金净值持久化端口的**契约方**（由网络层定义、data 层实现）。
 *
 * 之前 [FundAPI] 在 `init(context)` 里直接 `StockDatabase.getInstance(...).fundNavCacheDao()`，
 * 网络层因此硬依赖 Room 与具体 DAO：单测没法替换持久化实现，也挡住了「以后换存储介质」。
 * 现在网络层只认这三个方法，具体落库由 `data/fund/FundNavCacheStoreImpl` 提供；
 * 未注入时 [FundAPI] 整体降级为纯内存缓存（行为与之前 DAO 注入失败时一致）。
 */
interface FundNavCacheStore {

    /** 读取某只基金的净值缓存行；读不到返回 null */
    suspend fun get(code: String): FundNavCache?

    /** 写入/更新一行净值缓存（新旧比较等业务校验在 FundAPI 内完成，不搬到存储层） */
    suspend fun upsert(row: FundNavCache)

    /** 清空全部净值缓存（「清除所有数据」用） */
    suspend fun clear()
}
