package com.example.stockdiary.ui.screens

import com.example.stockdiary.common.DateFormats
import com.example.stockdiary.data.contract.SyncStrategy
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.components.LocalAppSnackbar
import com.example.stockdiary.ui.screens.settings.components.AboutDialog
import com.example.stockdiary.ui.screens.settings.components.BinanceImportDialog
import com.example.stockdiary.ui.screens.settings.components.ClearDataDialog
import com.example.stockdiary.ui.screens.settings.components.DashboardConfigSheet
import com.example.stockdiary.ui.screens.settings.components.DataSourceStatusDialog
import com.example.stockdiary.ui.screens.settings.components.ExportDataDialog
import com.example.stockdiary.ui.screens.settings.components.ImportBrokerageDialog
import com.example.stockdiary.ui.screens.settings.components.ImportSource
import com.example.stockdiary.ui.screens.settings.components.ImportSourceDialog
import com.example.stockdiary.ui.screens.settings.components.LogViewerDialog
import com.example.stockdiary.ui.screens.settings.components.HoldingSnapshotDialog
import com.example.stockdiary.ui.screens.settings.components.SettingsItem
import com.example.stockdiary.ui.screens.settings.components.SettingsSection
import com.example.stockdiary.ui.screens.settings.components.ThemeSelectionDialog
import com.example.stockdiary.ui.theme.rememberThemeMode
import com.example.stockdiary.ui.theme.ThemeManager
import com.example.stockdiary.ui.viewmodels.HoldingSnapshotViewModel
import com.example.stockdiary.ui.viewmodels.SettingsViewModel
import com.example.stockdiary.ui.viewmodels.createSettingsViewModelFactory

