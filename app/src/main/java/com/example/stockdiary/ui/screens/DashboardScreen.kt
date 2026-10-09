package com.example.stockdiary.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.AssetGrowthCalculator
import com.example.stockdiary.data.SortOrder
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.DashboardViewModel
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.sumTotalValueCny
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPortfolio: () -> Unit = {}
) {
    val assets by viewModel.assets.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val dividendLoading by viewModel.dividendLoading.collectAsState()
    val realAnnualIncome by viewModel.realAnnualIncome.collectAsState()
    val incomeCalculationStatus by viewModel.incomeCalculationStatus.collectAsState()
    val existingAccounts by viewModel.existingAccounts.collectAsState()
    val dividendProgress by viewModel.dividendProgress.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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

    // 分布图必须用「可见资产」做分母：分子是过滤后的，若分母用未过滤的总资产，
    // 用户把某类资产藏起来后各分类百分比之和会小于 100%，看起来像账算错了。
    val visibleTotalAssets = remember(visibleAssetsOnly) { visibleAssetsOnly.sumTotalValueCny() }
    val hasHiddenAssets = remember(visibleAssetsOnly, assetsOnly) { visibleAssetsOnly.size < assetsOnly.size }

    val totalLiabilities = remember(liabilities) { liabilities.sumTotalValueCny() }
    val netAssets = totalAssets - totalLiabilities

    val snapshots by viewModel.snapshots.collectAsState()
    val growth = remember(snapshots, netAssets) {
        AssetGrowthCalculator.fromSnapshots(snapshots = snapshots, currentNetAssets = netAssets)
    }

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
                            realAnnualIncome = realAnnualIncome,
                            dividendLoading = dividendLoading,
                            incomeCalculationStatus = incomeCalculationStatus
                        )
                    }
                }

                // 资产增值：本月 / 今年净资产增加额
                item {
                    AssetGrowthCard(growth = growth)
                }

                // 收息进度：今年已收 / 全年预计（收入这一半的主角）
                item {
                    DividendProgressCard(progress = dividendProgress)
                }

                dividendProgress.nextPayout?.let { next ->
                    item {
                        NextPayoutCard(next = next)
                    }
                }

                item {
                    MonthlyCashFlowCard(progress = dividendProgress)
                }

                // 资产分布（分母用可见资产，保证百分比之和为 100%）
                if (preferences.showDistribution) {
                    item {
                        AssetDistributionCard(
                            assetsByType = assetsByType,
                            totalAssets = visibleTotalAssets
                        )
                    }
                    if (hasHiddenAssets) {
                        item {
                            Text(
                                "分布图仅统计当前显示的资产类别",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }

                // 资产明细列表已并入「持仓」页，这里只留总览与收入视角。
                // 给一个能直接跳过去的按钮，不然用户得自己猜明细去哪了。
                item {
                    OutlinedButton(
                        onClick = onNavigateToPortfolio,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("去「持仓」看每个账户的明细")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAssetDialog(
            onDismiss = { showAddDialog = false },
            existingAccounts = existingAccounts,
            // 保存失败也必须关掉弹窗并告诉用户：异常会让下面那行关闭语句根本执行不到，
            // 而弹窗内的 isSaving 没有任何复位路径 —— "添加"按钮会变成永久转圈。
            onConfirm = { asset ->
                scope.launch {
                    runCatching { viewModel.addAsset(asset) }
                        .onFailure {
                            AppLogger.e("新增资产失败: ${it.message}", it, "DashboardScreen")
                            Toast.makeText(context, "保存失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                        }
                    showAddDialog = false
                }
            }
        )
    }
}