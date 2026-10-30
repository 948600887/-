package com.example.stockdiary.data.importer

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import kotlin.math.abs

/**
 * 快照对齐内核：把「一份外部持仓清单」对齐到库里已有的资产，产出预览、确认后落库。
 *
 * ## 为什么两条链路要共享这个内核
 *
 * 持仓快照导入（[HoldingSnapshotService]）与币安持仓导入（[BinanceImportService]）
 * 的算法是同构的，逐行对照同构度约 80%：
 *
 * 1. 按归一化代码分组（快照侧是 [StockCodeManager.normalizeSymbol] 后的带前缀形态，
 *    币安侧是 `<基础资产>USDT` 交易对）；
 * 2. 同代码多行合并，数量求和；
 * 3. 与库存匹配，按浮点容差判定 NEW / UPDATE / UNCHANGED：普通资产 1e-6、加密货币 1e-12
 *    （见 [QUANTITY_TOLERANCE_CRYPTO]，避免第 10 位变动被吞）（快照侧多一个 CONFLICT）；
 * 4. commit 时 NEW 组装 [Asset] 批量 insert、UPDATE 走
 *    [SnapshotAssetWriter.updateBalance]、UNCHANGED 计数跳过。
 *
 * 两处各写一遍的后果是改一处忘一处：浮点容差、UNCHANGED 跳过计数、账户兜底
 * 这些规则任何一次漂移都会表现为「导入行为两个入口不一致」。所以内核收在这里，
 * 纯函数、无 IO、可 JVM 单测；两条链路各自退化成薄壳，只做「来源数据 → [RawHolding]」
 * 的映射。
 *
 * ## 哪些差异必须保留（不要统一）
 *
 * 评审明确点名，以下几件事是来源特质，**刻意不进内核逻辑，只以参数传入**：
 *
 * 1. **CONFLICT 状态**（多账户同代码冲突）只有持仓快照侧有——币安一个账户只有一份
 *    持仓，不存在「同代码跨多个账户」。由 [align] 的 `multiAccountConflict` 开关控制。
 * 2. **nav / navDate / currency** 只有基金E账户导出里有。内核只负责合并与透传
 *    （同代码取最新净值、币种取不到共识时给 null），不解释含义。
 * 3. **dust 过滤、市值阈值、USDT 映射、USD 币种**是币安来源特有，留在
 *    [BinanceImportService] 壳里（过滤在调 [align] 之前，币种走
 *    [CommitPolicy.defaultCurrency]）。
 * 4. **assetTypeFor** 两边各自的资产类型推断（快照侧 `fund_` 前缀一律场外基金，
 *    币安侧恒为 CRYPTO），由 [CommitPolicy.assetTypeOf] 传入。
 * 5. **默认账户名**「默认账户」vs「币安」是刻意的（来源一目了然），由
 *    [CommitPolicy.defaultAccount] 传入，不统一。
 *
 * 另有两处口径差异由壳层消化，内核不感知：
 *
 * - **existing 选取**：快照侧只认唯一命中（多账户时 `singleOrNull` 给 null，不猜），
 *   币安侧取第一条（`firstOrNull`）。壳层从 [AlignedItem.matched] 自己选。
 * - **两个 Service 的 ItemStatus 枚举不合并**：UI 的 `when (item.status)` 是穷尽匹配，
 *   币安对话框只有 3 个分支（没有 CONFLICT）。壳层各持有一套枚举，与内核枚举做一次映射。
 */
object SnapshotAlignment {

    /** 数量比较容差（非加密货币）：持仓数量来自 OCR 文本 / 接口字符串，挡住 1e-9 级浮点表示误差 */
    private const val QUANTITY_TOLERANCE = 1e-6

    /**
     * 加密货币数量常见到 10 位小数，真实变动可小到 1e-10。容差必须远小于它，否则第 10 位的
     * 变动会被判成「未变」而跳过更新——这正是「导入后持仓没更新」的隐形丢数据来源。
     * 1e-12 远小于 1e-10 的真实变动，又远大于 Double 浮点噪声（~1e-16），不会误判为变动。
     */
    private const val QUANTITY_TOLERANCE_CRYPTO = 1e-12

