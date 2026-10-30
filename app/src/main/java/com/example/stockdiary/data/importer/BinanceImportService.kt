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
 * 借币负债 / 抵押资产的预览 symbol 后缀。
 *
 * 为什么需要后缀：SnapshotAlignment.align 按 symbol 合并同代码余额，借币负债
 * （USDT -50.8）若用真实交易对 symbol 会和现货 USDT 142.52 合并成净 91.7，
 * 用户就看不到独立的「借币负债」项、只看到被抵消的净数字。给它们加后缀让 align
 * 不去重、独立成行；落库时用 [stripLoanSuffix] 把后缀还原成真实交易对，
 * 仍按币安净额口径合并（净值正确，且不污染行情同步的取价键）。
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
        val items: List<PreviewItem>
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
                // 特例：LD 理财/锁仓壳（LDBNB）落库 symbol 用**原始 LD 代码 LDBNB**，
                // 而不是底层交易对 BNBUSDT——否则会和现货 BNB 撞同一个去重键，被
                // SnapshotAlignment 合并成一行，看不到「多少锁在 earn、多少在现货」，
                // 行名还会误导（见 P1 修复）。LD 与底层 1:1 可赎回，取价/估值仍走
                // getCryptoPrice / priceKeyFor 的 LD→底层逻辑（两条路径都已支持 LD 代码），
                // 改完定价不断。稳定币（priceKeyFor 返回 null）回落 baseAssetToSymbol
                // （USDT→USDT）。name 始终存原始代码（LDBNB / BNB），展示层不受影响。
                // 借币负债 / 抵押资产带 kind 标记，用带后缀的独立 symbol 跳过 align 的
                // 同代码合并（否则会与同币种现货抵消成净数字，用户看不到独立的负债/抵押项）。
                // 其余（现货 / 理财壳）走原逻辑：LD 壳用原始 LD 代码、其它补 USDT 交易对。
                symbol = when (balance.kind) {
                    BinanceBalance.Kind.LOAN_DEBT ->
                        (priceKeyFor(balance.asset) ?: baseAssetToSymbol(balance.asset)) + LOAN_DEBT_SUFFIX
                    BinanceBalance.Kind.LOAN_COLLATERAL ->
                        (priceKeyFor(balance.asset) ?: baseAssetToSymbol(balance.asset)) + LOAN_COLLATERAL_SUFFIX
                    else -> if (BinanceAPI.isEarnProduct(balance.asset)) {
                        balance.asset
                    } else {
                        priceKeyFor(balance.asset) ?: baseAssetToSymbol(balance.asset)
                    }
                },
                name = balance.asset,
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
        }

        AppLogger.i(
            "币安持仓对齐完成：共 ${items.size} 条，新增 ${items.count { it.status == ItemStatus.NEW }}，" +
                "覆盖 ${items.count { it.status == ItemStatus.UPDATE }}，" +
                "未变 ${items.count { it.status == ItemStatus.UNCHANGED }}",
            "BinanceImport"
        )

        return Preview(items = items)
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
            rows = preview.items.map { it.toCommitRow(prices) },
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
            // 落库用真实交易对：去掉借币/抵押后缀，让负债与同币种现货按币安净额口径合并
            // （净值正确，且行情同步拿到的取价键仍是真实交易对、不会 400）。
            symbol = stripLoanSuffix(symbol),
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
 * 把理财持仓（活期 + 定期）合并进现货余额。
 *
 * ## 合并规则：同资产取 **较大值**，不是相加
 *
 * 为什么不能相加：`/api/v3/account` 的 balances **已经包含了一部分理财持仓**——
 * 实测用户现货余额里的 USDC 43.46 / PAXG 0.002474 / BNB 0.003154 与官方对账单的
 * Earn 持仓数字完全一致，说明这些理财资产在现货接口里是有体现的。直接相加会把
 * 同一笔钱记两遍。取 max 则两种情形都对：
 *
 * - 理财已包含在现货里（两者相等或现货更大）→ 值不变，不会重复计；
 * - 现货只有一部分 / 完全没有（这部分理财不在现货口径里）→ 用理财的完整数量补齐。
 *
 * ## 匹配用 [earnMergeKey]，不是原始资产名
 *
 * 现货侧的 Simple Earn 活期持仓是 LD 壳（LDPAXG），理财接口给的是底层币（PAXG）。
 * 按原名匹配的话两者互不相识，max 规则形同虚设（见 [earnMergeKey]）。
 *
 * ## 每个底层资产只让**一行**承载理财总量
 *
 * 库里可能同时有 `LDPAXG`（活期壳）和 `PAXG`（现货）。若两行都拿理财总量去 max，
 * 活期那笔会被记两遍。因此按归组键选出唯一的承载行：优先 LD 壳（它本身就是
 * 理财在现货里的容器），没有壳时退回第一条。其余行原样保留。
 *
 * 代价：若同一资产在两个来源里是**互不重叠的两笔**（如现货 57 + 定期 34.5），
 * max 会漏掉小的那笔。宁可少记也不能虚高——虚高会让总资产凭空多出一笔钱，
 * 比少记危险得多；且这种情况在币安口径下极少（理财接口返回的是该资产的全部持仓）。
 *
 * @param balances 现货余额（`/api/v3/account`）
 * @param earn     理财持仓（simple-earn 的活期与定期接口）；为空时原样返回
 * @return 合并后的余额列表（顺序：先现货原序，后理财独有的资产）
 */
