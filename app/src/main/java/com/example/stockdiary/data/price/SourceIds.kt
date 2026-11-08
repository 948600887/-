package com.example.stockdiary.data.price

/**
 * 行情「稳定源 ID」的唯一映射中心（纯 Kotlin，无 Android / Context 依赖，JVM 单测可直调）。
 *
 * ## 为什么要有这一层
 *
 * 从前源标识散落在 [ApiManager.API_SINA]（中文名，只用于日志）与磁盘缓存的
 * `source` 字段（可能残留中文名）两处，血缘归属时口径容易漂移、甚至「腾讯源的价
 * 被记成新浪」。这里把**稳定源 ID** 收成一处、统一为小写 snake_case，缓存键与血缘
 * 归属都只认这套 ID；中文展示名由 [displayName] 单点映射，绝不外泄到逻辑里。
 *
 * ## 稳定源 ID 清单
 * - [SINA] = `"sina"`
 * - [TENCENT] = `"tencent"`
 * - [EASTMONEY_SKILL] = `"eastmoney_skill"`
 * - [EASTMONEY_FUND] = `"eastmoney_fund"`
 * - [BINANCE] = `"binance"`
 *
 * ## 兼容性
 * 磁盘旧缓存里可能残留中文源名（如 `"新浪财经"`），[normalizeLegacySource]
 * 统一归一成稳定 ID，保证血缘口径不被历史数据污染；任何未知串原样返回，保底不崩。
 */
object SourceIds {
    const val SINA = "sina"
    const val TENCENT = "tencent"
    const val EASTMONEY_SKILL = "eastmoney_skill"
    const val EASTMONEY_FUND = "eastmoney_fund"
    const val BINANCE = "binance"

    /** 仅用于内存/磁盘缓存命中且原始源不可考时，避免把缓存价错标成某个真实行情源 */
    const val MEMORY_CACHE = "memory_cache"

    /** 稳定 ID → 中文展示名。未知 ID 原样返回，绝不抛异常。 */
    fun displayName(id: String): String = when (id) {
        SINA -> "新浪财经"
        TENCENT -> "腾讯财经"
        EASTMONEY_SKILL -> "妙想Skill"
        EASTMONEY_FUND -> "东方财富"
        BINANCE -> "币安"
        MEMORY_CACHE -> "内存缓存"
        else -> id
    }

    /**
     * 把磁盘旧缓存 / 历史日志里可能残留的中文源名归一成稳定 ID。
     *
     * 保底不崩：任何未知串（包括已经是稳定 ID 的串）原样返回，调用方据此继续
     * 走血缘流程，不会因一个旧值而中断整轮同步。
     */
    fun normalizeLegacySource(raw: String): String = when (raw) {
        ApiManager.API_SINA, SINA -> SINA
        ApiManager.API_TENCENT, TENCENT -> TENCENT
        ApiManager.API_SKILL, EASTMONEY_SKILL -> EASTMONEY_SKILL
        else -> raw
    }
}
