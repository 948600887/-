package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 场外基金净值的**持久化**缓存（一张表一只基金，主键是纯数字代码）。
 *
 * ## 为什么要有这张表
 *
 * 刷新一只场外基金要下载天天基金 `pingzhongdata/{code}.js` —— 完整历史净值，
 * 实测 **0.5~2MB**，而我们要的只是最新一个数。106 个持仓乘上同步并发数，
 * 峰值内存足以让进程被系统回收（用户看到的就是「点刷新黑屏然后闪退」，
 * 且零崩溃报告）。
 *
 * [com.example.stockdiary.network.FundAPI] 原来只有一张**进程内** 5 分钟
 * TTL 的 `ConcurrentHashMap` 缓存。进程一被杀，缓存清空，下次刷新全部重下
 * —— 于是「刷新 → 内存爆 → 进程被杀 → 缓存全丢 → 再刷新」的死亡螺旋。
 *
 * 本表把净值落进 Room，进程死了也还在，下次刷新直接读库复用，零下载。
 *
 * ## 主键为什么是纯数字代码
 *
 * [FundAPI] 从头到尾只见过纯数字代码（`000306`），从不接触资产表的
 * `fund_000306` 形态：调用方先把 `fund_` 前缀剥掉才传进来。用纯代码做主键
 * 才对得上，也免去每次读写再做一次前缀转换（转换错了就永远读不到、每次重下）。
 *
 * ## [navDate] 是唯一的复用依据
 *
 * 光有净值数字无法判断「这是昨天的还是今天的」—— 而跨进程复用恰恰只需要
 * 这个判断。所以 [navDate]（净值公布日，格式 `yyyy-MM-dd`）是本表的灵魂字段，
 * 也正因如此它 NOT NULL、且单独建了索引。
 *
 * @property code 纯数字基金代码，如 `000306`
 * @property name 基金名称；复用时必须非空（行情门面会以空名为无效数据丢弃）
 * @property nav 单位净值；货币基金恒为 1.0（收益看 [millionCopiesIncome]）
 * @property changePercent 日涨跌幅，可能缺失
 * @property navDate 净值公布日，格式 `yyyy-MM-dd`
 * @property millionCopiesIncome 每万份收益（货币基金专用，必须一并持久化）
 * @property fetchedAt 写入时间戳（毫秒），用于同一自然日内的下载节流
 */
@Entity(tableName = "fund_nav_cache", indices = [Index(value = ["navDate"])])
data class FundNavCache(
    @PrimaryKey val code: String,
    val name: String,
    val nav: Double,
    val changePercent: Double? = null,
    val navDate: String,
    val millionCopiesIncome: Double? = null,
    val fetchedAt: Long = 0L
)
