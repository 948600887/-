package com.example.stockdiary.data

import com.example.stockdiary.data.repository.shouldReuseEmptyResult
import com.example.stockdiary.data.repository.shouldSkipAfterFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldReuseEmptyResult] 的回归。
 *
 * 这条判断是「闪退反复出现」那个放大器的一环：绝大多数标的确实没有分红，
 * 空结果以前从来不落库，于是每次冷启动都把全部标的重抓一遍 ——
 * 59 个代码 × 3 个数据源 ≈ 250 个请求，接口被打到返回 HTTP 514（实测 106 次），
 * 请求排队到 60 秒，进程长时间占着内存解析，然后更容易被系统回收，进入下一轮。
 *
 * 两个方向都要测：认错了要么重抓几百个请求，要么把真有分红的压上 24 小时。
 */
class DividendRepositoryCachePolicyTest {

    private val ttl = 24 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    // ---------- 该认的 ----------

    @Test
    fun 近期抓过且库里为空_认定没有分红() {
        assertTrue(
            shouldReuseEmptyResult(
                dbRecordCount = 0,
                lastFetchedAt = now - 3 * 60 * 60 * 1000L,
                now = now,
                ttl = ttl
            )
        )
    }

    @Test
    fun 刚抓过也算_哪怕只过去一秒() {
        assertTrue(
            shouldReuseEmptyResult(dbRecordCount = 0, lastFetchedAt = now - 1_000L, now = now, ttl = ttl)
        )
    }

    // ---------- 不该认的 ----------

    @Test
    fun 从没抓过必须去抓() {
        // null = 没有抓取记录。这是最关键的否定用例：认了就会永远不去抓真数据
        assertFalse(
            shouldReuseEmptyResult(dbRecordCount = 0, lastFetchedAt = null, now = now, ttl = ttl)
        )
    }

    @Test
    fun 过期了要重抓() {
        assertFalse(
            shouldReuseEmptyResult(
                dbRecordCount = 0,
                lastFetchedAt = now - ttl,
                now = now,
                ttl = ttl
            )
        )
    }

    @Test
    fun 刚好差一毫秒过期也要重抓_边界是左闭右开() {
        assertFalse(
            shouldReuseEmptyResult(
                dbRecordCount = 0,
                lastFetchedAt = now - ttl - 1,
                now = now,
                ttl = ttl
            )
        )
        assertTrue(
            shouldReuseEmptyResult(
                dbRecordCount = 0,
                lastFetchedAt = now - ttl + 1,
                now = now,
                ttl = ttl
            )
        )
    }

    @Test
    fun 库里有记录时不走这条路径() {
        // 库里有数据说明该走正常的库恢复逻辑，这里必须让路，
        // 否则会被当成「没有分红」把已有的记录覆盖掉
        assertFalse(
            shouldReuseEmptyResult(
                dbRecordCount = 3,
                lastFetchedAt = now - 1_000L,
                now = now,
                ttl = ttl
            )
        )
    }

    @Test
    fun 时间戳是零视为没抓过() {
        // SharedPreferences 的 getLong 默认值就是 0，必须当成"没抓过"而不是 1970 年抓过
        assertFalse(shouldReuseEmptyResult(dbRecordCount = 0, lastFetchedAt = 0L, now = now, ttl = ttl))
    }

    @Test
    fun 时间戳来自未来不认_否则会永久压住不抓() {
        // 用户改过系统时间时会出现。认了的话 now - fetched 是负数，永远小于 ttl，
        // 这只标的从此再也不会更新
        assertFalse(
            shouldReuseEmptyResult(
                dbRecordCount = 0,
                lastFetchedAt = now + 10 * 24 * 60 * 60 * 1000L,
                now = now,
                ttl = ttl
            )
        )
    }

    // ---------- TTL 分层：空结果比有结果容忍得多 ----------

    @Test
    fun 空结果的TTL比有分红的长_这是把请求量压下来的关键() {
        // 87% 的标的没有分红，它们是请求量的绝大多数来源。
        // 如果两者同 TTL，冷启动每隔一天就要把全部标的重抓一遍
        val day = 24 * 60 * 60 * 1000L
        val negativeTtl = 7 * day

        // 第 3 天：有分红的标的 TTL（24h）已过，空结果的还没过
        assertFalse(
            shouldReuseEmptyResult(0, now - 3 * day, now, ttl = day)
        )
        assertTrue(
            shouldReuseEmptyResult(0, now - 3 * day, now, ttl = negativeTtl)
        )
    }

    @Test
    fun 空结果第七天开始也要重抓_不是永久缓存() {
        // 万一第一次抓取正好撞上限流/反爬拿到空结果，过期后还得有机会重试
        val negativeTtl = 7 * 24 * 60 * 60 * 1000L
        assertTrue(shouldReuseEmptyResult(0, now - 6 * negativeTtl / 7, now, negativeTtl))
        assertFalse(shouldReuseEmptyResult(0, now - negativeTtl, now, negativeTtl))
    }
}

/**
 * [shouldSkipAfterFailure] 的回归 —— 「失败」与「确实没有分红」的分界。
 *
 * 这条判断守护两件事，两边都是真实事故：
 *
 * - 失败**不能**写成空结果结论：那会把一次网络抖动固化成「这只没有分红」
 *   最长 7 天（冷启动只读缓存，全程静默，界面显示 0）
 * - 失败也**不能**什么都不记：场外基金没有批量入口，上百只失败标的每轮
 *   各重打一次就是请求风暴（历史事故形态：250 请求 → 514 → LMK 杀进程）
 *
 * 所以失败只压一个短退避窗，过期自动重试，用户手动刷新随时可绕过。
 */
class DividendFailureBackoffTest {

    private val ttl = 6 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun 刚失败过_窗内不重打() {
        assertTrue(shouldSkipAfterFailure(lastFailedAt = now - 1_000L, now = now, ttl = ttl))
    }

    @Test
    fun 没失败过_该抓就抓() {
        assertFalse(shouldSkipAfterFailure(lastFailedAt = null, now = now, ttl = ttl))
        // SharedPreferences 的 getLong 默认值是 0，必须当成「没失败过」
        assertFalse(shouldSkipAfterFailure(lastFailedAt = 0L, now = now, ttl = ttl))
    }

    @Test
    fun 退避窗过期后自动重试() {
        assertFalse(shouldSkipAfterFailure(lastFailedAt = now - ttl, now = now, ttl = ttl))
    }

    @Test
    fun 时钟回拨也压住_宁可多等一窗不放大请求() {
        // 时间戳来自未来（改过系统时间）时 now - failed 是负数，
        // 认作「在窗内」而不是「早就该重试」
        assertTrue(shouldSkipAfterFailure(lastFailedAt = now + 10 * 24 * 60 * 60 * 1000L, now = now, ttl = ttl))
    }

    @Test
    fun 失败退避比空结果TTL短得多_不会把错误固化成结论() {
        // 失败 6 小时后就重试，而「确实没有分红」压 7 天 ——
        // 前者是临时故障，后者是结论，两者绝不能共用一套 TTL
        val day = 24 * 60 * 60 * 1000L
        val failureTtl = 6 * 60 * 60 * 1000L
        val negativeTtl = 7 * day
        assertFalse(shouldSkipAfterFailure(now - 3 * day, now, failureTtl))
        assertTrue(shouldReuseEmptyResult(0, now - 3 * day, now, negativeTtl))
    }
}