    /**
     * 对齐状态。
     *
     * [CONFLICT] 只有持仓快照链路会产生（`multiAccountConflict = true` 且库里同代码
     * 有多条）；币安链路的内核调用不开这个开关，永远走不到这个分支。
     */
    enum class ItemStatus {
        /** 库里没有这个代码，要新建 */
        NEW,

        /** 库里已有，数量不同，按快照覆盖 */
        UPDATE,

        /** 库里已有且数量一致（1e-6 容差），不用动 */
        UNCHANGED,

        /**
         * 库里同一代码有**多条**（分属不同账户），而这份快照只有一个总数——
         * 分不清该把这份额算到哪条上。这种情况一条都不敢动：挑一条覆盖会凭空
         * 多出一份持仓，比不动错得更离谱。
         */
        CONFLICT
    }

    /**
     * 对齐后的一项。不直接落库、不依赖任何 UI 类型；壳层再映射成自己的预览项。
     *
     * @param T 来源行类型（[ParsedHolding] / [com.example.stockdiary.network.BinanceBalance]…），
     *          原样带回 [sources]
     */
    data class AlignedItem<out T>(
        val symbol: String,
        val name: String,
        val quantity: Double,
        /** 原始数量写法：单行就是它自己，多行用 "+" 连接（"1000+2000"） */
        val rawQuantity: String,
        val status: ItemStatus,
        /** 库里同代码命中的全部资产；空列表即 [ItemStatus.NEW] */
        val matched: List<Asset>,
        /** 库里同代码命中的全部账户名：[ItemStatus.CONFLICT] 时要告诉用户是哪几个在抢 */
        val matchedAccounts: List<String>,
        /** 净值。同代码多行时取日期最新的一条（只有基金E账户导出有） */
        val nav: Double?,
        /** 净值日期（yyyy-MM-dd），与 [nav] 同源 */
        val navDate: String?,
        /** 结算币种；同代码多渠道不一致时为 null——不猜，交给预览页标出来 */
        val currency: String?,
        /** 合并前的原始条目，预览页要让用户看得见「这条其实是几笔加起来的」 */
        val sources: List<T>
    )

    /**
     * 来源无关的归一化持仓行：壳层把各自来源的数据映射成它再交给 [align]。
     *
     * @param symbol      **已归一化**的代码（快照侧 `sh600519`，币安侧 `BTCUSDT`）。
     *                    归一化是壳层的事——两条链路的规则不同，内核不猜
     * @param name        展示名。同代码合并时取第一行的
     * @param quantity    持有数量
     * @param rawQuantity 数量在来源里的原始写法（如 "1.44万"），预览页展示用；没有可传 null
     * @param nav         净值，只有基金E账户导出有
     * @param navDate     净值日期（yyyy-MM-dd）
     * @param currency    结算币种代码（CNY / USD…）
     */
    data class RawHolding(
        val symbol: String,
        val name: String,
        val quantity: Double,
        val rawQuantity: String? = null,
        val nav: Double? = null,
        val navDate: String? = null,
        val currency: String? = null
    )