import com.example.stockdiary.utils.format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 提示宿主在导航层（见 GoldVaultNavHost）：本页只发消息，不持有 SnackbarHostState，
    // 否则切 Tab 时页面移出组合，刚弹出的提示会被取消、回来又重弹。
    val appSnackbar = LocalAppSnackbar.current
    // 版本号从包信息动态读取，避免发版后忘记改硬编码的 "1.0.0"
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }
    val viewModel: SettingsViewModel = viewModel(factory = createSettingsViewModelFactory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 仪表盘偏好保留独立流（跨页面共享的整块偏好，写入路径与 UiState 不同，见 VM KDoc）
    val dashboardPrefs by viewModel.dashboardPreferences.collectAsStateWithLifecycle()
    // 价格同步的 4 个快照字段已合入 uiState（同 Dashboard/Portfolio 模式）：
    // 原来多条流各自 collect，一次 refreshPriceSyncState 连发多次、整页重组多遍；
    // 现在按原变量名从 uiState 取别名，页面主体逻辑不动。
    val autoPriceSyncEnabled = uiState.autoPriceSyncEnabled
    val lastPriceSyncTime = uiState.lastPriceSyncTime
    val stockIntradaySyncEnabled = uiState.stockIntradaySyncEnabled
    val cryptoAutoSyncEnabled = uiState.cryptoAutoSyncEnabled
    val currentTheme = rememberThemeMode()

    // 当前生效的美元汇率与上次刷新时间：从 UiState 读（刷新后要能重组），
    // 不再直接读单例——直接读时刷新完值变了、界面却不更新。
    val usdRate = uiState.usdRate
    val usdRateUpdatedAt = uiState.usdRateUpdatedAt
    val isRefreshingRate = uiState.isRefreshingRate

    // 通知权限当前是否可用：被拒后开关仍可打开，但到期不会有任何提示，必须让用户看见。
    // 用可变状态而不是 remember 常量：应用内申请结果回来时要立刻更新提示。
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDashboardConfigSheet by remember { mutableStateOf(false) }
    var showDataSourceDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    /** 「导入持仓与资产」合并入口：4 种导入方式收敛到一个列表对话框 */
    var showImportSourceDialog by remember { mutableStateOf(false) }
    var showHoldingSnapshotDialog by remember { mutableStateOf(false) }
    var showFundSnapshotDialog by remember { mutableStateOf(false) }
    var showBinanceImportDialog by remember { mutableStateOf(false) }
    var showLogViewerDialog by remember { mutableStateOf(false) }

    /**
     * 恢复是「先选文件 → 只读解析摘要 → 二次确认 → 再写库」三步，Uri 要跨这几步暂存。
     * 摘要存在 ViewModel 的 UiState 里（可观察），Uri 只留在本页内存：进程被杀后不残留
     * 一个「待恢复」的悬空状态，用户得重新走一遍确认。
     */
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // 导出：CreateDocument 让用户自己选保存位置，彻底脱离会被 pruneOldExports 清理的 cacheDir。
    // MIME 固定 application/json；建议文件名带时间戳，同目录连续导出不会互相覆盖。
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }

    // 恢复：OpenDocument 只读打开用户选中的备份文件。用 */* 兜住各家文件管理器对 .json
    // 上报的不同 MIME（有的给 application/octet-stream），选错文件由 BackupCodec 校验给出可读错误。
    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            viewModel.readBackup(uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationsGranted = isGranted
        if (!isGranted) {
            appSnackbar.show("已开启自动同步，但通知权限被拒绝，同步结果将不会显示通知")
        }
    }

    // 只保留计数，不常驻整表（快照/交易只在导出时按需查库）
    val assetCount = uiState.assetCount
    val snapshotCount = uiState.snapshotCount
    val transactionCount = uiState.transactionCount

    // 每次进入「我的」页从磁盘重读一次：同步/提醒快照 + 仪表盘偏好 + 汇率，
    // 合并为一次 IO（原先两个方法各自一次 IO、一次重组）。
    LaunchedEffect(Unit) {
        viewModel.refreshOnEnter()
    }

    // 通知权限：除了应用内申请回调（见 notificationPermissionLauncher）会更新，
    // 用户在系统设置里手动授予/撤回权限后回到本页，必须重新查一次——
    // 否则开关旁的「通知权限未开启」提示会一直停在旧状态（#26）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 先清状态再展示（理由同 DashboardScreen）：切页时状态已清，回来不会重弹；
    // show 跑在应用级作用域上，不会随本效应取消。
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            viewModel.clearErrorMessage()
            appSnackbar.show(message)
        }
    }

    LaunchedEffect(uiState.deleteResult) {
        uiState.deleteResult?.let { result ->
            viewModel.clearDeleteResult()
            if (result.isSuccess) {
                showClearDataDialog = false
            } else {
                appSnackbar.show(result.exceptionOrNull()?.message ?: "清除失败")
            }
        }
    }

    // 6 种 CSV 导出写 exportResult；全量备份 / 恢复同时写 exportResult 与 backupResult。
    // 合并成一条消费：备份结果也能进 Snackbar，且同一动作不会弹两次提示。
    // 每次消费后把两个结果都清回 null，保证下一次（哪怕消息文本完全相同）仍会重新触发。
    val actionResult = uiState.exportResult ?: uiState.backupResult
    LaunchedEffect(actionResult) {
        actionResult?.let { result ->
            // 成功也提示：文件名是用户取消分享面板后唯一能追踪导出结果的信息
            val message = result.getOrNull() ?: result.exceptionOrNull()?.message ?: "操作失败"
            viewModel.clearActionResult()
            appSnackbar.show(message)
        }
    }

    val settingsSections = remember(
        currentTheme,
        autoPriceSyncEnabled,
        lastPriceSyncTime,
        stockIntradaySyncEnabled,
        cryptoAutoSyncEnabled,
        usdRate,
        usdRateUpdatedAt,
        isRefreshingRate,
        notificationsGranted,
        uiState.dataSourceHealthSummary,
        assetCount,
        snapshotCount,
        transactionCount,
        uiState.isExporting
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
            // 「数据」分区：两个自动同步子开关收纳进主开关——
            // 仅当「自动刷新价格与分红」开启时才渲染「盘中同步股票」「自动同步虚拟货币」，
            // 关闭时只留主开关一行，子项自身的勾选/点击逻辑保持不变
            SettingsSectionData("数据", buildList {
                add(SettingsItemData(
                    icon = Icons.Default.UploadFile,
                    title = "导入持仓与资产",
                    subtitle = "交割单 / 持仓截图 / 场外基金 / 币安",
                    onClick = { showImportSourceDialog = true }
                ))
                add(SettingsItemData(
                    icon = Icons.Default.Download,
                    title = "导出数据",
                    subtitle = buildString {
                        append("资产 $assetCount · 快照 $snapshotCount · 交易 $transactionCount")
                        // 导出没有对话框内的进度（点完即关），用这一行告诉用户还在写文件
                        if (uiState.isExporting) append(" · 导出中…")
                    },
                    onClick = { showExportDialog = true }
                ))
                add(SettingsItemData(
                    icon = Icons.Default.SyncAlt,
                    title = "数据源状态",
                    // 数据源已改为全自动：妙想Skill(配置了 API Key 才参与) > 新浪 > 腾讯，
                    // 某个源连续失败由数据源级熔断器自动短期禁用并降级，不再提供手动切换
                    // （原「行情数据源」选择入口已去除，见 ApiManager 注释）。
                    // 这里保留体检入口：点进去可看每个源当前通不通、熔断状态。
                    subtitle = uiState.dataSourceHealthSummary ?: "全自动选择，失败自动切换",
                    onClick = {
                        viewModel.checkDataSourceHealth()
                        showDataSourceDialog = true
                    }
                ))
                add(SettingsItemData(
                    icon = Icons.Default.Sync,
                    title = "自动刷新价格与分红",
                    // 实际策略是 SyncStrategy：交易时段 15 分钟一次、非交易时段 4 小时一次，
                    // 文案不能写"每日 17:15"，那会让人以为一天只更新一次。
                    subtitle = buildString {
                        append(
                            when {
                                !autoPriceSyncEnabled -> "已关闭"
                                lastPriceSyncTime > 0 -> "交易时段每 ${SyncStrategy.TRADING_INTERVAL_MINUTES} 分钟、其余时段每 ${SyncStrategy.NON_TRADING_INTERVAL_HOURS} 小时 · 上次: ${formatPriceSyncTime(lastPriceSyncTime)}"
                                else -> "交易时段每 ${SyncStrategy.TRADING_INTERVAL_MINUTES} 分钟、其余时段每 ${SyncStrategy.NON_TRADING_INTERVAL_HOURS} 小时 · 尚未同步"
                            }
                        )
                        if (autoPriceSyncEnabled && !notificationsGranted) {
                            append(" · 通知权限未开启，结果不会提醒")
                        }
                    },
                    isSwitch = true,
                    switchChecked = autoPriceSyncEnabled,
                    onSwitchCheckedChange = { enabled ->
                        // 调度/取消已收敛到 ViewModel；这里只负责在开启时补一次通知权限申请
                        viewModel.setAutoPriceSyncEnabled(enabled)
                        if (enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                ))
                // 子开关紧邻主开关、排在「汇率」之前，仅主开关开启时可见
                if (autoPriceSyncEnabled) {
                    add(SettingsItemData(
                        icon = Icons.Default.Schedule,
                        title = "盘中同步股票",
                        subtitle = if (stockIntradaySyncEnabled)
                            "盘中每 ${SyncStrategy.TRADING_INTERVAL_MINUTES} 分钟也刷新"
                        else
                            "仅收盘后刷新，盘中不刷（更省）",
                        isSwitch = true,
                        switchChecked = stockIntradaySyncEnabled,
                        onSwitchCheckedChange = { enabled ->
                            // 重排周期由 ViewModel 内部处理（仅在总开关开启时）
                            viewModel.setStockIntradaySyncEnabled(enabled)
                        }
                    ))
                    add(SettingsItemData(
                        icon = Icons.Default.CurrencyBitcoin,
                        title = "自动同步虚拟货币",
                        subtitle = if (cryptoAutoSyncEnabled)
                            "跟随自动同步周期刷新"
                        else
                            "不自动刷新，仅手动点刷新时更新",
                        isSwitch = true,
                        switchChecked = cryptoAutoSyncEnabled,
                        onSwitchCheckedChange = { enabled ->
                            viewModel.setCryptoAutoSyncEnabled(enabled)
                        }
                    ))
                }
                add(SettingsItemData(
                    icon = Icons.Default.CurrencyExchange,
                    title = "汇率",
                    subtitle = buildString {
                        if (usdRate > 0.0) {
                            append("1 美元 ≈ ¥")
                            append(usdRate.format(2))
                        } else {
                            append("汇率获取中（未连上汇率源时用内置默认）")
                        }
                        if (usdRateUpdatedAt > 0) {
                            append(" · 更新于 ")
                            append(formatPriceSyncTime(usdRateUpdatedAt))
                        } else if (usdRate > 0.0) {
                            append(" · 内置默认汇率")
                        }
                        append(" · 点此刷新")
                    },
                    // 汇率有 6 小时 TTL：看到旧值时用户应该能自己刷一次，
                    // 而不是只能等后台回源（原先这行是 enabled=false 的纯展示行）
                    onClick = { viewModel.refreshExchangeRate() },
                    enabled = !isRefreshingRate
                ))
            }),
            SettingsSectionData("应用", listOf(
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
                )
            )),
            // 危险操作沉到页面最底部独立分区：原先夹在「数据」分区中部，
            // 滚动列表时容易误触（有二次确认，但少一次惊吓）
            SettingsSectionData("危险操作", listOf(
                SettingsItemData(
                    icon = Icons.Default.DeleteForever,
                    title = "清除所有数据",
                    subtitle = "清空资产、快照、设置与本地文件",
                    onClick = { showClearDataDialog = true },
                    isDestructive = true
                )
            ))
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier,
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
                // key 必须**跨分区全局唯一**：LazyColumn 的 key 作用域是整个列表，
                // 只拿 title 当 key，将来两个分区出现同名项会直接抛 Duplicate key
                items(section.items, key = { "${section.title}/${it.title}" }) { item ->
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
            assetCount = assetCount,
            snapshotCount = snapshotCount,
            transactionCount = transactionCount,
            dividendCount = uiState.dividendCount,
            isExporting = uiState.isExporting,
            onExport = { type ->
                viewModel.exportData(context, type)
                showExportDialog = false
            },
            onBackupExport = {
                showExportDialog = false
                createBackupLauncher.launch("goldvault_backup_${System.currentTimeMillis()}.json")
            },
            onBackupRestore = {
                showExportDialog = false
                openBackupLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showExportDialog = false }
        )
    }

    // 恢复前二次确认：先展示只读解析出的摘要，明确「覆盖且不可撤销」，确认后才写库。
    // 摘要来自 ViewModel 的 UiState；确认时把暂存的 Uri 交给 ViewModel 再读一次流 + decode。
    uiState.pendingRestore?.let { summary ->
        AlertDialog(
            onDismissRequest = {
                viewModel.clearPendingRestore()
                pendingRestoreUri = null
            },
            title = { Text("确认从备份恢复") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "将覆盖当前全部账目，此操作不可撤销。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "备份时间：${formatBackupTime(summary.exportedAt)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "格式版本：${summary.formatVersion} · 数据库版本：${summary.dbVersion}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "资产 ${summary.assetCount} 条 · 历史快照 ${summary.snapshotCount} 条 · " +
                            "快照明细 ${summary.snapshotDetailCount} 条",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "分红 ${summary.dividendCount} 笔 · 交易 ${summary.transactionCount} 笔",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "股票基础信息 ${summary.stockBasicCount} 条 · 基金净值缓存 ${summary.fundNavCacheCount} 条",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "恢复不包含显示偏好与币安 API 密钥，恢复后不会自动刷新行情。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pendingRestoreUri
                        viewModel.clearPendingRestore()
                        pendingRestoreUri = null
                        if (target != null) viewModel.restoreBackup(target)
                    },
                    enabled = !uiState.isBackingUp
                ) {
                    Text("确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.clearPendingRestore()
                    pendingRestoreUri = null
                }) {
                    Text("取消")
                }
            }
        )
    }

    if (showImportDialog) {
        ImportBrokerageDialog(
            container = container,
            onDismiss = { showImportDialog = false }
        )
    }

    if (showImportSourceDialog) {
        // 「导入持仓与资产」合并入口：本对话框已在回调前自行 dismiss，
        // 这里只负责把选择结果分发给对应的既有导入对话框
        ImportSourceDialog(
            onDismiss = { showImportSourceDialog = false },
            onSelect = { source ->
                when (source) {
                    ImportSource.BROKERAGE -> showImportDialog = true
                    ImportSource.STOCK_SNAPSHOT -> showHoldingSnapshotDialog = true
                    ImportSource.FUND_EXPORT -> showFundSnapshotDialog = true
                    ImportSource.BINANCE -> showBinanceImportDialog = true
                }
            }
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
    /** false 时置灰且不可点击（如在刷新汇率期间不可再点） */
    val enabled: Boolean = true
)

private fun formatPriceSyncTime(timeMillis: Long): String {
    val sdf = java.text.SimpleDateFormat(DateFormats.MONTH_DAY_TIME, java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timeMillis))
}

/** 备份确认框里的导出时间：带完整年月日，便于用户分辨「哪一份」备份。 */
private fun formatBackupTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return "未知"
    val sdf = java.text.SimpleDateFormat(DateFormats.DISPLAY_DATETIME, java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timeMillis))
}
