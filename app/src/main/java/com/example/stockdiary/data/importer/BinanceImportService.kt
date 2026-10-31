package com.example.stockdiary.data.importer

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.BinanceAPI
import com.example.stockdiary.network.BinanceBalance
import com.example.stockdiary.network.BinanceEarnPosition
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.ExchangeRateManager
import kotlin.math.abs

/**
 * 币安现货持仓导入：把账户接口拉回的余额对齐到库里的加密资产，产出预览、确认后落库。
 *
 * ## 与持仓快照的关系
 *
 * 两条链路共用同一套对齐内核（[SnapshotAlignment]）：**按代码合并 → 与库存匹配 →
 * NEW/UPDATE/UNCHANGED 判定 → commit 落库**。复用同一套心智模型，用户在两个导入
 * 入口看到的行为一致，代码也不用各写一遍。
 *
 * ## 与持仓快照的区别
 *
 * - 快照吃的是 OCR/xlsx，这里是签名 HTTP，没有「解析失败」这一步。
 * - 币安一个账户只有一份持仓，不存在「同代码跨多个账户」的冲突，
 *   所以状态只有 NEW / UPDATE / UNCHANGED，没有 [HoldingSnapshotService.ItemStatus.CONFLICT]。
 * - 多一步来源特有的处理：dust 过滤（[DUST_THRESHOLD]）与 USDT 交易对映射
 *   （[baseAssetToSymbol]）。
 *
 * ## symbol 映射（关键，见 [baseAssetToSymbol]）
 *
 * 币安账户接口给的是**基础资产名**（BTC/ETH/USDT…），而 App 的加密货币资产
 * 按**交易对**存代码（BTCUSDT）——[com.example.stockdiary.network.UnifiedPriceService]
 * 的 CRYPTO 取价直接用 `symbol.uppercase()` 当币安交易对查价。映射错了，
 * 导入的资产就永远同步不到行情。这里统一拼成 `<基础资产>USDT`，与既有加密资产同格式。
 */
/**
 * 借币负债 / 抵押资产的 symbol 后缀。
 *
 * ## 为什么预览要加后缀
 *
 * [SnapshotAlignment.align] 按 symbol 合并同代码余额，借币负债（USDT -50.8）若用真实
 * 交易对 symbol 会和现货 USDT 142.52 合并成净 91.7——用户核对时看不到独立的「借币负债」
 * 项，只看到被抵消的净数字，也就对不上币安逐条资产的数量。加后缀让 align 不去重、
 * 负债与现货各成一行。
 *
 * ## 为什么落库**保留**后缀（2026-10-30 改）
 *
 * 早期版本在 [toCommitRow] 里用 [stripLoanSuffix] 把后缀剥掉，让负债「按币安净额口径」
 * 合并进现货行——数量被改写，与用户拍板的「列表数量和币安逐条对齐」冲突。剥后缀还有
 * 一个更硬的伤：DB 里同一个 symbol 会有两条（USDT 现货 + USDT 负债），而下一次导入
 * 的分组键是 `USDT#loan`（库里没有），于是每次导入都再插一条负债，净值随导入次数线性崩坏。
 *
 * 保留后缀后 symbol 是**稳定唯一键**：重复导入命中同一行走 UPDATE（不累积），
 * 数量为负自动参与净值扣减（净资产 = 资产 − 欠币），两个目标同时成立。
 * 代价：symbol 不是币安交易对，取价必须剥掉后缀按底层交易对走
 * （[com.example.stockdiary.network.UnifiedPriceService.getCryptoPrice] 已处理）。
 */
private const val LOAN_DEBT_SUFFIX = "#loan"
private const val LOAN_COLLATERAL_SUFFIX = "#collateral"

/** 预览 symbol 还原成真实交易对（去掉借币/抵押后缀），供落库与取价使用 */
internal fun stripLoanSuffix(symbol: String): String = symbol.substringBefore('#')

/** 预览 symbol 是否代表借币负债 / 抵押资产（带对应后缀） */
internal fun isLoanDebtSymbol(symbol: String): Boolean = symbol.endsWith(LOAN_DEBT_SUFFIX)
internal fun isLoanCollateralSymbol(symbol: String): Boolean = symbol.endsWith(LOAN_COLLATERAL_SUFFIX)

