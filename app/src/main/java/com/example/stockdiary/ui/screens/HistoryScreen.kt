package com.example.stockdiary.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.components.LocalAppSnackbar
import com.example.stockdiary.ui.screens.history.components.HistorySaveSnapshotDialog
import com.example.stockdiary.ui.screens.history.components.HistoryPeriodSelector
import com.example.stockdiary.ui.screens.history.components.SnapshotManagerDialog
import com.example.stockdiary.ui.screens.history.components.historyAssetDistribution
import com.example.stockdiary.ui.screens.history.components.historyMonthlyReturnChart
import com.example.stockdiary.ui.screens.history.components.historyOverviewCard
import com.example.stockdiary.ui.screens.history.components.historyPeriodReturnCard
import com.example.stockdiary.ui.screens.history.components.historyRiskCard
import com.example.stockdiary.ui.screens.history.components.historyTrendChart
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.ui.viewmodels.HistoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    // 提示宿主在导航层（见 GoldVaultNavHost）：本页只发消息，不持有 SnackbarHostState
    val appSnackbar = LocalAppSnackbar.current
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSnapshotManager by remember { mutableStateOf(false) }
    val managerSnapshots by viewModel.managerSnapshots.collectAsStateWithLifecycle()

    // 快照管理列表只在其打开时订阅全表，关闭即停
    LaunchedEffect(showSnapshotManager) {
        viewModel.setSnapshotManagerVisible(showSnapshotManager)
    }

    // 每次进入历史页重读「默认历史周期」：VM 常驻、默认周期只在构造时读一次，
    // 设置页改完不重进应用的话本页永远用旧周期。
    LaunchedEffect(Unit) { viewModel.reloadDefaultPeriod() }

    // 回前台补一次「今天」重算：进程被冻结后午夜协程可能被推迟，
    // 否则零点后回到本页，「今日快照已保存」标记与周期窗口还停在昨天。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 先清状态再展示（理由同 DashboardScreen）：切页时状态已清，回来不会重弹。
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            viewModel.clearError()
            appSnackbar.show(message)
        }
    }

    val saveResult = uiState.saveResult
    LaunchedEffect(saveResult) {
        saveResult?.let { result ->
            viewModel.clearSaveResult()
            if (result.isSuccess) {
                appSnackbar.show("保存成功")
                showSaveDialog = false
            } else {
                // 失败时也要关闭对话框：失败原因写在 uiState.errorMessage 里、由上面的
                // errorMessage effect 弹 Snackbar；若对话框仍开着，它的模态遮罩会盖住
                // Snackbar，用户只看到「保存」按钮点了没反应、反复重试。关掉遮罩后
                // 真实原因（如「当天没有持仓，未保存快照」）才能正常显示。
                showSaveDialog = false
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("资产分析", fontWeight = FontWeight.Bold) },
                actions = {
                    if (uiState.hasTodaySnapshot) {
                        Text(
                            text = "今日快照已保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.colors.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    // 快照管理入口：列表式按日期删除/重存，趋势图不承担编辑入口
                    IconButton(onClick = { showSnapshotManager = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = "快照管理",
                            tint = AppTheme.colors.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showSaveDialog = true },
                        enabled = !uiState.isSaving
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "保存快照",
                                tint = if (uiState.hasTodaySnapshot) AppTheme.colors.onSurfaceVariant else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        // 周期选择器放在两条分支的公共外层：否则一旦某周期窗口内没有快照，
        // 选择器随 LazyColumn 一起消失，用户再也切不回「全部」。
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HistoryPeriodSelector(
                selectedPeriod = uiState.selectedPeriod,
                onPeriodSelected = { viewModel.selectPeriod(it) }
            )
            val loadError = uiState.loadError
            when {
                // 冷启动 Room 首帧未到：显示加载态，别先闪一屏「暂无历史数据」
                uiState.isLoading || !uiState.snapshotsFrameReady -> HistoryLoadingContent(
                    modifier = Modifier.fillMaxSize()
                )
                // 读取流自身失败（磁盘/DB）：页面级错误 + 重试，不能一直空着
                loadError != null -> HistoryLoadErrorContent(
                    message = loadError,
                    onRetry = viewModel::refresh,
                    modifier = Modifier.fillMaxSize()
                )
                uiState.filteredSnapshots.isEmpty() -> EmptyHistoryContent(
                    // 按全库计数判断，不能用当前周期的 filteredSnapshots：
                    // 「1周内没有」不等于「一条快照都没有」，否则会误报「暂无历史数据」
                    hasAnySnapshot = uiState.hasAnySnapshot,
                    modifier = Modifier.fillMaxSize()
                )
                else -> {
                    val latest = uiState.filteredSnapshots.last()
                    val earliest = uiState.filteredSnapshots.first()

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val trendChartHeight = maxOf(maxHeight * 0.3f, 180.dp)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            historyOverviewCard(latest = latest)
                            historyTrendChart(
                                snapshots = uiState.filteredSnapshots,
                                selectedPeriod = uiState.selectedPeriod,
                                height = trendChartHeight
                            )
                            historyPeriodReturnCard(
                                earliest = earliest,
                                latest = latest,
                                snapshotCount = uiState.filteredSnapshots.size
                            )

                            // 快照不足（<2 份）时 statistics 为 null，风险卡片仍保留并提示「至少需要
                            // 2 份历史快照」而非整块消失——否则用户会以为功能丢了。
                            historyRiskCard(stats = uiState.statistics)

                            if (preferences.showHistoryMonthlyGrowth) {
                                historyMonthlyReturnChart(monthlyReturns = uiState.monthlyReturns)
                            }
                            historyAssetDistribution(latest = latest)
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        HistorySaveSnapshotDialog(
            assets = uiState.assets,
            isSaving = uiState.isSaving,
            onDismiss = { showSaveDialog = false },
            onConfirm = { viewModel.saveSnapshot() }
        )
    }

    if (showSnapshotManager) {
        SnapshotManagerDialog(
            snapshots = managerSnapshots,
            onDelete = viewModel::deleteManagedSnapshot,
            onResave = viewModel::resaveManagedSnapshot,
            onDismiss = { showSnapshotManager = false }
        )
    }
}

@Composable
private fun HistoryLoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "正在读取历史数据…",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryLoadErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyHistoryContent(
    hasAnySnapshot: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.ShowChart, contentDescription = null,
                modifier = Modifier.size(64.dp), tint = AppTheme.colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (hasAnySnapshot) {
                // 有快照但被当前周期滤掉了：提示切更长的周期，而不是让人以为没数据。
                Text("当前周期内暂无快照", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "试试切换到更长的周期，或点右上角补一份快照",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant
                )
            } else {
                Text("暂无历史数据", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "点击右上角保存第一份快照",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}
