package com.example.stockdiary.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * [FundNavCache] 的读写接口。
 *
 * 所有方法都是 suspend（Room 强制 IO 线程）；调用方
 * [com.example.stockdiary.network.FundAPI] 只在注入成功时才用得到它，
 * 未注入时整条持久化链路静默降级为「纯内存缓存」，绝不抛异常。
 */
@Dao
interface FundNavCacheDao {

    /** 取一只基金的持久化净值；没缓存过返回 null。 */
    @Query("SELECT * FROM fund_nav_cache WHERE code = :code LIMIT 1")
    suspend fun get(code: String): FundNavCache?

    /** 批量取（预热 / 批量同步用）。 */
    @Query("SELECT * FROM fund_nav_cache WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<FundNavCache>

    /** 按净值公布日取（排查「库里到底是几号的净值」用）。 */
    @Query("SELECT * FROM fund_nav_cache WHERE navDate = :date")
    suspend fun getByNavDate(date: String): List<FundNavCache>

    /**
     * 写入一条净值缓存；同代码已存在则整行覆盖。
     *
     * 「不要用更旧的净值覆盖更新的」由调用方判断：Room 的 Upsert 只看主键，
     * 判断新旧需要读一次旧行，放在调用方可以复用已经读过的那一行，省一次查询。
     */
    @Upsert
    suspend fun upsert(row: FundNavCache)

    @Query("DELETE FROM fund_nav_cache WHERE code = :code")
    suspend fun delete(code: String)

    /** 清空整表（「清除所有数据」用）。 */
    @Query("DELETE FROM fund_nav_cache")
    suspend fun clear()
}