class BinanceImportService(
    private val assetRepository: AssetRepository
) {

    /** 内核写入口（适配仓库的三个 suspend 写操作） */
    private val writer: SnapshotAlignment.SnapshotAssetWriter =
        assetRepository.asSnapshotAssetWriter()

    /** 本链路的落库策略：默认账户名「币安」、类型恒为 CRYPTO、币种恒为 USD */
    private val commitPolicy = SnapshotAlignment.CommitPolicy(
        defaultAccount = DEFAULT_ACCOUNT,
        assetTypeOf = { AssetType.CRYPTO },
        defaultCurrency = CURRENCY
    )

    enum class ItemStatus {
        /** 库里没有这个交易对，要新建 */
        NEW,

        /** 库里已有，数量不同，按币安余额覆盖 */
        UPDATE,

        /** 库里已有且数量一致，不用动 */
        UNCHANGED
    }

    /**
     * 预览里的一项（一个交易对）。
     *
     * [name] 是给用户看的基础资产名（BTC），[symbol] 是落库/取价用的交易对（BTCUSDT）。
     */
    data class PreviewItem(
        val symbol: String,
        val name: String,
        val quantity: Double,
        val existing: Asset?,
        val status: ItemStatus,
        /**
         * 是否币安**定期/锁仓**理财（对应库里 [Asset.isEarnLocked]）。
         * 仅当 symbol 是 LD 理财壳时有意义：true=定期锁仓、false=活期理财。
         * 由 buildPreview 从原始 [BinanceBalance.isEarnLocked] 透传而来（按底层资产归组，
         * 见 [mergeEarnIntoBalances]）；toCommitRow 再转成内核 CommitRow 落库。
         */
        val isEarnLocked: Boolean = false
    ) {
        /** 数量变化量；新建时就是全部数量 */
        val delta: Double
            get() = quantity - (existing?.balance ?: 0.0)
    }

    data class Preview(
        val items: List<PreviewItem>,
        /**
         * 库里**已被这一次导入归并掉**的旧资产行（同一个底层资产在币安被拆成两行，
         * 现在合到 [items] 的那一行里了）。落库时把它们的数量清零。
         *
         * 为什么必须清零：合并只改 quantity 不足以让列表少一行——旧行留在库里还是
         * 一条独立资产，用户看到的仍是「同一个币两行」，而且它们的数量已经计入了新行，
         * 留着就是同一笔钱记两遍（正是 USDC / LDUSDC 那个现象）。
         */
        val supersededAssets: List<Asset> = emptyList()
    ) {
        val newItems: List<PreviewItem> get() = items.filter { it.status == ItemStatus.NEW }
        val updatedItems: List<PreviewItem> get() = items.filter { it.status == ItemStatus.UPDATE }
        val unchangedItems: List<PreviewItem> get() = items.filter { it.status == ItemStatus.UNCHANGED }

        /** 需要落库的条数（NEW + UPDATE，未变不算） */
        val pendingCount: Int
            get() = items.count { it.status == ItemStatus.NEW || it.status == ItemStatus.UPDATE }
    }

    data class CommitResult(
        val inserted: Int,
        val updated: Int,
        val skipped: Int
    )

    /**
     * 把币安余额对齐到库里资产，产出预览。**不写库**。
     *
     * @param balances      账户接口返回的原始余额（未过滤 dust）
     * @param existingAssets 库里已有的加密货币资产（调用方按 CRYPTO 类型取出后传入）
     */
    suspend fun buildPreview(
        balances: List<BinanceBalance>,
        existingAssets: List<Asset>
    ): Preview {
        val items = SnapshotAlignment.align(
            // dust 过滤：币安会返还一堆 0.00000000 的零头资产，free+locked 的**绝对值**低于
            // 阈值一律忽略，否则预览里会出现几十条数量为 0 的「新增」，用户根本不知道自己持有这些。
            //
            // 注意**负余额不能丢**：借币 / 组合保证金欠币在接口里就是 free+locked 为负，
            // 丢掉等于「只记资产不记负债」，净资产被虚高（2026-10-25 对账单排查时发现）。
            // 负余额按同代码合并时会与现货持仓自然抵消（持有 100、欠 30 → 净 70），
            // 正是币安自己的净额口径；纯负债（没有对应持仓）会落成一条数量为负的加密资产，
            // 参与净值计算时自动扣减。
            rows = balances.filter { abs(it.total) >= DUST_THRESHOLD },
            existing = existingAssets,
            // 币安一个账户只有一份持仓，不存在「同代码跨多个账户」的冲突
            multiAccountConflict = false
        ) { balance ->
            SnapshotAlignment.RawHolding(
                // 基础资产名 → 交易对代码（BTC → BTCUSDT），落库/取价都用这个形态。
                // LD 理财/锁仓壳（LDBNB）现在**剥掉 LD 前缀**输出底层交易对（BNBUSDT）：
                // 活期理财与现货钱包本就是同一底层资产的两个视图，合并成一行（数量=现货+活期）
                // 才能让资产数目正确，也不再暴露 LD 裸码。取价/估值仍走 priceKeyFor 的
                // LD→底层逻辑，定价不断。稳定币（priceKeyFor 返回 null）回落 baseAssetToSymbol
                // （USDT→USDT）。name 同步剥 LD（LDBNB → BNB），展示层干净。
                // 借币负债 / 抵押资产带 kind 标记，用带后缀的独立 symbol 跳过 align 的
                // 同代码合并（否则会与同币种现货抵消成净数字，用户看不到独立的负债/抵押项）。
                symbol = symbolForBalance(balance),
                // 展示名剥掉 LD 前缀（LDBNB → BNB）：与 symbol 一致，列表/预览不再出现 LD 裸码
                name = if (BinanceAPI.isEarnProduct(balance.asset)) balance.asset.removePrefix("LD") else balance.asset,
                quantity = balance.total
            )
        }.map { aligned ->
            PreviewItem(
                symbol = aligned.symbol,
                name = aligned.name,
                quantity = aligned.quantity,
                existing = aligned.matched.firstOrNull(),
                status = aligned.status.toUiStatus(),
                // 该底层资产是否在币安有定期/锁仓持仓：原始 BinanceBalance 已带 isEarnLocked 标记
                // （mergeEarnIntoBalances 按归组键透传），这里从对齐内核带回的 sources 取出来。
                isEarnLocked = aligned.sources.filterIsInstance<BinanceBalance>().any { it.isEarnLocked }
            )
        }.let { adoptLegacyLoanRows(it, existingAssets) }

        AppLogger.i(
            "币安持仓对齐完成：共 ${items.size} 条，新增 ${items.count { it.status == ItemStatus.NEW }}，" +
                "覆盖 ${items.count { it.status == ItemStatus.UPDATE }}，" +
                "未变 ${items.count { it.status == ItemStatus.UNCHANGED }}",
            "BinanceImport"
        )

        return Preview(
            items = items,
            // 同一底层资产折叠成一行后，被折掉那行在库里的旧资产要清零（见 [Preview.supersededAssets]）
            supersededAssets = findSupersededAssets(balances, existingAssets)
        )
    }

    /**
     * 落库。只处理 [ItemStatus.NEW] 与 [ItemStatus.UPDATE]，未变的跳过。
     *
     * @param account 用户输入的账户名，只用于**新建**的资产；
     *                已存在的资产保持它原来的账户，不跟着改（与持仓快照一致）。
     * @param prices  拉取阶段批量取到的公开价（键为 [priceKeyFor] 的交易对，
     *                见 BinanceImportViewModel.fetch）。网络可达时，用户确认导入的
     *                那一刻就把价格预填进 currentPrice——行情同步还没跑、甚至暂时
     *                连不上（币安熔断 / 离线）时，总资产也已经是对的。查不到价的
     *                标的静默留空等行情同步补，绝不编造价格、也绝不让导入失败。
     */
    suspend fun commit(
        preview: Preview,
        account: String,
        prices: Map<String, Double> = emptyMap()
    ): CommitResult {
        val result = SnapshotAlignment.commit(
            rows = preview.items.map { it.toCommitRow(prices) } + preview.supersededAssets.map { it.toZeroRow() },
            writer = writer,
            account = account,
            policy = commitPolicy
        )

        AppLogger.i(
            "币安持仓落库：新增 ${result.inserted}，覆盖 ${result.updated}，跳过 ${result.skipped}",
            "BinanceImport"
        )

        return CommitResult(
            inserted = result.inserted,
            updated = result.updated,
            skipped = result.skipped
        )
    }

    /**
     * 预览项 → 内核行视图。status 映射回内核枚举，其余字段原样带。
     *
     * @param prices 拉取阶段的公开价，经 nav 落进 currentPrice（导入即预填参考价，
     *               见 [referencePriceForImport]）。键用 [priceKeyFor] 现算——与拉取
     *               阶段生成 getPrices 请求列表是同一个函数，两边永不错位。
     */
    private fun PreviewItem.toCommitRow(prices: Map<String, Double>): SnapshotAlignment.CommitRow =
        SnapshotAlignment.CommitRow(
            // 落库**保留** #loan / #collateral 后缀（不再 stripLoanSuffix）：
            // 借币负债要与同币种现货分列（数量和币安逐条对齐），后缀是它在库里的稳定唯一键，
            // 重复导入才能命中同一行走 UPDATE 而不是每导一次多插一条负债。
            // 净资产仍是对的：负债数量是负的，参与汇总时自动扣减（资产 − 欠币）。
            // 取价按剥掉后缀的交易对走（UnifiedPriceService.getCryptoPrice）。
            symbol = symbol,
            name = name,
            quantity = quantity,
            status = status.toKernelStatus(),
            existing = existing,
            // 导入即预填：查得到价就写 currentPrice，总资产当场就是对的；
            // 查不到（含 LD 剥不出底层）留空，等行情同步按交易对补真实价
            nav = referencePriceForImport(name, prices),
            // 币安现货价以 USDT 计价（≈美元）：币种恒走策略里的 USD，不按行取
            currency = null,
            // 是否定期/锁仓理财：LD 壳持仓才会为真，落库进 Asset.isEarnLocked
            isEarnLocked = isEarnLocked
        )

    /**
     * 被 [mergeEarnIntoBalances] 折叠掉的旧行 → 清零行。
     *
     * 只改数量、不碰价格与 [Asset.isEarnLocked]：这一行的钱已经并入同底层的主行，
     * 刷价/改标记都是无谓写；清零才是让它不再被重复计入总资产。
     */
    private fun Asset.toZeroRow(): SnapshotAlignment.CommitRow =
        SnapshotAlignment.CommitRow(
            symbol = symbol.orEmpty(),
            name = name,
            quantity = 0.0,
            status = SnapshotAlignment.ItemStatus.UPDATE,
            existing = this,
            nav = null,
            currency = null,
            isEarnLocked = isEarnLocked
        )

    /** 内核状态 → 本链路 UI 状态（两边枚举常量一一对应） */
    private fun SnapshotAlignment.ItemStatus.toUiStatus(): ItemStatus = when (this) {
        SnapshotAlignment.ItemStatus.NEW -> ItemStatus.NEW
        SnapshotAlignment.ItemStatus.UPDATE -> ItemStatus.UPDATE
        SnapshotAlignment.ItemStatus.UNCHANGED -> ItemStatus.UNCHANGED
        // 本链路 multiAccountConflict=false，内核永远不会产出 CONFLICT；
        // 真出现了说明调用方传错参数，按「未变」兜底（不写库）比崩溃好
        SnapshotAlignment.ItemStatus.CONFLICT -> ItemStatus.UNCHANGED
    }

    /** 本链路 UI 状态 → 内核状态 */
    private fun ItemStatus.toKernelStatus(): SnapshotAlignment.ItemStatus = when (this) {
        ItemStatus.NEW -> SnapshotAlignment.ItemStatus.NEW
        ItemStatus.UPDATE -> SnapshotAlignment.ItemStatus.UPDATE
        ItemStatus.UNCHANGED -> SnapshotAlignment.ItemStatus.UNCHANGED
    }

    companion object {
        /** 用户没填账户名时的兜底。用「币安」而非通用「默认账户」——来源一目了然 */
        const val DEFAULT_ACCOUNT = "币安"

        /**
         * dust 阈值：free+locked 低于此值的零头资产忽略。
         *
         * 1e-8 约是常见币种的最小可交易精度（BTC 8 位小数）。币安会把历史上
         * 接触过的所有币都列出来，绝大多数是 0，不滤掉会污染预览。
         */
        private const val DUST_THRESHOLD = 1e-8

        /** 币安现货计价币种（USDT 稳定币 ≈ 美元） */
        private const val CURRENCY = "USD"
    }
}

