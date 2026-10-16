package com.example.stockdiary.ui.screens

import com.example.stockdiary.data.price.ApiManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.data.sync.PriceSyncScheduler
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.components.ApiSelectionDialog
import com.example.stockdiary.ui.screens.settings.components.AboutDialog
import com.example.stockdiary.ui.screens.settings.components.BinanceImportDialog
import com.example.stockdiary.ui.screens.settings.components.ClearDataDialog
import com.example.stockdiary.ui.screens.settings.components.DashboardConfigSheet
import com.example.stockdiary.ui.screens.settings.components.DataSourceStatusDialog
import com.example.stockdiary.ui.screens.settings.components.ExportDataDialog
import com.example.stockdiary.ui.screens.settings.components.ImportBrokerageDialog
import com.example.stockdiary.ui.screens.settings.components.LogViewerDialog
import com.example.stockdiary.ui.screens.settings.components.HoldingSnapshotDialog
import com.example.stockdiary.ui.screens.settings.components.ReminderTimePickerDialog
import com.example.stockdiary.ui.screens.settings.components.SettingsItem
import com.example.stockdiary.ui.screens.settings.components.SettingsSection
import com.example.stockdiary.ui.screens.settings.components.ThemeSelectionDialog
import com.example.stockdiary.ui.theme.rememberThemeMode
import com.example.stockdiary.ui.theme.ThemeManager
import com.example.stockdiary.ui.viewmodels.HoldingSnapshotViewModel
import com.example.stockdiary.ui.viewmodels.SettingsViewModel
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 版本号从包信息动态读取，避免发版后忘记改硬编码的 "1.0.0"
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dashboardPrefs by viewModel.dashboardPreferences.collectAsStateWithLifecycle()
    val autoPriceSyncEnabled by viewModel.autoPriceSyncEnabled.collectAsStateWithLifecycle()
    val lastPriceSyncTime by viewModel.lastPriceSyncTime.collectAsStateWithLifecycle()
    val reminderEnabled by viewModel.reminderEnabled.collectAsStateWithLifecycle()
    val reminderHour by viewModel.reminderHour.collectAsStateWithLifecycle()
    val reminderMinute by viewModel.reminderMinute.collectAsStateWithLifecycle()
    val reminderError by viewModel.reminderError.collectAsStateWithLifecycle()
    val currentTheme = rememberThemeMode()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDashboardConfigSheet by remember { mutableStateOf(false) }
    var showDataSourceDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showHoldingSnapshotDialog by remember { mutableStateOf(false) }
    var showFundSnapshotDialog by remember { mutableStateOf(false) }
    var showBinanceImportDialog by remember { mutableStateOf(false) }
    var showApiSelectionDialog by remember { mutableStateOf(false) }
    var showLogViewerDialog by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var currentPriceApi by remember { mutableStateOf(com.example.stockdiary.data.price.ApiManager.getPriceApi(context)) }
    /** 记录这次通知权限请求是哪个开关发起的，权限回调时按来源给出准确文案 */
    var pendingPermissionReason by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            val message = when (pendingPermissionReason) {
                "每日查看提醒" -> "已开启每日查看提醒，但通知权限被拒绝，到点不会弹出通知"
                else -> "已开启自动同步，但通知权限被拒绝，同步结果将不会显示通知"
            }
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
        pendingPermissionReason = null
    }

    val assets = uiState.assets
    val snapshots = uiState.snapshots
    val assetCount = assets.size
    // 一次遍历同时累加资产与负债：原来每次重组都要 filter 两遍（列表上百条时是白花的开销）
    val (totalAssets, totalLiabilities) = remember(assets) {
        var assetSum = 0.0
        var liabilitySum = 0.0
        // 折算成人民币再累加：多币种资产的原币金额直接相加没有意义
        assets.forEach { asset ->
            if (asset.type.isLiability) liabilitySum += asset.totalValueCny
            else assetSum += asset.totalValueCny
        }
        assetSum to liabilitySum
    }

    // 每次进入「我的」页都从磁盘重读同步/提醒快照：
    // 后台任务或系统设置改过状态后，页面要能反映真实值
    LaunchedEffect(Unit) {
        viewModel.refreshPriceSyncState()
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(reminderError) {
        reminderError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearReminderError()
        }
    }

    LaunchedEffect(uiState.deleteResult) {
        uiState.deleteResult?.let { result ->
            if (result.isSuccess) {
                showClearDataDialog = false
            } else {
                snackbarHostState.showSnackbar(result.exceptionOrNull()?.message ?: "清除失败")
            }
            viewModel.clearDeleteResult()
        }
    }

    LaunchedEffect(uiState.exportResult) {
        uiState.exportResult?.let { result ->
            if (result.isError) {
                snackbarHostState.showSnackbar(result.exceptionOrNull()?.message ?: "导出失败")
            }
            viewModel.clearExportResult()
        }
    }

    val settingsSections = remember(
        assetCount,
        totalAssets,
        totalLiabilities,
        currentTheme,
        autoPriceSyncEnabled,
        lastPriceSyncTime,
        reminderEnabled,
        reminderHour,
        reminderMinute,
        currentPriceApi
    ) {
        listOf(
            SettingsSectionData("外观", listOf(
                SettingsItemData(
                    icon = Icons.Default.Palette,
                    title = "主题",
                    subtitle = when (currentTheme) {
                        com.example.stockdiary.ui.theme.ThemeMode.SYSTEM -> "跟随系统"
                        com.example.stockdiary.ui.theme.ThemeMode.LIGHT -> "浅色模式"
                        com.example.stockdiary.ui.theme.ThemeMode.DARK -> "深色模式"
                    },
                    onClick = { showThemeDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.Dashboard,
                    title = "自定义仪表盘",
                    subtitle = "选择显示的组件和排序方式",
                    onClick = { showDashboardConfigSheet = true }
                )
            )),
            SettingsSectionData("数据", listOf(
                SettingsItemData(
                    icon = Icons.Default.Download,
                    title = "导出数据",
                    subtitle = "导出为 CSV 或文本文件",
                    onClick = { showExportDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.UploadFile,
                    title = "导入交割单",
                    subtitle = "从券商 CSV 或对账单截图批量导入交易",
                    onClick = { showImportDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.AccountBalanceWallet,
                    title = "导入持仓截图",
                    subtitle = "中国结算「一码通持有查询」，场内股票/ETF/LOF/REITs",
                    onClick = { showHoldingSnapshotDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.Savings,
                    title = "导入场外基金",
                    subtitle = "基金E账户导出的持有明细，场外公募基金",
                    onClick = { showFundSnapshotDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.CurrencyBitcoin,
                    title = "同步币安资产",
                    subtitle = "只读拉取币安现货持仓，按代码合并到加密资产",
                    onClick = { showBinanceImportDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.CloudSync,
                    title = "数据源状态",
                    subtitle = "检查网络接口可用性",
                    onClick = {
                        viewModel.checkDataSourceHealth()
                        showDataSourceDialog = true
                    }
                ),
                SettingsItemData(
                    icon = Icons.Default.SyncAlt,
                    title = "股价数据源",
                    subtitle = "当前: $currentPriceApi",
                    onClick = { showApiSelectionDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.Sync,
                    title = "自动刷新价格与分红",
                    // 实际策略是 SyncStrategy：交易时段 15 分钟一次、非交易时段 4 小时一次，
                    // 文案不能写"每日 17:15"，那会让人以为一天只更新一次。
                    subtitle = when {
                        !autoPriceSyncEnabled -> "已关闭"
                        lastPriceSyncTime > 0 -> "交易时段每 15 分钟、其余时段每 4 小时 · 上次: ${formatPriceSyncTime(lastPriceSyncTime)}"
                        else -> "交易时段每 15 分钟、其余时段每 4 小时 · 尚未同步"
                    },
                    isSwitch = true,
                    switchChecked = autoPriceSyncEnabled,
                    onSwitchCheckedChange = { enabled ->
                        viewModel.setAutoPriceSyncEnabled(enabled)
                        if (enabled) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                pendingPermissionReason = "自动同步"
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            PriceSyncScheduler.scheduleDailySync(context)
                        } else {
                            PriceSyncScheduler.cancelDailySync(context)
                        }
                    }
                ),
                SettingsItemData(
                    icon = Icons.Default.Storage,
                    title = "资产数量",
                    subtitle = "$assetCount 个资产",
                    onClick = { },
                    // 纯展示行：不置灰会被渲染成可点击，误导用户
                    enabled = false
                ),
                SettingsItemData(
                    icon = Icons.Default.AccountBalance,
                    title = "总资产",
                    subtitle = "¥${totalAssets.format(2)}",
                    onClick = { },
                    enabled = false
                ),
                SettingsItemData(
                    icon = Icons.Default.MoneyOff,
                    title = "总负债",
                    subtitle = "¥${totalLiabilities.format(2)}",
                    onClick = { },
                    enabled = false
                ),
                SettingsItemData(
                    icon = Icons.Default.DeleteForever,
                    title = "清除所有数据",
                    subtitle = "删除所有资产和历史快照",
                    onClick = { showClearDataDialog = true },
                    isDestructive = true
                )
            )),
            SettingsSectionData("应用", listOf(
                SettingsItemData(
                    icon = Icons.Default.Notifications,
                    title = "每日查看提醒",
                    subtitle = if (reminderEnabled) {
                        "每天 ${reminderHour.toString().padStart(2, '0')}:${reminderMinute.toString().padStart(2, '0')} 提醒查看资产"
                    } else {
                        "已关闭"
                    },
                    isSwitch = true,
                    switchChecked = reminderEnabled,
                    onSwitchCheckedChange = { enabled ->
                        // 开启前先看通知权限：没授权也能把闹钟排上，但通知永远发不出来，
                        // 界面上却显示"每天 09:00 提醒"，用户根本无从自查。
                        if (enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingPermissionReason = "每日查看提醒"
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.setReminderEnabled(enabled)
                    }
                ),
                SettingsItemData(
                    icon = Icons.Default.Schedule,
                    title = "提醒时间",
                    subtitle = "${reminderHour.toString().padStart(2, '0')}:${reminderMinute.toString().padStart(2, '0')}",
                    enabled = reminderEnabled,
                    onClick = { if (reminderEnabled) showReminderTimePicker = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.BugReport,
                    title = "运行日志与崩溃报告",
                    subtitle = "闪退排查用，可一键分享出来",
                    onClick = { showLogViewerDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.Info,
                    title = "关于",
                    subtitle = "金库 v$versionName",
                    onClick = { showAboutDialog = true }
                ),
                SettingsItemData(
                    icon = Icons.Default.Android,
                    title = "系统设置",
                    subtitle = "打开系统设置",
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
            ))
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // 与底部导航标签保持一致
                title = { Text("我的", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
        ) {
            settingsSections.forEach { section ->
                item {
                    SettingsSection(title = section.title)
                }
                items(section.items, key = { it.title }) { item ->
                    SettingsItem(
                        icon = item.icon,
                        title = item.title,
                        subtitle = item.subtitle,
                        onClick = item.onClick,
                        isDestructive = item.isDestructive,
                        isSwitch = item.isSwitch,
                        switchChecked = item.switchChecked,
                        onSwitchCheckedChange = item.onSwitchCheckedChange,
                        enabled = item.enabled
                    )
                }
            }
        }
    }

    if (showApiSelectionDialog) {
        ApiSelectionDialog(
            onDismiss = { showApiSelectionDialog = false },
            onConfirm = { selectedApi ->
                currentPriceApi = selectedApi
                showApiSelectionDialog = false
            }
        )
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = currentTheme,
            onThemeSelected = { mode ->
                // currentTheme 是 rememberThemeMode() 的只读值：
                // setThemeMode 落盘 theme_prefs 后，其内部监听会驱动刷新，无需手动赋值
                ThemeManager.setThemeMode(context, mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showClearDataDialog) {
        ClearDataDialog(
            assetCount = assetCount,
            isDeleting = uiState.isDeleting,
            onConfirm = { viewModel.deleteAllData() },
            onDismiss = { showClearDataDialog = false }
        )
    }

    if (showExportDialog) {
        ExportDataDialog(
            snapshots = snapshots,
            transactions = uiState.transactions,
            isExporting = uiState.isExporting,
            onExportText = {
                viewModel.exportData(context, SettingsViewModel.ExportType.TEXT)
                showExportDialog = false
            },
            onExportCsvAssets = {
                viewModel.exportData(context, SettingsViewModel.ExportType.CSV_ASSETS)
                showExportDialog = false
            },
            onExportCsvSnapshots = {
                viewModel.exportData(context, SettingsViewModel.ExportType.CSV_SNAPSHOTS)
                showExportDialog = false
            },
            onExportCsvTransactions = {
                viewModel.exportData(context, SettingsViewModel.ExportType.CSV_TRANSACTIONS)
                showExportDialog = false
            },
            onExportCsvStatement = {
                viewModel.exportData(context, SettingsViewModel.ExportType.CSV_STATEMENT)
                showExportDialog = false
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showImportDialog) {
        ImportBrokerageDialog(
            container = container,
            onDismiss = { showImportDialog = false }
        )
    }

    if (showHoldingSnapshotDialog) {
        HoldingSnapshotDialog(
            container = container,
            source = HoldingSnapshotViewModel.Source.STOCK_SNAPSHOT,
            onDismiss = { showHoldingSnapshotDialog = false }
        )
    }

    if (showFundSnapshotDialog) {
        HoldingSnapshotDialog(
            container = container,
            source = HoldingSnapshotViewModel.Source.FUND_EXPORT,
            onDismiss = { showFundSnapshotDialog = false }
        )
    }

    if (showBinanceImportDialog) {
        BinanceImportDialog(
            container = container,
            onDismiss = { showBinanceImportDialog = false }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            version = versionName,
            onDismiss = { showAboutDialog = false }
        )
    }

    if (showLogViewerDialog) {
        LogViewerDialog(onDismiss = { showLogViewerDialog = false })
    }

    if (showDashboardConfigSheet) {
        DashboardConfigSheet(
            preferences = dashboardPrefs,
            onPreferencesChange = { viewModel.saveDashboardPreferences(it) },
            onDismiss = { showDashboardConfigSheet = false }
        )
    }

    if (showReminderTimePicker) {
        ReminderTimePickerDialog(
            initialHour = reminderHour,
            initialMinute = reminderMinute,
            onDismiss = { showReminderTimePicker = false },
            onConfirm = { hour, minute ->
                viewModel.setReminderTime(hour, minute)
                showReminderTimePicker = false
            }
        )
    }

    if (showDataSourceDialog) {
        // 之前这里写成 uiState.dataSourceHealth?.let { ... }：健康列表初始是 null，
        // 点开后要等好几个网络请求跑完才突然出现；一旦检测抛异常（离线）就永远为 null，
        // 这个菜单项再也打不开。判空从"渲染条件"移到对话框内部的空状态处理。
        DataSourceStatusDialog(
            healthList = uiState.dataSourceHealth ?: emptyList(),
            isLoading = uiState.isCheckingHealth,
            onDismiss = { showDataSourceDialog = false },
            onRefresh = { viewModel.checkDataSourceHealth() }
        )
    }
}

private data class SettingsSectionData(
    val title: String,
    val items: List<SettingsItemData>
)

private data class SettingsItemData(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit = {},
    val isDestructive: Boolean = false,
    val isSwitch: Boolean = false,
    val switchChecked: Boolean = false,
    val onSwitchCheckedChange: ((Boolean) -> Unit)? = null,
    /** false 时置灰且不可点击（用于依赖其它开关的子项，如「提醒时间」依赖「每日提醒」） */
    val enabled: Boolean = true
)

private fun formatPriceSyncTime(timeMillis: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timeMillis))
}
