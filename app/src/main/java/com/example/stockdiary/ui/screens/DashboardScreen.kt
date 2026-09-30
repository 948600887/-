package com.example.stockdiary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.SortOrder
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.DashboardViewModel
import com.example.stockdiary.utils.sumCostCny
import com.example.stockdiary.utils.sumProfitCny
import com.example.stockdiary.utils.sumTotalValueCny
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onNavigateToAssetDetail: (Long) -> Unit = {}
) {
    val assets by viewModel.assets.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val expandedTypes by viewModel.expandedTypes.collectAsState()
    val dividendLoading by viewModel.dividendLoading.collectAsState()
    val realAnnualIncome by viewModel.realAnnualIncome.collectAsState()
    val incomeCalculationStatus by viewModel.incomeCalculationStatus.collectAsState()
    val existingAccounts by viewModel.existingAccounts.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val assetsOnly = remember(assets) { assets.filter { !it.type.isLiability } }
    val liabilities = remember(assets) { assets.filter { it.type.isLiability } }

    val visibleAssetTypes = remember(preferences) {
        setOfNotNull(
            AssetType.BANK_DEPOSIT.takeIf { preferences.showBankDeposits },
            AssetType.STOCK.takeIf { preferences.showStocks },
            AssetType.FUND.takeIf { preferences.showFunds },
            AssetType.CRYPTO.takeIf { preferences.showCrypto }
        )
    }
    val visibleAssetsOnly = remember(assetsOnly, visibleAssetTypes) {
        assetsOnly.filter { it.type in visibleAssetTypes }
    }

    val totalAssets = remember(assetsOnly) { assetsOnly.sumTotalValueCny() }
    val totalCost = remember(assetsOnly) { assetsOnly.sumCostCny() }
    val totalProfit = totalAssets - totalCost
    val totalProfitPercent = if (totalCost > 0) (totalProfit / totalCost) * 100 else 0.0
    val totalLiabilities = remember(liabilities) { liabilities.sumTotalValueCny() }
    val netAssets = totalAssets - totalLiabilities

    val assetsByType by remember(visibleAssetsOnly, preferences.sortOrder) {
        derivedStateOf {
            val sorted = when (preferences.sortOrder) {
                SortOrder.BY_TYPE -> visibleAssetsOnly.toList()
                SortOrder.BY_VALUE_DESC -> visibleAssetsOnly.sortedByDescending { it.totalValue }
                SortOrder.BY_VALUE_ASC -> visibleAssetsOnly.sortedBy { it.totalValue }
                SortOrder.BY_NAME -> visibleAssetsOnly.sortedBy { it.name }
            }
            sorted.groupBy { it.type }
        }
    }

    LaunchedEffect(assetsOnly) {
        viewModel.loadDividendIncome(assetsOnly)
    }

    syncResult?.let { result ->
        LaunchedEffect(result) {
            val msg = buildString {
                append("同步完成")
                if (result.failedCount > 0) append("，${result.failedCount}个失败")
            }
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSyncResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("我的资产") },
                actions = {
                    if (assets.isNotEmpty()) {
                        if (isSyncing) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(80.dp)
                                    .padding(end = 8.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.sync() }, enabled = !isSyncing) {
                            Icon(Icons.Filled.Sync, contentDescription = "同步")
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
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Primary,
                contentColor = OnPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加资产")
            }
        },
        modifier = modifier
    ) { padding ->
        if (assets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                EmptyAssetCard(onAddAsset = { showAddDialog = true })
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 总资产卡片
                if (preferences.showTotalAssets || preferences.showNetAssets || preferences.showProfit) {
                    item {
                        TotalAssetCard(
                            showTotalAssets = preferences.showTotalAssets,
                            showNetAssets = preferences.showNetAssets,
                            showProfit = preferences.showProfit,
                            showLiabilities = preferences.showLiabilities,
                            totalAssets = totalAssets,
                            totalLiabilities = totalLiabilities,
                            netAssets = netAssets,
                            profit = totalProfit,
                            profitPercent = totalProfitPercent,
                            realAnnualIncome = realAnnualIncome,
                            dividendLoading = dividendLoading,
                            incomeCalculationStatus = incomeCalculationStatus
                        )
                    }
                }

                // 资产分布
                if (preferences.showDistribution) {
                    item {
                        AssetDistributionCard(assetsByType = assetsByType, totalAssets = totalAssets)
                    }
                }

                // 排序 & 标题
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("资产列表", style = MaterialTheme.typography.titleSmall)
                        SortChip(
                            currentSort = preferences.sortOrder,
                            onSortChange = { viewModel.updateSortOrder(it) }
                        )
                    }
                }

                // 按类型分组
                items(assetsByType.keys.toList(), key = { it.name }) { type ->
                    val typeAssets = assetsByType[type] ?: emptyList()
                    if (typeAssets.isNotEmpty()) {
                        AssetTypeSection(
                            type = type,
                            assets = typeAssets,
                            isExpanded = expandedTypes.contains(type),
                            onToggleExpand = { viewModel.toggleExpand(type) }
                        )

                        if (expandedTypes.contains(type)) {
                            Column {
                                typeAssets.forEach { asset ->
                                    key(asset.id) {
                                        AssetItem(asset = asset, onClick = { onNavigateToAssetDetail(asset.id) })
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // 负债
                if (preferences.showLiabilities && liabilities.isNotEmpty()) {
                    item {
                        Text("负债", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(liabilities, key = { it.id }) { asset ->
                        LiabilityItem(
                            asset = asset,
                            onClick = { onNavigateToAssetDetail(asset.id) }
                        )
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
                scope.launch {
                    viewModel.addAsset(asset)
                    showAddDialog = false
                }
            }
        )
    }
}