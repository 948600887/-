package com.example.stockdiary.data.price

import com.example.stockdiary.utils.AppLogger
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 数据源相位（标准三态断路器）——结构与 [com.example.stockdiary.network.BinanceHostGate] 对齐。
 *
 * - [CLOSED]：健康，请求全放行；
 * - [OPEN]：判定该源不健康，冷却期内跳过（不白试、不计失败）；
 * - [HALF_OPEN]：冷却结束，只放行**一个**探测请求试水——成功即闭路，失败即重新熔禁。
 */
internal enum class SourcePhase { CLOSED, OPEN, HALF_OPEN }

/**
 * 熔断器的不可变快照（整包 CAS，保证状态迁移原子）。
 *
 * @param phase 当前相位
 * @param blockedUntil OPEN 相位的冷却截止时间戳（epoch ms）；CLOSED 时为 0
 * @param probeUntil HALF_OPEN 相位的探测截止时间戳（epoch ms）；用于自愈「卡死的探测」
 * @param failures CLOSED 下的连续**源级**失败计数（达到 [FAIL_THRESHOLD] 触发熔禁）
 */
private data class SourceGateState(
    val phase: SourcePhase,
    val blockedUntil: Long,
    val probeUntil: Long,
    val failures: Int
)

/**
 * 单个数据源（新浪 / 腾讯 / 妙想）的三态断路器。
 *
 * [com.example.stockdiary.data.price.StockDataManager] 是 class，每个源持一个本类实例，
 * 由 [AtomicReference] 整包 CAS 保证并发安全（同步 3 并发 + 手动/Worker 撞车）。
 *
 * ## 为什么从「计数 + 30min delay」改成三态
 *
 * 旧实现 `maxFailures=3` 恰等于同步并发度，且**符号级业务失败也计数**：Sina 返回
 * 「未获取到股票数据，请检查股票代码」（代码不在此源支持范围）3 次就整源禁用 30 分钟；
 * Part B 把挂死变成 20s 失败后，单波 3 个并发同时超时 = 恰好 3 次 = 误禁，禁用期间全部
 * A 股/场内基金只能走腾讯单源，腾讯再抖就整批失败。三态改造：
 * - 阈值 3→5（≈两波，与 [com.example.stockdiary.network.BinanceHostGate.FAIL_THRESHOLD] 对齐）；
 * - 冷却 30min→5min，配合半开探测把「恢复成本」从「干等 30min」降到「1 个探测请求」；
 * - 冷却结束进 [SourcePhase.HALF_OPEN]，只放行一个探测：成功→闭路，失败→立即重开冷却。
 *
 * 注意：只有**源级**失败才喂 [onFailure]；符号级确定性失败（代码不在此源支持范围）与
 * 停牌（QuoteSuspendedException）由调用方在调本类之前就分流掉，不计入。
 */
internal class SourceCircuitBreaker {

    private val state = AtomicReference(SourceGateState(SourcePhase.CLOSED, 0L, 0L, 0))

    /**
     * 请求前闸门（带副作用）：这个数据源这次到底用不用。
     *
     * - CLOSED → 放行；
     * - OPEN 且冷却未到 → 跳过；
     * - OPEN 且冷却已过 → CAS 转 HALF_OPEN，**仅这一个调用**作为探测放行，其余跳过；
     * - HALF_OPEN 且探测未卡死 → 跳过（探测已在进行，半开期不白试）；
     * - HALF_OPEN 但已超过 [PROBE_TIMEOUT_MS] → 探测卡死（被取消未 resolve），重新武装一个新探测并放行。
     */
    fun shouldAllow(now: Long): Boolean {
        while (true) {
            val s = state.get()
            when (s.phase) {
                SourcePhase.CLOSED -> return true
                SourcePhase.OPEN -> {
                    if (now < s.blockedUntil) return false
                    val probe = s.copy(phase = SourcePhase.HALF_OPEN, probeUntil = now + PROBE_TIMEOUT_MS)
                    if (state.compareAndSet(s, probe)) return true
                }
                SourcePhase.HALF_OPEN -> {
                    if (now < s.probeUntil) return false
                    val rearmed = s.copy(probeUntil = now + PROBE_TIMEOUT_MS)
                    if (state.compareAndSet(s, rearmed)) return true
                }
            }
        }
    }

    /** 当前是否「不可用」（纯读、无副作用，供状态展示）。非 CLOSED 即 true。 */
    fun isUnavailable(): Boolean = state.get().phase != SourcePhase.CLOSED

