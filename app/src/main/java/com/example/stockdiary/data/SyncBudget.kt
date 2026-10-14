package com.example.stockdiary.data

/**
 * 刷新链路的时间预算。
 *
 * ## 为什么需要它
 *
 * 手动刷新从前没有总时长上限：60 个持仓的价格请求 + 分红级联，任何一环
 * （服务端慢、限流、弱网）都能把刷新拖到几分钟。用户看到的是无限转圈，
 * 等到系统回收进程就是「闪退」。后台 Worker 一直有 9 分钟上限并会提前
 * 截断，手动刷新这条最常被点击的路径反而没有。
 *
 * 超时的策略是**优雅收尾**而不是抛错：还没开始的标的本次跳过（已启动的
 * 跑完），结果里带 `truncatedByTimeLimit`，由调用方提示用户
 * 「同步超时，已保留上次数据」。跳过的分红标的不会写缓存，之后按需抓取
 * 时还会补上，不存在数据丢失。
 *
 * 所有判定都是**纯函数**（不碰 Context / 网络 / 时钟之外的一切），
 * 所以预算规则错了能直接单测钉死。
 */
internal object SyncBudget {

    /**
     * 手动刷新的总时长上限（毫秒）。
     *
     * 90 秒的构成：分红预取最多拿 30 秒（见 [prefetchPhaseBudget]），
     * 价格同步拿剩下 60 秒；单个标的的网络耗时被各数据源 15 秒读超时
     * ×3 次重试兜住，最坏情况也就是预算用完后多跑完手头那几个。
     * 再长就没有意义了——用户点刷新是想看最新价，不是想看转圈。
     */
    const val MANUAL_SYNC_TIME_LIMIT_MS = 90_000L

    /**
     * 分红预取阶段的时间预算硬顶（毫秒）。
     *
     * 预取只是刷新的配角：价格同步才是用户点刷新真正要看的东西。
     * 不划上限的话，预取阶段（52 个代码的级联）能把总预算吃光，
     * 价格一个都还没同步就被判定超时。
     */
    const val PREFETCH_PHASE_MAX_MS = 30_000L

    /** 分红预取阶段最多拿总预算的比例：2/5 */
    private const val PREFETCH_SHARE_NUM = 2
    private const val PREFETCH_SHARE_DEN = 5

    /**
     * 是否已经达到时间预算（达到即停，不等超过）。
     *
     * @param startTime 同步真正开始的时间戳（毫秒）。注意是「真正开始」——
     *        在互斥锁上排队的时间不算，否则第二次刷新拿到的是被第一次
     *        吃掉大半的残预算
     * @param timeLimitMs 总预算；<= 0 表示不设限
     * @param now 当前时间戳（做成入参而不是内部读时钟，才能单测）
     */
    fun isExceeded(startTime: Long, timeLimitMs: Long, now: Long): Boolean {
        if (timeLimitMs <= 0L) return false
        return now - startTime >= timeLimitMs
    }

    /**
     * 分红预取阶段能拿到的子预算：总预算的 2/5，且不超过 [PREFETCH_PHASE_MAX_MS]。
     *
     * 不设总预算（后台 Worker 传自己的上限，或调用方显式不限）时返回 0，
     * 表示这个阶段也不设限——保持改造前的行为。
     */
    fun prefetchPhaseBudget(timeLimitMs: Long): Long {
        if (timeLimitMs <= 0L) return 0L
        val share = timeLimitMs * PREFETCH_SHARE_NUM / PREFETCH_SHARE_DEN
        return minOf(share, PREFETCH_PHASE_MAX_MS)
    }
}
