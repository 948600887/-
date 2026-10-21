package com.example.stockdiary.data.dividend

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 手动刷新（forceRefresh=true）时场外基金（天天基金）「要不要重新抓」的判定。
 *
 * 纯函数，便于单测钉死四种组合。
 *
 * 场外基金没有批量入口（天天基金每基金一个 HTML 页面），手动刷新若无视 TTL
 * 把全部 `fund_xxxxxx` 逐个重打，第一只撞 514 限流前就是请求风暴。所以手动刷新时，
 * 场外基金只补抓「TTL 已过期 且 今天还没成功抓过」的；非手动刷新维持原 TTL 语义。
 *
 * @param ttlExpired  该 symbol 的缓存是否已过 TTL（或从未抓过）
 * @param forceRefresh 是否手动刷新（用户点刷新按钮，forceRefresh=true）
 * @param fetchedToday 今天是否成功抓过（见 [isFetchedToday]）
 */
internal fun shouldRefetchOtc(
    ttlExpired: Boolean,
    forceRefresh: Boolean,
    fetchedToday: Boolean
): Boolean = ttlExpired && (!forceRefresh || !fetchedToday)

/**
 * 给定「上次抓取时间戳」，判定它与 [now] 是否落在同一自然日（按设备时区）。
 *
 * 纯函数：接收 [now] 而非 `LocalDate.now()`，便于单测跨日 / 同日边界。
 * 用 LocalDate 的日期部分比较，**不能**只比毫秒差 24h——跨零点刷新的「今天」
 * 必须按日历日算，否则 23:59 抓过、00:01 又被当成「没抓过」重打一遍。
 *
 * @param lastFetchedAt 上次成功抓取的时间戳（markFetched 写入）；null 表示从未抓过
 * @param now 当前时间戳（用于单测注入固定基准）
 */
internal fun isFetchedToday(lastFetchedAt: Long?, now: Long): Boolean {
    val ts = lastFetchedAt ?: return false
    if (ts <= 0L) return false
    val zone = ZoneId.systemDefault()
    val fetchedDay = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return fetchedDay == today
}
