package com.example.stockdiary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stockdiary.utils.AppLogger
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.example.stockdiary.data.dividend.dividendInputFingerprint
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.data.prefs.lowValueThreshold
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.PortfolioViewModel
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.sumDebtValueCny
import com.example.stockdiary.utils.excludingDebts
import com.example.stockdiary.utils.debts
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** 负债单独成组时使用的分组 key；真实账户名不会叫这个，不会与账户分组撞车 */
private const val LIABILITY_GROUP_KEY = "负债"

/**
 * 持仓的一级分组：场外 / 场内 / 加密货币 / 银行存款 / 其他。
 *
 * 一级分组优先按**资产类型与 symbol 形态**判定，不是账户名——账户名是用户自己填的，
 * 两次导入填同一个名字就会混在一起，那是不可靠的。
 *
 * - `AssetType.CRYPTO`：加密货币（币安导入的 BTC/ETH…，代码形如 BTCUSDT）
 * - `AssetType.BANK_DEPOSIT`：银行存款（没有代码，但**不能**因此落进「其他」）
 * - `fund_` 前缀：基金E账户导入的**场外**份额（按净值成交）
 * - `sh`/`sz`/`bj` 前缀：一码通导入的**场内**持仓（股票、ETF、LOF、REITs）
 * - 其余没有代码的资产：其他
 */
internal enum class HoldingsMarket(val label: String) {
    EXCHANGE("场内"),
    OTC("场外"),
    CRYPTO("加密货币"),
    DEPOSIT("银行存款"),
    OTHER("其他");

    companion object {
        /** 展示顺序：场外（新导入的那批）放前面，其余按原顺序 */
        val displayOrder = listOf(OTC, EXCHANGE, CRYPTO, DEPOSIT, OTHER)
    }
}

internal fun marketOf(asset: Asset): HoldingsMarket {
    // 加密货币必须优先按**类型**判：币安导入的代码形如 BTCUSDT，只按 symbol 形态会落进
    // 「场内」的 else 分支——加密不是场内证券，得单列一组。
    if (asset.type == AssetType.CRYPTO) return HoldingsMarket.CRYPTO
    // 存款同样必须按类型判：它没有 symbol，若只看 symbol 形态会被静默归进「其他」。
    if (asset.type == AssetType.BANK_DEPOSIT) return HoldingsMarket.DEPOSIT
    val symbol = asset.symbol
    return when {
        symbol.isNullOrBlank() -> HoldingsMarket.OTHER
        symbol.startsWith("fund_", ignoreCase = true) -> HoldingsMarket.OTC
        else -> HoldingsMarket.EXCHANGE
    }
}

/** 一级分组 + 该组资产 + 该组本币总额（组头展示与组间排序同源） */
internal data class MarketGroup(
    val market: HoldingsMarket,
    val assets: List<Asset>,
    val totalValue: Double
)

/**
 * 把资产按一级分组聚合，并按当前排序偏好决定**组间顺序**。
 *
 * 金额排序必须作用于分组本身：只排组内资产、组头仍按固定顺序输出的话，
 * 「其他」会永远留在最后——真机复现：其他 ¥71616.93 排在加密货币 ¥1106.30 之后。
 * 组总额与资产行同一个口径 [totalValueCny]（已按汇率换算成人民币），
 * 避免「显示值看着更大、排序却更小」这种原值/显示值不一致的问题。
 */
internal fun groupAssets(assets: List<Asset>, sortOrder: SortOrder): List<MarketGroup> {
    val groups = HoldingsMarket.displayOrder.mapNotNull { market ->
        val list = assets.filter { marketOf(it) == market }
        if (list.isEmpty()) null
        else MarketGroup(market, list, list.sumOf { it.totalValueCny })
    }
    return when (sortOrder) {
        SortOrder.BY_VALUE_DESC -> groups.sortedByDescending { it.totalValue }
        SortOrder.BY_VALUE_ASC -> groups.sortedBy { it.totalValue }
        // 按类型 / 按名称时保持既有展示顺序；组内资产已按各自规则排序
        SortOrder.BY_TYPE, SortOrder.BY_NAME -> groups
    }
}

/**
 * 持仓列表的渲染单元：市场大区头、账户头，或一只资产。
 *
 * ## 为什么必须拍扁
 *
 * LazyColumn 的懒加载**只对它自己的直接子项生效**。原先「账户头 + 该账户下所有资产」
 * 被打包成一个 item，内部用 Column + forEach 渲染，于是一个账户下的上百只基金
 * 会被一次性全部组合——首屏直接卡死，持仓越多越明显。
 *
 * 拍扁成三种单元后，每一项都是 LazyColumn 的直接子项，滚动到哪才组合到哪。
 * 账户小计也在构造时算好，不在组合里重复 sumOf。
 */