internal fun mergeEarnIntoBalances(
    balances: List<BinanceBalance>,
    earn: List<BinanceEarnPosition>
): List<BinanceBalance> {
    if (earn.isEmpty()) return balances

    // 按底层资产归组：累计理财总量，并记住该资产是否存在**定期/锁仓**持仓。
    // 一个底层资产可能既有活期又有定期（币安允许同币双开），只要任一笔是定期，
    // 承载行就标记为 isEarnLocked=true（展示层据此显示「锁仓」而非「理财」）。
    val earnTotals = linkedMapOf<String, Double>()
    val earnLockedFlags = mutableMapOf<String, Boolean>()
    for (position in earn) {
        val key = earnMergeKey(position.asset)
        if (key.isEmpty() || position.amount <= 0.0) continue
        earnTotals[key] = (earnTotals[key] ?: 0.0) + position.amount
        // 同底层资产可能既活期又定期：任一笔是定期就标 true（OR 累计，不能只取首条）
        earnLockedFlags[key] = (earnLockedFlags[key] ?: false) || position.locked
    }
    if (earnTotals.isEmpty()) return balances

    // 每个归组键挑唯一的承载行：LD 壳优先（它就是理财在现货里的容器），
    // 没有壳时退回第一条（现货行 / 同名现货）。
    val carrierIndex = mutableMapOf<String, Int>()
    balances.forEachIndexed { index, balance ->
        val key = earnMergeKey(balance.asset)
        val current = carrierIndex[key]
        val carrierIsEarnShell = current != null && BinanceAPI.isEarnProduct(balances[current].asset)
        if (current == null || (!carrierIsEarnShell && BinanceAPI.isEarnProduct(balance.asset))) {
            carrierIndex[key] = index
        }
    }

    var raised = 0
    val merged = balances.mapIndexed { index, balance ->
        val key = earnMergeKey(balance.asset)
        val earnAmount = earnTotals[key]
        if (earnAmount != null && carrierIndex[key] == index && earnAmount > balance.total) {
            raised++
            // 用理财数量覆盖：free 存合并值、locked 归零（理财侧不再区分可用/冻结）。
            // isEarnLocked 透传：该底层资产有定期持仓则为 true（现货 LD 壳本身都是活期，默认 false）。
            BinanceBalance(
                asset = balance.asset,
                free = earnAmount,
                locked = 0.0,
                isEarnLocked = earnLockedFlags[key] ?: false
            )
        } else {
            balance
        }
    }.toMutableList()

    // known 同样按归组键判重：已有 LDPAXG 时，理财的 PAXG 不该再插一行
    val known = balances.map { earnMergeKey(it.asset) }.toSet()
    var added = 0
    earnTotals.forEach { (asset, amount) ->
        if (asset !in known) {
            merged.add(
                BinanceBalance(
                    asset = asset,
                    free = amount,
                    locked = 0.0,
                    isEarnLocked = earnLockedFlags[asset] ?: false
                )
            )
            added++
        }
    }

    AppLogger.i(
        "币安理财持仓合并：现货 ${balances.size} 项 + 理财 ${earnTotals.size} 个资产 → " +
            "补齐 $raised 项、新增 $added 项",
        "BinanceImport"
    )
    return merged
}