/**
 * 币安基础资产名 → App 加密货币交易对代码。
 *
 * ## 为什么是 `<基础资产>USDT`
 *
 * App 的加密资产按**交易对**存 symbol，行情同步
 * （[com.example.stockdiary.network.UnifiedPriceService] 的 CRYPTO 分支）
 * 直接拿 `symbol.uppercase()` 当币安交易对查价。所以落库的 symbol 必须是
 * 币安认得的交易对形态（BTCUSDT、ETHUSDT…），即 `<基础资产>USDT` 形式。
 *
 * ## 为什么不复用 [com.example.stockdiary.network.BinanceAPI] 的 formatSymbol
 *
 * formatSymbol 面向「用户输入的交易对/简称」，它把 BTC、ETH 当作**报价币**：
 * `formatSymbol("BTC")` 命中 `endsWith("BTC")` 分支返回 "BTC" 本身，而非 "BTCUSDT"。
 * 账户接口给的是裸基础资产，直接套 formatSymbol 会让 BTC→BTC、ETH→ETH，
 * 全部同步不到价。所以这里用一段专用于「基础资产补 USDT」的映射，产出
 * 与 cryptoTradingPairFor 一致的 `<基础资产>USDT` 格式。
 *
 * **查价键（而非落库 symbol）现在统一走
 * [com.example.stockdiary.network.BinanceAPI.cryptoTradingPairFor]**：那个函数同时认
 * 「裸基础资产」和「已是交易对」两种形态，币安的符号知识只此一处定义。历史上正是因为
 * 这段逻辑分散在两处，行情同步路径自己拿 `symbol.uppercase()` 拼 URL，导致手动添加的
 * 加密货币（库里存的是 BTC 而非 BTCUSDT）永远取不到价。
 *
 * ## 稳定币特例
 *
 * USDT 自身没有 `USDTUSDT` 交易对（它就是报价币）。这类原样返回，
 * 价格同步查不到对应交易对时会留空——稳定币价值≈1 美元，影响有限，
 * 但余额仍被准确记录。
 */
