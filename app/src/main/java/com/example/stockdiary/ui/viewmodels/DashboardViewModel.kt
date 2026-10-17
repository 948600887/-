package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.prefs.DashboardPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.DividendProgressCalculator
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.data.sync.SyncProgress
import com.example.stockdiary.data.sync.SyncResult
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * 刷新/同步路径的兜底异常处理。
     *
     * `viewModelScope` 用的是 SupervisorJob：子协程失败不会牵连兄弟，
     * 但失败本身仍会向上抛。没有 handler 的话，它会一路抛到线程默认的
     * UncaughtExceptionHandler —— 也就是用户看到的「闪退」，而且一行日志都没有。
     *
     * 刷新的失败原因千奇百怪（网络、解析、某个标的的数据特别），
     * 任何一个都不值得让整个 App 死掉：记录日志、提示用户、保持界面可用。
     *
     * 这是**兜底**不是修因。真因仍要靠日志定位，所以这里必须把堆栈打全。
     * （持仓页 PortfolioViewModel 早就挂了这个 handler，资产页一直漏了。）
     */
    private val refreshExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e(
            "刷新过程发生未捕获异常: ${throwable.message}",
            throwable,
            "DashboardVM"
        )
        _errorMessage.value = "刷新没能完成：${throwable.message ?: "未知错误"}"
        _isRefreshing.value = false
    }

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
    val preferences: StateFlow<com.example.stockdiary.data.prefs.DashboardPreferences> = _preferences.asStateFlow()

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

    // 刷新兜底异常的 user-facing 出口：handler 只能记日志，用户需要看到「刷新没成功」
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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

    /**
     * 加载预计收入。
     *
     * ## 为什么进场不再「重新计算」
     *
     * 原先进来就把 `hasLoadedIncome` 重置、重新算一遍，于是每次打开 App
     * 都要看一次「预计年度收入：计算中」——数据根本没变，纯属等待。
     * 触发 key 用的还是资产列表，任何一次价格刷新都会重启效应再算一遍，
     * 而分红跟价格毫无关系。
     *
     * 现在：
     * - 触发 key 改为「持仓代码集合」（见 DashboardScreen），集合不变就不重跑；
     * - 进场先把上次算出的值摆出来、**不进 loading 态**，界面直接显示数字而不是「计算中」；
     * - 之后按 forceRefresh=false 静默取一次（命中库缓存时不出网），
     *   只为补齐「收息进度」需要的明细记录，不占屏。
     *
     * ## 为什么这里不比对「代码集合」来决定要不要取
     *
     * 持仓页可以这么做是因为它每次传入的资产集合口径一致。但首页、持仓页、
     * 以及持仓页的手动刷新，三者传入的资产范围**并不相同**（首页含全部非负债资产、
     * 持仓页只含用户勾选可见的类型、手动刷新只含股票/基金）。
     * 若各自拿自己的集合去比对并回写同一份缓存，就会互相覆盖、永远命中不了，
     * 反而变成每次都重算。所以这里只取「有没有算过」这层判断，
     * 值本身由后台那次静默取数负责校正。
     */
    fun loadDividendIncome(assetsOnly: List<Asset>) {
        if (assetsOnly.isEmpty()) return

        // 上次是否算过：有就先摆出来，别让「计算中」占屏
        val lastValue = _preferences.value.annualDividendCache
        val hasCached = _preferences.value.annualDividendSymbols.isNotEmpty()

        viewModelScope.launch {
            if (hasCached) {
                _realAnnualIncome.value = lastValue
                _estimatedDailyDividend.value = lastValue / 365
                _incomeCalculationStatus.value = null
            }
            // 已有可显示的值时不转圈；只有真没值可显示时才转
            if (!hasLoadedIncome && !hasCached) _dividendLoading.value = true
            try {
                val bankDeposits = assetsOnly.filter { it.type == AssetType.BANK_DEPOSIT }
                _hasInterestData.value = bankDeposits.any { (it.interestRate ?: 0.0) > 0 }

                // cacheOnly：冷启动只读缓存，TTL 过期也照用（30 天硬上限）。
                // 连过期数据都没有时内部会自己走抓取流程，所以首次安装仍然有数可显示
                val result = incomeUseCase.load(
                    assetsOnly,
                    forceRefresh = false,
                    tag = "DashboardVM",
                    cacheOnly = true
                )
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

                // 存下来：下次进场先摆这个值，不用等这一轮算完
                _preferences.update { state ->
                    state.copy(
                        annualDividendCache = result.totalAnnualIncome,
                        annualDividendSymbols = assetsOnly.mapNotNull { it.symbol }.toSet()
                    )
                }
                preferencesManager.savePreferences(_preferences.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("计算收入失败: ${e.message}", e, "DashboardVM")
                // 已经摆出来的旧值别抹成 0
                if (!hasCached) {
                    _realAnnualIncome.value = 0.0
                    _estimatedDailyDividend.value = 0.0
                    _incomeCalculationStatus.value = "计算失败"
                }
            } finally {
                _dividendLoading.value = false
            }
        }
    }

    /**
     * 刷新：强制联网更新有行情资产的最新价，再重算派生收入数据。
     *
     * ## 为什么只有一个刷新按钮
     *
     * 原先是「同步」+「刷新」两个按钮：同步走缓存（forceRefresh=false）、
     * 刷新强制联网。但这个区别在界面上几乎不可感知——60 秒内点「同步」，
     * 网络层全是缓存命中，用户看到的结果和点「刷新」一样，只会困惑
     * "这俩到底啥区别"。而定期同步的职责已由后台 PriceSyncWorker 承担，
     * 手动再给一个轻量入口是重复的。
     *
     * 现在统一为一个「刷新」：强制联网 + 实时进度 + 结果 Snackbar + 刷完重算收入。
     *
     * ## 两个范围，别混
     *
     * - **同步范围** = [AssetType.getPriceSyncedTypes]（STOCK/FUND/CRYPTO）：
     *   银行存款与负债没有行情，传进去会被同步器静默跳过却白占进度分母。
     * - **收入计算范围** = 全部非负债资产：必须含银行存款，否则漏掉利息收入。
     */
    fun refresh() {
        viewModelScope.launch(refreshExceptionHandler) {
            _isRefreshing.value = true
            _errorMessage.value = null
            _syncResult.value = null

            // 两个范围，别混（详见方法 KDoc）：
            // - priceAssets 用于同步，只含有行情的类型；
            // - assetsOnly 用于收入计算，必须含银行存款，否则漏掉利息收入。
            val priceAssets = assets.value.filter { it.type in AssetType.getPriceSyncedTypes() }
            val assetsOnly = assets.value.filter { !it.type.isLiability }

            // 两个阶段分开收尾，理由见下面两处 finally 的注释
            var syncResult: SyncResult? = null
            var syncError: String? = null
            try {
                syncResult = AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = priceAssets,
                    // 实时进度：刷新要跑完上百个标的，只转圈用户无从判断是在干活还是卡死
                    onProgress = { _syncProgress.value = it },
                    forceRefresh = true
                )
                AppLogger.i(
                    "价格同步完成（成功 ${syncResult.successCount}，失败 ${syncResult.failedCount}）",
                    "DashboardVM"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 不捕获的话 _isRefreshing 永远停在 true，后续刷新全被挡死、界面一直转圈
                AppLogger.w("同步失败: ${e.message}", "DashboardVM")
                // 静默是最糟的反馈：用户点了刷新，转圈停下，什么也没发生
                syncError = e.message ?: "未知错误"
            } finally {
                // 价格同步这一段结束就把进度清掉：后面的收入计算是另一件事。
                // 不清的话标题会一直挂着「10 / 10」而转圈还在继续，看着像卡死
                _syncProgress.value = null
            }

            try {
                // 这条是「同步完成后再算一次收入」，**不能** cacheOnly：
                // 用户刚刷新过行情，需要的是最新分红，不是缓存里的旧值。
                // 传 assetsOnly（含银行存款）而不是 priceAssets，否则漏掉利息收入。
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
                // 结果反馈统一放到最后：转圈停下与提示出现应当同时发生。
                // 原先同步一结束就置 _syncResult，Snackbar 弹「刷新完成」时转圈还在
                // 为收入计算而转，用户会看到「已完成」的提示和一个仍在转的按钮，自相矛盾
                if (_errorMessage.value == null) {
                    when {
                        syncError != null -> _errorMessage.value = "刷新失败：$syncError"
                        // 时间预算兜底：超时不抛错，但要让用户知道「这次没跑完」，
                        // 看到的是保留的上次数据，而不是转圈到天荒地老
                        syncResult?.truncatedByTimeLimit == true ->
                            _errorMessage.value = "同步超时，已保留上次数据"
                    }
                }
                _syncResult.value = syncResult
                _isRefreshing.value = false
            }
        }
    }

    fun clearSyncResult() {
        _syncResult.value = null
        _syncProgress.value = null
    }

    fun clearError() {
        _errorMessage.value = null
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