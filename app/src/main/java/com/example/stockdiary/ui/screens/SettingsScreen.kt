package com.example.stockdiary.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.data.PriceSyncScheduler
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.components.ApiSelectionDialog
import com.example.stockdiary.ui.screens.settings.components.AboutDialog
import com.example.stockdiary.ui.screens.settings.components.ClearDataDialog
import com.example.stockdiary.ui.screens.settings.components.DashboardConfigSheet
import com.example.stockdiary.ui.screens.settings.components.DataSourceStatusDialog
import com.example.stockdiary.ui.screens.settings.components.ExportDataDialog
import com.example.stockdiary.ui.screens.settings.components.ImportBrokerageDialog
import com.example.stockdiary.ui.screens.settings.components.SettingsItem
import com.example.stockdiary.ui.screens.settings.components.SettingsSection
import com.example.stockdiary.ui.screens.settings.components.ThemeSelectionDialog
import com.example.stockdiary.ui.theme.ThemeManager
import com.example.stockdiary.ui.viewmodels.SettingsViewModel
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.sumTotalValueCny

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container))
    val uiState by viewModel.uiState.collectAsState()
    val dashboardPrefs by viewModel.dashboardPreferences.collectAsState()
    val autoPriceSyncEnabled by viewModel.autoPriceSyncEnabled.collectAsState()
    val lastPriceSyncTime by viewModel.lastPriceSyncTime.collectAsState()
    var currentTheme by remember { mutableStateOf(ThemeManager.getThemeMode(context)) }

    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_mode") {
                currentTheme = ThemeManager.getThemeMode(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDashboardConfigSheet by remember { mutableStateOf(false) }
    var showDataSourceDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showApiSelectionDialog by remember { mutableStateOf(false) }
    var currentPriceApi by remember { mutableStateOf(com.example.stockdiary.data.ApiManager.getPriceApi(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                context,
                "已开启自动同步，但通知权限被拒绝，同步结果将不会显示通知",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val assets = uiState.assets
    val snapshots = uiState.snapshots
    val assetCount = assets.size
    val totalAssets = assets.filter { !it.type.isLiability }.sumOf { it.totalValue }
    val totalLiabilities = assets.filter { it.type.isLiability }.sumOf { it.totalValue }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(uiState.deleteResult) {
        uiState.deleteResult?.let { result ->
            if (result.isSuccess) {
                showClearDataDialog = false
            } else {
                Toast.makeText(
                    context,
                    result.exceptionOrNull()?.message ?: "清除失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            viewModel.clearDeleteResult()
        }
    }

    LaunchedEffect(uiState.exportResult) {
        uiState.exportResult?.let { result ->
            if (result.isError) {
                Toast.makeText(
                    context,
                    result.exceptionOrNull()?.message ?: "导出失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            viewModel.clearExportResult()
        }
    }

    val settingsSections = remember(assetCount, totalAssets, totalLiabilities, currentTheme, autoPriceSyncEnabled, lastPriceSyncTime) {
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
                    subtitle = when {
                        !autoPriceSyncEnabled -> "已关闭"
                        lastPriceSyncTime > 0 -> "每日约 17:15 自动同步价格与分红 · 上次: ${formatPriceSyncTime(lastPriceSyncTime)}"
                        else -> "每日约 17:15 自动同步价格与分红 · 尚未同步"
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
                    onClick = { }
                ),
                SettingsItemData(
                    icon = Icons.Default.AccountBalance,
                    title = "总资产",
                    subtitle = "¥${totalAssets.format(2)}",
                    onClick = { }
                ),
                SettingsItemData(
                    icon = Icons.Default.MoneyOff,
                    title = "总负债",
                    subtitle = "¥${totalLiabilities.format(2)}",
                    onClick = { }
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
                    icon = Icons.Default.Info,
                    title = "关于",
                    subtitle = "金库 v1.0.0",
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
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) }
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
                        onSwitchCheckedChange = item.onSwitchCheckedChange
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
                currentTheme = mode
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

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showDashboardConfigSheet) {
        DashboardConfigSheet(
            preferences = dashboardPrefs,
            onPreferencesChange = { viewModel.saveDashboardPreferences(it) },
            onDismiss = { showDashboardConfigSheet = false }
        )
    }

    if (showDataSourceDialog) {
        uiState.dataSourceHealth?.let { currentHealth ->
            DataSourceStatusDialog(
                healthList = currentHealth,
                isLoading = uiState.isCheckingHealth,
                onDismiss = { showDataSourceDialog = false },
                onRefresh = { viewModel.checkDataSourceHealth() }
            )
        }
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
    val onSwitchCheckedChange: ((Boolean) -> Unit)? = null
)

private fun formatPriceSyncTime(timeMillis: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timeMillis))
}
