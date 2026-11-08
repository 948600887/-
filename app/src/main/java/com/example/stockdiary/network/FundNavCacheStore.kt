package com.example.stockdiary.network

/**
 * 净值缓存行的**纯数据模型**（网络层端口契约用）。
 *
 * 之前端口的读写类型直接是 `database.FundNavCache` 实体，网络层因此顺着端口把
 * Room 实体也拖了进来：换存储介质、或在单测里给假存储，都得先认识数据库实体。
 * 现在网络层只认这个模型，实体 ↔ 模型的映射由 data 层的
 * `data/fund/FundNavCacheStoreImpl` 负责。
 */
data class FundNavSnapshot(
    val code: String,
    val name: String,
    val nav: Double,
    val changePercent: Double? = null,
    val navDate: String,
    val millionCopiesIncome: Double? = null,
    val fetchedAt: Long = 0L
)

/**
 * 基金净值持久化端口的**契约方**（由网络层定义、data 层实现）。
 *
 * 之前 [FundAPI] 在 `init(context)` 里直接 `StockDatabase.getInstance(...).fundNavCacheDao()`，
 * 网络层因此硬依赖 Room 与具体 DAO：单测没法替换持久化实现，也挡住了「以后换存储介质」。
 * 现在网络层只认这三个方法，具体落库由 `data/fund/FundNavCacheStoreImpl` 提供；
 * 未注入时 [FundAPI] 整体降级为纯内存缓存（行为与之前 DAO 注入失败时一致）。
 */
interface FundNavCacheStore {

    /** 读取某只基金的净值缓存；读不到返回 null */
    suspend fun get(code: String): FundNavSnapshot?

    /** 写入/更新一行净值缓存（新旧比较等业务校验在 FundAPI 内完成，不搬到存储层） */
    suspend fun upsert(row: FundNavSnapshot)

    /** 清空全部净值缓存（「清除所有数据」用） */
    suspend fun clear()
}
