package com.example.stockdiary.utils

/**
 * 逐标的失败 / 跳过日志的统一拼装（**纯函数**，便于 JVM 单测）。
 *
 * 从前各条链路各写各的：新浪只有一句「无有效报价」、腾讯只有「未获取到股票数据」、
 * 基金 legacy 只有「基金请求失败: HTTP xxx」，既没有标的代码，也没有源名和耗时，
 * 用户发来一份日志后仍然无法回答「是哪只票、哪个源、慢在哪」。
 *
 * 统一前缀 `[轮次#N][symbol][source]`：
 * - N 是同步轮次号（[com.example.stockdiary.data.price.RoundSourceFailureScope]），
 *   0 表示**非同步轮次**（前台单标的查价、导入回显等），便于按轮次过滤；
 * - 三个方括号字段位置固定，诊断界面/脚本可以直接按 `]` 切分。
 */
object FailureLogFormat {

    /** 非同步路径的轮次号 */
    const val NO_ROUND = 0

    /**
     * 逐标的失败行（源级故障、网络异常、解析失败等）。
     *
     * @param roundId 本轮同步编号；非同步路径传 [NO_ROUND]
     * @param symbol 标的代码（股票/基金/币种），必带——没有它就无法定位是哪只
     * @param source 数据源名（与源统计里的稳定名字一致，如「新浪」「基金-legacy源」）
     * @param reason 失败原因；空则写「未知原因」，绝不留下空尾巴
     * @param durationMs 本次请求耗时；null 表示没有测量（不写耗时，避免假装 0ms）
     */
    fun failureLine(
        roundId: Int,
        symbol: String,
        source: String,
        reason: String?,
        durationMs: Long? = null
    ): String = line(roundId, symbol, source, "失败", reason, durationMs)

    /**
     * 逐标的跳过行（停牌、代码不在此源支持范围、熔断冷却中、批量已记账等）。
     *
     * 跳过≠故障：这类行落 INFO，不进源级熔断，也不该让用户以为数据源坏了。
     */
    fun skipLine(
        roundId: Int,
        symbol: String,
        source: String,
        reason: String?,
        durationMs: Long? = null
    ): String = line(roundId, symbol, source, "跳过", reason, durationMs)

    /**
     * 批量请求失败行（一次请求覆盖多个代码，用批次信息代替单一 symbol）。
     *
     * @param batchLabel 批次标签，如 `600519,000001…(共 20 只)`
     */
    fun batchFailureLine(
        roundId: Int,
        batchLabel: String,
        source: String,
        reason: String?,
        durationMs: Long? = null
    ): String = failureLine(roundId, batchLabel, source, reason, durationMs)

    /**
     * 把自由文本的失败/跳过原因归到稳定的桶，供轮次汇总统计「原因分布」。
     *
     * 逐标的行保留原始原因（可 grep 到具体请求），汇总行只报桶：
     * 二十只票各自不同的报错文案不该产生二十个统计桶。
     */
    fun reasonBucket(reason: String?): String {
        val text = reason?.trim().orEmpty()
        if (text.isEmpty()) return "未知原因"
        return when {
            text.contains("停牌") || text.contains("无有效报价") -> "停牌"
            text.contains("HTTP") || text.contains("非成功") -> "HTTP 错误"
            text.contains("超时") || text.contains("timeout", ignoreCase = true) ||
                text.contains("timed out", ignoreCase = true) -> "超时"
            text.contains("网络") || text.contains("Unable to resolve", ignoreCase = true) ||
                text.contains("连接") || text.contains("connect", ignoreCase = true) -> "网络不可用"
            text.contains("解析") || text.contains("无效") || text.contains("过短") ||
                text.contains("为空") || text.contains("空数据") -> "解析失败"
            text.contains("熔断") || text.contains("冷却") || text.contains("退避") -> "熔断/冷却"
            else -> "其他"
        }
    }

    /**
     * 每轮每源一行汇总（**纯函数**）。
     *
     * 逐标的失败行负责「哪只票、为什么」；这一行负责「这个源这轮到底怎样」：
     * 成功/失败/跳过各多少、失败与跳过分别是什么原因分布。二十个标的各打一行会刷屏，
     * 但只有失败行没有汇总，用户仍然要自己数。
     *
     * @param failureReasons 失败原因桶 → 次数（由 [reasonBucket] 归一）
     * @param skipReasons 跳过原因桶 → 次数
     */
    fun sourceSummaryLine(
        roundId: Int,
        source: String,
        success: Int,
        failed: Int,
        skipped: Int,
        failureReasons: Map<String, Int> = emptyMap(),
        skipReasons: Map<String, Int> = emptyMap(),
        durationMs: Long = 0L
    ): String = buildString {
        append("[轮次#").append(roundId.coerceAtLeast(NO_ROUND)).append(']')
        append('[').append(source).append("] 汇总: 成功 ").append(success)
        append("，失败 ").append(failed)
        appendDistribution(failureReasons)
        append("，跳过 ").append(skipped)
        appendDistribution(skipReasons)
        if (durationMs > 0) append(" | 耗时 ").append(durationMs).append("ms")
    }

    /**
     * 异常 → 记账原因（**各源唯一的「失败分类 → 记账」入口**，纯函数）。
     *
     * 只统一「写进源统计/逐标的日志的文案与归类」，**不动**各源自己的重试次数、
     * 熔断档位、退避窗口——那些是各源策略，互不相同是合理的。
     *
     * 之前新浪/腾讯/基金各拼各的（`e.message`、「网络异常：…」、「请求失败」），
     * 同一场 DNS 故障在不同源里落成三个不同的原因桶，轮次汇总与健康提示各说各话。
     */
    fun accountingReason(e: Throwable, maxLen: Int = 40): String {
        val head = when (e) {
            is java.net.UnknownHostException -> "主机不可达"
            is java.net.SocketTimeoutException -> "网络超时"
            is java.net.ConnectException -> "连接失败"
            is java.io.IOException -> "网络异常"
            else -> e.javaClass.simpleName
        }
        val detail = e.message?.trim()?.take(maxLen)?.takeIf { it.isNotEmpty() }
        return if (detail != null) "$head：$detail" else head
    }

    private fun StringBuilder.appendDistribution(distribution: Map<String, Int>) {
        if (distribution.isEmpty()) return
        append("（")
        append(
            distribution.entries
                .sortedByDescending { it.value }
                .joinToString("、") { "${it.key} ${it.value}" }
        )
        append("）")
    }

    private fun line(
        roundId: Int,
        symbol: String,
        source: String,
        kind: String,
        reason: String?,
        durationMs: Long?
    ): String = buildString {
        append("[轮次#").append(roundId.coerceAtLeast(NO_ROUND)).append(']')
        append('[').append(symbol).append(']')
        append('[').append(source).append("] ").append(kind).append(": ")
        append(reason?.takeIf { it.isNotBlank() } ?: "未知原因")
        if (durationMs != null && durationMs >= 0) {
            append(" | 耗时 ").append(durationMs).append("ms")
        }
    }
}
