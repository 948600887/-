package com.example.stockdiary.network

import java.util.Locale

/**
 * 币安理财链路的**纯文案与格式化**函数（无 I/O、无状态、无日志）。
 *
 * 从 [BinanceAccountAPI] 外提：那里同时负责签名、重试、解析、失败分类与中文文案，
 * 1600 行里改协议字段和改提示词要动同一个文件。这里只保留「给定数据 → 字符串」的映射，
 * 行为与原先逐字符一致，便于单测直接钉住文案口径。
 */
internal object BinanceMessages {

    /** 活期 / 定期的人话标签（用于失败原因与诊断日志） */
    fun earnKindLabel(locked: Boolean): String = if (locked) "定期" else "活期"

    /**
     * 诊断用：本页有没有 USDT，有就带上金额（`是(144.99343452)` / `否`）。
     *
     * 只进日志、不参与任何判定——它是给「USDT 到底在不在接口返回里」这个反复排查的问题
     * 一个确定性答案，而不是又一次靠猜。
     */
    fun describeUsdt(positions: List<BinanceEarnPosition>): String {
        val usdt = positions.firstOrNull { it.asset.uppercase(Locale.US) == "USDT" }
            ?: return "否"
        return "是(${formatAmount(usdt.amount)})"
    }

    /** 诊断用：本页前 3 个资产（`USDT:144.99、BUSD:12.3…`），看清接口到底给了什么 */
    fun describeHeadAssets(positions: List<BinanceEarnPosition>): String {
        if (positions.isEmpty()) return "无"
        return positions.take(3).joinToString("、") {
            "${it.asset}:${formatAmount(it.amount)}"
        }
    }

    /**
     * 把金额格式化成诊断日志里好读的形态：最多 8 位小数、去掉尾随 0。
     *
     * 固定用 [Locale.US]：日志是要拿去 grep 的，绝不能让某些语言环境把小数点变成逗号。
     */
    fun formatAmount(value: Double): String =
        String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
}