internal fun baseAssetToSymbol(asset: String): String {
    val base = asset.uppercase().trim()
    return if (base.endsWith("USDT")) base else "${base}USDT"
}

/**
 * 该资产用于查价的交易对；稳定币返回 null（无需查价）。
 *
 * 与 [estimatedValueCny] 内部的取价键保持一致：ViewModel 据此生成 getPrices
 * 的请求列表，estimatedValueCny 据此查 priceOf，两边永不错位。
 *
 * 非 LD 输入（账户接口给的基础资产名 BTC/USDT/ADA…）委托给
 * [BinanceAPI.cryptoTradingPairFor]——那个函数额外还认「已是交易对」的形态，
 * 覆盖面更宽，行为在本函数的输入域上完全一致（现有单测即为此背书）。
 *
 * ## 理财/锁仓（LD 前缀）走底层交易对
 *
 * `LDBNB` 这类持仓壳没有现货交易对，但它与底层资产 1:1 可赎回，估值用底层币
 * 市价（见 [BinanceAPI.earnUnderlyingPair]）。不转的话会去向币安请求并不存在的
 * `LDBNB` 交易对（400），预览的市值估算与导入预填参考价都拿不到价。
 */
internal fun priceKeyFor(asset: String): String? {
    if (BinanceAPI.isEarnProduct(asset)) return BinanceAPI.earnUnderlyingPair(asset)
    return BinanceAPI.cryptoTradingPairFor(asset)
}

