package com.example.stockdiary.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.example.stockdiary.utils.AppLogger
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stockdiary.data.SortOrder
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.PortfolioViewModel
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.launch

/** 负债单独成组时使用的分组 key；真实账户名不会叫这个，不会与账户分组撞车 */
private const val LIABILITY_GROUP_KEY = "负债"

/**
 * 持仓列表的渲染单元：账户头，或一只资产。
 *
 * ## 为什么必须拍扁
 *
 * LazyColumn 的懒加载**只对它自己的直接子项生效**。原先「账户头 + 该账户下所有资产」
 * 被打包成一个 item，内部用 Column + forEach 渲染，于是一个账户下的上百只基金
 * 会被一次性全部组合——首屏直接卡死，持仓越多越明显。
 *
 * 拍扁成「账户头 / 资产行」两种单元后，每一项都是 LazyColumn 的直接子项，
 * 滚动到哪才组合到哪。账户小计也在构造时算好，不在组合里重复 sumOf。
 */
private sealed interface PortfolioRow {
    val key: String

    data class AccountHeader(
        val account: String,
        val assetCount: Int,
        val totalValue: Double
    ) : PortfolioRow {
        override val key: String get() = "account:$account"
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
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val estimatedAnnualDividend by viewModel.estimatedAnnualDividend.collectAsStateWithLifecycle()
    val dividendLoading by viewModel.dividendLoading.collectAsStateWithLifecycle()
    val incomeCalculationStatus by viewModel.incomeCalculationStatus.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val existingAccounts by viewModel.existingAccounts.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var selectedDividendAsset by remember { mutableStateOf<Asset?>(null) }
    var expandedAccounts by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveAccountDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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

    val visibleAssets by remember(assets, visibleAssetTypes, preferences.showLiabilities) {
        derivedStateOf {
            assets.filter {
                it.type in visibleAssetTypes || (it.type.isLiability && preferences.showLiabilities)
            }
        }
    }
    val assetsOnly = remember(visibleAssets) { visibleAssets.filter { !it.type.isLiability } }
    val liabilities = remember(visibleAssets) { visibleAssets.filter { it.type.isLiability } }

    val totalAssets = remember(assetsOnly) { assetsOnly.sumTotalValueCny() }
    val totalLiabilities = remember(liabilities) { liabilities.sumTotalValueCny() }
    val netAssets = totalAssets - totalLiabilities

    val sortedAssets = remember(assetsOnly, preferences.sortOrder) {
        when (preferences.sortOrder) {
            SortOrder.BY_TYPE -> assetsOnly.sortedBy { it.type.ordinal }
            SortOrder.BY_VALUE_DESC -> assetsOnly.sortedByDescending { it.totalValueCny }
            SortOrder.BY_VALUE_ASC -> assetsOnly.sortedBy { it.totalValueCny }
            SortOrder.BY_NAME -> assetsOnly.sortedBy { it.name }
        }
    }

    val filteredAssetsByAccount by remember {
        derivedStateOf {
            sortedAssets.groupBy { it.account }
        }
    }

    /**
     * 拍扁后的渲染单元。展开/收起只影响要不要把资产行放进去，
     * 账户头始终在——收起后仍看得到这个账户和小计。
     */
    val rows = remember(sortedAssets, liabilities, expandedAccounts) {
        buildList {
            sortedAssets.groupBy { it.account }.forEach { (account, list) ->
                add(PortfolioRow.AccountHeader(account, list.size, list.sumOf { it.totalValueCny }))
                if (account in expandedAccounts) {
                    list.forEach { add(PortfolioRow.AssetRow(it, account)) }
                }
            }
            // 负债单独成组：混进账户小计里会让「这个账户有多少钱」变得不可读
            if (liabilities.isNotEmpty()) {
                add(
                    PortfolioRow.AccountHeader(
                        account = LIABILITY_GROUP_KEY,
                        assetCount = liabilities.size,
                        totalValue = liabilities.sumOf { it.totalValueCny }
                    )
                )
                if (LIABILITY_GROUP_KEY in expandedAccounts) {
                    liabilities.forEach { add(PortfolioRow.AssetRow(it, LIABILITY_GROUP_KEY)) }
                }
            }
        }
    }

    LaunchedEffect(assetsOnly) {
        viewModel.loadDividendIncome(assetsOnly)
    }

    // Set 的相等性是结构性的，用它做副作用 key 可以保证「账户集合没变」时不重跑，
    // 避免价格波动刷新列表就把用户刚收起的分组重新展开。
    val accountKeys = remember(filteredAssetsByAccount, liabilities) {
        filteredAssetsByAccount.keys.toSet() +
            (if (liabilities.isNotEmpty()) setOf(LIABILITY_GROUP_KEY) else emptySet())
    }

    // null = 还没初始化过（首屏）。初始化后只自动展开「新出现」的账户，
    // 用户手动收起的分组不会再被强行展开。
    var initializedAccounts by remember { mutableStateOf<Set<String>?>(null) }

    // 处在批量选择态时，返回键应当先退出选择态，而不是直接退出 App
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    LaunchedEffect(accountKeys) {
        val current = initializedAccounts
        if (current == null) {
            expandedAccounts = accountKeys
        } else {
            val added = accountKeys - current
            if (added.isNotEmpty()) {
                expandedAccounts = expandedAccounts + added
            }
        }
        initializedAccounts = accountKeys
    }

    if (errorMessage != null) {
        LaunchedEffect(errorMessage) {
            snackbarHostState.showSnackbar(errorMessage ?: "")
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("持仓管理") },
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
                    containerColor = Primary,
                    contentColor = OnPrimary
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
        if (visibleAssets.isEmpty()) {
            // 分清两种「空」：有资产但被显示开关全藏了 → 给恢复入口；
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
                            Text("当前未显示任何资产类别", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "你共有 ${assets.size} 项记录，但显示开关把它们全部隐藏了",
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
                        assetCount = assetsOnly.size
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

                // 账户头与资产行都是 LazyColumn 的直接子项，滚动到哪组合到哪
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is PortfolioRow.AccountHeader -> AccountHeaderCard(
                            account = row.account,
                            assetCount = row.assetCount,
                            totalValue = row.totalValue,
                            isExpanded = expandedAccounts.contains(row.account),
                            onToggleExpand = {
                                if (!selectionMode) {
                                    expandedAccounts =
                                        if (expandedAccounts.contains(row.account)) {
                                            expandedAccounts - row.account
                                        } else {
                                            expandedAccounts + row.account
                                        }
                                }
                            }
                        )

                        is PortfolioRow.AssetRow -> {
                            val asset = row.asset
                            StockAssetItem(
                                asset = asset,
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds =
                                            if (selectedIds.contains(asset.id)) {
                                                selectedIds - asset.id
                                            } else {
                                                selectedIds + asset.id
                                            }
                                    } else {
                                        onNavigateToAssetDetail(asset.id)
                                    }
                                },
                                onDividendClick = { selectedDividendAsset = asset },
                                selectionMode = selectionMode,
                                isSelected = selectedIds.contains(asset.id),
                                onToggleSelection = {
                                    selectedIds =
                                        if (selectedIds.contains(asset.id)) {
                                            selectedIds - asset.id
                                        } else {
                                            selectedIds + asset.id
                                        }
                                }
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
                            Toast.makeText(context, "保存失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
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
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}