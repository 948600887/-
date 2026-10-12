package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlin.math.abs

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
 *
 * ## 代码形态：必须走 [StockCodeManager.normalizeSymbol]
 *
 * 全项目存库的代码是这个 **带市场前缀** 的形态（600519 → `sh600519`），
 * 这在 BrokerageImportService 里写成了约定。截图里给的是裸的 6 位码，
 * 直接拿去查库一条都匹配不上——表现为「每次导入都在造重复资产」，而且新行的
 * 行情同步、分红匹配也全跟着失效。所以这里统一规范化后再查库、再落库。
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
        UNCHANGED,

        /**
         * 库里同一代码有**多条**（分属不同账户），而这张快照只有一个总数——
         * 分不清该把这份额算到哪条上。这种情况一条都不敢动：
         * 挑一条覆盖会凭空多出一份持仓，比不动错得更离谱。
         */
        CONFLICT
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
        val sources: List<ParsedHolding>,
        /** 库里同代码命中的**全部**账户：[ItemStatus.CONFLICT] 时要告诉用户是哪几个在抢 */
        val matchedAccounts: List<String> = emptyList()
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
        val conflictItems: List<SnapshotItem> get() = items.filter { it.status == ItemStatus.CONFLICT }

        /**
         * 需要落库的条数。
         *
         * 显式只数 NEW + UPDATE，不放任 CONFLICT 混进来——它是「没法处理」，
         * 不是「处理完」；一旦计入，界面上的「导入 N 条」就会变成一句假承诺。
         */
        val pendingCount: Int
            get() = items.count { it.status == ItemStatus.NEW || it.status == ItemStatus.UPDATE }
    }

    data class CommitResult(
        val inserted: Int,
        val updated: Int,
        val skipped: Int,
        /** 因库里同代码有多条而不敢动的条数 */
        val conflicts: Int = 0
    )

    /**
     * 把解析结果对齐到库里的资产，产出预览。**不写库**。
     */
    suspend fun buildPreview(parsed: HoldingSnapshotResult): Preview {
        // 同代码先合并：中国结算会按托管机构把同一只票拆成多行。
        // 顺手把裸码规范成库存形态（600519 → sh600519），否则查库一条都匹配不上
        val merged = parsed.holdings.groupBy { StockCodeManager.normalizeSymbol(it.symbol) }

        // 一次批量查完，避免每个代码查一次库（截图几十条会变成几十次查询）。
        // 分片是因为 SQLite 的 IN 子句有变量个数上限；长截图上百条，不能赌「用户不会截那么长」
        val existingGroups = merged.keys
            .chunked(SQL_IN_CHUNK)
            .flatMap { assetRepository.getAssetsBySymbols(it) }
            .groupBy { it.symbol }

        val items = merged.map { (symbol, rows) ->
            val first = rows.first()
            val quantity = rows.sumOf { it.quantity }
            val matched = existingGroups[symbol].orEmpty()
            val existing = matched.singleOrNull()
            val status = when {
                matched.size > 1 -> resolveMultiAccountStatus(matched, quantity)
                existing == null -> ItemStatus.NEW
                // 浮点比较：持仓数量来自 OCR 文本，整数份额不会有精度问题，
                // 但用容差挡住 1e-9 级别的表示误差
                abs(existing.balance - quantity) < 1e-6 -> ItemStatus.UNCHANGED
                else -> ItemStatus.UPDATE
            }
            SnapshotItem(
                symbol = symbol,
                name = existing?.name ?: first.name,
                quantity = quantity,
                rawQuantity = if (rows.size == 1) first.rawQuantity else rows.joinToString("+") { it.rawQuantity },
                existing = existing,
                status = status,
                sources = rows,
                matchedAccounts = matched.map { it.account }
            )
        }

        AppLogger.i(
            "持仓快照对齐完成：共 ${items.size} 条，新增 ${items.count { it.status == ItemStatus.NEW }}，" +
                "覆盖 ${items.count { it.status == ItemStatus.UPDATE }}，" +
                "未变 ${items.count { it.status == ItemStatus.UNCHANGED }}，" +
                "冲突 ${items.count { it.status == ItemStatus.CONFLICT }}",
            "HoldingSnapshot"
        )

        return Preview(items = items, dataDate = parsed.dataDate, incomplete = parsed.incomplete)
    }

    /**
     * 库里同一个代码有多条时（不同账户各持一份）判定该干什么。
     *
     * 这张快照给的是**总数**，没法知道怎么摊到各账户：
     * - 加起来正好等于快照数量 → 说明各账户的拆分本来就是对的，什么也不用动
     * - 对不上 → 不敢猜，判 [ItemStatus.CONFLICT] 交给用户手动处理。
     *   挑一条覆盖会让总持仓凭空多出一份，比什么都不做错得多
     */
    private fun resolveMultiAccountStatus(matched: List<Asset>, quantity: Double): ItemStatus =
        if (abs(matched.sumOf { it.balance } - quantity) < 1e-6) {
            ItemStatus.UNCHANGED
        } else {
            ItemStatus.CONFLICT
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
        var conflicts = 0

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

                // 库里同代码有多条，分不清该改哪一条 → 一条都不动，
                // 在结果页告诉用户这几条要他自己处理
                ItemStatus.CONFLICT -> conflicts++
            }
        }

        if (toInsert.isNotEmpty()) assetRepository.insertAssets(toInsert)

        AppLogger.i(
            "持仓快照落库：新增 ${toInsert.size}，覆盖 $updated，跳过 $skipped，冲突 $conflicts",
            "HoldingSnapshot"
        )
        return CommitResult(
            inserted = toInsert.size,
            updated = updated,
            skipped = skipped,
            conflicts = conflicts
        )
    }

    companion object {
        /** 用户没填账户名时的兜底，跟交割单导入那边保持一致 */
        const val DEFAULT_ACCOUNT = "默认账户"

        /**
         * 单次 IN 查询的代码个数上限。
         *
         * SQLite 的 IN 子句变量个数上限是 999，取 500 留出余量。
         * 超过就分批查再合并——长截图上百条持仓是真实存在的场景。
         */
        private const val SQL_IN_CHUNK = 500
    }
}