/**
 * 导入即预填的参考价：查得到价就返回，查不到返回 null（落库后 currentPrice 留空，
 * 等行情同步补——**绝不编造价格**，也绝不让导入因为查不到价而失败）。
 *
 * 键与拉取阶段一致（[priceKeyFor]，LD 走底层交易对）：ViewModel 用同一个函数
 * 生成 getPrices 的请求列表，这里用同一个函数查表，两边永不错位。
 *
 * 稳定币没有交易对可查，按 1.0 美元计——这不是"抓到的价"，是定义价，与
 * [com.example.stockdiary.network.UnifiedPriceService] 取价时对稳定币的口径一致
 * （USDT 持仓不预填的话，导入后、行情同步前它的市值是 0）。
 *
 * @param asset  账户接口给的基础资产名（BTC / LDBNB / USDT…），即 PreviewItem.name
 * @param prices 拉取阶段批量取到的公开价（Map<交易对, 最新价>）
 */
internal fun referencePriceForImport(asset: String, prices: Map<String, Double>): Double? {
    // 稳定币（白名单 + 命名规律兜底）锚定 1 美元，按定义价 1.0 计（见 [BinanceAPI.isLikelyStablecoin]）。
    // 与 [estimatedValueCny] 在预览里的口径完全一致，杜绝「预览 1.0、落库 0」的口径分裂。
    if (BinanceAPI.isLikelyStablecoin(asset)) {
        return 1.0
    }
    val key = priceKeyFor(asset)
    if (key == null) {
        // 非稳定币但查价键为 null（如稳定币理财壳 LDUSDT——底层是稳定币，无现货交易对）。
        // 判定口径统一为 priceKeyFor 是否返回 null（不再单独判稳定币集合），
        // 这里同样按定义价 1.0 计，避免落库 currentPrice 留空 → 市值恒 0
        // （即「USDT 价格默认为 0」已知问题）。
        return 1.0
    }
    return prices[key]?.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * 估算某持仓值多少人民币。
 *
 * @param asset   基础资产名（BTC / USDT…）
 * @param total   持有量（free + locked）
 * @param priceOf 按交易对取 USD 价；查不到返回 null
 * @return 人民币市值；**估不出价时返回 null（不猜）**——调用方应保留而非丢弃
 *
 * 稳定币按 1.0 美元计；其余查 `priceOf(asset + "USDT")`。美元价经
 * [ExchangeRateManager] 的 USD 汇率折算成人民币。
 */
internal fun estimatedValueCny(
    asset: String,
    total: Double,
    priceOf: (String) -> Double?
): Double? {
    // 稳定币（白名单 + 命名规律兜底）锚定 1 美元，无需查价，按定义价 1.0 计。
    // 见 [BinanceAPI.isLikelyStablecoin]：白名单漏收的新稳定币也能拿到 1.0 兜底，
    // 不再退化成裸码查价 → 查不到 → 市值显 0（Dim3 健壮性）。
    if (BinanceAPI.isLikelyStablecoin(asset)) {
        return total * 1.0 * ExchangeRateManager.getRate("USD")
    }
    val key = priceKeyFor(asset)
    val usdUnitPrice = if (key == null) 1.0 else priceOf(key) ?: return null
    return total * usdUnitPrice * ExchangeRateManager.getRate("USD")
}

/**
 * 资产归组键：把理财壳（LDUSDT/LDPAXG）与它的底层币（USDT/PAXG）看成同一个资产。
 *
 * 为什么必须归一：`/api/v3/account` 把 Simple Earn 的活期持仓以 **LD 壳**形式返回
 * （LDUSDT、LDPAXG…），而 simple-earn 的 position 接口返回的是**底层币**
 * （USDT、PAXG…）。两边按原样名称匹配永远对不上——这正是「对账单 Earn 有
 * 141.02 USDT、App 里怎么导都只有 124.35」的根因：定期部分补不进来，
 * 底层币还会被当成「理财独有资产」再插一行，活期那笔等于记两遍。
 *
 * 归一后 LDPAXG 与 PAXG 同键，合并规则（取较大值）才对两者都成立。
 * LD 与底层 1:1 可赎回（见 [BinanceAPI.earnUnderlyingPair]），归一是等价的。
 *
 * `LDO` 这类只是恰好以 LD 开头的真实现货由 [BinanceAPI.isEarnProduct] 排除，不受影响。
 */
internal fun earnMergeKey(asset: String): String {
    val key = asset.uppercase().trim()
    return if (BinanceAPI.isEarnProduct(key)) key.removePrefix("LD") else key
}

/**
 * 把理财持仓（活期 + 定期）与借币抵押资产合并进现货余额，**并把同一个底层资产折叠成一行**。
 *
 * ## 核心模型：现货 / 活期 / 定期是三个互不重叠的钱包
 *
 * 币安口径下，一份加密资产分布在三间互不重叠的钱包里：
 *
 * - **现货钱包**（`/api/v3/account` 的非 LD 余额，如 `USDC` 现货）；
 * - **活期理财**（Simple Earn 活期，账户里以 `LDUSDC` 壳形式出现）；
 * - **定期 / 锁仓理财**（Simple Earn 定期，账户余额里**没有**，只在 simple-earn 接口返回）。
 *
 * 借币 / 抵押（[BinanceBalance.Kind.LOAN_DEBT] / [BinanceBalance.Kind.LOAN_COLLATERAL]）
 * 不在此合并，由调用方追加在结果之后独立成行。
 *
 * ## 合并规则
 *
 * 对每个底层资产，最终数量 = **现货 + max(账户活期壳, 理财活期) + 理财定期**：
 *
 * - 现货与活期理财都来自 `/api/v3/account`（现货余额 + LD 壳），二者相加即账户里的全部
 *   （实测 `USDC` 现货 43.56 + `LDUSDC` 壳 0.0043 才等于官方的 43.567）；
 * - 活期理财在「账户 LD 壳」与「simple-earn 接口」两个来源里各描述一次。账户壳可能因
 *   借币抵消而偏小（实测 `LDUSDT` 壳 124.58、接口全量 142.06，差额即被押进抵押金库的份额），
 *   所以取两者**较大值**，既不会漏（借币偏移）也不会重复（同一笔钱记两遍）；
 * - **定期 / 锁仓理财不在账户余额里**，是独立金库，永远**直接相加**——旧实现用单一的
 *   `max(账户, 理财全量)` 会在「仅定期、现货无此笔」时把定期漏掉，导致总资产少算一块。
 *
 * 这样「求和」只发生在真正互不重叠的钱包之间，账户已含的部分不会被简单相加重复计。
 *
 * ## 一个底层资产只留一行
 *
 * 被折掉的行以 0 数量返回，由 [BinanceImportService.buildPreview] 把库里对应的旧资产清零
 * （[BinanceImportService.Preview.supersededAssets]），否则同一笔钱会在列表里记两遍。
 *
 * @param balances 现货余额（`/api/v3/account`）
 * @param earn     理财持仓（simple-earn 的活期与定期接口）
 * @return 合并后的余额列表：每个底层资产一行；被折叠的行数量为 0
 */
internal fun mergeEarnIntoBalances(
    balances: List<BinanceBalance>,
    earn: List<BinanceEarnPosition>
): List<BinanceBalance> {
    if (balances.isEmpty()) return balances

    // 把理财持仓拆成活期(flex)与定期/锁仓(lock)两桶，分别处理。
    // 账户里的活期已以 LD 壳形式出现，与 simple-earn 接口的活期是同一笔的两个视图；
    // 定期/锁仓则只在接口里，必须独立相加（否则「仅定期、现货无此笔」会被漏算）。
    val earnFlex = linkedMapOf<String, Double>()
    val earnLock = linkedMapOf<String, Double>()
    for (position in earn) {
        val key = earnMergeKey(position.asset)
        if (key.isEmpty() || position.amount <= 0.0) continue
        if (position.locked) {
            earnLock[key] = (earnLock[key] ?: 0.0) + position.amount
        } else {
            earnFlex[key] = (earnFlex[key] ?: 0.0) + position.amount
        }
    }

    // 按底层资产归组：账户里 LD 壳与现货同键（earnMergeKey 已剥 LD）
    val groupIndices = linkedMapOf<String, MutableList<Int>>()
    balances.forEachIndexed { index, balance ->
        groupIndices.getOrPut(earnMergeKey(balance.asset)) { mutableListOf() }.add(index)
    }

    var folded = 0
    val merged = mutableListOf<BinanceBalance>()
    val accountKeys = groupIndices.keys
    groupIndices.forEach { (key, indices) ->
        val members = indices.map { balances[it] }
        // 单一来源且理财也无此资产：原样透传（保留它的 free/locked 原貌），典型是纯现货
        if (members.size == 1 && key !in earnFlex && key !in earnLock) {
            merged.add(members[0])
            return@forEach
        }
        val carrier = pickCarrier(members)
        // 账户内按是否 LD 壳拆分：现货钱包与活期理财壳是两块互不重叠的钱包
        val spotTotal = members.filter { !BinanceAPI.isEarnProduct(it.asset) }.sumOf { it.total }
        val flexAccount = members.filter { BinanceAPI.isEarnProduct(it.asset) }.sumOf { it.total }
        // 活期：账户壳与 simple-earn 全量取较大者（账户壳可能因借币抵消偏小，
        // 取全量才能含被押进抵押金库的份额，既不漏也不重复）
        val flex = maxOf(flexAccount, earnFlex[key] ?: 0.0)
        // 定期/锁仓：账户余额里没有，恒为独立金库，直接相加（修复「仅定期、现货无此笔」漏算）
        val lock = earnLock[key] ?: 0.0
        val total = spotTotal + flex + lock
        merged.add(
            BinanceBalance(
                asset = carrier.asset,
                free = total,
                locked = 0.0,
                // 该底层资产有定期/锁仓持仓则标记（不再驱动展示标签，仅作数据留存）
                isEarnLocked = lock > 0.0
            )
        )
        // 被折叠的行：数量归 0 —— 既是「已并入主行」的标记，也让 buildPreview 能把
        // 库里对应的旧资产清零（否则旧行留着，同一笔钱在列表里被记两遍）
        for (member in members) {
            if (member === carrier) continue
            merged.add(BinanceBalance(asset = member.asset, free = 0.0, locked = 0.0))
            folded++
        }
    }

    // known 同样按归组键判重：已有该底层时，理财的部分已并入主行，不再插一行
    val known = accountKeys
    var added = 0
    val allEarnKeys = earnFlex.keys + earnLock.keys
    allEarnKeys.forEach { asset ->
        if (asset !in known) {
            // 账户完全没有该底层：活期 + 定期一起兜底补进来（账户漏的这里补全）
            val amount = (earnFlex[asset] ?: 0.0) + (earnLock[asset] ?: 0.0)
            merged.add(
                BinanceBalance(
                    asset = asset,
                    free = amount,
                    locked = 0.0,
                    isEarnLocked = (earnLock[asset] ?: 0.0) > 0.0
                )
            )
            added++
        }
    }

    AppLogger.i(
        "币安理财持仓合并： ${balances.size} 项 + 理财 ${allEarnKeys.size} 个资产 → " +
            "折叠 $folded 行、新增 $added 项",
        "BinanceImport"
    )
    return merged
}

/**
 * 从同一底层资产的多行里挑出**承载**那一行：持仓量最大的优先，持平则优先 LD 壳。
 *
 * 为什么不是「LD 壳优先」：壳常常只是一个 0.004 的残余份额，真正的钱在现货行；
 * 以最大的那行作为承行者，行的 symbol/名称就是用户原本认得的那一行，
 * 换 key 会留下库里的孤儿行（旧代码再插一行、旧行永不清理，正是列表里出现重复行的来源）。
 */
private fun pickCarrier(members: List<BinanceBalance>): BinanceBalance =
    members.reduce { best, next ->
        val nextAbs = abs(next.total)
        val bestAbs = abs(best.total)
        val nextWins = nextAbs > bestAbs ||
            // 持平：LD 壳优先保留（它是「这笔钱在 Simple Earn 里」的语义标记）
            (nextAbs == bestAbs && !BinanceAPI.isEarnProduct(best.asset) && BinanceAPI.isEarnProduct(next.asset))
        if (nextWins) next else best
    }

/** 一条余额在预览 / 落库里的 symbol：LD 壳用原始 LD 码、借币与抵押加后缀、其余补 USDT 交易对 */
private fun symbolForBalance(balance: BinanceBalance): String {
    // 现货 / 理财壳统一输出底层交易对。LD 壳（LDUSDT/LDBNB…）必须先剥掉 LD 前缀再映射：
    // 否则 priceKeyFor 对稳定币理财壳返回 null、baseAssetToSymbol 又不剥 LD，
    // 会落出 "LDUSDT" 这种裸码，与库里底层 USDT 错开去重键（旧实现此处 NPE 的根因）。
    val base = if (BinanceAPI.isEarnProduct(balance.asset)) {
        baseAssetToSymbol(balance.asset.removePrefix("LD"))
    } else {
        priceKeyFor(balance.asset) ?: baseAssetToSymbol(balance.asset)
    }
    return when (balance.kind) {
        BinanceBalance.Kind.LOAN_DEBT -> base + LOAN_DEBT_SUFFIX
        BinanceBalance.Kind.LOAN_COLLATERAL -> base + LOAN_COLLATERAL_SUFFIX
        // 现货 / 理财壳统一输出底层交易对（如 LDBNB → BNBUSDT）：剥掉 LD 前缀，
        // 列表里不再出现 LD 裸码；取价/估值仍走 priceKeyFor 的 LD→底层逻辑，定价不断。
        BinanceBalance.Kind.EARN,
        BinanceBalance.Kind.SPOT -> base
    }
}

/**
 * 资产代码 → 底层资产归组键（供 [earnMergeKey] 口径复用）。
 *
 * 库里存的是交易对形态（`USDCUSDT`）或 LD 壳（`LDUSDC`），而 [earnMergeKey] 吃的是
 * 基础资产名，这里先把交易对还原成基础资产名（并跳过稳定币 `USDT` 自身的剥后缀）。
 */
private fun underlyingKeyOf(symbol: String): String {
    val core = stripLoanSuffix(symbol).uppercase().trim()
    if (BinanceAPI.isEarnProduct(core)) return earnMergeKey(core)
    return earnMergeKey(if (core.endsWith("USDT") && core.length > 4) core.removeSuffix("USDT") else core)
}

/**
 * 找出库里「已被这次导入折叠掉」的旧行：某条余额数量为 0（被 [mergeEarnIntoBalances]
 * 折进别的行），而同一底层还有其它行带着钱——那么这条余额对应在库里的旧资产就该清零。
 *
 * 为什么限定「同底层还有别的行有钱」：币安会把历史接触过的币都列成 0 余额，
 * 那些行只是 dust，不该把用户手动建的同类持仓一并清掉。
 */
internal fun findSupersededAssets(
    balances: List<BinanceBalance>,
    existingAssets: List<Asset>
): List<Asset> {
    if (existingAssets.isEmpty()) return emptyList()
    // 这次导入里「有钱」的底层资产键（归并后只剩这些底层，LD 壳已并入）
    val fundedUnderlying = balances.filter { it.total != 0.0 }
        .map { underlyingKeyOf(symbolForBalance(it)) }
        .toSet()
    // 被折叠（归并掉、数量置 0）的底层资产键：同一底层在币安被拆成两行，现在合到主行里了
    val foldedUnderlying = balances.filter { it.total == 0.0 }
        .map { underlyingKeyOf(symbolForBalance(it)) }
        .filter { it in fundedUnderlying }
        .toSet()
    if (foldedUnderlying.isEmpty()) return emptyList()
    // 库里同一底层、且这次被折叠掉的旧行 → 清零（含历史 LD 壳 LDBNB → 已并入底层 BNB）
    return existingAssets.filter { it.symbol != null && underlyingKeyOf(it.symbol) in foldedUnderlying }
}

/**
 * 认领老口径留下的借币负债行。
 *
 * 以前负债按**剥掉后缀**的交易对落库（`USDT` 一条负数）；改成带 `#loan` 后缀后，
 * 直接按新 code 落库会再插一条负债 —— 同一笔欠款记两遍、每导一次多一遍。这里把那种
 * 「基础交易对 + 负余额」的老行认领过来走 UPDATE，升级后无需用户手工清理。
 *
 * 只认领负债（负余额）：抵押行以前也落成真实交易对，但正余额行没法与用户自己的
 * 现货持仓区分，贸然认领会改写真实持仓，故不动。
 */
// 这两个类型是 BinanceImportService 的嵌套成员，顶层函数必须写全限定名才能引用
private fun adoptLegacyLoanRows(
    items: List<BinanceImportService.PreviewItem>,
    existingAssets: List<Asset>
): List<BinanceImportService.PreviewItem> = items.map { item ->
    if (item.existing != null || !isLoanDebtSymbol(item.symbol)) return@map item
    val legacy = existingAssets.firstOrNull { asset ->
        asset.symbol == stripLoanSuffix(item.symbol) && asset.balance < 0.0
    } ?: return@map item
    item.copy(
        existing = legacy,
        status = if (abs(legacy.balance - item.quantity) < ZERO_QUANTITY_TOLERANCE) {
            BinanceImportService.ItemStatus.UNCHANGED
        } else {
            BinanceImportService.ItemStatus.UPDATE
        }
    )
}

/** [adoptLegacyLoanRows] 判定「老行数量是否已经等于本次拉取」的容差 */
private const val ZERO_QUANTITY_TOLERANCE = 1e-12
