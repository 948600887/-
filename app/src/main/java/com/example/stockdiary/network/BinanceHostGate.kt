package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 熔断相位（标准三态断路器）。
 *
 * - [CLOSED]：健康，请求全放行；
 * - [OPEN]：判定主机不可达，冷却期内拒绝一切请求（不烧时间、不计失败）；
 * - [HALF_OPEN]：冷却结束，只放行**一个**探测请求试水——成功即闭路，失败即重新熔断。
 */
internal enum class Phase { CLOSED, OPEN, HALF_OPEN }

/**
 * 熔断器的不可变快照（整包 CAS，保证状态迁移原子）。
 *
 * @param phase 当前相位
 * @param blockedUntil OPEN 相位的冷却截止时间戳（epoch ms）；CLOSED 时为 0
 * @param probeUntil HALF_OPEN 相位的探测截止时间戳（epoch ms）；用于自愈「卡死的探测」
 * @param consecutiveFailures CLOSED 下的连续网络级失败计数（达到 [BinanceHostGate.FAIL_THRESHOLD] 触发熔断）
 */
private data class GateState(
    val phase: Phase,
    val blockedUntil: Long,
    val probeUntil: Long,
    val consecutiveFailures: Int
)

/**
 * 币安主机级熔断器（进程内状态，仅针对 api.binance.com）——标准三态断路器。
 *
 * 治的场景：用户所在网络压根连不上币安（IPv6 ConnectException / SocketTimeoutException）。
 * 此时每轮同步会为每个加密货币标的各烧最多 20s callTimeout，且都计入 failedCount、
 * 触发「同步多次失败」通知——既白烧时间又误导用户。
 *
 * 与 StockDataManager 里「数据源级」熔断的区别：那是按数据源（新浪 / 腾讯 / 妙想）计的，
 * 加密货币路径不走那条链；这里是**主机级**——连不上的是主机，换哪个标的、哪个交易对
 * 都一样连不上，逐个标的重复试错没有意义，直接整体短路到冷却结束。
 *
 * ## 为什么从「两态 + 计数」改成三态
 *
 * 旧实现 `FAIL_THRESHOLD=3` 恰等于同步并发度 3：一晚熔断 3 次，每次都是 3 个并发请求
 * 同时挂满 20s → 达到阈值 → 约半个组合的加密货币整批停更 10 分钟；而同一晚另一时刻
 * 币安全程通畅（成功 150）。**单波网络抖动就误熔半个组合**，误伤率过高。且冷却是「时间
 * 自然过去」，过期后下一轮同步直接全量重打、再烧一轮 20s 才可能再次熔断。
 *
 * 三态改造：
 * - 阈值 3→5（≈两波抖动），真实不可达仍能在约 40s 内熔断，单波抖动不再误伤；
 * - 冷却 10min→5min，配合半开探测把「恢复成本」从全量重打降到 1 个探测请求；
 * - 冷却结束进 [Phase.HALF_OPEN]，只放行一个探测：成功→闭路，失败→立即重开冷却。
 *
 * ## 线程安全
 *
 * 价格同步是 3 并发的（AssetSyncManager.Semaphore），再加手动刷新可能与后台 Worker 撞车。
 * 整个状态打包进 [GateState]、用 [AtomicReference] 整包 CAS：并发越界时只熔断一次、
 * 日志只打一条；[blockCount] 用 [AtomicInteger] 单独计「从健康态判死」的次数。
 */
internal object BinanceHostGate {
    private const val TAG = "BinanceHostGate"

    /**
     * 触发熔断的连续网络级失败次数。
     *
     * 取 5（原为 3）：3 恰等于同步并发度，单波抖动（3 个在途同时挂满 20s callTimeout）
     * 就误熔半个组合，误伤率过高。5 ≈ 两波抖动，真实不可达仍能在约 40s 内熔断——
     * 且 Part B 看门狗已给同步总预算兜底，多烧一波也不怕。
     */
    const val FAIL_THRESHOLD = 5

    /**
     * 熔断冷却时长（毫秒）。
     *
     * 取 5 分钟（原为 10 分钟）：配合半开探测，恢复成本从「全量重打再烧一轮 20s」降到
     * 「1 个探测请求」，冷却短一些用户能更快拿到恢复后的价格。也不宜更短：主机不可达
     * （连不上 / 被地区限制）的恢复时间以分钟计，冷却太短会在每轮同步里反复试探。
     */
    const val COOLDOWN_MS = 5 * 60 * 1000L

