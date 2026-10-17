package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.sync.SyncBudget
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.perTenSharesToAmount
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

class CalendarViewModel(
    private val assetRepository: AssetRepository,
    private val dividendRepository: DividendRepository,
    private val snapshotDao: SnapshotDao,
    private val dashboardPreferencesManager: DashboardPreferencesManager,
    private val dividendIncomeUseCase: DividendIncomeUseCase
) : ViewModel() {

    data class UiState(
        val selectedTab: String = "资产日历",
        val currentMonth: YearMonth = YearMonth.now(),
        val selectedDate: LocalDate? = null,
        val calendarData: Map<LocalDate, Double> = emptyMap(),
        val dividendDates: Set<LocalDate> = emptySet(),
        val dividends: List<Pair<String, List<DividendRecord>>> = emptyList(),
        val assets: List<Asset> = emptyList(),
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val monthlyExpense: String = "5000",
        val realAnnualIncome: Double = 0.0
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val supportedTypes = listOf(AssetType.STOCK, AssetType.FUND, AssetType.BANK_DEPOSIT)

    init {
        val prefs = dashboardPreferencesManager.loadPreferences()
        _uiState.update { state -> state.copy(monthlyExpense = prefs.monthlyExpense) }

        viewModelScope.launch {
            assetRepository.getAllAssets()
                .map { assets -> assets.filter { it.type in supportedTypes } }
                .collect { assets ->
                    _uiState.update { state -> state.copy(assets = assets) }
                    generateCalendarData(assets)
                    loadDividendIncome(assets)
                }
        }
    }

    private fun loadDividendIncome(assets: List<Asset>) {
        if (assets.isEmpty()) return
        viewModelScope.launch {
            try {
                // cacheOnly：冷启动只读缓存，不为了「预计收入」去敲几十个接口。
                // 连过期数据都没有（首次安装）时内部会自己走抓取流程
                val result = dividendIncomeUseCase.load(
                    assets,
                    forceRefresh = false,
                    tag = "CalendarVM",
                    cacheOnly = true
                )
                _uiState.update { state -> state.copy(realAnnualIncome = result.totalAnnualIncome) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("计算分红收入失败: ${e.message}", e, "CalendarVM")
            }
        }
    }

    fun updateMonthlyExpense(expense: String) {
        _uiState.update { state -> state.copy(monthlyExpense = expense) }
        viewModelScope.launch {
            try {
                val current = dashboardPreferencesManager.loadPreferences()
                dashboardPreferencesManager.savePreferences(current.copy(monthlyExpense = expense))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存月度生活费失败: ${e.message}", e, "CalendarVM")
            }
        }
    }

    fun selectTab(tab: String) {
        if (_uiState.value.selectedTab == tab) return
        _uiState.update { state -> state.copy(selectedTab = tab) }
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets)
        }
    }

    fun selectMonth(month: YearMonth) {
        if (_uiState.value.currentMonth == month) return
        _uiState.update { state -> state.copy(currentMonth = month) }
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets)
        }
    }

    fun selectDate(date: LocalDate?) {
        _uiState.update { state -> state.copy(selectedDate = date) }
    }

    fun refresh() {
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets, forceRefresh = true)
        }
    }

    fun clearError() {
        _uiState.update { state -> state.copy(errorMessage = null) }
    }

    private suspend fun generateCalendarData(assets: List<Asset>, forceRefresh: Boolean = false) {
        if (assets.isEmpty()) {
            _uiState.update { state -> state.copy(
                isLoading = false,
                calendarData = emptyMap(),
                dividendDates = emptySet(),
                dividends = emptyList(),
                errorMessage = null
            ) }
            return
        }
        _uiState.update { state -> state.copy(isLoading = true, errorMessage = null) }
        val requestState = _uiState.value
        // 顶层兜底：内部虽返回 Result，但 withContext 链路上的意外异常不能让 viewModelScope 崩溃
        val result = try {
            withContext(Dispatchers.IO) {
                if (requestState.selectedTab == "资产日历") {
                    generateAssetCalendarData(requestState.currentMonth, assets, forceRefresh)
                } else {
                    generateIncomeCalendarData(requestState.currentMonth, assets, forceRefresh)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("生成日历数据异常: ${e.message}", e, "CalendarVM")
            _uiState.update { state -> state.copy(isLoading = false, errorMessage = "日历数据加载失败，请重试") }
            return
        }
        val currentState = _uiState.value
        if (currentState.selectedTab != requestState.selectedTab || currentState.currentMonth != requestState.currentMonth) {
            return
        }
        result.onSuccess { calendarResult ->
            // 用 update{} 原子读-改-写：不能直接拿上面读到的 currentState 去 copy，
            // 期间 loadDividendIncome 等协程可能已经写过 uiState，那会把它们的更新整体冲掉
            _uiState.update { state -> state.copy(
                isLoading = false,
                calendarData = calendarResult.calendarData,
                dividendDates = calendarResult.dividendDates,
                dividends = calendarResult.dividends,
                errorMessage = null
            ) }
        }.onFailure { e ->
            _uiState.update { state -> state.copy(
                isLoading = false,
                errorMessage = "日历数据生成失败"
            ) }
            AppLogger.e("生成日历数据失败: ${e.message}", e, "CalendarViewModel")
        }
    }

    private data class CalendarResult(
        val calendarData: Map<LocalDate, Double>,
        val dividendDates: Set<LocalDate>,
        val dividends: List<Pair<String, List<DividendRecord>>>
    )

    private suspend fun generateAssetCalendarData(
        month: YearMonth,
        assets: List<Asset>,
        forceRefresh: Boolean
    ): Result<CalendarResult> = try {
        Result.success(withContext(Dispatchers.IO) {
            val data = mutableMapOf<LocalDate, Double>()
            for (day in 1..month.lengthOfMonth()) {
                data[month.atDay(day)] = 0.0
            }

            if (assets.isEmpty()) {
                return@withContext CalendarResult(data, emptySet(), emptyList())
            }

            // 只 parse 一次：筛当月和取日期用的是同一个 LocalDate。
            // 原先 filter 里 parse 一遍、下面的循环再 parse 一遍同一批字符串。
            val monthSnapshots = snapshotDao.getAllSnapshotsSync().mapNotNull { snapshot ->
                val date = try {
                    LocalDate.parse(snapshot.date)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (date != null && date.year == month.year && date.monthValue == month.monthValue) {
                    date to snapshot.netAssets
                } else {
                    null
                }
            }
            for ((date, netAssets) in monthSnapshots) {
                data[date] = netAssets
            }

            val symbols = assets.mapNotNull { it.symbol }
            // 必须带时间预算：不传就是不设限，而这条路径会强制重抓全部标的的分红
            // （refresh() 传 forceRefresh=true）。每个请求最坏 15s 读超时 ×3 次重试，
            // 十几个场外基金足以把日历页拖到两分多钟且无法中止 —— 和「点刷新卡死」
            // 是同一类病，只是没那么致命。超时后跳过的标的不会写缓存，之后按需抓取
            // 还会补上，不丢数据
            dividendRepository.preFetchDividends(
                symbols = symbols,
                forceRefresh = forceRefresh,
                timeBudgetMs = SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS
            )

            val dividendDates = mutableSetOf<LocalDate>()
            val dividends = mutableListOf<Pair<String, List<DividendRecord>>>()

            for (asset in assets) {
                val symbol = asset.symbol ?: continue
                try {
                val records = dividendRepository.getDividendsBySymbol(symbol)
                // 照样只 parse 一次：筛当月和记分红日用的是同一个 LocalDate。
                // 原先 filter 里 parse 一遍、下面记日期时又 parse 一遍同样的记录。
                val monthEntries = records.mapNotNull { record ->
                    val date = try {
                        LocalDate.parse(record.exDividendDate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (date == null) {
                        AppLogger.w("解析分红日期失败: ${record.exDividendDate}", "CalendarViewModel")
                        return@mapNotNull null
                    }
                    if (date.year == month.year && date.monthValue == month.monthValue) {
                        date to record
                    } else {
                        null
                    }
                }
                if (monthEntries.isNotEmpty()) {
                    dividends.add(symbol to monthEntries.map { it.second })
                    monthEntries.forEach { (date, _) -> dividendDates.add(date) }
                }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("获取 ${asset.name} 分红记录失败: ${e.message}", "CalendarViewModel")
                }
            }

            AppLogger.d(
                "生成资产日历: ${month.year}年${month.monthValue}月, 快照${monthSnapshots.size}天, 分红日${dividendDates.size}个",
                "CalendarViewModel"
            )

            CalendarResult(data, dividendDates, dividends)
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun generateIncomeCalendarData(
        month: YearMonth,
        assets: List<Asset>,
        forceRefresh: Boolean
    ): Result<CalendarResult> = try {
        Result.success(withContext(Dispatchers.IO) {
            val data = mutableMapOf<LocalDate, Double>()
            for (day in 1..month.lengthOfMonth()) {
                data[month.atDay(day)] = 0.0
            }

            val symbols = assets.mapNotNull { it.symbol }
            // 同 generateAssetCalendarData：不带预算就是不设限，弱网下能把日历页
            // 拖到几分钟且无法中止。超时跳过的标的不写缓存，按需抓取会补上
            dividendRepository.preFetchDividends(
                symbols = symbols,
                forceRefresh = forceRefresh,
                timeBudgetMs = SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS
            )

            val dividendDates = mutableSetOf<LocalDate>()
            val dividends = mutableListOf<Pair<String, List<DividendRecord>>>()

            for (asset in assets) {
                val symbol = asset.symbol ?: continue
                try {
                    val records = dividendRepository.getDividendsBySymbol(symbol)
                    val monthRecords = records.filter { record ->
                        try {
                            val date = LocalDate.parse(record.exDividendDate)
                            date.year == month.year && date.monthValue == month.monthValue
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    }

                    if (monthRecords.isNotEmpty()) {
                        dividends.add(symbol to monthRecords)

                        monthRecords.groupBy { it.exDividendDate }.forEach { (dateStr, dayRecords) ->
                            try {
                                val date = LocalDate.parse(dateStr)
                                dividendDates.add(date)
                                val totalAmount = perTenSharesToAmount(
                                    dayRecords.sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 },
                                    asset.balance
                                )
                                if (totalAmount > 0) {
                                    data[date] = (data[date] ?: 0.0) + totalAmount
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("获取 ${asset.name} 分红记录失败: ${e.message}", "CalendarViewModel")
                }
            }

            AppLogger.d(
                "生成收支日历: ${month.year}年${month.monthValue}月, 分红记录${dividends.sumOf { it.second.size }}条",
                "CalendarViewModel"
            )

            CalendarResult(data, dividendDates, dividends)
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CalendarViewModel(
                assetRepository = container.assetRepository,
                dividendRepository = container.dividendRepository,
                snapshotDao = container.database.snapshotDao(),
                dashboardPreferencesManager = container.dashboardPreferencesManager,
                dividendIncomeUseCase = container.dividendIncomeUseCase
            ) as T
        }
    }
}
