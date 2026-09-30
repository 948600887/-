package com.example.stockdiary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.PortfolioViewModel
import com.example.stockdiary.utils.sumCostCny
import com.example.stockdiary.utils.sumTotalValueCny
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    viewModel: PortfolioViewModel,
    modifier: Modifier = Modifier,
    onNavigateToAssetDetail: (Long) -> Unit = {}
) {
    val assets by viewModel.assets.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val estimatedAnnualDividend by viewModel.estimatedAnnualDividend.collectAsState()
    val dividendLoading by viewModel.dividendLoading.collectAsState()
    val incomeCalculationStatus by viewModel.incomeCalculationStatus.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val existingAccounts by viewModel.existingAccounts.collectAsState()

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

    val stockAssets by remember {
        derivedStateOf {
            assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND || it.type == AssetType.CRYPTO }
        }
    }

    val totalValue = remember(stockAssets) { stockAssets.sumTotalValueCny() }
    val totalCost = remember(stockAssets) { stockAssets.sumCostCny() }
    val totalProfit = totalValue - totalCost
    val totalProfitPercent = if (totalCost > 0) (totalProfit / totalCost) * 100 else 0.0

    val filteredAssetsByAccount by remember {
        derivedStateOf {
            stockAssets.groupBy { it.account }
        }
    }

    LaunchedEffect(stockAssets) {
        viewModel.loadDividendIncome(stockAssets)
    }

    LaunchedEffect(stockAssets) {
        if (expandedAccounts.isEmpty() && filteredAssetsByAccount.isNotEmpty()) {
            expandedAccounts = filteredAssetsByAccount.keys.toSet()
        }
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
                                    val selectedAssets = stockAssets.filter { it.id in selectedIds }
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
        if (stockAssets.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                EmptyAssetCard(onAddAsset = { showAddDialog = true })
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PortfolioOverviewCard(
                        totalValue = totalValue,
                        totalProfit = totalProfit,
                        totalProfitPercent = totalProfitPercent,
                        estimatedAnnualDividend = estimatedAnnualDividend,
                        isDividendLoading = dividendLoading,
                        dividendStatus = incomeCalculationStatus,
                        stockCount = stockAssets.size
                    )
                }

                items(filteredAssetsByAccount.keys.sorted(), key = { it }) { account ->
                    val accountAssets = filteredAssetsByAccount[account] ?: return@items
                    AccountGroup(
                        account = account,
                        assets = accountAssets,
                        isExpanded = expandedAccounts.contains(account),
                        onToggleExpand = {
                            if (!selectionMode) {
                                expandedAccounts = if (expandedAccounts.contains(account)) {
                                    expandedAccounts - account
                                } else {
                                    expandedAccounts + account
                                }
                            }
                        },
                        onAssetClick = { if (!selectionMode) onNavigateToAssetDetail(it.id) },
                        onDividendClick = { selectedDividendAsset = it },
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onToggleSelection = {
                            selectedIds = if (selectedIds.contains(it.id)) {
                                selectedIds - it.id
                            } else {
                                selectedIds + it.id
                            }
                        }
                    )
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

    if (showSearchDialog) {
        PortfolioSearchDialog(
            assets = stockAssets,
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