    /**
     * 半开探测的自愈截止（毫秒，自进入 [Phase.HALF_OPEN] 起算）。
     *
     * 正常探测由 callTimeout（20s，见 [NetworkClient]）兜底，约 20s 内必然 resolve。
     * 但探测请求可能被**取消**（看门狗超预算 / 退后台 / VM 销毁）——取消走
     * CancellationException 重抛，[onSuccess] / [onNetworkFailure] 都不会被调用，
     * 相位将永远卡在 HALF_OPEN，之后所有加密货币请求都被拒、价格永久冻结。
     * 取 30s（> callTimeout 20s，留足正常探测的余量）：[tryAcquireProbe] 若发现 HALF_OPEN
     * 已超过这个期限，判定探测卡死，重新武装一个新探测——自愈，不永久冻结。
     * internal：供单测引用，钉死自愈行为。
     */
    internal const val PROBE_TIMEOUT_MS = 30_000L

    /** 连续网络级失败次数（CLOSED 相位的累计；OPEN/HALF_OPEN 下仅作有界记账） */
    private val state = AtomicReference(GateState(Phase.CLOSED, 0L, 0L, 0))

    /** 累计「从健康态判死」的次数（诊断用；半开探测失败的重开不计入，见 [onNetworkFailure]） */
    private val blockCount = AtomicInteger(0)

    /**
     * 请求闸门（**带 CAS 副作用，调用即可能消耗唯一的半开探测槽**）：这次加密货币询价到底发不发。
     *
     * ⚠️ 严禁在「非真正要发请求」的路径上调用（典型反面教材：UI 想判断可用性而顺手调它）——
     * 误调会悄悄吃掉探测名额、让真请求被拒，直到 [PROBE_TIMEOUT_MS] 自愈。判断是否「暂不可达」
     * 只应走纯读的 [isUnavailable]。
     *
     * 各分支：
     * - CLOSED → 放行；
     * - OPEN 且冷却未到 → 拒绝（不烧时间）；
     * - OPEN 且冷却已过 → CAS 转 HALF_OPEN，**仅这一个调用**作为探测放行，其余拒绝；
     * - HALF_OPEN 且探测未卡死 → 拒绝（探测已在进行，半开期不烧时间）；
     * - HALF_OPEN 但已超过 [PROBE_TIMEOUT_MS] → 探测卡死（被取消未 resolve），重新武装一个新探测并放行。
     *
     * 用 CAS 保证「冷却结束瞬间的并发请求里只有一个能成为探测」。
     */
    fun tryAcquireProbe(now: Long): Boolean {
        while (true) {
            val s = state.get()
            when (s.phase) {
                Phase.CLOSED -> return true
                Phase.OPEN -> {
                    if (now < s.blockedUntil) return false
                    // 冷却已过：竞争成为唯一的半开探测
                    val probe = s.copy(phase = Phase.HALF_OPEN, probeUntil = now + PROBE_TIMEOUT_MS)
                    if (state.compareAndSet(s, probe)) return true
                    // CAS 失败（并发），重读重试
                }
                Phase.HALF_OPEN -> {
                    if (now < s.probeUntil) return false
                    // 探测卡死（被取消、未 resolve）：自愈，重新武装一个新探测
                    val rearmed = s.copy(probeUntil = now + PROBE_TIMEOUT_MS)
                    if (state.compareAndSet(s, rearmed)) return true
                    // CAS 失败（并发），重读重试
                }
            }
        }
    }

    /**
     * 当前是否「暂不可达」（纯读、无副作用，供 UI 展示「币安暂不可达」标签）。
     *
     * 只要不是 CLOSED（即 OPEN 冷却中或 HALF_OPEN 探测中）就返回 true。
     * **不要**用它做请求闸门——那是 [tryAcquireProbe] 的职责（带 CAS 副作用）；
     * UI 只读展示，不能驱动状态机。
     */
    fun isUnavailable(): Boolean = state.get().phase != Phase.CLOSED

