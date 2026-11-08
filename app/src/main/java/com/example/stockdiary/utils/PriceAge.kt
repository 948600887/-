package com.example.stockdiary.utils

/**
 * 价格「数据年龄」的唯一判定口径（纯函数，无 Android / Compose 依赖，JVM 单测可直调）。
 *
 * ## 为什么要有这一层
 *
 * [com.example.stockdiary.database.Asset.lastSyncTime] 只在**成功落到新价**时写入。
 * 这里把分级、阈值、文案格式收成一处，供资产详情页与价格血缘展示共用，杜绝两套口径。
 *
 * 注意：首页 / 持仓**卡片**已不再展示数据年龄（也不再用 24 小时判定做警示），
 * 卡片只在「上一次刷新该资产确实失败」时提示「上次更新未成功」（见
 * `AssetSyncFailureStore`）。本文件保留给详情页，两者语义有意不同：
 * 这里是「价格有多旧」，那边是「这次刷新成没成功」，不要互相替代。
 *
 * ## 阈值与理由（抽成常量，单测逐档钉死）
 *
 * - [PRICE_AGE_VISIBLE_THRESHOLD_MS] = 1 小时：行情缓存的刻意设计是
 *   交易时段 5 分钟 TTL、16 点后 / 周末复用收盘价（[com.example.stockdiary.data.price.PriceCacheManager]）。
 *   1 小时内的价格对用户决策仍算新鲜，满屏「刚刚 / 3 分钟前」只会淹没真正要紧的提示；
 *   超过 1 小时起才**常驻**显示年龄，格式沿用详情页的 " · N小时前更新"。
 * - [PRICE_AGE_STALE_THRESHOLD_MS] = 24 小时：跨过一个交易日（含周末）仍没更新，
 *   多半是行情源/网络异常、标的长期停牌或缓存旧价伪装成新数据。此时只靠灰色小字
 *   不够，必须给一条可见警示；颜色取主题里的 error（MaterialTheme.colorScheme.error /
 *   AppTheme.colors.error，明暗主题各自适配），**不硬编码深色/浅色**。
 *
 * `lastSyncTime == 0` = 从未成功同步过（手工录入、行情一直没抓到）。此时必须返回空串：
 * `0L.toRelativeTime()` 会算成「几十年前」，比不显示更糟；卡片保持原有的「价格待更新」。
 */
const val PRICE_AGE_VISIBLE_THRESHOLD_MS: Long = 60L * 60L * 1000L
const val PRICE_AGE_STALE_THRESHOLD_MS: Long = 24L * 60L * 60L * 1000L

/** 数据年龄四档：从未同步 / 新鲜 / 需要显示年龄 / 过期警示。 */
enum class PriceAgeLevel {
    /** lastSyncTime <= 0：从未同步，卡片保持「价格待更新」，不显示年龄 */
    NEVER_SYNCED,

    /** 1 小时内：足够新鲜，不占用卡片篇幅 */
    FRESH,

    /** 1 小时 ~ 24 小时：常驻显示 " · N小时前更新" */
    AGED,

    /** 超过 24 小时：显示年龄 + 主题 error 色可见警示 */
    STALE
}

/**
 * 按 [lastSyncTime] 与 [nowMillis] 分级。
 *
 * `nowMillis` 显式可注入，便于 JVM 单测钉死 0 / 1h 内 / >1h / >24h 四档边界，
 * 不依赖真实时钟。时钟回拨（`lastSyncTime` 在未来）按 FRESH 处理，绝不显示负年龄。
 */
fun priceAgeLevel(
    lastSyncTime: Long,
    nowMillis: Long = System.currentTimeMillis()
): PriceAgeLevel {
    if (lastSyncTime <= 0L) return PriceAgeLevel.NEVER_SYNCED
    val age = nowMillis - lastSyncTime
    if (age <= 0L) return PriceAgeLevel.FRESH
    return when {
        age < PRICE_AGE_VISIBLE_THRESHOLD_MS -> PriceAgeLevel.FRESH
        age < PRICE_AGE_STALE_THRESHOLD_MS -> PriceAgeLevel.AGED
        else -> PriceAgeLevel.STALE
    }
}

/**
 * 数据年龄文案（纯函数，无 Android 依赖，JVM 单测可直调）。
 *
 * 与 [priceAgeStamp] 共用同一套「分钟/小时/天」分级，但**不退回日期串**：
 * 哪怕超过 7 天，仍然返回 `"N天前"`，而不是 [Long.toRelativeTime] 那种具体日期。
 * 卡片上「N天前」比一个绝对日期更易读、也更不容易让人误以为「刚更新」。
 *
 * `nowMillis` 与阈值判定用**同一个**传入值，保证分级与文案严格同源、不漂移。
 * `lastSyncTime <= 0`（从未同步）返回空串——调用方据此保持「价格待更新」。
 */
fun priceAgeLabel(
    lastSyncTime: Long,
    nowMillis: Long = System.currentTimeMillis()
): String {
    if (lastSyncTime <= 0L) return ""
    val diff = nowMillis - lastSyncTime
    if (diff <= 0L) return "刚刚" // 时钟回拨：绝不显示负年龄
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000}分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000}小时前"
        else -> "${diff / 86_400_000}天前" // 超过 7 天仍返回「N天前」，不退回日期串
    }
}

/**
 * 价格旁的统一后缀，复用 [priceAgeLabel] 的文案（分钟/小时/天前）。
 *
 * 详情页的 syncStamp 调它，格式永远一致：
 * `""` 或 `" · 2小时前更新"` / `" · 3天前更新"`（超过 7 天也是「N天前更新」，无日期串）。
 */
fun priceAgeStamp(
    lastSyncTime: Long,
    nowMillis: Long = System.currentTimeMillis()
): String = when (priceAgeLevel(lastSyncTime, nowMillis)) {
    PriceAgeLevel.NEVER_SYNCED, PriceAgeLevel.FRESH -> ""
    PriceAgeLevel.AGED, PriceAgeLevel.STALE -> " · ${priceAgeLabel(lastSyncTime, nowMillis)}更新"
}

/** 是否需要在 UI 上给出「数据过期」可见警示（主题 error 色）。 */
fun isPriceStale(
    lastSyncTime: Long,
    nowMillis: Long = System.currentTimeMillis()
): Boolean = priceAgeLevel(lastSyncTime, nowMillis) == PriceAgeLevel.STALE
