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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stockdiary.utils.AppLogger
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
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
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 负债单独成组时使用的分组 key；真实账户名不会叫这个，不会与账户分组撞车 */
private const val LIABILITY_GROUP_KEY = "负债"

/**
 * 持仓的一级分组：场外 / 场内 / 加密货币 / 其他。
 *
 * 一级分组优先按**资产类型与 symbol 形态**判定，不是账户名——账户名是用户自己填的，
 * 两次导入填同一个名字就会混在一起，那是不可靠的。
 *
 * - `AssetType.CRYPTO`：加密货币（币安导入的 BTC/ETH…，代码形如 BTCUSDT）
 * - `fund_` 前缀：基金E账户导入的**场外**份额（按净值成交）
 * - `sh`/`sz`/`bj` 前缀：一码通导入的**场内**持仓（股票、ETF、LOF、REITs）
 * - 没有代码：存款等
 */
private enum class HoldingsMarket(val label: String) {
    EXCHANGE("场内"),
    OTC("场外"),
    CRYPTO("加密货币"),
    OTHER("其他");

    companion object {
        /** 展示顺序：场外（新导入的那批）放前面，其余按原顺序 */
        val displayOrder = listOf(OTC, EXCHANGE, CRYPTO, OTHER)
    }
}

private fun marketOf(asset: Asset): HoldingsMarket {
    // 加密货币必须优先按**类型**判：币安导入的代码形如 BTCUSDT，只按 symbol 形态会落进
    // 「场内」的 else 分支——加密不是场内证券，得单列一组。
    if (asset.type == AssetType.CRYPTO) return HoldingsMarket.CRYPTO
    val symbol = asset.symbol
    return when {
        symbol.isNullOrBlank() -> HoldingsMarket.OTHER
        symbol.startsWith("fund_", ignoreCase = true) -> HoldingsMarket.OTC
        else -> HoldingsMarket.EXCHANGE
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
        val expandKey: String = "market:${market.name}"
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
    val snackbarHostState = remember { SnackbarHostState() }

    // 与首页（DashboardScreen）同一处修复：本页 VM 挂在 Activity 作用域、跨标签页长期存活，
    // 偏好只在构造期加载一次。从「设置 → 自定义仪表盘」改完开关回本页时，内存副本仍是旧值，
    // 表现为「设置改了没反应」（负债照旧显示、隐藏低价值开关改不动），
    // 而首页有 ON_RESUME reload 却是正常的。这里同样在回前台时重新拉一次磁盘偏好。
    val portfolioLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(portfolioLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reloadPreferences()
        }
        portfolioLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { portfolioLifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        preferences.hideUnknownPriceCrypto
    ) {
        filterLowValueCrypto(
            visibleAssets,
            preferences.hideLowValueCrypto,
            preferences.lowValueThreshold(),
            preferences.hideUnknownPriceCrypto
        )
    }
    val assetsOnly = remember(displayAssets) { displayAssets.filter { !it.type.isLiability } }
    val liabilities = remember(displayAssets) { displayAssets.filter { it.type.isLiability } }

    // 总资产 / 负债 / 净资产一律按**全部资产**算，与资产页、每日快照同源。
    // 从前这里用按显示开关过滤后的可见资产，于是用户在设置里关掉某一类（比如虚拟货币）
    // 后，持仓页「总资产」少一块、资产页不变 —— 同一个名字两个数字，用户不知道信哪个。
    // 显示开关的语义是「列表里看不看」，不是「算不算进我的家底」
    val allAssetsOnly = remember(assets) { assets.filter { !it.type.isLiability } }
    val allLiabilities = remember(assets) { assets.filter { it.type.isLiability } }
    // 卡片上如实标注列表少显示了多少项，免得用户拿列表逐项加总对不上顶部数字
    val hiddenAssetCount = remember(displayAssets, assets) { assets.size - displayAssets.size }

    val totalAssets = remember(allAssetsOnly) { allAssetsOnly.sumTotalValueCny() }
    val totalLiabilities = remember(allLiabilities) { allLiabilities.sumTotalValueCny() }
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
     * 拍扁后的渲染单元：市场大区 → 账户 → 资产。
     *
     * 一级分组按 symbol 形态（场内/场外/其他），不按账户名——
     * 账户名是用户填的，两次导入填同一个名字就会混在一起。
     * 展开/收起只影响要不要把资产行放进去，各级头始终在。
     */
    val rows = remember(sortedAssets, liabilities, expandedAccounts) {
        buildList {
            HoldingsMarket.displayOrder.forEach { market ->
                val marketAssets = sortedAssets.filter { marketOf(it) == market }
                if (marketAssets.isEmpty()) return@forEach

                add(
                    PortfolioRow.MarketHeader(
                        market = market,
                        assetCount = marketAssets.size,
                        totalValue = marketAssets.sumOf { it.totalValueCny }
                    )
                )
                // 这个市场大区的展开 key
                val marketKey = "market:${market.name}"
                // 市场大区收起时，它下面的**账户头也要一起藏起来**。
                // 之前只藏资产行、账户头无条件 add，于是收起「场内」后底下一排账户头
                // 还挂在那儿 —— 看着就像点了没反应、一级标题根本折叠不了。
                if (marketKey in expandedAccounts) {
                    marketAssets.groupBy { it.account }.forEach { (account, list) ->
                        val header = PortfolioRow.AccountHeader(
                            account = account,
                            assetCount = list.size,
                            totalValue = list.sumOf { it.totalValueCny },
                            market = market
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

            // 负债单独成组：混进账户小计里会让「这个账户有多少钱」变得不可读。
            // expandKey 必须显式给 LIABILITY_GROUP_KEY，否则会与「其他」市场撞车
            if (liabilities.isNotEmpty()) {
                add(
                    PortfolioRow.MarketHeader(
                        market = HoldingsMarket.OTHER,
                        assetCount = liabilities.size,
                        totalValue = liabilities.sumOf { it.totalValueCny },
                        expandKey = LIABILITY_GROUP_KEY
                    )
                )
                if (LIABILITY_GROUP_KEY in expandedAccounts) {
                    liabilities.forEach { add(PortfolioRow.AssetRow(it, LIABILITY_GROUP_KEY)) }
                }
            }
        }
    }

    /**
     * 抓分红的触发条件：**持仓代码集合**，不是资产列表本身。
     *
     * 用列表做 key 的话，任何一次价格刷新都会让列表内容变化、效应重启、
     * 分红重抓一遍——而分红跟价格半点关系都没有。代码集合不变就不该重抓。
     *
     * 注意 key 用「集合内容」而不是 [Set] 对象身份：每次 assetsOnly 变化都会新建一个
     * Set，内容相同身份也不同，若用对象当 key，刷新时每写一个标的 LaunchedEffect 就重启一次
     * loadDividendIncome，纯属空转。这里排序后拼成稳定字符串，内容不变就不动。
     */
    val dividendSymbolsKey = remember(assetsOnly) {
        assetsOnly.mapNotNull { it.symbol }.sorted().joinToString(",")
    }

    LaunchedEffect(dividendSymbolsKey) {
        viewModel.loadDividendIncome(assetsOnly)
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
    val groupMembers = remember(sortedAssets, liabilities) {
        val map = LinkedHashMap<String, List<Long>>()
        HoldingsMarket.displayOrder.forEach { market ->
            val marketAssets = sortedAssets.filter { marketOf(it) == market }
            if (marketAssets.isEmpty()) return@forEach
            map["market:${market.name}"] = marketAssets.map { it.id }
            marketAssets.groupBy { it.account }.forEach { (account, list) ->
                map["account:${market.name}:$account"] = list.map { it.id }
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

    if (errorMessage != null) {
        LaunchedEffect(errorMessage) {
            snackbarHostState.showSnackbar(errorMessage ?: "")
            viewModel.clearError()
        }
    }

    // 天天基金限流提示：刷新撞 514 时，部分场外基金分红没抓全，给个非阻塞提示 + 可稍后重试。
    // 与上面的 errorMessage 分开（那是「同步失败」），避免混淆。
    dividendWarning?.let { warning ->
        LaunchedEffect(warning) {
            snackbarHostState.showSnackbar(warning)
            viewModel.clearDividendWarning()
        }
    }

    // 刷新完成一次性事件：用 SharedFlow 收尾，不再依赖会被清掉的持久字段，
    // 连点刷新 / 离开页面再回来都不会重复弹或丢弹（见 PortfolioViewModel.syncDone）。
    // replay=0 保证新进场收不到历史事件，extraBufferCapacity=1 让「正在弹时又刷完一次」排队不丢
    LaunchedEffect(Unit) {
        viewModel.syncDone.collect { result ->
            // 刷新结果反馈：成功/失败都报数。持仓页原先只有 errorMessage、没有成功反馈，
            // 用户点完刷新只看到转圈停下，到底刷成了几个无从得知（资产页一直有）
            val msg = if (result.failedCount > 0) {
                "刷新完成：成功 ${result.successCount}，失败 ${result.failedCount}"
            } else {
                "刷新完成：成功 ${result.successCount}"
            } + result.skippedHint // 上百个加密货币若因行情源不可达没更新，得让用户看见
            snackbarHostState.showSnackbar(msg)
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
    val onAssetDividendClick: (Long) -> Unit = remember {
        { id: Long -> selectedDividendAsset = visibleAssets.firstOrNull { it.id == id } }
    }
    val onAssetToggleSelection: (Long) -> Unit = remember {
        { id: Long ->
            selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val selectedCount = selectedIds.size
                        Text("已选 $selectedCount", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        TextButton(
                            onClick = {
                                if (selectedIds.isNotEmpty()) showMoveAccountDialog = true
                            },
                            enabled = selectedIds.isNotEmpty()
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
                            enabled = selectedIds.isNotEmpty() && !isRefreshing
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("同步")
                        }
                        TextButton(
                            onClick = { if (selectedIds.isNotEmpty()) showDeleteConfirmDialog = true },
                            enabled = selectedIds.isNotEmpty()
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
            // 分清两种「空」：有资产但被显示设置（类别开关 / 低价值隐藏）全藏了 → 给提示；
            // 压根没有资产 → 引导添加。混为一谈会让用户以为数据丢了。
            if (assets.isNotEmpty()) {
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
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
                            label = row.market.label,
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
            onConfirm = { asset ->
                // 保存失败也要关闭并提示，否则异常让关闭语句执行不到，
                // 弹窗里的 isSaving 又没人复位 —— 按钮会一直转圈
                scope.launch {
                    runCatching { viewModel.addAsset(asset) }
                        .onFailure {
                            AppLogger.e("新增资产失败: ${it.message}", it, "PortfolioScreen")
                            snackbarHostState.showSnackbar("保存失败：${it.message ?: "未知错误"}")
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
            onDismiss = { selectedDividendAsset = null }
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
                        viewModel.batchDelete(selectedIds)
                        showDeleteConfirmDialog = false
                        selectionMode = false
                        selectedIds = emptySet()
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