package com.example.stockdiary.utils

/**
 * 一轮价格同步的**如实文案**（纯函数，无 Android 依赖，JVM 单测可直调）。
 *
 * 为什么单独抽出来：通知（[NotificationHelper.showPriceSyncNotification]）与 Worker 日志
 * 从前各写一份 when，口径一漂移就会出现「通知说成功、日志说没更新」；更要命的是
 * 旧文案对「0 成功 / 0 失败 / N 跳过」这一轮照样输出「成功同步 0 个资产」——
 * 用户以为刷新生效，实际一个标的价格都没动。这里统一成唯一文案来源：
 *
 * - 跳过量（[skippedCount]）无论成功/失败都如实带上；
 * - 全跳过时明确说「无有效更新 / 被跳过 / 价格未刷新」，绝不出现「成功同步 0 个资产」；
 * - 「跳过不算失败」：全跳过不是网络故障，文案里不出现「同步失败」。
 */
fun buildPriceSyncMessage(successCount: Int, failedCount: Int, skippedCount: Int): String {
    val skippedPart = if (skippedCount > 0) "，跳过 $skippedCount 个" else ""
    return when {
        successCount == 0 && failedCount == 0 && skippedCount > 0 ->
            "本轮无有效更新：$skippedCount 个资产被跳过（价格未刷新）"
        // 全 0 且无跳过：资产为空 / 全部被偏好过滤，历史上也只报「成功同步 0 个资产」，
        // 同样误导；改成如实说明。判定本身仍保持「全 0 极端无害覆盖」的原语义。
        successCount == 0 && failedCount == 0 -> "本轮无可同步资产"
        successCount == 0 -> "同步失败，请检查网络$skippedPart"
        failedCount == 0 -> "成功同步 $successCount 个资产$skippedPart"
        else -> "成功 $successCount 个，失败 $failedCount 个$skippedPart"
    }
}

/**
 * 跳过原因的分布后缀，形如「（币安熔断 130、退市 1）」。
 *
 * 只报总数时用户无法判断「该不该管」：130 个是行情源不可达、还是 1 个退市股，
 * 处置完全不同。按数量降序拼接保证同一轮日志顺序稳定，方便逐轮对比。
 */
fun buildPriceSyncReasonSuffix(skippedByReason: Map<String, Int>): String {
    val reasons = skippedByReason.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
    if (reasons.isEmpty()) return ""
    return "（" + reasons.joinToString("、") { "${it.key} ${it.value}" } + "）"
}

/**
 * 通知的标题与正文（纯函数，JVM 单测可直调）。
 *
 * [com.example.stockdiary.utils.NotificationHelper.showPriceSyncNotification] 只做
 * 「取文案 → 交给系统通知栏」这一层薄壳，标题/正文全部由这里生成：这样通知的如实性
 * （尤其全跳过时不许出现「价格同步完成 / 成功同步 0 个资产」）能在 JVM 单测里钉死，
 * 不必起 Context / NotificationManager。正文仍复用 [buildPriceSyncMessage]，与 Worker
 * 日志共用同一个来源，两处口径不会漂移。
 */
data class PriceSyncNotificationText(val title: String, val message: String)

fun buildPriceSyncNotificationText(
    successCount: Int,
    failedCount: Int,
    skippedCount: Int,
    skippedByReason: Map<String, Int> = emptyMap()
): PriceSyncNotificationText {
    val allSkipped = successCount == 0 && failedCount == 0 && skippedCount > 0
    return PriceSyncNotificationText(
        // 全跳过不是「完成」，标题也要如实，别用「价格同步完成」给人虚假安心。
        title = if (allSkipped) "价格同步无有效更新" else "价格同步完成",
        message = buildPriceSyncMessage(successCount, failedCount, skippedCount) +
            buildPriceSyncReasonSuffix(skippedByReason)
    )
}