private sealed interface PortfolioRow {
    val key: String

    data class MarketHeader(
        val market: HoldingsMarket,
        val assetCount: Int,
        val totalValue: Double,
        /**
         * 展开态用的 key。
         *
         * 默认取 "market:xxx"，与账户名不会撞车；负债区必须显式传
         * [LIABILITY_GROUP_KEY]——否则它会和「其他」市场共用 OTHER，两处会一起展开/收起。
         */
        val expandKey: String = "market:${market.name}",
        /**
         * 覆盖标题：负债区借用 [HoldingsMarket.OTHER] 的枚举值（排序/分组逻辑复用它），
         * 但标题不能也叫「其他」——列表里会同时出现真实的「其他」资产分组。
         * 这里显式覆盖成「负债」。
         */
        val displayLabel: String? = null
    ) : PortfolioRow {
        override val key: String get() = "header:$expandKey"
    }

    data class AccountHeader(
        val account: String,
        val assetCount: Int,
        val totalValue: Double,
        /** 所属市场。同一个账户名可能两边都出现（比如都填「中国结算」），key 要带上它 */
        val market: HoldingsMarket
    ) : PortfolioRow {
        override val key: String get() = "account:${market.name}:$account"

        /**
         * 展开态用的 key，**必须像 [key] 一样带市场**。
         *
         * 之前这里用的是裸账户名，于是场内、场外两个区里的同名账户共用同一个展开态：
         * 点其中一个展开，另一个区的同名账户也跟着展开、资产行一起加进来，
         * 看着就像「点了一个账户，场内场外全展开了」。
         * 与负债区那个 LIABILITY_GROUP_KEY 是同一类撞 key 问题，只是出在账户这一层。
         */
        val expandKey: String get() = "account:${market.name}:$account"
    }

    data class AssetRow(val asset: Asset, val account: String) : PortfolioRow {
        override val key: String get() = "asset:${asset.id}"
    }
}

/**
 * 一级分组在渲染前的统一形态：市场组与负债组共用。
 *
 * 负债从前是「永远追加在最后」的另一段逻辑，导致按金额排序时它固定垫底。
 * 收成同一形态后才能把负债也丢进同一套排序里。
 */
