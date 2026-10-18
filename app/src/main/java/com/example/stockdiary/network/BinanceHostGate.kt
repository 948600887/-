package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 币安主机级熔断器（进程内状态，仅针对 api.binance.com）。
 *
 * 治的场景：用户所在网络压根连不上币安（IPv6 ConnectException /
 * SocketTimeoutException）。此时每轮同步会为每个加密货币标的各烧最多
 * 20s 连接超时（NetworkClient.binanceClient 的 callTimeout），且都计入
 * `failedCount`、触发「同步多次失败」通知——既白烧时间又误导用户。
 *
 * 与 StockDataManager 里「数据源级」熔断的区别：那是按数据源
 * （新浪 / 腾讯 / 妙想）计的，加密货币路径不走那条链；这里是**主机级**——
 * 连不上的是主机，换哪个标的、哪个交易对都一样连不上，逐个标的重复试错
 * 没有意义，直接整体短路到冷却结束。
 *
 * 线程安全：价格同步是 3 并发的（AssetSyncManager.Semaphore），再加手动
 * 刷新可能与后台 Worker 撞车，计数与截止时间都用原子类型；
 * [onNetworkFailure] 用 CAS 保证并发越界时只熔断一次、日志只打一条。
 */
object BinanceHostGate {
    private const val TAG = "BinanceHostGate"

    /**
     * 触发熔断的连续网络级失败次数。
     *
     * 取 3：单轮同步会为每个加密货币标的各打一次币安，3 次约等于
     * 「手里过半的加密货币持仓都连不上」；取 1~2 会把偶发的单次超时
     * 误判成主机不可达，把本可成功的同步也一起挡掉。
     */
    const val FAIL_THRESHOLD = 3

    /**
     * 熔断冷却时长（毫秒）。
     *
     * 取 10 分钟：主机不可达（连不上 / 被地区限制）的恢复时间以分钟计，
     * 冷却短于 1 分钟会在每轮同步里反复试探，每个标的继续白烧最多 20s
     * 连接超时——正是本类要消除的浪费。也不宜更长：网络恢复后用户要能在
     * 下一次同步就拿到价格，而不是干等半小时。
     */
    const val COOLDOWN_MS = 10 * 60 * 1000L

    /** 连续网络级失败次数。[onSuccess] 与触发熔断时清零，杜绝跨轮累计 */
    private val consecutiveFailures = AtomicInteger(0)

    /** 熔断截止时间戳（epoch ms）；0 表示未熔断 */
    private val blockedUntil = AtomicLong(0L)

    /** 累计进入熔断的次数（诊断用；并发下每次熔断只应 +1，测试钉这一点） */
    private val blockCount = AtomicInteger(0)

    /** 当前是否处于熔断期。[now] 由调用方传入 epoch ms，便于测试注入时间 */
    fun isBlocked(now: Long): Boolean = now < blockedUntil.get()

    /**
     * 记录一次网络级失败（连不上 / 超时 / 域名解析失败 / IO 错误）。
     *
     * 连续达到 [FAIL_THRESHOLD] 次时进入 [COOLDOWN_MS] 冷却并清零计数。
     * 并发安全：多个协程可能同时越界，CAS 保证截止时间只被设置一次、
     * WARN 日志只打一条。已在熔断期时**不顺延**截止时间——每次失败都顺延，
     * 主机长时间不可达时熔断永远解不了封；也不重复打日志。
     */
    fun onNetworkFailure(now: Long) {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures < FAIL_THRESHOLD) return

        val until = now + COOLDOWN_MS
        while (true) {
            val current = blockedUntil.get()
            if (current >= now) {
                // 仍在熔断期内（并发失败同时到达，或冷却未过又失败）：
                // 不重复设置截止时间，只把计数压回阈值以下，防止计数随
                // 并发数虚涨越界、冷却结束后被一次失败就再次顶爆。
                consecutiveFailures.set(FAIL_THRESHOLD - 1)
                return
            }
            if (blockedUntil.compareAndSet(current, until)) {
                // 首次越界（或上一轮熔断已过期后的重新武装）：真正进入熔断
                consecutiveFailures.set(0)
                blockCount.incrementAndGet()
                AppLogger.w(
                    "币安主机连续 $failures 次网络级失败，熔断 ${COOLDOWN_MS / 1000} 秒" +
                        "（至 ${Instant.ofEpochMilli(until)} 暂停访问）",
                    TAG
                )
                return
            }
            // CAS 失败说明有并发写入，重试
        }
    }

    /**
     * 记录一次成功：清零连续失败计数。
     *
     * 刻意不清 [blockedUntil]：冷却期内并发请求可能陆续成功，每次成功都
     * 解封会让熔断形同虚设（抖动即解封）。解封只认冷却时间自然过去。
     */
    fun onSuccess() {
        consecutiveFailures.set(0)
    }

    /** 测试专用复位：本类是进程内 object，用例之间必须清状态（与 ExchangeRateManager 同惯例） */
    internal fun resetForTesting() {
        consecutiveFailures.set(0)
        blockedUntil.set(0L)
        blockCount.set(0)
    }

    /** 测试专用读数：当前连续失败计数 */
    internal fun consecutiveFailuresForTesting(): Int = consecutiveFailures.get()

    /** 测试专用读数：熔断截止时间戳（epoch ms） */
    internal fun blockedUntilForTesting(): Long = blockedUntil.get()

    /** 测试专用读数：累计熔断次数（并发下应只 +1） */
    internal fun blockCountForTesting(): Int = blockCount.get()
}
