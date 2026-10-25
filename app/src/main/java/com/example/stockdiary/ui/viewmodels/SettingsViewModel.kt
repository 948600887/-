package com.example.stockdiary.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.common.Result
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.export.DataExporter
import com.example.stockdiary.data.sync.PriceSyncPreferencesManager
import com.example.stockdiary.data.sync.PriceSyncScheduler
import com.example.stockdiary.data.sync.SnapshotScheduler
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionDao
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.DataSourceHealth
import com.example.stockdiary.utils.DataSourceHealthChecker
import com.example.stockdiary.utils.NotificationHelper
import com.example.stockdiary.utils.isDisplayEquivalentTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File

class SettingsViewModel(
    private val appContext: Context,
    private val assetRepository: AssetRepository,
    private val snapshotDao: SnapshotDao,
    private val transactionDao: TransactionDao,
    private val dataExporter: DataExporter,
    private val dashboardPreferencesManager: DashboardPreferencesManager,
    private val dataSourceHealthChecker: DataSourceHealthChecker,
    private val priceSyncPreferencesManager: PriceSyncPreferencesManager
) : ViewModel() {

    /**
     * 设置页状态机。
     *
     * 原为「UiState(10 字段) + 9 个附加流」混用：数据类列表/导出/删除/体检走 UiState，
     * 而价格同步与每日提醒的 8 个快照字段各自为政（开关 ×4、上次同步时间、提醒开关、
     * 时/分、提醒错误），写入点散落在 setReminderEnabled / setReminderTime /
     * setAutoPriceSyncEnabled / refreshPriceSyncState 等处；UI 同层 collect 十条流，
     * 一次 refreshPriceSyncState 要连发八次、整页重组八遍。
     *
     * 合流判断：
     * - 8 个同步/提醒快照字段并入本 UiState——它们同属「持久化设置的内存镜像」，
     *   由 [refreshPriceSyncState] 整组刷新、由设置页同一个 remember 块整组消费，
     *   合起来后一次刷新只发射一次（原来八次）；
     * - [dashboardPreferences] **刻意保留独立流**：它是跨页面共享的整块偏好
     *   （日历写月度生活费、仪表盘/持仓写分红缓存），与 Dashboard/Portfolio 两个 VM
     *   保持同样的「preferences 独立流」先例；且它的写入路径（config sheet 整块提交）
     *   与 UiState 其余字段（一次性动作驱动）语义不同，混在一起只会让「谁改了什么」更难读。
     */
    data class UiState(
        val assets: List<Asset> = emptyList(),
        val snapshots: List<AssetSnapshot> = emptyList(),
        val transactions: List<Transaction> = emptyList(),
        val isExporting: Boolean = false,
        val isDeleting: Boolean = false,
        val isCheckingHealth: Boolean = false,
        val dataSourceHealth: List<DataSourceHealth>? = null,
        val exportResult: Result<String>? = null,
        val deleteResult: Result<Unit>? = null,
        val errorMessage: String? = null,
        // ---- 价格同步快照：与 PriceSyncPreferencesManager 磁盘值一一对应 ----
        val autoPriceSyncEnabled: Boolean = false,
        val lastPriceSyncTime: Long = 0L,
        /** 盘中是否也自动刷股票/场内基金。关=仅盘后刷(默认) */
        val stockIntradaySyncEnabled: Boolean = false,
        /** 是否自动刷虚拟货币。关=只手动刷(默认) */
        val cryptoAutoSyncEnabled: Boolean = false,
        // ---- 每日提醒快照：与 NotificationHelper 内部状态一一对应 ----
        val reminderEnabled: Boolean = false,
        val reminderHour: Int = 8,
        val reminderMinute: Int = 0,
        /** 提醒是否真的排上了（无精确闹钟权限时会失败，需要给用户可见反馈） */
        val reminderError: String? = null
    )

    private val _uiState = MutableStateFlow(
        UiState(
            autoPriceSyncEnabled = priceSyncPreferencesManager.isAutoSyncEnabled(),
            lastPriceSyncTime = priceSyncPreferencesManager.getLastSyncTimeMillis(),
            stockIntradaySyncEnabled = priceSyncPreferencesManager.isStockIntradaySyncEnabled(),
            cryptoAutoSyncEnabled = priceSyncPreferencesManager.isCryptoAutoSyncEnabled(),
            reminderEnabled = NotificationHelper.isReminderEnabled(appContext),
            reminderHour = NotificationHelper.getReminderTime(appContext).first,
            reminderMinute = NotificationHelper.getReminderTime(appContext).second
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 自定义仪表盘偏好。保留独立流的理由见 [UiState] KDoc：跨页面共享的整块偏好，
     * 写入路径与 UiState 其余字段不同。
     */
    private val _dashboardPreferences = MutableStateFlow(dashboardPreferencesManager.loadPreferences())
    val dashboardPreferences: StateFlow<DashboardPreferences> = _dashboardPreferences.asStateFlow()

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(reminderError = null) }
                val ok = if (enabled) {
                    NotificationHelper.setDailyReminder(appContext, _uiState.value.reminderHour, _uiState.value.reminderMinute)
                } else {
                    NotificationHelper.cancelReminder(appContext)
                    true
                }
                if (ok) {
                    _uiState.update { it.copy(reminderEnabled = NotificationHelper.isReminderEnabled(appContext)) }
                } else {
                    _uiState.update {
                        it.copy(
                            reminderEnabled = false,
                            reminderError = "无法设置提醒：系统未提供精确闹钟权限，请在系统闹钟设置中允许"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("设置每日提醒失败: ${e.message}", e, "SettingsVM")
                _uiState.update { it.copy(reminderError = "设置提醒失败: ${e.message}") }
            }
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(reminderError = null, reminderHour = hour, reminderMinute = minute)
                }
                // 开关打开时改时间要立刻重排，否则要等到第二天闹钟触发后才用新时间
                if (_uiState.value.reminderEnabled) {
                    val ok = NotificationHelper.setDailyReminder(appContext, hour, minute)
                    if (!ok) {
                        _uiState.update {
                            it.copy(
                                reminderEnabled = false,
                                reminderError = "无法更新提醒时间：缺少精确闹钟权限"
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("设置提醒时间失败: ${e.message}", e, "SettingsVM")
                _uiState.update { it.copy(reminderError = "设置提醒时间失败: ${e.message}") }
            }
        }
    }

    fun clearReminderError() {
        _uiState.update { it.copy(reminderError = null) }
    }

    init {
        // Room Flow 在数据库异常时会向收集者抛错，不兜住会让 viewModelScope 崩溃
        viewModelScope.launch {
            try {
                assetRepository.getAllAssets()
                    .distinctUntilChanged { a, b -> a.isDisplayEquivalentTo(b) }
                    .collect { assets ->
                    _uiState.update { state -> state.copy(assets = assets) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("资产流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
        viewModelScope.launch {
            try {
                snapshotDao.getAllSnapshots()
                    .distinctUntilChanged()
                    .collect { snapshots ->
                    _uiState.update { state -> state.copy(snapshots = snapshots) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("快照流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
        viewModelScope.launch {
            try {
                transactionDao.getAllFlow()
                    .distinctUntilChanged()
                    .collect { transactions ->
                    _uiState.update { state -> state.copy(transactions = transactions) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("交易流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
    }

    enum class ExportType {
        TEXT,
        CSV_ASSETS,
        CSV_SNAPSHOTS,
        CSV_TRANSACTIONS,
        CSV_STATEMENT,
        CSV_DIVIDENDS
    }

    fun exportData(context: Context, type: ExportType) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(isExporting = true, exportResult = null) }
            try {
                val currentState = _uiState.value
                val uri = when (type) {
                    ExportType.TEXT -> dataExporter.exportToText(currentState.assets)
                    ExportType.CSV_ASSETS -> dataExporter.exportAssetsToCSV(currentState.assets)
                    ExportType.CSV_SNAPSHOTS -> dataExporter.exportSnapshotsToCSV(currentState.snapshots)
                    ExportType.CSV_TRANSACTIONS -> dataExporter.exportTransactionsToCSV(currentState.assets, currentState.transactions)
                    ExportType.CSV_STATEMENT -> dataExporter.exportStatementCSV(currentState.assets, currentState.transactions, currentState.snapshots)
                    ExportType.CSV_DIVIDENDS -> dataExporter.exportDividendsToCSV(assetRepository.getAllDividends())
                }
                if (uri != null) {
                    withContext(Dispatchers.Main) {
                        val title = when (type) {
                            ExportType.TEXT -> "资产报告"
                            ExportType.CSV_ASSETS -> "资产数据"
                            ExportType.CSV_SNAPSHOTS -> "历史快照"
                            ExportType.CSV_TRANSACTIONS -> "交易对账单"
                            ExportType.CSV_STATEMENT -> "综合对账单"
                            ExportType.CSV_DIVIDENDS -> "分红明细"
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            setType(
                                when {
                                    uri.toString().contains(".csv") -> "text/csv"
                                    uri.toString().contains(".txt") -> "text/plain"
                                    else -> "application/octet-stream"
                                }
                            )
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, title))
                    }
                    _uiState.update { state -> state.copy(isExporting = false, exportResult = Result.success("导出成功")) }
                } else {
                    _uiState.update { state -> state.copy(isExporting = false, exportResult = Result.failure("导出失败")) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("导出失败: ${e.message}", e, "SettingsVM")
                _uiState.update { state -> state.copy(isExporting = false, exportResult = Result.failure(e)) }
            }
        }
    }

    /** 跳转系统设置开启精确闹钟权限（SCHEDULE_EXACT_ALARM 无法用弹窗请求，只能手动开） */
    fun openAlarmSettings() {
        try {
            NotificationHelper.openAlarmSettings(appContext)
        } catch (e: Exception) {
            AppLogger.e("打开闹钟设置失败: ${e.message}", e, "SettingsVM")
        }
    }

    fun deleteAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(isDeleting = true, deleteResult = null) }
            try {
                assetRepository.deleteAllData()
                clearAllPreferences()
                // 数据清了，后台任务和内存缓存不能留着：否则用户以为关干净了，
                // 后台还在按旧偏好跑同步、到点还弹提醒。最后从磁盘回读真实状态。
                PriceSyncScheduler.cancelDailySync(appContext)
                // 每日快照任务同样要取消：原先只取消了价格同步与提醒，漏了快照。
                // 于是清库后 SnapshotWorker 仍在每天 15:10 运行，对已清空的组合继续建快照
                // （createSnapshot 不判空），历史净值曲线会多出一串净资产为 0 的点。
                SnapshotScheduler.cancelDailySnapshot(appContext)
                NotificationHelper.cancelReminder(appContext)
                refreshPriceSyncState()
                _uiState.update { state -> state.copy(isDeleting = false, deleteResult = Result.success(Unit)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("删除数据失败: ${e.message}", e, "SettingsVM")
                _uiState.update { state -> state.copy(isDeleting = false, deleteResult = Result.failure(e)) }
            }
        }
    }

    /**
     * 清掉所有 SharedPreferences。
     *
     * `deleteAllData` 原先只清数据库：显示偏好、提醒开关与时间、畅想 api 配置、
     * 「名称→代码」记忆、崩溃记录、通知去重记录全都留在磁盘上。用户以为"清干净了"，
     * 重新录入时旧记忆/旧设置还在，而且散落七处，出问题时根本想不到是残留造成的。
     *
     * 直接扫 shared_prefs 目录而不是逐个列名字：将来新增任何 Preferences 都会被自动覆盖。
     */
    private fun clearAllPreferences() {
        // commit() 而非 apply()：清除后用户大概率立刻重启 App，异步落盘可能来不及写。
        val dir = File(appContext.applicationInfo?.dataDir ?: appContext.filesDir.parent, "shared_prefs")
        val files = try {
            dir.listFiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        var cleared = 0
        for (file in files) {
            if (!file.name.endsWith(".xml")) continue
            val name = file.name.removeSuffix(".xml")
            // 别动 WorkManager 自己的偏好：里面存着「被强制停止后需要重排」的标记，
            // 清掉它会漏掉一次后台任务重排。那不是用户数据，不该跟着一起清。
            if (name.startsWith("androidx.work")) continue
            try {
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().commit()
                cleared++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("清除偏好失败: $name - ${e.message}", "SettingsVM")
            }
        }
        AppLogger.i("已清除 $cleared 项偏好设置", "SettingsVM")
    }

    fun checkDataSourceHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(isCheckingHealth = true) }
            try {
                val health = dataSourceHealthChecker.checkAllHealth()
                _uiState.update { state -> state.copy(isCheckingHealth = false, dataSourceHealth = health) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("检查数据源健康失败: ${e.message}", e, "SettingsVM")
                _uiState.update { state -> state.copy(
                    isCheckingHealth = false,
                    errorMessage = "检查数据源状态失败"
                ) }
            }
        }
    }

    fun clearExportResult() {
        _uiState.update { state -> state.copy(exportResult = null) }
    }

    fun clearDeleteResult() {
        _uiState.update { state -> state.copy(deleteResult = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { state -> state.copy(errorMessage = null) }
    }

    /**
     * 保存「自定义仪表盘」偏好。
     *
     * ## 铁律：必须走原子「读-改-写」，禁止整块回写
     *
     * 原先直接 `savePreferences(preferences)` 把 sheet 累积的完整副本整块落盘。
     * 该副本的初值取自本 VM **构造期**加载的 [_dashboardPreferences]——用户在设置页
     * 停留期间，日历页可能改了月度生活费、仪表盘/持仓刷新可能更新年度分红缓存，
     * 整块回写会把这些字段冲回旧值（MEMORY.md 记录过同类事故：持仓页用陈旧副本
     * 整体回写踩掉日历的月度生活费）。因此改走 Manager 的
     * [DashboardPreferencesManager.updatePreferences]：先拉磁盘最新值，只把
     * DashboardConfigSheet 负责的显示/排序字段合并上去，其余字段
     * （月度生活费 / 年度分红缓存 / 分组展开态）以 fresh 为准原样保留。
     */
    fun saveDashboardPreferences(preferences: DashboardPreferences) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = dashboardPreferencesManager.updatePreferences { fresh ->
                    fresh.copy(
                        showTotalAssets = preferences.showTotalAssets,
                        showNetAssets = preferences.showNetAssets,
                        showProfit = preferences.showProfit,
                        showDistribution = preferences.showDistribution,
                        showAssetGrowth = preferences.showAssetGrowth,
                        showDividendProgress = preferences.showDividendProgress,
                        showNextPayout = preferences.showNextPayout,
                        showMonthlyCashFlow = preferences.showMonthlyCashFlow,
                        showBankDeposits = preferences.showBankDeposits,
                        showStocks = preferences.showStocks,
                        showFunds = preferences.showFunds,
                        showCrypto = preferences.showCrypto,
                        showLiabilities = preferences.showLiabilities,
                        hideLowValueCrypto = preferences.hideLowValueCrypto,
                        lowValueThresholdCny = preferences.lowValueThresholdCny,
                        hideUnknownPriceCrypto = preferences.hideUnknownPriceCrypto,
                        sortOrder = preferences.sortOrder,
                        defaultPeriod = preferences.defaultPeriod
                    )
                }
                _dashboardPreferences.value = updated
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存仪表盘偏好失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    fun setAutoPriceSyncEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                priceSyncPreferencesManager.setAutoSyncEnabled(enabled)
                if (!enabled) {
                    priceSyncPreferencesManager.setConsecutiveFailureCount(0)
                }
                _uiState.update { it.copy(autoPriceSyncEnabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存自动价格同步设置失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    /** 盘中是否也自动刷股票/场内基金。关=仅盘后刷(默认)。改完由调用方重排后台周期 */
    fun setStockIntradaySyncEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                priceSyncPreferencesManager.setStockIntradaySyncEnabled(enabled)
                _uiState.update { it.copy(stockIntradaySyncEnabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存盘中同步股票设置失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    /** 是否自动刷虚拟货币。关=只手动刷(默认)。改完由调用方重排后台周期 */
    fun setCryptoAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                priceSyncPreferencesManager.setCryptoAutoSyncEnabled(enabled)
                _uiState.update { it.copy(cryptoAutoSyncEnabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存自动同步虚拟货币设置失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    /**
     * 从磁盘重读价格同步与每日提醒的快照状态。
     *
     * StateFlow 初值只在 ViewModel 创建时读一次：别处（后台任务、系统设置、
     * 清除数据）改过磁盘后，内存里的值就过期了。进入设置页或清除数据后调用，
     * 保证界面显示的是真实值。
     *
     * 八个字段已在同一 UiState 里，整组刷新合并为**一次** update：原先八条流各写一次，
     * UI 要连收八个状态、整页重组八遍（设置页的 remember 块同时 key 着其中七个值）。
     */
    fun refreshPriceSyncState() {
        viewModelScope.launch(Dispatchers.IO) {
            val reminderTime = NotificationHelper.getReminderTime(appContext)
            _uiState.update { state ->
                state.copy(
                    autoPriceSyncEnabled = priceSyncPreferencesManager.isAutoSyncEnabled(),
                    lastPriceSyncTime = priceSyncPreferencesManager.getLastSyncTimeMillis(),
                    stockIntradaySyncEnabled = priceSyncPreferencesManager.isStockIntradaySyncEnabled(),
                    cryptoAutoSyncEnabled = priceSyncPreferencesManager.isCryptoAutoSyncEnabled(),
                    reminderEnabled = NotificationHelper.isReminderEnabled(appContext),
                    reminderHour = reminderTime.first,
                    reminderMinute = reminderTime.second
                )
            }
        }
    }

    companion object {
        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    appContext = container.context.applicationContext,
                    assetRepository = container.assetRepository,
                    snapshotDao = container.database.snapshotDao(),
                    transactionDao = container.database.transactionDao(),
                    dataExporter = container.dataExporter,
                    dashboardPreferencesManager = container.dashboardPreferencesManager,
                    dataSourceHealthChecker = DataSourceHealthChecker,
                    priceSyncPreferencesManager = container.priceSyncPreferencesManager
                )
            }
        }
    }
}