    /**
     * 记录一次**源级**成功（拿到有效价格，或探测拿到确定性应答）。
     *
     * - HALF_OPEN（探测成功）→ CAS 回 CLOSED，计数全清；
     * - 其它相位 → 仅把连续失败计数清零（沿用旧语义：成功即复位）。
     */
    fun onSuccess() {
        while (true) {
            val s = state.get()
            when (s.phase) {
                SourcePhase.HALF_OPEN -> {
                    if (state.compareAndSet(s, SourceGateState(SourcePhase.CLOSED, 0L, 0L, 0))) {
                        AppLogger.i("数据源探测成功，熔断解除，恢复正常访问", "SourceBreaker")
                        return
                    }
                }
                SourcePhase.OPEN, SourcePhase.CLOSED -> {
                    if (state.compareAndSet(s, s.copy(failures = 0))) return
                }
            }
        }
    }

    /**
     * 记录一次**源级**失败（超时 / 连接失败 / 空返回 / 格式坏 / 无效价格）。
     *
     * - HALF_OPEN（探测失败）→ 主机/源仍不健康的确定证据，**立即重开冷却**（不等计数到阈值）；
     * - OPEN（冷却期内迟来失败）→ 不顺延截止时间，把计数压回阈值以下防越界；
     * - CLOSED → 累计，达到 [FAIL_THRESHOLD] 时 CAS 转 OPEN（只熔禁一次、日志只打一条）。
     */
    fun onFailure(now: Long) {
        while (true) {
            val s = state.get()
            when (s.phase) {
                SourcePhase.HALF_OPEN -> {
                    val reopened = s.copy(phase = SourcePhase.OPEN, blockedUntil = now + COOLDOWN_MS, probeUntil = 0L, failures = 0)
                    if (state.compareAndSet(s, reopened)) {
                        AppLogger.w(
                            "数据源探测失败，重新禁用 ${COOLDOWN_MS / 1000} 秒" +
                                "（至 ${Instant.ofEpochMilli(reopened.blockedUntil)}）",
                            "SourceBreaker"
                        )
                        return
                    }
                }
                SourcePhase.OPEN -> {
                    if (state.compareAndSet(s, s.copy(failures = FAIL_THRESHOLD - 1))) return
                }
                SourcePhase.CLOSED -> {
                    val failures = s.failures + 1
                    if (failures < FAIL_THRESHOLD) {
                        if (state.compareAndSet(s, s.copy(failures = failures))) return
                    } else {
                        val opened = SourceGateState(SourcePhase.OPEN, now + COOLDOWN_MS, 0L, 0)
                        if (state.compareAndSet(s, opened)) {
                            AppLogger.w(
                                "数据源连续 $failures 次源级失败，临时禁用 ${COOLDOWN_MS / 1000} 秒" +
                                    "（至 ${Instant.ofEpochMilli(opened.blockedUntil)}）",
                                "SourceBreaker"
                            )
                            return
                        }
                    }
                }
            }
        }
    }

    /** 测试专用复位 */
    fun resetForTesting() {
        state.set(SourceGateState(SourcePhase.CLOSED, 0L, 0L, 0))
    }

    /** 测试专用读数：当前连续源级失败计数 */
    fun failuresForTesting(): Int = state.get().failures

    /** 测试专用读数：当前相位 */
    fun phaseForTesting(): SourcePhase = state.get().phase

    /** 测试专用读数：禁用截止时间戳（epoch ms） */
    fun blockedUntilForTesting(): Long = state.get().blockedUntil

    companion object {
        /**
         * 触发禁用的连续**源级**失败次数。
         *
         * 取 5（原为 3）：3 恰等于同步并发度，单波并发同时失败即误禁；5 ≈ 两波，
         * 真实不健康仍能在约 100s 内禁用（每次源级失败约 20s callTimeout ×重试），
         * 且与 [com.example.stockdiary.network.BinanceHostGate.FAIL_THRESHOLD] 对齐。
         */
        const val FAIL_THRESHOLD = 5

        /**
         * 禁用冷却时长（毫秒）。取 5 分钟（原为 30 分钟）：配合半开探测，恢复成本从
         * 「干等 30min」降到「1 个探测请求」，冷却短一些用户更快拿到恢复后的行情。
         */
        const val COOLDOWN_MS = 5 * 60 * 1000L

        /**
         * 半开探测自愈截止（毫秒）。与 [com.example.stockdiary.network.BinanceHostGate.PROBE_TIMEOUT_MS]
         * 同值：正常探测由 callTimeout 兜底会 resolve，取消未 resolve 时防止相位永久卡 HALF_OPEN。
         */
        const val PROBE_TIMEOUT_MS = 30_000L
    }
}
