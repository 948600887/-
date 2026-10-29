package com.example.stockdiary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.calculator.AssetGrowthCalculator
import com.example.stockdiary.data.dividend.dividendInputFingerprint
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.components.*
import com.example.stockdiary.ui.dialogs.AddAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.DashboardViewModel
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPortfolio: () -> Unit = {}
) {
    // 刷新/收入计算整组状态合并进 uiState 一条流（见 DashboardViewModel.DashboardUiState）；
    // preferences / existingAccounts 与刷新状态机无关，保持独立流
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val assets = uiState.assets
    val isRefreshing = uiState.isRefreshing
    val syncProgress = uiState.syncProgress
    val dividendLoading = uiState.dividendLoading
    val realAnnualIncome = uiState.realAnnualIncome
    val incomeCalculationStatus = uiState.incomeCalculationStatus
    val dividendProgress = uiState.dividendProgress
    val errorMessage = uiState.errorMessage
    val dividendWarning = uiState.dividendWarning
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val existingAccounts by viewModel.existingAccounts.collectAsStateWithLifecycle()

    // rememberSaveable：旋屏/配置变更后对话框不会凭空消失
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
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

    val snapshots = uiState.snapshots
    // 基准日 = 今天：跨零点/跨年后 today 变化，必须进 remember key 让增长重算，
    // 否则基准日滞留旧日期、跨天不更新（净值曲线/涨跌幅显示过期）。
    val today = java.time.LocalDate.now()
    val growth = remember(snapshots, netAssets, today) {
        AssetGrowthCalculator.fromSnapshots(snapshots = snapshots, currentNetAssets = netAssets)
    }

    // 原来是 remember{ derivedStateOf{...} }：lambda 里读的是普通 List 而不是 State，
    // derivedStateOf 根本不会自动失效，实际靠外层 remember 的 key 失效 ——
    // 等于白包一层 DerivedState 对象。直接 remember 返回 Map 即可。
    val assetsByType = remember(visibleAssetsOnly, preferences.sortOrder) {
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

    // 抓分红的触发条件：代码 + 持仓数量 + 存款利率（见 dividendInputFingerprint），
    // 不是资产列表本身——用列表做 key 的话，任何一次价格刷新都会让列表内容变化、
    // 效应重启、分红重算一遍，而分红跟价格没关系。
    //
    // 但只用「代码集合」也不够：年化收入 = 数量 × 每股分红，加仓不改变代码集合；
    // 银行存款 symbol=null，改余额/利率同样不改集合。这两种情况都必须重算，
    // 所以 key 里带上数量与利率。数组内容相同则指纹相同，刷新价格不会误触发。
    val dividendInputKey = remember(assetsOnly) { dividendInputFingerprint(assetsOnly) }

    LaunchedEffect(dividendInputKey) {
        viewModel.loadDividendIncome(assetsOnly)
    }

    // P0-3 修复：仪表盘回前台（如从「设置 → 自定义仪表盘」返回）时重新加载偏好，
    // 让刚改的开关立即生效，避免界面停留在构造期陈旧的旧值。
    // 配合 DashboardViewModel.reloadPreferences() + 存缓存前先拉最新偏好，
    // 杜绝「改了没反应」与「过一会儿被收入计算回滚」两条故障链。
    val dashboardLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(dashboardLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reloadPreferences()
        }
        dashboardLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { dashboardLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 底栏切 Tab 不会触发 Activity 的 ON_RESUME，只靠上面的 DisposableEffect 会漏掉
    // 「设置页改完开关直接切回首页」这条路径（内存副本还是构造期那份）。
    // 这里在每次进入该页面（重新组合）时再拉一次磁盘偏好。
    LaunchedEffect(Unit) { viewModel.reloadPreferences() }

    // 刷新结果放在 UiState 里：切走 Tab 不会丢（SharedFlow replay=0 会丢），
    // 消费后立刻清掉也不会在回到首页时重复弹（replay=1 会重复）。
    //
    // 用 snapshotFlow 而不是 LaunchedEffect(pendingSyncResult)：先清空再弹，
    // 清空会让 key 变成 null、把正在显示的 Snackbar 一起取消；snapshotFlow 不受
    // key 变化影响，清空只让下一次发射被 filterNotNull 过滤掉。
    // 立刻消费还有个好处：用户在 Snackbar 消失前切走，回来也不会再弹一遍。
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.pendingSyncResult }
            .filterNotNull()
            .collect { result ->
                viewModel.clearPendingSyncResult()
                val msg = buildString {
                    // 统一结果反馈：成功/失败都报数，用户才知道这一轮到底刷到了什么
                    append("刷新完成：成功 ${result.successCount}")
                    if (result.failedCount > 0) append("，失败 ${result.failedCount}")
                    // 跳过必须报：只说「成功 107」的话，用户不知道还有上百个加密货币
                    // 因行情源不可达（熔断）压根没更新，会以为刷新没生效而反复去点
                    append(result.skippedHint)
                }
                snackbarHostState.showSnackbar(msg)
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

    // 天天基金限流提示：刷新撞 514 时，部分场外基金分红没抓全，给个非阻塞提示 + 可稍后重试。
    // 与上面的 errorMessage 分开（那是「同步失败」），避免混淆。
    dividendWarning?.let { warning ->
        LaunchedEffect(warning) {
            snackbarHostState.showSnackbar(warning)
            viewModel.clearDividendWarning()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
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
                containerColor = AppTheme.colors.primary,
                contentColor = AppTheme.colors.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加资产")
            }
        },
        modifier = modifier
    ) { padding ->
        // Room 首帧到达前 assetsFlow 的初值是 emptyList；此时若渲染空态卡片，
        // 会让资产并不为空的老用户每次冷启动都闪一下「还没有添加任何资产」。
        // 首帧未到 → 占位；首帧到了 → 才按 assets.isEmpty() 判空。
        if (!uiState.assetsFrameReady && assets.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) { /* 首帧占位：不做任何误导性渲染 */ }
        } else if (assets.isEmpty()) {
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
                // 与 PortfolioScreen 同因：加号(FAB)悬浮在列表之上，而 Scaffold 的 innerPadding
                // **不为 FAB 预留空间**，不手动留白滚到底时最后一项会被绿色加号压住。
                // 56dp(FAB 直径) + 16dp(Scaffold 给 FAB 的默认外边距) = 72dp。
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + 72.dp
                ),
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
                            estimatedDailyDividend = uiState.estimatedDailyDividend,
                            dividendLoading = dividendLoading,
                            incomeCalculationStatus = incomeCalculationStatus
                        )
                    }
                }

                // 资产增值：本月 / 今年净资产增加额
                if (preferences.showAssetGrowth) {
                    item {
                        AssetGrowthCard(growth = growth)
                    }
                }

                // 收息进度：今年已收 / 今年预计（收入这一半的主角，同为自然年口径）
                if (preferences.showDividendProgress) {
                    item {
                        DividendProgressCard(progress = dividendProgress)
                    }
                }

                if (preferences.showNextPayout) {
                    dividendProgress.nextPayout?.let { next ->
                        item {
                            NextPayoutCard(next = next)
                        }
                    }
                }

                if (preferences.showMonthlyCashFlow) {
                    item {
                        MonthlyCashFlowCard(progress = dividendProgress)
                    }
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
            existingAssets = assets,
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