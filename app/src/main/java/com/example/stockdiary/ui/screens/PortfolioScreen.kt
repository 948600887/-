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
import androidx.compose.ui.platform.LocalContext
import com.example.stockdiary.utils.AppLogger
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.PortfolioViewModel
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
    val context = LocalContext.current

    val stockAssets by remember {
        derivedStateOf {
            assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND || it.type == AssetType.CRYPTO }
        }
    }

    val totalValue = remember(stockAssets) { stockAssets.sumTotalValueCny() }

    val filteredAssetsByAccount by remember {
        derivedStateOf {
            stockAssets.groupBy { it.account }
        }
    }

    LaunchedEffect(stockAssets) {
        viewModel.loadDividendIncome(stockAssets)
    }

    // Set 的相等性是结构性的，用它做副作用 key 可以保证「账户集合没变」时不重跑，
    // 避免价格波动刷新列表就把用户刚收起的分组重新展开。
    val accountKeys = remember(filteredAssetsByAccount) { filteredAssetsByAccount.keys.toSet() }

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