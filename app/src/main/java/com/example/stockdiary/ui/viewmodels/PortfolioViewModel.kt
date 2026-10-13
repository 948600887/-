package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.AssetSyncManager
import com.example.stockdiary.data.DashboardPreferences
import com.example.stockdiary.data.DividendIncomeUseCase
import com.example.stockdiary.data.SortOrder
import com.example.stockdiary.data.SyncProgress
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

class PortfolioViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * 刷新路径的兜底异常处理。
     *
     * `viewModelScope` 用的是 SupervisorJob：子协程失败不会牵连兄弟，
     * 但失败本身仍会向上抛。没有 handler 的话，它会一路抛到线程默认的
     * UncaughtExceptionHandler —— 也就是用户看到的「闪退」。
     *
     * 刷新的失败原因千奇百怪（网络、解析、某个标的的数据特别），
     * 任何一个都不值得让整个 App 死掉：记录日志、提示用户、保持界面可用。
     *
     * 这是**兜底**不是修因。真因仍要靠日志定位，所以这里必须把堆栈打全。
     */
    private val refreshExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e(
            "刷新过程发生未捕获异常: ${throwable.message}",
            throwable,
            "PortfolioVM"
        )
        _errorMessage.value = "刷新没能完成：${throwable.message ?: "未知错误"}"
        _isRefreshing.value = false
    }

    private val repository = container.assetRepository
    private val incomeUseCase = container.dividendIncomeUseCase

    val assets: StateFlow<List<Asset>> = repository.getAllAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * 同步进度。刷新要跑完上百个标的，没有进度的话用户只看到转圈，
     * 「运行一段时间」到底是在干活还是卡死了，完全无从判断。
     */
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    private val _estimatedAnnualDividend = MutableStateFlow(0.0)
    val estimatedAnnualDividend: StateFlow<Double> = _estimatedAnnualDividend.asStateFlow()

    private val _dividendLoading = MutableStateFlow(false)
    val dividendLoading: StateFlow<Boolean> = _dividendLoading.asStateFlow()

    private val _incomeCalculationStatus = MutableStateFlow<String?>(null)
    val incomeCalculationStatus: StateFlow<String?> = _incomeCalculationStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _existingAccounts = MutableStateFlow<List<String>>(emptyList())

    val existingAccounts: StateFlow<List<String>> = _existingAccounts.asStateFlow()

    // 资产列表已从资产页并入这里，显示开关与排序偏好随之接管
    private val _preferences = MutableStateFlow(
        container.dashboardPreferencesManager.loadPreferences()
    )
    val preferences: StateFlow<DashboardPreferences> = _preferences.asStateFlow()

    private var hasLoadedDividend = false

    init {
        viewModelScope.launch {
            try {
                _existingAccounts.value = repository.getAllAccounts().sorted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("加载账户列表失败: ${e.message}", "PortfolioVM")
            }
        }
    }

    /**
     * 加载年度分红。
     *
     * ## 为什么进场不重算
     *
     * ViewModel 每次进入持仓页都会重建，旧做法是进来就重抓一遍分红，
     * 于是用户每次打开 App 都要看一次「年度分红：计算中」——数据根本没变。
     *
     * 现在按「持仓代码集合」比对持久化的缓存：集合一致就直接用旧值，
     * 一次网络请求都不发；持仓变了（导入、增删）才重新抓。
     * 抓取期间也照常显示旧值，不用「计算中」把屏幕占住。
     */
    fun loadDividendIncome(stockAssets: List<Asset>) {
        if (stockAssets.isEmpty()) return

        val symbols = stockAssets.mapNotNull { it.symbol }.toSet()
        val cached = _preferences.value

        // 缓存命中：直接用旧值，不抓
        if (symbols == cached.annualDividendSymbols && symbols.isNotEmpty()) {
            _estimatedAnnualDividend.value = cached.annualDividendCache
            _incomeCalculationStatus.value = if (cached.annualDividendCache == 0.0) "暂无分红数据" else null
            hasLoadedDividend = true
            _dividendLoading.value = false
            return
        }

        viewModelScope.launch(refreshExceptionHandler) {
            if (!hasLoadedDividend) _dividendLoading.value = true
            try {
                val result = incomeUseCase.load(stockAssets, forceRefresh = false, tag = "PortfolioVM")
                _estimatedAnnualDividend.value = result.totalAnnualIncome
                _incomeCalculationStatus.value = if (result.totalAnnualIncome == 0.0 && !result.hasDividendData) {
                    "暂无分红数据"
                } else null
                hasLoadedDividend = true

                // 抓到手就存缓存：下次进场同一批持仓直接命中
                _preferences.update { state ->
                    state.copy(
                        annualDividendCache = result.totalAnnualIncome,
                        annualDividendSymbols = symbols
                    )
                }
                container.dashboardPreferencesManager.savePreferences(_preferences.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("计算分红失败: ${e.message}", e, "PortfolioVM")
            } finally {
                _dividendLoading.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(refreshExceptionHandler) {
            _isRefreshing.value = true
            _errorMessage.value = null
            val stockAssets = assets.value.filter {
                it.type == AssetType.STOCK || it.type == AssetType.FUND
            }
            // 阶段日志：闪退发生在「运行一段时间后」，只有知道走到哪一步才能定位
            AppLogger.i(
                "刷新开始：待同步 ${stockAssets.size} 项，分红预取 ${stockAssets.mapNotNull { it.symbol }.size} 个代码",
                "PortfolioVM"
            )
            try {
                AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = stockAssets,
                    onProgress = { progress -> _syncProgress.value = progress },
                    forceRefresh = true
                )
                AppLogger.i("价格同步完成，开始计算分红", "PortfolioVM")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("同步失败: ${e.message}", "PortfolioVM")
            }

            try {
                val result = incomeUseCase.load(stockAssets, forceRefresh = false, tag = "PortfolioVM")
                _estimatedAnnualDividend.value = result.totalAnnualIncome
                _incomeCalculationStatus.value = null
                AppLogger.i(
                    "刷新完成：年度分红 ${result.totalAnnualIncome}",
                    "PortfolioVM"
                )

                // 手动刷新说明用户想看最新数，抓到就更新缓存
                val symbols = stockAssets.mapNotNull { it.symbol }.toSet()
                _preferences.update { state ->
                    state.copy(
                        annualDividendCache = result.totalAnnualIncome,
                        annualDividendSymbols = symbols
                    )
                }
                container.dashboardPreferencesManager.savePreferences(_preferences.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("刷新失败: ${e.message}", e, "PortfolioVM")
                _errorMessage.value = "刷新失败"
            } finally {
                _isRefreshing.value = false
                _syncProgress.value = null
            }
        }
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

    fun clearError() {
        _errorMessage.value = null
    }

    // 「设置 → 仪表盘显示」里把某类资产全关掉后，这里会整片空白，
    // 而开关只在设置页 —— 必须给一个就地恢复的出口。
    fun showAllAssetTypes() {
        // update{} 原子读-改-写，与 UiState 的处理保持一致
        _preferences.update { state -> state.copy(
            showBankDeposits = true,
            showStocks = true,
            showFunds = true,
            showCrypto = true,
            showLiabilities = true
        ) }
        container.dashboardPreferencesManager.savePreferences(_preferences.value)
    }

    fun updateSortOrder(sortOrder: SortOrder) {
        _preferences.update { state -> state.copy(sortOrder = sortOrder) }
        container.dashboardPreferencesManager.savePreferences(_preferences.value)
    }

    fun batchDelete(assetIds: Set<Long>) {
        viewModelScope.launch {
            try {
                repository.deleteAssetsByIds(assetIds)
                _existingAccounts.value = repository.getAllAccounts().sorted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("批量删除失败: ${e.message}", e, "PortfolioVM")
                _errorMessage.value = "批量删除失败"
            }
        }
    }

    fun batchMoveToAccount(assetIds: Set<Long>, targetAccount: String) {
        viewModelScope.launch {
            try {
                repository.updateAccountForAssets(assetIds, targetAccount)
                _existingAccounts.value = repository.getAllAccounts().sorted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("批量移动账户失败: ${e.message}", e, "PortfolioVM")
                _errorMessage.value = "批量移动账户失败"
            }
        }
    }

    fun batchSync(assets: List<Asset>) {
        viewModelScope.launch(refreshExceptionHandler) {
            _isRefreshing.value = true
            AppLogger.i("批量同步开始：${assets.size} 项", "PortfolioVM")
            try {
                AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = assets,
                    onProgress = { },
                    forceRefresh = true
                )
                val result = incomeUseCase.load(assets, forceRefresh = false, tag = "PortfolioVM")
                _estimatedAnnualDividend.value = result.totalAnnualIncome
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("批量同步失败: ${e.message}", "PortfolioVM")
                _errorMessage.value = "批量同步失败"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PortfolioViewModel(container) as T
        }
    }
}