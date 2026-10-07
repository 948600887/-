package com.example.stockdiary.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.common.Result
import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.DashboardPreferences
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.data.DataExporter
import com.example.stockdiary.data.PriceSyncPreferencesManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val errorMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _dashboardPreferences = MutableStateFlow(dashboardPreferencesManager.loadPreferences())
    val dashboardPreferences: StateFlow<DashboardPreferences> = _dashboardPreferences.asStateFlow()

    private val _autoPriceSyncEnabled = MutableStateFlow(priceSyncPreferencesManager.isAutoSyncEnabled())
    val autoPriceSyncEnabled: StateFlow<Boolean> = _autoPriceSyncEnabled.asStateFlow()

    private val _lastPriceSyncTime = MutableStateFlow(priceSyncPreferencesManager.getLastSyncTimeMillis())
    val lastPriceSyncTime: StateFlow<Long> = _lastPriceSyncTime.asStateFlow()

    /**
     * 每日提醒状态。
     *
     * 之前 [NotificationHelper.setDailyReminder] 全项目只有 BootReceiver 一个调用点，
     * 而 BootReceiver 生效的前提是 isReminderEnabled() 为真 —— 但没有任何 UI 能把开关打开，
     * 于是整条链路（含 BootReceiver、REMINDER 通知渠道、 ~150 行提醒代码）永远走不到。
     * 这里把开关状态暴露给设置页，闭环。
     */
    private val _reminderEnabled = MutableStateFlow(NotificationHelper.isReminderEnabled(appContext))
    val reminderEnabled: StateFlow<Boolean> = _reminderEnabled.asStateFlow()

    private val initialReminderTime = NotificationHelper.getReminderTime(appContext)
    private val _reminderHour = MutableStateFlow(initialReminderTime.first)
    val reminderHour: StateFlow<Int> = _reminderHour.asStateFlow()

    private val _reminderMinute = MutableStateFlow(initialReminderTime.second)
    val reminderMinute: StateFlow<Int> = _reminderMinute.asStateFlow()

    /** 提醒是否真的排上了（无精确闹钟权限时会失败，需要给用户可见反馈） */
    private val _reminderError = MutableStateFlow<String?>(null)
    val reminderError: StateFlow<String?> = _reminderError.asStateFlow()

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _reminderError.value = null
                val ok = if (enabled) {
                    NotificationHelper.setDailyReminder(appContext, _reminderHour.value, _reminderMinute.value)
                } else {
                    NotificationHelper.cancelReminder(appContext)
                    true
                }
                if (ok) {
                    _reminderEnabled.value = NotificationHelper.isReminderEnabled(appContext)
                } else {
                    _reminderEnabled.value = false
                    _reminderError.value = "无法设置提醒：系统未提供精确闹钟权限，请在系统闹钟设置中允许"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("设置每日提醒失败: ${e.message}", e, "SettingsVM")
                _reminderError.value = "设置提醒失败: ${e.message}"
            }
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            try {
                _reminderError.value = null
                _reminderHour.value = hour
                _reminderMinute.value = minute
                // 开关打开时改时间要立刻重排，否则要等到第二天闹钟触发后才用新时间
                if (_reminderEnabled.value) {
                    val ok = NotificationHelper.setDailyReminder(appContext, hour, minute)
                    if (!ok) {
                        _reminderEnabled.value = false
                        _reminderError.value = "无法更新提醒时间：缺少精确闹钟权限"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("设置提醒时间失败: ${e.message}", e, "SettingsVM")
                _reminderError.value = "设置提醒时间失败: ${e.message}"
            }
        }
    }

    fun clearReminderError() {
        _reminderError.value = null
    }

    init {
        // Room Flow 在数据库异常时会向收集者抛错，不兜住会让 viewModelScope 崩溃
        viewModelScope.launch {
            try {
                assetRepository.getAllAssets().collect { assets ->
                    _uiState.value = _uiState.value.copy(assets = assets)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("资产流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
        viewModelScope.launch {
            try {
                snapshotDao.getAllSnapshots().collect { snapshots ->
                    _uiState.value = _uiState.value.copy(snapshots = snapshots)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("快照流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
        viewModelScope.launch {
            try {
                transactionDao.getAllFlow().collect { transactions ->
                    _uiState.value = _uiState.value.copy(transactions = transactions)
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
        CSV_STATEMENT
    }

    fun exportData(context: Context) {
        exportData(context, ExportType.TEXT)
    }

    fun exportData(context: Context, type: ExportType) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isExporting = true, exportResult = null)
            try {
                val currentState = _uiState.value
                val uri = when (type) {
                    ExportType.TEXT -> dataExporter.exportToText(currentState.assets)
                    ExportType.CSV_ASSETS -> dataExporter.exportAssetsToCSV(currentState.assets)
                    ExportType.CSV_SNAPSHOTS -> dataExporter.exportSnapshotsToCSV(currentState.snapshots)
                    ExportType.CSV_TRANSACTIONS -> dataExporter.exportTransactionsToCSV(currentState.assets, currentState.transactions)
                    ExportType.CSV_STATEMENT -> dataExporter.exportStatementCSV(currentState.assets, currentState.transactions, currentState.snapshots)
                }
                if (uri != null) {
                    withContext(Dispatchers.Main) {
                        val title = when (type) {
                            ExportType.TEXT -> "资产报告"
                            ExportType.CSV_ASSETS -> "资产数据"
                            ExportType.CSV_SNAPSHOTS -> "历史快照"
                            ExportType.CSV_TRANSACTIONS -> "交易对账单"
                            ExportType.CSV_STATEMENT -> "综合对账单"
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
                    _uiState.value = _uiState.value.copy(isExporting = false, exportResult = Result.success("导出成功"))
                } else {
                    _uiState.value = _uiState.value.copy(isExporting = false, exportResult = Result.failure("导出失败"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("导出失败: ${e.message}", e, "SettingsVM")
                _uiState.value = _uiState.value.copy(isExporting = false, exportResult = Result.failure(e))
            }
        }
    }

    fun deleteAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isDeleting = true, deleteResult = null)
            try {
                assetRepository.deleteAllData()
                clearAllPreferences()
                _uiState.value = _uiState.value.copy(isDeleting = false, deleteResult = Result.success(Unit))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("删除数据失败: ${e.message}", e, "SettingsVM")
                _uiState.value = _uiState.value.copy(isDeleting = false, deleteResult = Result.failure(e))
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
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        var cleared = 0
        for (file in files) {
            if (!file.name.endsWith(".xml")) continue
            val name = file.name.removeSuffix(".xml")
            // 别动 WorkManager 自己的偏好：里面存着「被强制停止后需要重排」的标记，
            // 清掉它会漏掉一次后台任务重排。那不是用户数据，不该跟着一起清。
            if (name.startsWith("androidx.work")) continue
            runCatching {
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().commit()
                cleared++
            }.onFailure {
                AppLogger.w("清除偏好失败: $name - ${it.message}", "SettingsVM")
            }
        }
        AppLogger.i("已清除 $cleared 项偏好设置", "SettingsVM")
    }

    fun checkDataSourceHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isCheckingHealth = true)
            try {
                val health = dataSourceHealthChecker.checkAllHealth()
                _uiState.value = _uiState.value.copy(isCheckingHealth = false, dataSourceHealth = health)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("检查数据源健康失败: ${e.message}", e, "SettingsVM")
                _uiState.value = _uiState.value.copy(
                    isCheckingHealth = false,
                    errorMessage = "检查数据源状态失败"
                )
            }
        }
    }

    fun clearExportResult() {
        _uiState.value = _uiState.value.copy(exportResult = null)
    }

    fun clearDeleteResult() {
        _uiState.value = _uiState.value.copy(deleteResult = null)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun saveDashboardPreferences(preferences: DashboardPreferences) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dashboardPreferencesManager.savePreferences(preferences)
                _dashboardPreferences.value = preferences
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
                _autoPriceSyncEnabled.value = enabled
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存自动价格同步设置失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                appContext = container.context.applicationContext,
                assetRepository = container.assetRepository,
                snapshotDao = container.database.snapshotDao(),
                transactionDao = container.database.transactionDao(),
                dataExporter = container.dataExporter,
                dashboardPreferencesManager = container.dashboardPreferencesManager,
                dataSourceHealthChecker = DataSourceHealthChecker,
                priceSyncPreferencesManager = container.priceSyncPreferencesManager
            ) as T
        }
    }
}