    /**
     * 记录一次网络级成功。
     *
     * - HALF_OPEN（探测成功）→ CAS 回 CLOSED，计数 / blockedUntil 全清，[blockCount] 不涨
     *   （探测成功是恢复，不是又一次判死）；
     * - OPEN（冷却期内迟到成功）→ **不解封**（防抖动即解封，解封只认冷却自然过去或探测成功），
     *   仅按旧语义把连续失败计数清零；
     * - CLOSED → 仅把连续失败计数清零。
     */
    fun onSuccess() {
        while (true) {
            val s = state.get()
            when (s.phase) {
                Phase.HALF_OPEN -> {
                    // 探测成功：闭路，全部清零
                    if (state.compareAndSet(s, GateState(Phase.CLOSED, 0L, 0L, 0))) {
                        AppLogger.i("币安主机探测成功，熔断解除，恢复正常访问", TAG)
                        return
                    }
                    // CAS 失败，重读重试
                }
                Phase.OPEN, Phase.CLOSED -> {
                    // 迟到成功不解封，只清计数（沿用旧语义）
                    if (state.compareAndSet(s, s.copy(consecutiveFailures = 0))) return
                }
            }
        }
    }

    /**
     * 记录一次网络级失败（连不上 / 超时 / 域名解析失败 / IO 错误）。
     *
     * - HALF_OPEN（探测失败）→ 主机仍不可达的确定证据，**立即重开冷却**
     *   （blockedUntil = now + [COOLDOWN_MS]，不等计数到阈值），打日志；不计入 [blockCount]；
     * - OPEN（冷却期内迟来失败）→ 不顺延截止时间（每次失败都顺延，主机长时间不可达时
     *   熔断永远解不了封），也不重复打日志；把计数压回阈值以下，防止随并发虚涨越界；
     * - CLOSED → 累计，达到 [FAIL_THRESHOLD] 时 CAS 转 OPEN（只熔断一次、日志只打一条）、
     *   [blockCount] +1、计数清零。
     */
    fun onNetworkFailure(now: Long) {
        while (true) {
            val s = state.get()
            when (s.phase) {
                Phase.HALF_OPEN -> {
                    // 探测失败：主机仍不可达，立即重开冷却（不等阈值）
                    val reopened = s.copy(phase = Phase.OPEN, blockedUntil = now + COOLDOWN_MS, probeUntil = 0L, consecutiveFailures = 0)
                    if (state.compareAndSet(s, reopened)) {
                        AppLogger.w(
                            "币安主机探测失败，重新熔断 ${COOLDOWN_MS / 1000} 秒" +
                                "（至 ${Instant.ofEpochMilli(reopened.blockedUntil)} 暂停访问）",
                            TAG
                        )
                        return
                    }
                    // CAS 失败，重读重试
                }
                Phase.OPEN -> {
                    // 仍在冷却期：不顺延、不重复日志，只把计数压回阈值以下防越界
                    if (state.compareAndSet(s, s.copy(consecutiveFailures = FAIL_THRESHOLD - 1))) return
                }
                Phase.CLOSED -> {
                    val failures = s.consecutiveFailures + 1
                    if (failures < FAIL_THRESHOLD) {
                        if (state.compareAndSet(s, s.copy(consecutiveFailures = failures))) return
                        // CAS 失败，重读重试
                    } else {
                        // 首次越界（或上一轮熔断已过期后的重新武装）：真正进入熔断
                        val opened = GateState(Phase.OPEN, now + COOLDOWN_MS, 0L, 0)
                        if (state.compareAndSet(s, opened)) {
                            blockCount.incrementAndGet()
                            AppLogger.w(
                                "币安主机连续 $failures 次网络级失败，熔断 ${COOLDOWN_MS / 1000} 秒" +
                                    "（至 ${Instant.ofEpochMilli(opened.blockedUntil)} 暂停访问）",
                                TAG
                            )
                            return
                        }
                        // CAS 失败，重读重试
                    }
                }
            }
        }
    }

    /** 测试专用复位：本类是进程内 object，用例之间必须清状态（与 ExchangeRateManager 同惯例） */
    internal fun resetForTesting() {
        state.set(GateState(Phase.CLOSED, 0L, 0L, 0))
        blockCount.set(0)
    }

    /** 测试专用读数：当前连续失败计数 */
    internal fun consecutiveFailuresForTesting(): Int = state.get().consecutiveFailures

    /** 测试专用读数：熔断截止时间戳（epoch ms） */
    internal fun blockedUntilForTesting(): Long = state.get().blockedUntil

    /** 测试专用读数：累计熔断次数（半开探测失败的重开不计入） */
    internal fun blockCountForTesting(): Int = blockCount.get()

    /** 测试专用读数：当前相位，供三态迁移断言 */
    internal fun phaseForTesting(): Phase = state.get().phase
}
