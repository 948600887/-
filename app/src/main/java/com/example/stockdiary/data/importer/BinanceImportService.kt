package com.example.stockdiary.data.importer

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.BinanceAPI
import com.example.stockdiary.network.BinanceBalance
import com.example.stockdiary.network.BINANCE_STABLECOINS
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.ExchangeRateManager

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
        val status: ItemStatus
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
            // dust 过滤：币安会返还一堆 0.00000000 的零头资产，free+locked 低于阈值一律忽略，
            // 否则预览里会出现几十条数量为 0 的「新增」，用户根本不知道自己持有这些
            rows = balances.filter { it.total >= DUST_THRESHOLD },
            existing = existingAssets,
            // 币安一个账户只有一份持仓，不存在「同代码跨多个账户」的冲突
            multiAccountConflict = false
        ) { balance ->
            SnapshotAlignment.RawHolding(
                // 基础资产名 → 交易对代码（BTC → BTCUSDT），落库/取价都用这个形态
                symbol = baseAssetToSymbol(balance.asset),
                name = balance.asset,
                quantity = balance.total
            )
        }.map { aligned ->
            PreviewItem(
                symbol = aligned.symbol,
                name = aligned.name,
                quantity = aligned.quantity,
                existing = aligned.matched.firstOrNull(),
                status = aligned.status.toUiStatus()
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
            symbol = symbol,
            name = name,
            quantity = quantity,
            status = status.toKernelStatus(),
            existing = existing,
            // 导入即预填：查得到价就写 currentPrice，总资产当场就是对的；
            // 查不到（含 LD 剥不出底层）留空，等行情同步按交易对补真实价
            nav = referencePriceForImport(name, prices),
            // 币安现货价以 USDT 计价（≈美元）：币种恒走策略里的 USD，不按行取
            currency = null
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
 * 币安认得的交易对形态（BTCUSDT、ETHUSDT…），这也是
 * [com.example.stockdiary.network.BinanceAPI.getSupportedSymbols] 返回的全集格式。
 *
 * ## 为什么不复用 [com.example.stockdiary.network.BinanceAPI] 的 formatSymbol
 *
 * formatSymbol 面向「用户输入的交易对/简称」，它把 BTC、ETH 当作**报价币**：
 * `formatSymbol("BTC")` 命中 `endsWith("BTC")` 分支返回 "BTC" 本身，而非 "BTCUSDT"。
 * 账户接口给的是裸基础资产，直接套 formatSymbol 会让 BTC→BTC、ETH→ETH，
 * 全部同步不到价。所以这里用一段专用于「基础资产补 USDT」的映射，产出与
 * getSupportedSymbols 完全一致的格式。
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
    val key = priceKeyFor(asset)
    if (key == null) {
        return if (isStablecoin(asset)) 1.0 else null
    }
    return prices[key]?.takeIf { it.isFinite() && it > 0.0 }
}

/** 是否是 USD 稳定币（无需查价，按 1 美元计） */
internal fun isStablecoin(asset: String): Boolean =
    asset.uppercase().trim() in BINANCE_STABLECOINS

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
    val key = priceKeyFor(asset)
    val usdUnitPrice = if (key == null) 1.0 else priceOf(key) ?: return null
    return total * usdUnitPrice * ExchangeRateManager.getRate("USD")
}

/**
 * 按人民币市值过滤零碎资产。
 *
 * 保留两类：市值 ≥ [minValueCny] 的，以及**估不出价**的（priceOf 拿不到价）。
 * 后者一律保留——取不到价可能是新币/冷门币，静默丢弃等于弄丢用户真实持仓，
 * 宁可多导一条让人自己删，也不能凭空少一份。
 *
 * @return (保留的余额列表, 被过滤掉的数量)
 */
internal fun filterDustByValue(
    balances: List<BinanceBalance>,
    priceOf: (String) -> Double?,
    minValueCny: Double
): Pair<List<BinanceBalance>, Int> {
    val kept = mutableListOf<BinanceBalance>()
    var filtered = 0
    for (balance in balances) {
        val value = estimatedValueCny(balance.asset, balance.total, priceOf)
        // value == null（估不出价）→ 保留；value < 阈值 → 滤掉
        if (value == null || value >= minValueCny) {
            kept.add(balance)
        } else {
            filtered++
        }
    }
    return kept to filtered
}