    /**
     * 内核 commit 需要的行视图。
     *
     * 两条链路的 UI 预览项在各自 Service 里映射成它。之所以不让 UI 项直接实现某个
     * 接口：两边的 [ItemStatus] 枚举是两套类型（币安没有 CONFLICT），共用接口会把
     * 状态类型也绑成一套，那就等于把必须保留的差异统一掉了。
     *
     * @param existing UPDATE 时要覆盖数量的库内资产；库里有多条时用哪条由壳层定
     *                 （快照侧只在唯一命中时给值，币安侧给第一条）
     */
    data class CommitRow(
        val symbol: String,
        val name: String,
        val quantity: Double,
        val status: ItemStatus,
        val existing: Asset?,
        /**
         * 净值/参考价。基金E账户是权威净值；币安侧是拉取阶段批量取到的公开价
         * （导入即预填，见 [BinanceImportService.commit] 的 prices 参数）——查不到价时
         * 仍为 null，落库后 currentPrice 留空、等行情同步补，绝不编造。
         */
        val nav: Double?,
        /** 结算币种；null 表示多来源不一致，落库时回落 [CommitPolicy.defaultCurrency] */
        val currency: String?,
        /**
         * 是否币安**定期/锁仓**理财（对应 [Asset.isEarnLocked]）。
         * 仅币安 LD 壳持仓会为真（活期=false）；基金E账户链路恒为 false（其 [RawHolding]
         * 不带此信息，走默认）。落库时 NEW 写进 Asset、UPDATE 仅在变化时落库，避免无谓写。
         */
        val isEarnLocked: Boolean = false
    )

    /**
     * 落库时的来源策略。这三件事两条链路各有一套，**刻意不统一**，以参数传入：
     *
     * @param defaultAccount   账户名留空时的兜底名。持仓快照用「默认账户」（与交割单一致），
     *                         币安用「币安」——来源一目了然
     * @param assetTypeOf      资产类型推断。快照侧带 `fund_` 前缀的一律是场外基金
     *                         （见 [HoldingSnapshotService] 的 assetTypeFor），币安侧恒为 CRYPTO
     * @param defaultCurrency  结算币种兜底。快照侧多来源币种不一致时不猜、回落 CNY；
     *                         币安现货以 USDT 计价（≈美元），恒标 USD
     */
    data class CommitPolicy(
        val defaultAccount: String,
        val assetTypeOf: (String) -> AssetType,
        val defaultCurrency: String
    )

    /**
     * 内核落库只需要这三个写操作。
     *
     * 之所以不直接依赖 [AssetRepository]：那个类是 final 且构造需要 Context，
     * 纯 JVM 单测里造不出来。抽成小接口后，测试用一个记录调用的假写手
     * 就能验证整条 commit 分支（新增 / 覆盖 / 未变 / 冲突各自调了什么）。
     */
    interface SnapshotAssetWriter {
        suspend fun insertAssets(assets: List<Asset>)
        suspend fun updateBalance(id: Long, balance: Double)
        suspend fun updatePriceById(id: Long, price: Double)
        /** 更新「是否定期/锁仓理财」标记（币安 LD 壳专用）。写库与 updateBalance 同事务 */
        suspend fun updateEarnLocked(id: Long, locked: Boolean)

        /**
         * 把一段写库操作包进事务。
         *
         * [commit] 的写动作是「先逐条 updateBalance / updatePriceById，最后一次性
         * insertAssets」—— 这些必须**要么全成、要么全不成**。中途炸了（SQLITE_FULL、
         * 约束冲突、进程被回收）留下的是半套数据：数量改了、新标的没进来，
         * 用户看到的总资产是错的（重导能收敛，但当下这一屏是错的）。
         *
         * 放接口里而不是让壳层自己包，是因为只有写入口才知道怎么开事务
         * （真仓库有 Room，测试假实现直接执行）。
         */
        suspend fun <T> runInTransaction(block: suspend () -> T): T
    }

    /**
     * 落库结果计数。
     *
     * [conflicts] 只有持仓快照链路会产生（多账户冲突），币安链路恒为 0——
     * 它的 `CommitResult` 没有这个字段，壳层丢弃即可。
     */
    data class CommitResult(
        val inserted: Int,
        val updated: Int,
        val skipped: Int,
        val conflicts: Int = 0
    )

