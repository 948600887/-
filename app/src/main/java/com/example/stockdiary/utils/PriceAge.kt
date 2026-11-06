package com.example.stockdiary.utils

/**
 * 价格「数据年龄」的唯一判定口径（纯函数，无 Android / Compose 依赖，JVM 单测可直调）。
 *
 * ## 为什么要有这一层
 *
 * [com.example.stockdiary.database.Asset.lastSyncTime] 只在**成功落到新价**时写入，
 * 但首页 / 持仓卡片从前只在 `currentPrice == null` 时显示「价格待更新」：一个三天前
 * 抓到的旧价和一个刚抓到的新价在卡片上长得一模一样。详情页虽然已有 syncStamp，
 * 却另写了一份私有判定，两页口径必然漂移。这里把分级、阈值、文案格式收成一处，
 * 首页/持仓卡片与详情页共用，杜绝两套口径。
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
 * 价格旁的统一后缀，复用现有 [Long.toRelativeTime] 的文案（分钟/小时/天/日期）。
 *
 * 详情页的 syncStamp 与首页/持仓卡片都调它，格式永远一致：
 * `""` 或 `" · 2小时前更新"` / `" · 3天前更新"`。
 */
fun priceAgeStamp(
    lastSyncTime: Long,
    nowMillis: Long = System.currentTimeMillis()
): String = when (priceAgeLevel(lastSyncTime, nowMillis)) {
    PriceAgeLevel.NEVER_SYNCED, PriceAgeLevel.FRESH -> ""
    PriceAgeLevel.AGED, PriceAgeLevel.STALE -> " · ${lastSyncTime.toRelativeTime()}更新"
}

/** 是否需要在 UI 上给出「数据过期」可见警示（主题 error 色）。 */
fun isPriceStale(
    lastSyncTime: Long,
    nowMillis: Long = System.currentTimeMillis()
): Boolean = priceAgeLevel(lastSyncTime, nowMillis) == PriceAgeLevel.STALE
