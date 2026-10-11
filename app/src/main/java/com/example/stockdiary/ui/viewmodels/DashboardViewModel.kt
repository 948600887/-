package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.AssetSyncManager
import com.example.stockdiary.data.DividendIncomeUseCase
import com.example.stockdiary.data.DividendProgressCalculator
import com.example.stockdiary.data.SortOrder
import com.example.stockdiary.data.SyncProgress
import com.example.stockdiary.data.SyncResult
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.assetRepository
    private val preferencesManager = container.dashboardPreferencesManager
    private val incomeUseCase = container.dividendIncomeUseCase

    val assets: StateFlow<List<Asset>> = repository.getAllAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 「资产增值」卡片的基准数据：净资产要和本月/今年之前的快照对比
    val snapshots: StateFlow<List<AssetSnapshot>> = repository.getAllSnapshots()
        .map { it.sortedBy { snapshot -> snapshot.date } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _preferences = MutableStateFlow(preferencesManager.loadPreferences())
    val preferences: StateFlow<com.example.stockdiary.data.DashboardPreferences> = _preferences.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()


    private val _estimatedDailyDividend = MutableStateFlow(0.0)
    val estimatedDailyDividend: StateFlow<Double> = _estimatedDailyDividend.asStateFlow()

    private val _dividendLoading = MutableStateFlow(false)
    val dividendLoading: StateFlow<Boolean> = _dividendLoading.asStateFlow()

    private val _realAnnualIncome = MutableStateFlow(0.0)
    val realAnnualIncome: StateFlow<Double> = _realAnnualIncome.asStateFlow()

    private val _incomeCalculationStatus = MutableStateFlow<String?>(null)
    val incomeCalculationStatus: StateFlow<String?> = _incomeCalculationStatus.asStateFlow()

    // 收息进度：今年已收 / 全年预计 / 月度现金流 / 下一笔到账
    private val _dividendProgress = MutableStateFlow(DividendProgressCalculator.EMPTY)
    val dividendProgress: StateFlow<DividendProgressCalculator.Progress> = _dividendProgress.asStateFlow()

    private val _hasInterestData = MutableStateFlow(false)
    val hasInterestData: StateFlow<Boolean> = _hasInterestData.asStateFlow()

    private val _existingAccounts = MutableStateFlow<List<String>>(emptyList())
    val existingAccounts: StateFlow<List<String>> = _existingAccounts.asStateFlow()

    private var hasLoadedIncome = false

    init {
        viewModelScope.launch {
            try {
                _existingAccounts.value = repository.getAllAccounts().sorted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("加载账户列表失败: ${e.message}", "DashboardVM")
            }
        }
    }

    fun loadDividendIncome(assetsOnly: List<Asset>) {
        if (assetsOnly.isEmpty()) return
        viewModelScope.launch {
            if (!hasLoadedIncome) _dividendLoading.value = true
            try {
                val bankDeposits = assetsOnly.filter { it.type == AssetType.BANK_DEPOSIT }
                _hasInterestData.value = bankDeposits.any { (it.interestRate ?: 0.0) > 0 }

                val result = incomeUseCase.load(assetsOnly, forceRefresh = false, tag = "DashboardVM")
                _realAnnualIncome.value = result.totalAnnualIncome
                _estimatedDailyDividend.value = result.totalAnnualIncome / 365
                _dividendProgress.value = withContext(Dispatchers.Default) {
                    DividendProgressCalculator.calculate(
                        assets = assetsOnly,
                        recordsBySymbol = result.recordsBySymbol
                    )
                }

                _incomeCalculationStatus.value = when {
                    result.totalAnnualIncome == 0.0 && !result.hasDividendData && !_hasInterestData.value -> "暂无分红/利息数据"
                    result.totalAnnualIncome == 0.0 && assetsOnly.none { it.type == AssetType.STOCK || it.type == AssetType.FUND } -> "暂无计息资产"
                    else -> null
                }
                hasLoadedIncome = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("计算收入失败: ${e.message}", e, "DashboardVM")
                _realAnnualIncome.value = 0.0
                _estimatedDailyDividend.value = 0.0
                _incomeCalculationStatus.value = "计算失败"
            } finally {
                _dividendLoading.value = false
            }
        }
    }

    fun sync() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncResult.value = null
            try {
                var lastProgress: SyncProgress? = null
                val result = AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = assets.value,
                    onProgress = { lastProgress = it }
                )
                _syncProgress.value = lastProgress
                _syncResult.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 不捕获的话 _isSyncing 永远停在 true，后续同步全被挡死、界面一直转圈
                AppLogger.e("同步异常: ${e.message}", e, "DashboardVM")
                _syncResult.value = null
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val assetsOnly = assets.value.filter { !it.type.isLiability }
            try {
                AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = assetsOnly,
                    onProgress = { },
                    forceRefresh = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("同步失败: ${e.message}", "DashboardVM")
            }

            try {
                val result = incomeUseCase.load(assetsOnly, forceRefresh = false, tag = "DashboardVM")
                _realAnnualIncome.value = result.totalAnnualIncome
                _estimatedDailyDividend.value = result.totalAnnualIncome / 365
                _dividendProgress.value = withContext(Dispatchers.Default) {
                    DividendProgressCalculator.calculate(
                        assets = assetsOnly,
                        recordsBySymbol = result.recordsBySymbol
                    )
                }
                _incomeCalculationStatus.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("加载分红收入失败: ${e.message}", e, "DashboardViewModel")
                _incomeCalculationStatus.value = "加载分红收入失败: ${e.localizedMessage ?: e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun clearSyncResult() {
        _syncResult.value = null
        _syncProgress.value = null
    }

    suspend fun addAsset(asset: Asset) {
        if ((asset.type == AssetType.STOCK || asset.type == AssetType.FUND) && asset.symbol != null) {
            repository.addOrUpdateStockAsset(
                symbol = asset.symbol,
                name = asset.name,
                balance = asset.balance,
                price = asset.purchasePrice,
                account = asset.account,
                assetType = asset.type
            )
        } else {
            repository.insertAsset(asset)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(container) as T
        }
    }
}