    /**
     * 把解析结果对齐到库里的资产，产出预览数据。**纯函数，不写库**。
     *
     * @param rows                来源行（截图解析出的持仓 / 币安余额…），顺序即预览顺序
     * @param existing            库里已有的同类资产（调用方先按类型取出）
     * @param multiAccountConflict 是否启用多账户冲突判定。true（持仓快照）：库里同代码
     *                           有多条且合计对不上时判 [ItemStatus.CONFLICT]；
     *                            false（币安）：没有冲突概念，直接按合计走 [ItemStatus.UPDATE]
     * @param toRaw               来源行 → 归一化行。每条行只映射一次
     */
    fun <T> align(
        rows: List<T>,
        existing: List<Asset>,
        multiAccountConflict: Boolean,
        toRaw: (T) -> RawHolding
    ): List<AlignedItem<T>> {
        // 库存按代码分组。symbol 可空（历史数据可能没代码），分组键保留可空形态，
        // 与两条链路原来的 groupBy { it.symbol } 一致
        val existingBySymbol = existing.groupBy { it.symbol }

        // 同代码先合并：中国结算按托管机构把同一只票拆成多行；币安一个账户理论上
        // 只返回一行，但仍按同样套路合并，防御性一致。groupBy 保留首次出现顺序
        return rows
            .map { row -> row to toRaw(row) }
            .groupBy { (_, raw) -> raw.symbol }
            .map { (symbol, group) ->
                val raws = group.map { (_, raw) -> raw }
                val first = raws.first()
                val quantity = raws.sumOf { it.quantity }
                val matched = existingBySymbol[symbol].orEmpty()

                val status = when {
                    matched.isEmpty() -> ItemStatus.NEW
                    // 库里同代码有多条（分属不同账户）而这份快照只有一个总数：
                    // 加起来正好相等 → 各账户的拆分本来就是对的，什么也不用动；
                    // 对不上 → 不敢猜，判 CONFLICT 交给用户手动处理。
                    // 挑一条覆盖会让总持仓凭空多出一份，比什么都不做错得多
                    matched.size > 1 && multiAccountConflict ->
                        if (quantityMatches(matched, quantity)) {
                            ItemStatus.UNCHANGED
                        } else {
                            ItemStatus.CONFLICT
                        }
                    // 浮点比较：整数份额不会有精度问题，用容差挡住表示误差
                    quantityMatches(matched, quantity) -> ItemStatus.UNCHANGED
                    else -> ItemStatus.UPDATE
                }

                // 同代码合并时取**最新**那份净值：同一只基金在不同销售机构导出的净值
                // 一般是同一天的，但万一有先后，取日期靠前的那只净值会低估市值。
                // yyyy-MM-dd 的字典序就是时间序，直接比字符串即可
                val latestNav = raws.filter { it.nav != null }.maxByOrNull { it.navDate.orEmpty() }

                AlignedItem(
                    symbol = symbol,
                    name = first.name,
                    quantity = quantity,
                    rawQuantity = raws.joinToString("+") { it.rawQuantity.orEmpty() },
                    status = status,
                    matched = matched,
                    matchedAccounts = matched.map { it.account },
                    nav = latestNav?.nav,
                    navDate = latestNav?.navDate,
                    // 同代码多渠道合并时取币种：正常情况下都一样。
                    // 万一不一致（同一只基金在不同渠道以不同币种结算），
                    // 取不到共识就不该静默用一个——标 null 交给预览页
                    currency = raws.map { it.currency }.distinct().singleOrNull(),
                    sources = group.map { (row, _) -> row }
                )
            }
    }