private data class HeaderGroup(
    val market: HoldingsMarket,
    val assets: List<Asset>,
    /** 组头显示的金额：市场组 = 组内市值和；负债组 = 绝对值合计（取正显示） */
    val headerValue: Double,
    val expandKey: String,
    /** 负债组借用 OTHER 枚举，标题需覆盖成「负债」；市场组为 null */
    val displayLabel: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    viewModel: PortfolioViewModel,
    modifier: Modifier = Modifier,
    onNavigateToAssetDetail: (Long) -> Unit = {}
) {
    // 刷新/分红加载整组状态合并进 uiState 一条流（见 PortfolioViewModel.PortfolioUiState）；
    // existingAccounts / preferences / expandedGroups 与刷新状态机无关，保持独立流
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val assets = uiState.assets
    val isRefreshing = uiState.isRefreshing
    val syncProgress = uiState.syncProgress
    val estimatedAnnualDividend = uiState.estimatedAnnualDividend
    val dividendLoading = uiState.dividendLoading
    val incomeCalculationStatus = uiState.incomeCalculationStatus
    val errorMessage = uiState.errorMessage
    val dividendWarning = uiState.dividendWarning
    val existingAccounts by viewModel.existingAccounts.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    // 以下 UI 态改 rememberSaveable：旋屏/配置变更后不丢（对话框不会凭空关掉、多选不丢）。
    // 注意 selectedDividendAsset 是 Asset?，不是可保存类型，仍用 remember ——
    // 换成 rememberSaveable 会在保存时抛 IllegalArgumentException。
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showSearchDialog by rememberSaveable { mutableStateOf(false) }
    var selectedDividendAsset by remember { mutableStateOf<Asset?>(null) }
    // 展开态提升到 ViewModel：跨标签页存活，切走再回来保持用户的展开/收起
    val expandedAccounts by viewModel.expandedGroups.collectAsStateWithLifecycle()
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveAccountDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 提示宿主在导航层（见 GoldVaultNavHost）：本页只发消息，不持有 SnackbarHostState，
    // 否则切 Tab 时页面移出组合，刚弹出的提示会立刻被取消。
    val appSnackbar = LocalAppSnackbar.current

    // 偏好已收口到 Manager 的唯一流（见 PortfolioViewModel.preferences）：
    // 设置页改完开关会直接推送到这里，回前台 / 切 Tab 都不再需要手动重读磁盘。

    // 资产列表已从资产页并入这里：股票/基金/虚拟货币/银行存款 + 负债，一页看全。
    // 显示开关沿用「设置 → 仪表盘显示」，关掉某类就整类不出现。
    val visibleAssetTypes = remember(preferences) {
        setOfNotNull(
            AssetType.BANK_DEPOSIT.takeIf { preferences.showBankDeposits },
            AssetType.STOCK.takeIf { preferences.showStocks },
            AssetType.FUND.takeIf { preferences.showFunds },
            AssetType.CRYPTO.takeIf { preferences.showCrypto }
        )
    }

    // 原来是 remember{ derivedStateOf{...} } 反模式：lambda 里读的是普通 List 不是 State，
    // derivedStateOf 不会自动失效，白包一层对象。直接 remember 返回 List。
    val visibleAssets = remember(assets, visibleAssetTypes, preferences.showLiabilities) {
        assets.filter {
            it.type in visibleAssetTypes || (it.type.isLiability && preferences.showLiabilities)
        }
    }
    // 低价值虚拟货币隐藏：在「显示开关」按类别过滤之上再滤一层。
    // 仅藏「已知价且人民币市值低于阈值」的加密货币；价格未知的不藏（避免误丢真实持仓）。
    // 总资产仍按全量（allAssetsOnly / allLiabilities）算，这里只影响列表与分组头计数。
    val displayAssets = remember(
        visibleAssets,
        preferences.hideLowValueCrypto,
        preferences.hideUnknownPriceCrypto,
        // 阈值必须进 key：设置里只改阈值、开关不动时，remember 不重算 → 旧阈值继续生效，
        // 用户以为设置没保存（MEMORY.md 同类坑：过滤结果必须随阈值变）
        preferences.lowValueThreshold()
    ) {
        filterLowValueCrypto(
            visibleAssets,
            preferences.hideLowValueCrypto,
            preferences.lowValueThreshold(),
            preferences.hideUnknownPriceCrypto
        )
    }
    // 负债口径统一为 Asset.isDebt（含余额为负的借币负债），与快照/首页一致；
    // 只按 type.isLiability 分的话，负余额借币会留在资产列表里显示成负资产、负债区看不到。
    val assetsOnly = remember(displayAssets) { displayAssets.excludingDebts() }
    val liabilities = remember(displayAssets) { displayAssets.debts() }

    // 总资产 / 负债 / 净资产一律按**全部资产**算，与资产页、每日快照同源。
    // 从前这里用按显示开关过滤后的可见资产，于是用户在设置里关掉某一类（比如虚拟货币）
    // 后，持仓页「总资产」少一块、资产页不变 —— 同一个名字两个数字，用户不知道信哪个。
    // 显示开关的语义是「列表里看不看」，不是「算不算进我的家底」
    val allAssetsOnly = remember(assets) { assets.excludingDebts() }
    val allLiabilities = remember(assets) { assets.debts() }
    // 卡片上如实标注列表少显示了多少项，免得用户拿列表逐项加总对不上顶部数字
    val hiddenAssetCount = remember(displayAssets, assets) { assets.size - displayAssets.size }

    // 同 DashboardScreen：借款从资产端挪到负债端，净资产数值不变。
    val totalAssets = remember(allAssetsOnly) { allAssetsOnly.sumTotalValueCny() }
    val totalLiabilities = remember(allLiabilities) { allLiabilities.sumDebtValueCny() }
    val netAssets = totalAssets - totalLiabilities

    val sortedAssets = remember(assetsOnly, preferences.sortOrder) {
        when (preferences.sortOrder) {
            SortOrder.BY_TYPE -> assetsOnly.sortedBy { it.type.ordinal }
            SortOrder.BY_VALUE_DESC -> assetsOnly.sortedByDescending { it.totalValueCny }
            SortOrder.BY_VALUE_ASC -> assetsOnly.sortedBy { it.totalValueCny }
            SortOrder.BY_NAME -> assetsOnly.sortedBy { it.name }
        }
    }

    /**
     * 一级分组（含组间顺序）：金额排序时必须排**分组本身**，不能只排组内资产。
     */
    val marketGroups = remember(sortedAssets, preferences.sortOrder) {
        groupAssets(sortedAssets, preferences.sortOrder)
    }

    /**
     * 拍扁后的渲染单元：市场大区 → 账户 → 资产。
     *
     * 一级分组按资产类型 / symbol 形态（场内/场外/存款/其他），不按账户名——
     * 账户名是用户填的，两次导入填同一个名字就会混在一起。
     * 展开/收起只影响要不要把资产行放进去，各级头始终在。
     */
    val rows = remember(marketGroups, liabilities, expandedAccounts, preferences.sortOrder) {
        buildList {
            // 负债组与市场组一起进同一套组间排序：金额一律按**绝对值**比较
            // （币安借币以负余额存储、负债类型存正数，只有绝对值才和资产组同尺度）。
            // 按类型/名称排序时保持既有展示顺序，负债仍排最后。
            val headerGroups = buildList {
                marketGroups.forEach { group ->
                    add(
                        HeaderGroup(
                            market = group.market,
                            assets = group.assets,
                            headerValue = group.totalValue,
                            expandKey = "market:${group.market.name}",
                            displayLabel = null
                        )
                    )
                }
                if (liabilities.isNotEmpty()) {
                    add(
                        HeaderGroup(
                            market = HoldingsMarket.OTHER,
                            assets = liabilities,
                            // 负债金额取正显示：借币是负数，组头不能显示成负资产
                            headerValue = liabilities.sumDebtValueCny(),
                            // expandKey 必须显式给 LIABILITY_GROUP_KEY，否则会与「其他」市场撞车
                            expandKey = LIABILITY_GROUP_KEY,
                            displayLabel = "负债"
                        )
                    )
                }
            }
            val orderedGroups = when (preferences.sortOrder) {
                SortOrder.BY_VALUE_DESC -> headerGroups.sortedByDescending { kotlin.math.abs(it.headerValue) }
                SortOrder.BY_VALUE_ASC -> headerGroups.sortedBy { kotlin.math.abs(it.headerValue) }
                SortOrder.BY_TYPE, SortOrder.BY_NAME -> headerGroups
            }

            orderedGroups.forEach { group ->
                add(
                    PortfolioRow.MarketHeader(
                        market = group.market,
                        assetCount = group.assets.size,
                        totalValue = group.headerValue,
                        expandKey = group.expandKey,
                        displayLabel = group.displayLabel
                    )
                )
                // 市场大区收起时，它下面的**账户头也要一起藏起来**。
                // 之前只藏资产行、账户头无条件 add，于是收起「场内」后底下一排账户头
                // 还挂在那儿 —— 看着就像点了没反应、一级标题根本折叠不了。
                if (group.expandKey !in expandedAccounts) return@forEach
                if (group.expandKey == LIABILITY_GROUP_KEY) {
                    // 负债单独成组：混进账户小计里会让「这个账户有多少钱」变得不可读。
                    group.assets.forEach { add(PortfolioRow.AssetRow(it, LIABILITY_GROUP_KEY)) }
                } else {
                    group.assets.groupBy { it.account }.forEach { (account, list) ->
                        val header = PortfolioRow.AccountHeader(
                            account = account,
                            assetCount = list.size,
                            totalValue = list.sumOf { it.totalValueCny },
                            market = group.market
                        )
                        add(header)
                        // 账户 key 必须带市场：否则场内/场外的同名账户共用一个展开态，
                        // 点一个另一个跟着展开（已修）。市场这一层已在上面判过。
                        if (header.expandKey in expandedAccounts) {
                            list.forEach { add(PortfolioRow.AssetRow(it, account)) }
                        }
                    }
                }
            }
        }
    }

    /**
     * 抓分红的触发条件：**代码 + 持仓数量 + 存款利率**（见 dividendInputFingerprint），
     * 不是资产列表本身。
     *
     * 用列表做 key 的话，任何一次价格刷新都会让列表内容变化、效应重启、
     * 分红重抓一遍——而分红跟价格半点关系都没有。
     *
     * 但只用代码集合也不够：年化收入 = 数量 × 每股分红，加仓不改代码集合；
     * 银行存款 symbol=null，改余额/利率同样不改集合。两种情况都得重算，
     * 所以 key 带上数量与利率（数组内容相同则指纹相同，价格刷新不会误触发）。
     */
    // 输入范围必须是**全量非负债资产**，与首页/详情页同源：
    // 用 displayAssets 算的话，用户关掉「显示银行存款」后年度分红会凭空变少，
    // 刷新时又按全量算回来——同一个标签两个数字，来回跳变。
    // 显示开关的语义是「列表里看不看」，不该改变我的收入。
    val dividendInputKey = remember(allAssetsOnly) { dividendInputFingerprint(allAssetsOnly) }

    LaunchedEffect(dividendInputKey) {
        viewModel.loadDividendIncome(allAssetsOnly)
    }

    // 所有「可展开分组」的 key：市场大区 + 账户。
    // Set 的相等性是结构性的，用它做副作用 key 可以保证「分组集合没变」时不重跑，
    // 避免价格波动刷新列表就把用户刚收起的分组重新展开。
    val groupKeys = remember(rows) {
        rows.mapNotNull { row ->
            when (row) {
                is PortfolioRow.MarketHeader -> row.expandKey
                is PortfolioRow.AccountHeader -> row.expandKey
                is PortfolioRow.AssetRow -> null
            }
        }.toSet()
    }

    // 一级（市场大区）分组的 key：首屏默认收起这些，只留 场内/场外/… 概览头。
    // 负债区（LIABILITY_GROUP_KEY）也是一个一级头，一并算进来。
    val marketKeys = remember(rows) {
        rows.mapNotNull { (it as? PortfolioRow.MarketHeader)?.expandKey }.toSet()
    }

    /**
     * 每个标题（一级市场头 / 二级账户头）名下的全部资产 id，用于「整组勾选」。
     *
     * ## 为什么不直接复用 [rows]
     *
     * [rows] 随展开态变化（收起的分组不产资产行），而成员集合必须**与展开无关**——
     * 否则收起状态下点一级头的勾选框，只会选中零散几条，用户以为选了整组，
     * 删除后才发现残了一半。这里的成员与该头显示的「N 项」同源，**所见即所选**。
     *
     * 成员取自 [sortedAssets] / [liabilities]（即应用「隐藏低价值加密」等显示开关
     * 之后的集合）：被显示开关藏起来的资产本来也不在列表里，不该被整组勾选选中。
     */
    val groupMembers = remember(marketGroups, liabilities) {
        val map = LinkedHashMap<String, List<Long>>()
        marketGroups.forEach { group ->
            map["market:${group.market.name}"] = group.assets.map { it.id }
            group.assets.groupBy { it.account }.forEach { (account, list) ->
                map["account:${group.market.name}:$account"] = list.map { it.id }
            }
        }
        if (liabilities.isNotEmpty()) map[LIABILITY_GROUP_KEY] = liabilities.map { it.id }
        map
    }

    /**
     * 整组勾选/取消：组内已全选 → 整组取消；否则 → 整组选中。
     *
     * 「部分选中」时按选中处理（补齐剩下的），与常见全选控件一致——用户点一下
     * 的意图是「这个组我全要」，而不是「把已经勾的那些反选掉」。
     */
    val toggleGroupSelection: (String) -> Unit = { key ->
        val ids = groupMembers[key].orEmpty()
        if (ids.isNotEmpty()) {
            selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids else selectedIds + ids
        }
    }

    /** 整组三态：全选 / 部分选中 / 全未选（用于标题上的三态勾选框） */
    val groupSelectionState: (String) -> ToggleableState = { key ->
        val ids = groupMembers[key].orEmpty()
        if (ids.isEmpty()) {
            ToggleableState.Off
        } else {
            when (ids.count { it in selectedIds }) {
                0 -> ToggleableState.Off
                ids.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
        }
    }

    // 处在批量选择态时，返回键应当先退出选择态，而不是直接退出 App
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    // 分组展开态在 ViewModel（expandedGroups）：首次进场**只展开到一级标题以下**
    // （市场大区全收起、账户保持展开），之后只自动展开"新出现"的分组，
    // 用户手动收起的切走再回来也保持收起（不再被首屏逻辑强行展开）。
    LaunchedEffect(groupKeys, marketKeys) {
        viewModel.syncGroups(groupKeys, marketKeys)
    }

    // 先清状态再展示（理由同 DashboardScreen）：show 在应用级作用域上，不随效应取消，
    // 而状态已清，回到本页不会整段重弹。
    if (errorMessage != null) {
        LaunchedEffect(errorMessage) {
            val message = errorMessage ?: return@LaunchedEffect
            viewModel.clearError()
            appSnackbar.show(message)
        }
    }

    // 天天基金限流提示：刷新撞 514 时，部分场外基金分红没抓全，给个非阻塞提示 + 可稍后重试。
    // 与上面的 errorMessage 分开（那是「同步失败」），避免混淆。
    dividendWarning?.let { warning ->
        LaunchedEffect(warning) {
            viewModel.clearDividendWarning()
            appSnackbar.show(warning)
        }
    }

    // 刷新结果放在 UiState 里：切走标签页不会丢，消费后立刻清掉不会重复弹。
    // 用 snapshotFlow 而不是 LaunchedEffect(pendingSyncResult)：先清空再弹，
    // 清空若作为 key 会把正在显示的 Snackbar 取消；snapshotFlow 不受影响。
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.pendingSyncResult }
            .filterNotNull()
            .collect { result ->
                viewModel.clearPendingSyncResult()
                // 刷新结果反馈：成功/失败都报数。持仓页原先只有 errorMessage、没有成功反馈，
                // 用户点完刷新只看到转圈停下，到底刷成了几个无从得知（资产页一直有）
                val msg = if (result.failedCount > 0) {
                    "刷新完成：成功 ${result.successCount}，失败 ${result.failedCount}"
                } else {
                    "刷新完成：成功 ${result.successCount}"
                } + result.skippedHint + result.sourceHealthHint
                appSnackbar.show(msg)
            }
    }

    // 重试时的选择集校验：snackbar 跑在应用作用域，页面切走/回来时 rememberSaveable 的选择态
    // 已复位，闭包里旧的 id 可能失效。用「页面是否仍在组合 + id 是否仍存在于当前资产」过滤，
    // 只重试真实有效的 id（纯函数见文件底部 retrySelectionIds）。
    var screenActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { screenActive = false }
    }
    val latestSelectedIds by rememberUpdatedState(selectedIds)
    val latestExistingIds by rememberUpdatedState(assets.map { it.id }.toSet())

    // 批量删除结果：成功才退出多选并清空选择；失败保留选择态并给可重试的提示（#12）。
    //
    // 改用 snapshotFlow + 应用级作用域（原来 LaunchedEffect(deleteResult) + 页面协程 await）：
    // ① 结果先清再处理，切页时不会因为 clear 执行不到而残留、回来重弹；
    // ② await 也从页面协程挪到 controller 里，切走后提示与「重试」按钮都还在。
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.deleteResult }
            .filterNotNull()
            .collect { result ->
                viewModel.clearDeleteResult()
                if (result.isSuccess) {
                    selectionMode = false
                    selectedIds = emptySet()
                } else {
                    // 失败那一刻的选择集：重试应针对它，而不是用户之后又改成的选择
                    val failedIds = latestSelectedIds
                    appSnackbar.showWithAction(
                        message = "批量删除失败，请重试",
                        actionLabel = "重试"
                    ) {
                        // 选择态可能已失效（切页后复位、资产被删），只提交仍然有效的 id；
                        // 空则视为失效，什么都不做。
                        val retryIds = retrySelectionIds(
                            screenActive = screenActive,
                            selectedIds = failedIds,
                            existingIds = latestExistingIds
                        )
                        if (retryIds.isNotEmpty()) viewModel.batchDelete(retryIds)
                    }
                }
            }
    }

    // 选择态修剪：资产被删/被其它路径移除后，selectedIds 里残留的失效 id 要清掉，
    // 否则这些幽灵 id 会让「全选/反选」状态算错、删除按钮点数虚高（#12）
    LaunchedEffect(assets) {
        if (selectedIds.isNotEmpty()) {
            val existingIds = assets.map { it.id }.toSet()
            val pruned = selectedIds.filter { it in existingIds }.toSet()
            if (pruned.size != selectedIds.size) selectedIds = pruned
        }
    }

    // 稳定回调：以下 lambda 都用 remember 缓存同一实例，且只读「实时状态」
    // （selectionMode / selectedIds 等都是 by-remember 属性，lambda 体内读取拿到的是
    // 调用时刻的最新值；viewModel.toggleGroup 本身稳定）。这是持仓页流畅的关键：
    // 未变化的资产行必须能被 Compose 跳过重组——否则每次全屏重组（刷新时 assets 流
    // 每写一个标的就 emit 一次）都会把所有可见行重排一遍，列表发抖。
    val toggleExpand: (String) -> Unit = remember {
        { key: String ->
            // 选择态（批量编辑）下也必须允许展开/收起：折叠的组看不到里面的持仓，
            // 用户根本没法勾选组内资产。分组头点击是「展开/收起」，资产行点击才是
            // 「勾选/取消勾选」，两个目标不同，互不冲突。
            viewModel.toggleGroup(key)
        }
    }
    val onAssetClick: (Long) -> Unit = remember {
        { id: Long ->
            if (selectionMode) {
                selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
            } else {
                onNavigateToAssetDetail(id)
            }
        }
    }
    // 必须用 remember(assets) 且从全量 assets 取：本 lambda 被 StockAssetItem 稳定持有，
    // 若用无 key 的 remember 会捕获首次组合时的 visibleAssets（旧实例），价格刷新后列表里的
    // 资产对象已是新实例，这里却仍返回旧对象 —— 点分红弹出的可能是过期价格/市值。
    // 用全量 assets 而非 displayAssets：被显示开关/低价值隐藏的资产 id 仍在全量里，绝不会 firstOrNull 落空。
    val onAssetDividendClick: (Long) -> Unit = remember(assets) {
        { id: Long -> selectedDividendAsset = assets.firstOrNull { it.id == id } }
    }
    val onAssetToggleSelection: (Long) -> Unit = remember {
        { id: Long ->
            selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    // 刷新要跑完上百个标的，只显示转圈的话用户无从判断是在干活还是卡死。
                    // 把「已完成 / 总数」露出来，等待就有了预期
                    val progress = syncProgress
                    if (isRefreshing && progress != null && progress.total > 0) {
                        Column {
                            Text("持仓管理", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${progress.current} / ${progress.total}  ${progress.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text("持仓管理")
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                selectionMode = false
                                selectedIds = emptySet()
                            }
                        ) { Text("取消") }
                    } else {
                        IconButton(onClick = { showSearchDialog = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Filled.Checklist, contentDescription = "批量编辑")
                        }
                        IconButton(onClick = { viewModel.refresh() }, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = AppTheme.colors.primary,
                    contentColor = AppTheme.colors.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加资产")
                }
            }
        },
        bottomBar = {
            if (selectionMode) {
                // 外层 GoldVaultNavHost 的 NavigationBar + Modifier.padding(padding) 已完整处理
                // 导航栏避让（两层 Scaffold 分工见 MainActivity.kt:47-53）。BottomAppBar 若用默认
                // windowInsets 会二次避让 systemBars.bottom，撑出操作栏与 TabBar 间的大块空白；
                // 横屏的水平避让已由外层 padding.start/end 承担，这里整体关闭以免重复计算。
                // containerColor 取中性表面色做视觉锚点，把列表区与底部操作区明确分开。
                BottomAppBar(
                    containerColor = AppTheme.colors.surfaceContainerHighest,
                    contentColor = AppTheme.colors.onSurface,
                    tonalElevation = 3.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val selectedCount = selectedIds.size
                        // 启用态文字显式取 AppTheme 主色（深色下已提亮），避免沿用
                        // colorScheme.primary 的深绿落在操作栏灰底上对比度不足。
                        // 只覆盖 contentColor，禁用态仍走 M3 默认 onSurface@0.38。
                        val actionButtonColors = ButtonDefaults.textButtonColors(
                            contentColor = AppTheme.colors.primary
                        )
                        Text("已选 $selectedCount", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        TextButton(
                            onClick = {
                                if (selectedIds.isNotEmpty()) showMoveAccountDialog = true
                            },
                            enabled = selectedIds.isNotEmpty(),
                            colors = actionButtonColors
                        ) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("移动账户")
                        }
                        TextButton(
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    val selectedAssets = visibleAssets.filter { it.id in selectedIds }
                                    viewModel.batchSync(selectedAssets)
                                }
                            },
                            enabled = selectedIds.isNotEmpty() && !isRefreshing,
                            colors = actionButtonColors
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("同步")
                        }
                        TextButton(
                            onClick = { if (selectedIds.isNotEmpty()) showDeleteConfirmDialog = true },
                            enabled = selectedIds.isNotEmpty(),
                            colors = actionButtonColors
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除")
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { padding ->
        if (displayAssets.isEmpty()) {
            // 分清三种「空」：首帧 Room 还没发射（assetsFrameReady=false）→ 显示加载，
            // 否则一闪而过把"引导添加"空卡片误当成"没有数据"；
            // 有资产但被显示设置（类别开关 / 低价值隐藏）全藏了 → 给提示；
            // 压根没有资产 → 引导添加。后两者混淆会让用户以为数据丢了。
            if (!uiState.assetsFrameReady) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (assets.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text("当前未显示任何资产", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "你共有 ${assets.size} 项记录，但显示设置把它们全部隐藏了",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { viewModel.showAllAssetTypes() }) {
                                Text("显示全部资产")
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    EmptyAssetCard(onAddAsset = { showAddDialog = true })
                }
            }
        } else {
            // 加号(FAB)是**悬浮**在列表之上的：Scaffold 的 innerPadding 只为 topBar / bottomBar
            // 预留空间，**不会**为 FAB 预留。不手动留白的话，滚到底时最后一项资产会被绿色加号
            // 压住一截（真机截图：LDBTC 的金额被盖掉）。
            // 56dp(FAB 直径) + 16dp(Scaffold 给 FAB 的默认外边距) = 72dp；批量选择态下 FAB 隐藏
            // （换成底部操作栏，其高度已由 innerPadding 计入），此时不留白，避免底部空一大块。
            val fabBottomSpace = if (selectionMode) 0.dp else 72.dp
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + fabBottomSpace
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PortfolioOverviewCard(
                        totalAssets = totalAssets,
                        totalLiabilities = totalLiabilities,
                        netAssets = netAssets,
                        estimatedAnnualDividend = estimatedAnnualDividend,
                        isDividendLoading = dividendLoading,
                        dividendStatus = incomeCalculationStatus,
                        assetCount = assetsOnly.size,
                        hiddenAssetCount = hiddenAssetCount
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("全部资产", style = MaterialTheme.typography.titleSmall)
                        SortChip(
                            currentSort = preferences.sortOrder,
                            onSortChange = { viewModel.updateSortOrder(it) }
                        )
                    }
                }

                // 市场头 / 账户头 / 资产行都是 LazyColumn 的直接子项，滚动到哪组合到哪
                // contentType 让 LazyLayout 在跨类型滚动时复用组合（否则头/资产交替处要重排）
                items(rows, key = { it.key }, contentType = { it::class.qualifiedName }) { row ->
                    when (row) {
                        is PortfolioRow.MarketHeader -> MarketHeaderCard(
                            label = row.displayLabel ?: row.market.label,
                            assetCount = row.assetCount,
                            totalValue = row.totalValue,
                            isExpanded = expandedAccounts.contains(row.expandKey),
                            expandKey = row.expandKey,
                            onToggleExpand = toggleExpand,
                            selectionMode = selectionMode,
                            selectionState = groupSelectionState(row.expandKey),
                            onToggleSelection = { toggleGroupSelection(row.expandKey) }
                        )

                        is PortfolioRow.AccountHeader -> AccountHeaderCard(
                            account = row.account,
                            assetCount = row.assetCount,
                            totalValue = row.totalValue,
                            isExpanded = expandedAccounts.contains(row.expandKey),
                            expandKey = row.expandKey,
                            onToggleExpand = toggleExpand,
                            selectionMode = selectionMode,
                            selectionState = groupSelectionState(row.expandKey),
                            onToggleSelection = { toggleGroupSelection(row.expandKey) }
                        )

                        is PortfolioRow.AssetRow -> {
                            val asset = row.asset
                            StockAssetItem(
                                asset = asset,
                                onClick = onAssetClick,
                                onDividendClick = onAssetDividendClick,
                                selectionMode = selectionMode,
                                isSelected = selectedIds.contains(asset.id),
                                onToggleSelection = onAssetToggleSelection
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAssetDialog(
            onDismiss = { showAddDialog = false },
            existingAccounts = existingAccounts,
            existingAssets = assets,
            onConfirm = { asset ->
                // 保存失败也要关闭并提示，否则异常让关闭语句执行不到，
                // 弹窗里的 isSaving 又没人复位 —— 按钮会一直转圈
                scope.launch {
                    runCatching { viewModel.addAsset(asset) }
                        .onFailure {
                            AppLogger.e("新增资产失败: ${it.message}", it, "PortfolioScreen")
                            appSnackbar.show("保存失败：${it.message ?: "未知错误"}")
                        }
                    showAddDialog = false
                }
            }
        )
    }

    if (showSearchDialog) {
        PortfolioSearchDialog(
            assets = visibleAssets,
            onDismiss = { showSearchDialog = false },
            onAssetSelected = { asset ->
                showSearchDialog = false
                onNavigateToAssetDetail(asset.id)
            },
            onDividendSelected = {
                selectedDividendAsset = it
                showSearchDialog = false
            }
        )
    }

    selectedDividendAsset?.let { dividendAsset ->
        DividendDetailDialog(
            asset = dividendAsset,
            onDismiss = { selectedDividendAsset = null },
            // 手动改了分红记录后，年度分红的缓存指纹要作废重算，
            // 否则顶部「年度分红」一直停在旧值（指纹不含分红记录本身）
            onRecordsChanged = { viewModel.refreshDividendIncomeAfterManualEdit(allAssetsOnly) }
        )
    }

    if (showMoveAccountDialog) {
        AlertDialog(
            onDismissRequest = { showMoveAccountDialog = false },
            title = { Text("移动到账户") },
            text = {
                Column {
                    existingAccounts.forEach { account ->
                        TextButton(
                            onClick = {
                                viewModel.batchMoveToAccount(selectedIds, account)
                                showMoveAccountDialog = false
                                selectionMode = false
                                selectedIds = emptySet()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(account)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveAccountDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedIds.size} 项资产吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        // 先关对话框，但**保留选择态与多选模式**：删除结果（成功/失败）由
                        // LaunchedEffect(deleteResult) 处理——成功才清选择，失败给「重试」入口（#12）。
                        // 原写法无论成败都立刻清空选择，失败了用户只能重选重删，没有重试。
                        viewModel.batchDelete(selectedIds)
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 重试批量删除时实际提交的 id 集合。
 *
 * 返回空表示「选择集已失效」：页面已离开组合（rememberSaveable 的选择态复位），
 * 或失败批次的 id 已不在当前资产列表里（资产被删除/被过滤）。空集时调用方应放弃重试，
 * 而不是拿过期 id 再发一次删除请求。
 */
internal fun retrySelectionIds(
    screenActive: Boolean,
    selectedIds: Set<Long>,
    existingIds: Set<Long>
): Set<Long> {
    if (!screenActive) return emptySet()
    return selectedIds intersect existingIds
}