package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager

/**
 * 持仓快照导入：把解析出来的持仓与库里已有的资产对齐，产出预览、确认后落库。
 *
 * ## 与交割单导入的区别（为什么要单独一个服务）
 *
 * [BrokerageImportService] 吃的是**流水**，按日期回放：买入合并加权成本、卖出减仓。
 * 持仓截图给的是**当前余额快照**——它不关心你怎么买到的，只说明「现在有多少」。
 * 拿快照去走回放流程是错的（一笔「卖出」会因为找不到对应持仓而被跳过）。
 *
 * ## 对齐规则（用户拍板）
 *
 * - 同代码跨券商**合并成一条**：中国结算按托管机构分行显示，同一只票可能出现在多行，
 *   数量相加后作为这一条的数量。
 * - 库里已有同代码时**覆盖数量**（以官方数据为准），不追加。
 *   追加只在「把 App 当成额外仓位记录本」时才对，那会让数量越导越多。
 * - 覆盖只动数量：**名称与账户保持库里原样**，避免把用户自己整理过的分类打乱。
 */
class HoldingSnapshotService(
    private val assetRepository: AssetRepository
) {

    enum class ItemStatus {
        /** 库里没有，要新建 */
        NEW,

        /** 库里已有，数量不同，按快照覆盖 */
        UPDATE,

        /** 库里已有且数量一致，不用动 */
        UNCHANGED
    }

    /**
     * 预览里的一项。
     *
     * [sources] 是合并前的原始条目——同代码多券商时会有多条，
     * 预览页要让用户看得见「这条其实是两笔加起来的」。
     */
    data class SnapshotItem(
        val symbol: String,
        val name: String,
        val quantity: Double,
        val rawQuantity: String,
        val existing: Asset?,
        val status: ItemStatus,
        val sources: List<ParsedHolding>
    ) {
        /** 数量变化量；新建时就是全部数量 */
        val delta: Double
            get() = quantity - (existing?.balance ?: 0.0)
    }

    data class Preview(
        val items: List<SnapshotItem>,
        val dataDate: String?,
        /** 认得出代码却没配全的行（截图被裁、OCR 漏行），原样带给界面提示 */
        val incomplete: List<String>
    ) {
        val newItems: List<SnapshotItem> get() = items.filter { it.status == ItemStatus.NEW }
        val updatedItems: List<SnapshotItem> get() = items.filter { it.status == ItemStatus.UPDATE }
        val unchangedItems: List<SnapshotItem> get() = items.filter { it.status == ItemStatus.UNCHANGED }

        /** 需要落库的条数（未变的不算） */
        val pendingCount: Int get() = items.count { it.status != ItemStatus.UNCHANGED }
    }

    data class CommitResult(
        val inserted: Int,
        val updated: Int,
        val skipped: Int
    )

    /**
     * 把解析结果对齐到库里的资产，产出预览。**不写库**。
     */
    suspend fun buildPreview(parsed: HoldingSnapshotResult): Preview {
        // 同代码先合并：中国结算会按托管机构把同一只票拆成多行
        val merged = parsed.holdings.groupBy { it.symbol }.map { (symbol, rows) ->
            val first = rows.first()
            Triple(symbol, rows, first)
        }

        // 一次批量查完，避免每个代码查一次库（截图几十条会变成几十次查询）
        val existingBySymbol = assetRepository
            .getAssetsBySymbols(merged.map { it.first })
            .associateBy { it.symbol }

        val items = merged.map { (symbol, rows, first) ->
            val quantity = rows.sumOf { it.quantity }
            val existing = existingBySymbol[symbol]
            val status = when {
                existing == null -> ItemStatus.NEW
                // 浮点比较：持仓数量来自 OCR 文本，整数份额不会有精度问题，
                // 但用容差挡住 1e-9 级别的表示误差
                kotlin.math.abs(existing.balance - quantity) < 1e-6 -> ItemStatus.UNCHANGED
                else -> ItemStatus.UPDATE
            }
            SnapshotItem(
                symbol = symbol,
                name = existing?.name ?: first.name,
                quantity = quantity,
                rawQuantity = if (rows.size == 1) first.rawQuantity else rows.joinToString("+") { it.rawQuantity },
                existing = existing,
                status = status,
                sources = rows
            )
        }

        AppLogger.i(
            "持仓快照对齐完成：共 ${items.size} 条，新增 ${items.count { it.status == ItemStatus.NEW }}，" +
                "覆盖 ${items.count { it.status == ItemStatus.UPDATE }}，" +
                "未变 ${items.count { it.status == ItemStatus.UNCHANGED }}",
            "HoldingSnapshot"
        )

        return Preview(items = items, dataDate = parsed.dataDate, incomplete = parsed.incomplete)
    }

    /**
     * 落库。只处理 [ItemStatus.NEW] 与 [ItemStatus.UPDATE]，未变的跳过。
     *
     * @param account 用户输入的账户名，只用于**新建**的资产；
     *                已存在的资产保持它原来的账户，不跟着改。
     */
    suspend fun commit(preview: Preview, account: String): CommitResult {
        val toInsert = mutableListOf<Asset>()
        var updated = 0
        var skipped = 0

        for (item in preview.items) {
            when (item.status) {
                ItemStatus.NEW -> toInsert.add(
                    Asset(
                        name = item.name,
                        type = StockCodeManager.classifyCode(item.symbol).toAssetType(),
                        symbol = item.symbol,
                        account = account.ifBlank { DEFAULT_ACCOUNT },
                        balance = item.quantity,
                        // 参考单价留 0：持仓快照里没有价格信息，它是「无行情时的市值兜底价」，
                        // 给个假数字会让市值凭空多出来。留 0 等行情同步来填 currentPrice。
                        purchasePrice = 0.0
                    )
                )

                ItemStatus.UPDATE -> {
                    val existing = item.existing ?: continue
                    assetRepository.updateBalance(existing.id, item.quantity)
                    updated++
                }

                ItemStatus.UNCHANGED -> skipped++
            }
        }

        if (toInsert.isNotEmpty()) assetRepository.insertAssets(toInsert)

        AppLogger.i("持仓快照落库：新增 ${toInsert.size}，覆盖 $updated，跳过 $skipped", "HoldingSnapshot")
        return CommitResult(inserted = toInsert.size, updated = updated, skipped = skipped)
    }

    companion object {
        /** 用户没填账户名时的兜底，跟交割单导入那边保持一致 */
        const val DEFAULT_ACCOUNT = "默认账户"
    }
}
