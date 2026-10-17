package com.example.stockdiary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.calculator.AssetGrowthCalculator
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.DashboardViewModel
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPortfolio: () -> Unit = {}
) {
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val syncResult by viewModel.syncResult.collectAsStateWithLifecycle()
    val dividendLoading by viewModel.dividendLoading.collectAsStateWithLifecycle()
    val realAnnualIncome by viewModel.realAnnualIncome.collectAsStateWithLifecycle()
    val incomeCalculationStatus by viewModel.incomeCalculationStatus.collectAsStateWithLifecycle()
    val existingAccounts by viewModel.existingAccounts.collectAsStateWithLifecycle()
    val dividendProgress by viewModel.dividendProgress.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

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

    // 分布图必须用「可见资产」做分母：分子是过滤后的，若分母用未过滤的总资产，
    // 用户把某类资产藏起来后各分类百分比之和会小于 100%，看起来像账算错了。
    val visibleTotalAssets = remember(visibleAssetsOnly) { visibleAssetsOnly.sumTotalValueCny() }
    val hasHiddenAssets = remember(visibleAssetsOnly, assetsOnly) { visibleAssetsOnly.size < assetsOnly.size }

    val totalLiabilities = remember(liabilities) { liabilities.sumTotalValueCny() }
    val netAssets = totalAssets - totalLiabilities

    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val growth = remember(snapshots, netAssets) {
        AssetGrowthCalculator.fromSnapshots(snapshots = snapshots, currentNetAssets = netAssets)
    }

    val assetsByType by remember(visibleAssetsOnly, preferences.sortOrder) {
        derivedStateOf {
            val sorted = when (preferences.sortOrder) {
                SortOrder.BY_TYPE -> visibleAssetsOnly.toList()
                // 按折算后的人民币排：原币金额跨币种没有可比性
                // （100 美元和 100 日元按数字一样大，实际差了 150 倍）
                SortOrder.BY_VALUE_DESC -> visibleAssetsOnly.sortedByDescending { it.totalValueCny }
                SortOrder.BY_VALUE_ASC -> visibleAssetsOnly.sortedBy { it.totalValueCny }
                SortOrder.BY_NAME -> visibleAssetsOnly.sortedBy { it.name }
            }
            sorted.groupBy { it.type }
        }
    }

    // 抓分红的触发条件：持仓代码集合，不是资产列表本身。
    // 用列表做 key 的话，任何一次价格刷新都会让列表内容变化、效应重启、
    // 分红重算一遍——而分红跟价格半点关系都没有。代码集合不变就不该重算
    // （与持仓页 PortfolioScreen 同一套处理）。
    //
    // key 必须用「集合内容」而不是 [Set] 对象身份：每次 assetsOnly 变化都会新建一个 Set，
    // 内容相同身份也不同，若用对象当 key，刷新时每写一个标的 LaunchedEffect 就重启一次
    // loadDividendIncome，纯属空转。这里排序后拼成稳定字符串，内容不变就不动。
    val dividendSymbolsKey = remember(assetsOnly) {
        assetsOnly.mapNotNull { it.symbol }.sorted().joinToString(",")
    }

    LaunchedEffect(dividendSymbolsKey) {
        viewModel.loadDividendIncome(assetsOnly)
    }

    syncResult?.let { result ->
        LaunchedEffect(result) {
            val msg = buildString {
                // 统一结果反馈：成功/失败都报数，用户才知道这一轮到底刷到了什么
                append("刷新完成：成功 ${result.successCount}")
                if (result.failedCount > 0) append("，失败 ${result.failedCount}")
            }
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSyncResult()
        }
    }

    // 刷新兜底：ViewModel 的 CoroutineExceptionHandler 把逃逸异常变成这条提示，
    // 进程不再被杀掉；用户至少知道「刷新没成功」，而不是界面莫名停止转动
    errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // 刷新要跑完上百个标的，只显示转圈的话用户无从判断是在干活还是卡死。
                    // 把「已完成 / 总数」露出来，等待就有了预期（与持仓页同一套）
                    val progress = syncProgress
                    if (isRefreshing && progress != null && progress.total > 0) {
                        Column {
                            Text("我的资产", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${progress.current} / ${progress.total}  ${progress.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text("我的资产")
                    }
                },
                actions = {
                    if (assets.isNotEmpty()) {
                        // 只有一个「刷新」：原「同步」按钮与后台定时同步职责重复，
                        // 且走不走缓存的区别在界面上几乎不可感知，已合并
                        // （见 DashboardViewModel.refresh 的 KDoc）
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
                            snackbarHostState.showSnackbar("保存失败：${it.message ?: "未知错误"}")
                        }
                    showAddDialog = false
                }
            }
        )
    }
}