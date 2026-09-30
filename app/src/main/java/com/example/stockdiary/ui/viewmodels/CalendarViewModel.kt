package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.data.DividendIncomeLoader
import com.example.stockdiary.data.DividendRecord
import com.example.stockdiary.data.DividendRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

class CalendarViewModel(
    private val assetRepository: AssetRepository,
    private val dividendRepository: DividendRepository,
    private val snapshotDao: SnapshotDao,
    private val dashboardPreferencesManager: DashboardPreferencesManager
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

    private val incomeLoader = DividendIncomeLoader(dividendRepository)
    private val supportedTypes = listOf(AssetType.STOCK, AssetType.FUND, AssetType.BANK_DEPOSIT)

    init {
        val prefs = dashboardPreferencesManager.loadPreferences()
        _uiState.value = _uiState.value.copy(monthlyExpense = prefs.monthlyExpense)

        viewModelScope.launch {
            assetRepository.getAllAssets()
                .map { assets -> assets.filter { it.type in supportedTypes } }
                .collect { assets ->
                    _uiState.value = _uiState.value.copy(assets = assets)
                    generateCalendarData(assets)
                    loadDividendIncome(assets)
                }
        }
    }

    private fun loadDividendIncome(assets: List<Asset>) {
        if (assets.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = incomeLoader.loadIncome(assets, forceRefresh = false, tag = "CalendarVM")
                _uiState.value = _uiState.value.copy(realAnnualIncome = result.totalAnnualIncome)
            } catch (e: Exception) {
                AppLogger.e("计算分红收入失败: ${e.message}", e, "CalendarVM")
            }
        }
    }

    fun updateMonthlyExpense(expense: String) {
        _uiState.value = _uiState.value.copy(monthlyExpense = expense)
        viewModelScope.launch {
            try {
                val current = dashboardPreferencesManager.loadPreferences()
                dashboardPreferencesManager.savePreferences(current.copy(monthlyExpense = expense))
            } catch (e: Exception) {
                AppLogger.e("保存月度生活费失败: ${e.message}", e, "CalendarVM")
            }
        }
    }

    fun selectTab(tab: String) {
        if (_uiState.value.selectedTab == tab) return
        _uiState.value = _uiState.value.copy(selectedTab = tab)
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets)
        }
    }

    fun selectMonth(month: YearMonth) {
        if (_uiState.value.currentMonth == month) return
        _uiState.value = _uiState.value.copy(currentMonth = month)
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets)
        }
    }

    fun selectDate(date: LocalDate?) {
        _uiState.value = _uiState.value.copy(selectedDate = date)
    }

    fun refresh() {
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets, forceRefresh = true)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private suspend fun generateCalendarData(assets: List<Asset>, forceRefresh: Boolean = false) {
        if (assets.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                calendarData = emptyMap(),
                dividendDates = emptySet(),
                dividends = emptyList(),
                errorMessage = null
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        val requestState = _uiState.value
        val result = withContext(Dispatchers.IO) {
            if (requestState.selectedTab == "资产日历") {
                generateAssetCalendarData(requestState.currentMonth, assets, forceRefresh)
            } else {
                generateIncomeCalendarData(requestState.currentMonth, assets, forceRefresh)
            }
        }
        val currentState = _uiState.value
        if (currentState.selectedTab != requestState.selectedTab || currentState.currentMonth != requestState.currentMonth) {
            return
        }
        result.onSuccess { calendarResult ->
            _uiState.value = currentState.copy(
                isLoading = false,
                calendarData = calendarResult.calendarData,
                dividendDates = calendarResult.dividendDates,
                dividends = calendarResult.dividends,
                errorMessage = null
            )
        }.onFailure { e ->
            _uiState.value = currentState.copy(
                isLoading = false,
                errorMessage = "日历数据生成失败"
            )
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

            val snapshots = snapshotDao.getAllSnapshotsSync()
            val monthSnapshots = snapshots.filter { snapshot ->
                try {
                    val date = LocalDate.parse(snapshot.date)
                    date.year == month.year && date.monthValue == month.monthValue
                } catch (e: Exception) {
                    false
                }
            }
            for (snapshot in monthSnapshots) {
                try {
                    val date = LocalDate.parse(snapshot.date)
                    data[date] = snapshot.netAssets
                } catch (e: Exception) {
                    // ignore
                }
            }

            val symbols = assets.mapNotNull { it.symbol }
            dividendRepository.preFetchDividends(symbols, forceRefresh = forceRefresh)

            val dividendDates = mutableSetOf<LocalDate>()
            val dividends = mutableListOf<Pair<String, List<DividendRecord>>>()

            for (asset in assets) {
                val symbol = asset.symbol ?: continue
                try {
                    val records = dividendRepository.getDividendsBySymbol(symbol)
                    val monthDividends = records.filter { record ->
                        try {
                            val date = LocalDate.parse(record.exDividendDate)
                            date.year == month.year && date.monthValue == month.monthValue
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (monthDividends.isNotEmpty()) {
                        dividends.add(symbol to monthDividends)
                        monthDividends.forEach { record ->
                            try {
                                dividendDates.add(LocalDate.parse(record.exDividendDate))
                            } catch (e: Exception) {
                                AppLogger.w("解析分红日期失败: ${record.exDividendDate}", "CalendarViewModel")
                            }
                        }
                    }
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
            dividendRepository.preFetchDividends(symbols, forceRefresh = forceRefresh)

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
                                val totalAmount = dayRecords.sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 } / 10.0 * asset.balance
                                if (totalAmount > 0) {
                                    data[date] = (data[date] ?: 0.0) + totalAmount
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
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
                dashboardPreferencesManager = container.dashboardPreferencesManager
            ) as T
        }
    }
}
