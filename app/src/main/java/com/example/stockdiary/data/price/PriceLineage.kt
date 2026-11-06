package com.example.stockdiary.data.price

import com.example.stockdiary.utils.priceAgeLabel
import java.util.concurrent.ConcurrentHashMap

/**
 * 轮次级「价格数据血缘」（G3：零迁移、进程内内存方案，重启即空）。
 *
 * ## 为什么是内存方案
 * 血缘要回答的只是「当前这一轮同步里，某只代码的价到底从哪个源、哪个口径来」，
 * 重启后上一轮的本就无意义，没必要为此升 Room、动 `@Entity` / schema json /
 * MigrationTest。重启不展示血缘是刻意设计；而「数据陈旧 / 过期警示」由
 * [com.example.stockdiary.database.Asset.lastSyncTime] + [com.example.stockdiary.utils.PriceAge]
 * 保证，始终可见、与血缘无关。
 *
 * ## 三个 origin 口径
 * - [LineageOrigin.FILE_CACHE]：命中 [PriceCacheManager] 磁盘缓存旧价；
 * - [LineageOrigin.ROOM_FUND_NAV]：来自 fund_nav_cache（基金净值），其
 *   `millionCopiesIncome`（货币基金口径）**单独成列、完全不参与血缘、一行不动**；
 * - [LineageOrigin.IN_MEMORY]：本轮实时抓取 / 门面 60s 缓存命中的价。
 */
enum class LineageOrigin {
    /** 命中 PriceCacheManager 磁盘缓存旧价 */
    FILE_CACHE,

    /** 来自 fund_nav_cache（基金净值） */
    ROOM_FUND_NAV,

    /** 本轮实时抓取 / 门面内存缓存命中的价 */
    IN_MEMORY
}

/**
 * 单条价格血缘记录。
 *
 * @param code 资产代码
 * @param sourceId 稳定源 ID（[SourceIds] 常量），用于 UI 展示与归属
 * @param origin 数据来源口径（文件缓存 / 基金净值 / 内存）
 * @param asOf 数据本身的 as-of 时间戳（用于「数据年龄」计算）
 * @param fetchedAt 本条血缘被记录的时间
 * @param roundId 所属同步轮次 ID
 * @param navDate 基金净值日（仅 [LineageOrigin.ROOM_FUND_NAV] 可能非 null）
 * @param trigger 触发场景文案（仅记录，不进 [uiLabel]）
 */
data class PriceLineage(
    val code: String,
    val sourceId: String,
    val origin: LineageOrigin,
    val asOf: Long,
    val fetchedAt: Long,
    val roundId: Int,
    val navDate: String? = null,
    val trigger: String = ""
) {
    /** 源中文展示名：未知 sourceId 由 [SourceIds.displayName] 回退原串 */
    val displayName: String get() = SourceIds.displayName(sourceId)

    /**
     * 唯一 UI 文案来源（纯函数）。
     *
     * 规则：
     * - 基金特例（[navDate] != null）：用 ` · 净值日$navDate（${age}）` 代替普通年龄段；
     * - 普通资产：年龄段为 ` · ${PriceAge.priceAgeLabel(asOf)}更新`；
     * - [roundId] == 0 时不显示「轮次」段，否则末尾补 ` · 轮次#$roundId`；
     * - sourceId 未知时 [displayName] 回退原串。
     *
     * 注：「无任何血缘（构造不出 [PriceLineage]）时 UI 显示空串」由调用方决定，
     * 本属性始终基于已有字段产出可读文案。
     */
    val uiLabel: String
        get() {
            val ageSegment: String = if (navDate != null) {
                " · 净值日$navDate（${priceAgeLabel(asOf)}）"
            } else {
                " · ${priceAgeLabel(asOf)}更新"
            }
            val withRound: String = if (roundId != 0) "$ageSegment · 轮次#$roundId" else ageSegment
            return "$displayName$withRound"
        }
}

/**
 * 进程内血缘记录器（G3 仅建对象，不在同步流里调用；接入在 G4/G5）。
 *
 * 用 [ConcurrentHashMap] 存「code → 最新血缘」，重启即空（零 DB 迁移）。
 * 当前未被任何同步流调用，故对运行时行为零影响；单测通过 [resetForTesting]
 * 跨用例复位（object 单例跨用例持久，必须手动清）。
 */
object PriceLineageRecorder {
    private val store = ConcurrentHashMap<String, PriceLineage>()

    /**
     * 标记一轮同步开始。
     *
     * 语义上应丢掉上一轮的残留血缘（避免跨轮串味）。G3 不接入同步流，此处先按
     * 「开新轮即清旧」实现，留待 G4 接入时按需扩展（如记轮次元信息）。
     */
    fun beginRound(roundId: Int, trigger: String) {
        store.clear()
    }

    /** 记录 / 覆盖某代码的血缘 */
    fun report(code: String, lineage: PriceLineage) {
        store[code] = lineage
    }

    /** 当前全部血缘快照（不可变拷贝，防止外部改到内部表） */
    fun snapshot(): Map<String, PriceLineage> = store.toMap()

    /** 清空全部血缘（调用方显式废弃） */
    fun clear() {
        store.clear()
    }

    /** 测试专用复位：清空进程内单例，保证用例间隔离 */
    fun resetForTesting() {
        store.clear()
    }
}