    /**
     * 落库。只处理 [ItemStatus.NEW] 与 [ItemStatus.UPDATE]，未变的跳过，
     * 冲突的一条都不动（在结果页报出来让用户自己处理）。
     *
     * @param rows    [CommitRow] 列表，顺序即处理顺序
     * @param account 用户输入的账户名，只用于**新建**的资产；
     *                 已存在的资产保持它原来的账户，不跟着改。留空走
     *                 [CommitPolicy.defaultAccount]
     */
    suspend fun commit(
        rows: List<CommitRow>,
        writer: SnapshotAssetWriter,
        account: String,
        policy: CommitPolicy
    ): CommitResult {
        // 整批写进一个事务：见 [SnapshotAssetWriter.runInTransaction] 的说明。
        // 注意这里不能用 `= writer.runInTransaction { ... return CommitResult(...) }` ——
        // 它不是 inline 函数，lambda 里写不了非局部 return，只能把结果当最后一行表达式
        return writer.runInTransaction {
            val toInsert = mutableListOf<Asset>()
            var updated = 0
            var skipped = 0
            var conflicts = 0

            for (row in rows) {
                when (row.status) {
                    ItemStatus.NEW -> toInsert.add(
                        Asset(
                            name = row.name,
                            type = policy.assetTypeOf(row.symbol),
                            symbol = row.symbol,
                            account = account.ifBlank { policy.defaultAccount },
                            balance = row.quantity,
                            // 参考单价留 0：它是「无行情时的市值兜底价」，
                            // 给个假数字会让市值凭空多出来。有净值就走 currentPrice，没净值等行情同步
                            purchasePrice = 0.0,
                            currentPrice = row.nav?.takeIf { it > 0.0 },
                            // 结算币种必须按导入数据写：默认 CNY 会让一只美元结算的 QDII
                            // 被按人民币数值直接汇总。null（多来源币种不一致）时不猜，留默认
                            currency = row.currency ?: policy.defaultCurrency,
                            // 是否定期/锁仓理财（币安 LD 壳专用，基金E账户恒为 false）
                            isEarnLocked = row.isEarnLocked
                        )
                    )

                    ItemStatus.UPDATE -> {
                        val existing = row.existing ?: continue
                        writer.updateBalance(existing.id, row.quantity)
                        // 净值也是权威数据：份额变了，价格同样该跟着刷新，
                        // 否则用户会看到「数量更新了、市值还是上周的」
                        row.nav?.takeIf { it > 0.0 }?.let { nav ->
                            writer.updatePriceById(existing.id, nav)
                        }
                        // 定期/锁仓标记只在变化时落库：避免每条 UPDATE 都多一次无谓写
                        if (existing.isEarnLocked != row.isEarnLocked) {
                            writer.updateEarnLocked(existing.id, row.isEarnLocked)
                        }
                        updated++
                    }

                    ItemStatus.UNCHANGED -> skipped++

                    // 库里同代码有多条，分不清该改哪一条 → 一条都不动，
                    // 在结果页告诉用户这几条要他自己处理
                    ItemStatus.CONFLICT -> conflicts++
                }
            }

            if (toInsert.isNotEmpty()) writer.insertAssets(toInsert)

            CommitResult(
                inserted = toInsert.size,
                updated = updated,
                skipped = skipped,
                conflicts = conflicts
            )
        }
    }

    /** 库里命中数量合计与快照数量是否一致。加密货币用更小的容差，避免第 10 位变动被吞掉 */
    private fun quantityMatches(matched: List<Asset>, quantity: Double): Boolean {
        val tolerance = if (matched.any { it.type == AssetType.CRYPTO }) QUANTITY_TOLERANCE_CRYPTO else QUANTITY_TOLERANCE
        return abs(matched.sumOf { it.balance } - quantity) < tolerance
    }
}

/**
 * 把仓库适配成内核写入口。两条链路各自在 Service 里持有一个实例。
 *
 * 放在内核文件里而不是两个壳里各写一遍：适配逻辑是机械的，重复两遍没有意义。
 */
internal fun AssetRepository.asSnapshotAssetWriter(): SnapshotAlignment.SnapshotAssetWriter =
    object : SnapshotAlignment.SnapshotAssetWriter {
        override suspend fun insertAssets(assets: List<Asset>) {
            this@asSnapshotAssetWriter.insertAssets(assets)
        }

        override suspend fun updateBalance(id: Long, balance: Double) {
            this@asSnapshotAssetWriter.updateBalance(id, balance)
        }

        override suspend fun updatePriceById(id: Long, price: Double) {
            this@asSnapshotAssetWriter.updatePriceById(id, price)
        }

        override suspend fun updateEarnLocked(id: Long, locked: Boolean) {
            this@asSnapshotAssetWriter.updateEarnLocked(id, locked)
        }

        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            return this@asSnapshotAssetWriter.inTransaction(block)
        }
    }
