package com.example.stockdiary.data.importer

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
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
 *
 * ## 对齐算法在哪
 *
 * 「按代码合并 → 与库存匹配 → NEW/UPDATE/UNCHANGED/CONFLICT 判定 → 落库」这套内核
 * 与币安导入共用，见 [SnapshotAlignment]（同构度约 80%，两处各写一遍必然漂移）。
 * 本类退化成薄壳，只负责：把截图/xlsx 解析结果映射成内核的 [RawHolding]，
 * 基金来源特有的 nav/navDate/currency 透传，以及 `fund_` 前缀的类型推断。
 */
class HoldingSnapshotService(
    private val assetRepository: AssetRepository
) {

    /** 内核写入口（适配仓库的三个 suspend 写操作） */
    private val writer: SnapshotAlignment.SnapshotAssetWriter =
        assetRepository.asSnapshotAssetWriter()

    /** 本链路的落库策略：默认账户名、资产类型推断、币种兜底 */
    private val commitPolicy = SnapshotAlignment.CommitPolicy(
        defaultAccount = DEFAULT_ACCOUNT,
        assetTypeOf = ::assetTypeFor,
        defaultCurrency = DEFAULT_CURRENCY
    )

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
        val matchedAccounts: List<String> = emptyList(),
        /**
         * 净值。只有基金E账户的导出里有。
         * 落库时直接写进 `currentPrice`——它是官方给的数，不是凭空造的，
         * 市值得等一次行情同步才显示属于无谓的等待。
         */
        val nav: Double? = null,
        /**
         * 净值日期。与 [Preview.dataDate] 不一致时界面要提示——
         * 实测 QDII 的净值会比其它基金更旧，不能让用户以为全是同一天的数。
         */
        val navDate: String? = null,
        /**
         * 结算币种代码。为 null 表示同一代码的多条来源币种不一致，
         * 这时不该猜一个用——界面要标出来让用户自己处理。
         */
        val currency: String? = null
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
        // 一次批量查完，避免每个代码查一次库（截图几十条会变成几十次查询）。
        // 分片是因为 SQLite 的 IN 子句有变量个数上限；长截图上百条，不能赌「用户不会截那么长」
        val existing = parsed.holdings
            .map { StockCodeManager.normalizeSymbol(it.symbol) }
            .distinct()
            .chunked(SQL_IN_CHUNK)
            .flatMap { assetRepository.getAssetsBySymbols(it) }

        val items = SnapshotAlignment.align(
            rows = parsed.holdings,
            existing = existing,
            // 库里同一代码可能分属多个账户，而快照只有一个总数：
            // 合计对不上时不猜，判冲突交给用户手动处理
            multiAccountConflict = true
        ) { holding ->
            SnapshotAlignment.RawHolding(
                // 顺手把裸码规范成库存形态（600519 → sh600519），否则查库一条都匹配不上
                symbol = StockCodeManager.normalizeSymbol(holding.symbol),
                name = holding.name,
                quantity = holding.quantity,
                rawQuantity = holding.rawQuantity,
                nav = holding.nav,
                navDate = holding.navDate,
                currency = holding.currency
            )
        }.map { aligned ->
            SnapshotItem(
                symbol = aligned.symbol,
                // 名称以库里为准：覆盖只动数量，用户自己整理过的名字不被 OCR 结果冲掉
                name = aligned.matched.singleOrNull()?.name ?: aligned.name,
                quantity = aligned.quantity,
                rawQuantity = aligned.rawQuantity,
                // 同代码多条时不猜：existing 只在唯一命中时给出
                existing = aligned.matched.singleOrNull(),
                status = aligned.status.toUiStatus(),
                sources = aligned.sources,
                matchedAccounts = aligned.matchedAccounts,
                nav = aligned.nav,
                navDate = aligned.navDate,
                currency = aligned.currency
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
     * 落库。只处理 [ItemStatus.NEW] 与 [ItemStatus.UPDATE]，未变的跳过。
     *
     * @param account 用户输入的账户名，只用于**新建**的资产；
     *                已存在的资产保持它原来的账户，不跟着改。
     */
    suspend fun commit(preview: Preview, account: String): CommitResult {
        val result = SnapshotAlignment.commit(
            rows = preview.items.map { it.toCommitRow() },
            writer = writer,
            account = account,
            policy = commitPolicy
        )

        AppLogger.i(
            "持仓快照落库：新增 ${result.inserted}，覆盖 ${result.updated}，" +
                "跳过 ${result.skipped}，冲突 ${result.conflicts}",
            "HoldingSnapshot"
        )

        return CommitResult(
            inserted = result.inserted,
            updated = result.updated,
            skipped = result.skipped,
            conflicts = result.conflicts
        )
    }

    /**
     * 决定落库时的资产类型。
     *
     * **带 `fund_` 前缀的一律是场外基金**，不能用 [StockCodeManager.classifyCode] 去推：
     * 场外基金与深市股票共用 00xxxx 号段，而 classifyCode 只把「004xxx-009xxx」当场外，
     * 于是 `000001`（华夏成长混合）会被判成股票、`002001`（华夏回报）也一样。
     * 这些早年发行的基金代码恰好都落在那段里，是历史上绕不开的坑。
     */
    private fun assetTypeFor(symbol: String): AssetType =
        if (symbol.startsWith(FundEStatementParser.SYMBOL_PREFIX, ignoreCase = true)) {
            AssetType.FUND
        } else {
            StockCodeManager.classifyCode(symbol).toAssetType()
        }

    /** 预览项 → 内核行视图。status 映射回内核枚举，其余字段原样带 */
    private fun SnapshotItem.toCommitRow(): SnapshotAlignment.CommitRow =
        SnapshotAlignment.CommitRow(
            symbol = symbol,
            name = name,
            quantity = quantity,
            status = status.toKernelStatus(),
            existing = existing,
            nav = nav,
            currency = currency
        )

    /** 内核状态 → 本链路 UI 状态（两边枚举常量一一对应） */
    private fun SnapshotAlignment.ItemStatus.toUiStatus(): ItemStatus = when (this) {
        SnapshotAlignment.ItemStatus.NEW -> ItemStatus.NEW
        SnapshotAlignment.ItemStatus.UPDATE -> ItemStatus.UPDATE
        SnapshotAlignment.ItemStatus.UNCHANGED -> ItemStatus.UNCHANGED
        SnapshotAlignment.ItemStatus.CONFLICT -> ItemStatus.CONFLICT
    }

    /** 本链路 UI 状态 → 内核状态 */
    private fun ItemStatus.toKernelStatus(): SnapshotAlignment.ItemStatus = when (this) {
        ItemStatus.NEW -> SnapshotAlignment.ItemStatus.NEW
        ItemStatus.UPDATE -> SnapshotAlignment.ItemStatus.UPDATE
        ItemStatus.UNCHANGED -> SnapshotAlignment.ItemStatus.UNCHANGED
        ItemStatus.CONFLICT -> SnapshotAlignment.ItemStatus.CONFLICT
    }

    // ---------------------------------------------------------------------------
    // Phase 2b 双跑期保留区（下一提交删除）
    //
    // 下面是重构前的内联算法，一字未改。上面的 buildPreview / commit 已改走
    // [SnapshotAlignment] 内核；旧代码暂时留着是为了：① 评审时能逐行 diff 旧与新；
    // ② 新实现万一有漏，把上面两个方法体换回旧的一步即可回退。
    //
    // 等价性证明：现有 HoldingSnapshotServiceTest 零修改全绿——那套用例覆盖
    // 同代码合并、三种状态、多账户冲突、规范化查库、分片查询、落库各分支。
    // ---------------------------------------------------------------------------

    /** 重构前的对齐实现（双跑期保留，不被调用） */
    private suspend fun buildPreviewLegacy(parsed: HoldingSnapshotResult): Preview {
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
                matched.size > 1 -> resolveMultiAccountStatusLegacy(matched, quantity)
                existing == null -> ItemStatus.NEW
                // 浮点比较：持仓数量来自 OCR 文本，整数份额不会有精度问题，
                // 但用容差挡住 1e-9 级别的表示误差
                abs(existing.balance - quantity) < 1e-6 -> ItemStatus.UNCHANGED
                else -> ItemStatus.UPDATE
            }
            // 同代码合并时取**最新**那份净值：同一只基金在不同销售机构导出的净值
            // 一般是同一天的，但万一有先后，取日期靠前的那只净值会低估市值。
            // yyyy-MM-dd 的字典序就是时间序，直接比字符串即可
            val latestNav = rows.filter { it.nav != null }.maxByOrNull { it.navDate.orEmpty() }

            SnapshotItem(
                symbol = symbol,
                name = existing?.name ?: first.name,
                quantity = quantity,
                rawQuantity = if (rows.size == 1) first.rawQuantity else rows.joinToString("+") { it.rawQuantity },
                existing = existing,
                status = status,
                sources = rows,
                matchedAccounts = matched.map { it.account },
                nav = latestNav?.nav,
                navDate = latestNav?.navDate,
                // 同代码多渠道合并时取币种：正常情况下都一样。
                // 万一不一致（同一只基金在不同渠道以不同币种结算），
                // 取不到共识就不该静默用一个——交给预览页标出来
                currency = rows.map { it.currency }.distinct().singleOrNull()
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

    /** 重构前的落库实现（双跑期保留，不被调用） */
    private suspend fun commitLegacy(preview: Preview, account: String): CommitResult {
        val toInsert = mutableListOf<Asset>()
        var updated = 0
        var skipped = 0
        var conflicts = 0

        for (item in preview.items) {
            when (item.status) {
                ItemStatus.NEW -> toInsert.add(
                    Asset(
                        name = item.name,
                        type = assetTypeFor(item.symbol),
                        symbol = item.symbol,
                        account = account.ifBlank { DEFAULT_ACCOUNT },
                        balance = item.quantity,
                        // 参考单价留 0：它是「无行情时的市值兜底价」，
                        // 给个假数字会让市值凭空多出来。有净值就走 currentPrice，没净值等行情同步。
                        purchasePrice = 0.0,
                        currentPrice = item.nav?.takeIf { it > 0.0 },
                        // 结算币种必须按导入数据写：默认 CNY 会让一只美元结算的 QDII
                        // 被按人民币数值直接汇总。null（多来源币种不一致）时不猜，留默认
                        currency = item.currency ?: "CNY"
                    )
                )

                ItemStatus.UPDATE -> {
                    val existing = item.existing ?: continue
                    assetRepository.updateBalance(existing.id, item.quantity)
                    // 净值也是权威数据：份额变了，价格同样该跟着刷新，
                    // 否则用户会看到「数量更新了、市值还是上周的」
                    item.nav?.takeIf { it > 0.0 }?.let { nav ->
                        assetRepository.updatePriceById(existing.id, nav)
                    }
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

    /**
     * 库里同一个代码有多条时（不同账户各持一份）判定该干什么。
     *
     * 这张快照给的是**总数**，没法知道怎么摊到各账户：
     * - 加起来正好等于快照数量 → 说明各账户的拆分本来就是对的，什么也不用动
     * - 对不上 → 不敢猜，判 [ItemStatus.CONFLICT] 交给用户手动处理。
     *   挑一条覆盖会让总持仓凭空多出一份，比什么都不做错得多
     */
    private fun resolveMultiAccountStatusLegacy(matched: List<Asset>, quantity: Double): ItemStatus =
        if (abs(matched.sumOf { it.balance } - quantity) < 1e-6) {
            ItemStatus.UNCHANGED
        } else {
            ItemStatus.CONFLICT
        }

    // ---------------------------------------------------------------------------
    // 双跑期保留区结束
    // ---------------------------------------------------------------------------

    companion object {
        /** 用户没填账户名时的兜底，跟交割单导入那边保持一致 */
        const val DEFAULT_ACCOUNT = "默认账户"

        /** 结算币种兜底：多来源币种不一致时不猜，回落人民币 */
        private const val DEFAULT_CURRENCY = "CNY"

        /**
         * 单次 IN 查询的代码个数上限。
         *
         * SQLite 的 IN 子句变量个数上限是 999，取 500 留出余量。
         * 超过就分批查再合并——长截图上百条持仓是真实存在的场景。
         */
        private const val SQL_IN_CHUNK = 500
    }
}
