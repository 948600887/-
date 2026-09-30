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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
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

    init {
        viewModelScope.launch {
            assetRepository.getAllAssets().collect { assets ->
                _uiState.value = _uiState.value.copy(assets = assets)
            }
        }
        viewModelScope.launch {
            snapshotDao.getAllSnapshots().collect { snapshots ->
                _uiState.value = _uiState.value.copy(snapshots = snapshots)
            }
        }
        viewModelScope.launch {
            transactionDao.getAllFlow().collect { transactions ->
                _uiState.value = _uiState.value.copy(transactions = transactions)
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
                _uiState.value = _uiState.value.copy(isDeleting = false, deleteResult = Result.success(Unit))
            } catch (e: Exception) {
                AppLogger.e("删除数据失败: ${e.message}", e, "SettingsVM")
                _uiState.value = _uiState.value.copy(isDeleting = false, deleteResult = Result.failure(e))
            }
        }
    }

    fun checkDataSourceHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isCheckingHealth = true)
            try {
                val health = dataSourceHealthChecker.checkAllHealth()
                _uiState.value = _uiState.value.copy(isCheckingHealth = false, dataSourceHealth = health)
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
            } catch (e: Exception) {
                AppLogger.e("保存自动价格同步设置失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
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
