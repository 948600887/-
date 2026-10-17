package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

class HistoryViewModel(
    private val assetRepository: AssetRepository,
    dashboardPreferencesManager: DashboardPreferencesManager
) : ViewModel() {

    companion object {
        private val VALID_PERIODS = setOf("1W", "1M", "3M", "6M", "1Y", "ALL")
    }

    data class UiState(
        val assets: List<Asset> = emptyList(),
        val snapshots: List<AssetSnapshot> = emptyList(),
        val filteredSnapshots: List<AssetSnapshot> = emptyList(),
        val monthlyReturns: List<MonthlyReturn> = emptyList(),
        val statistics: SnapshotStatistics? = null,
        val selectedPeriod: String = "1M",
        val hasTodaySnapshot: Boolean = false,
        val isSaving: Boolean = false,
        val saveResult: Result<Unit>? = null,
        val errorMessage: String? = null
    )

    private val defaultPeriod = dashboardPreferencesManager.loadPreferences().defaultPeriod
        .takeIf { it in VALID_PERIODS } ?: "1M"

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(defaultPeriod)

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    init {
        // 只在资产或快照数据变化时排序和更新状态
        combine(
            assetRepository.getAllAssets(),
            assetRepository.getAllSnapshots()
        ) { assets, snapshots ->
            assets to snapshots.sortedBy { it.timestamp }
        }
            .distinctUntilChanged()
            .onEach { (assets, sortedSnapshots) ->
                val period = _selectedPeriod.value
                rebuildState(
                    assets = assets,
                    sortedSnapshots = sortedSnapshots,
                    period = period
                )
            }
            .launchIn(viewModelScope)

        // 只在周期变化时重新过滤和计算周期相关统计（StateFlow 本身已去重）
        _selectedPeriod
            .onEach { period ->
                val current = _uiState.value
                rebuildState(
                    assets = current.assets,
                    sortedSnapshots = current.snapshots,
                    period = period
                )
            }
            .launchIn(viewModelScope)
    }

    fun selectPeriod(period: String) {
        _selectedPeriod.value = period
    }

    fun saveSnapshot() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveResult = null, errorMessage = null) }
            val result = runCatching {
                val today = LocalDate.now().format(dateFormatter)
                assetRepository.createSnapshot(today) ?: error("创建快照失败")
            }.map { }
            // runCatching 会把协程取消也吞掉，必须重新抛出保证取消语义
            result.onFailure { if (it is CancellationException) throw it }
            result.onSuccess {
                AppLogger.i("Snapshot saved successfully", "HistoryVM")
            }.onFailure { e ->
                AppLogger.e("Failed to save snapshot: ${e.message}", e, "HistoryVM")
            }
            _uiState.update { current ->
                current.copy(
                    isSaving = false,
                    saveResult = result,
                    errorMessage = if (result.isFailure) {
                        result.exceptionOrNull()?.message ?: "保存失败"
                    } else null
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.Default) {
            val current = _uiState.value
            rebuildState(
                assets = current.assets,
                sortedSnapshots = current.snapshots,
                period = current.selectedPeriod,
                clearError = true
            )
            AppLogger.d("History data refreshed", "HistoryVM")
        }
    }

    private suspend fun rebuildState(
        assets: List<Asset>,
        sortedSnapshots: List<AssetSnapshot>,
        period: String,
        clearError: Boolean = false
    ) {
        val filtered = filterSnapshots(sortedSnapshots, period)
        val monthlyReturns = calculateMonthlyReturns(filtered)
        val stats = calculateStatistics(filtered)
        val today = LocalDate.now().format(dateFormatter)
        val hasToday = sortedSnapshots.any { it.date == today }
        _uiState.update { current ->
            current.copy(
                assets = assets,
                snapshots = sortedSnapshots,
                filteredSnapshots = filtered,
                monthlyReturns = monthlyReturns,
                statistics = stats,
                selectedPeriod = period,
                hasTodaySnapshot = hasToday,
                errorMessage = if (clearError) null else current.errorMessage
            )
        }
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveResult = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return HistoryViewModel(
                assetRepository = container.assetRepository,
                dashboardPreferencesManager = container.dashboardPreferencesManager
            ) as T
        }
    }
}

data class SnapshotStatistics(
    val maxValue: Double,
    val minValue: Double,
    val totalSnapshots: Int,
    val maxDrawdown: Double,
    /** 日均增幅（百分比）：净资产相对上一期快照的日均变化，不是收益率 */
    val avgDailyReturnPercent: Double,
    /** 日均增值额 */
    val avgDailyReturnAmount: Double,
    val volatility: Double
)

data class MonthlyReturn(
    val month: YearMonth,
    val returnAmount: Double,
    val returnRate: Double
)

private fun filterSnapshots(snapshots: List<AssetSnapshot>, period: String): List<AssetSnapshot> {
    if (snapshots.isEmpty()) return emptyList()
    val now = LocalDate.now()
    val startTime = when (period) {
        "1W" -> now.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "1M" -> now.minusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "3M" -> now.minusMonths(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "6M" -> now.minusMonths(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "1Y" -> now.minusYears(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "ALL" -> 0L
        else -> now.minusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    // 先过滤时间范围，再按日期去重保留最新一条（兼容历史脏数据）
    return snapshots
        .filter { it.timestamp >= startTime }
        .sortedByDescending { it.timestamp }
        .distinctBy { it.date }
        .sortedBy { it.timestamp }
}

private fun calculateStatistics(snapshots: List<AssetSnapshot>): SnapshotStatistics? {
    if (snapshots.size < 2) return null
    val values = snapshots.map { it.netAssets }
    val maxValue = values.maxOrNull() ?: 0.0
    val minValue = values.minOrNull() ?: 0.0
    var maxDrawdown = 0.0
    var peak = values.first()
    for (value in values) {
        if (value > peak) peak = value
        val drawdown = if (peak > 0) ((peak - value) / peak) * 100 else 0.0
        if (drawdown > maxDrawdown) maxDrawdown = drawdown
    }
    val dailyReturns = mutableListOf<Double>()
    val dailyReturnAmounts = mutableListOf<Double>()
    for (i in 1 until values.size) {
        val prev = values[i - 1]
        val amount = values[i] - prev
        dailyReturnAmounts.add(amount)
        if (prev > 0) dailyReturns.add((amount / prev) * 100)
    }
    val avgDailyReturnPercent = if (dailyReturns.isNotEmpty()) dailyReturns.average() else 0.0
    val avgDailyReturnAmount = if (dailyReturnAmounts.isNotEmpty()) dailyReturnAmounts.average() else 0.0
    val volatility = if (dailyReturns.isNotEmpty()) {
        val mean = dailyReturns.average()
        sqrt(dailyReturns.map { (it - mean) * (it - mean) }.average())
    } else 0.0
    return SnapshotStatistics(
        maxValue = maxValue,
        minValue = minValue,
        totalSnapshots = snapshots.size,
        maxDrawdown = maxDrawdown,
        avgDailyReturnPercent = avgDailyReturnPercent,
        avgDailyReturnAmount = avgDailyReturnAmount,
        volatility = volatility
    )
}

private fun calculateMonthlyReturns(sortedSnapshots: List<AssetSnapshot>): List<MonthlyReturn> {
    if (sortedSnapshots.size < 2) return emptyList()
    val monthMap = linkedMapOf<YearMonth, AssetSnapshot>()
    for (snapshot in sortedSnapshots) {
        val date = try {
            LocalDate.parse(snapshot.date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            continue
        }
        monthMap[YearMonth.from(date)] = snapshot
    }
    if (monthMap.size < 2) return emptyList()
    val entries = monthMap.entries.toList()
    val result = mutableListOf<MonthlyReturn>()
    for (i in 1 until entries.size) {
        val prev = entries[i - 1].value
        val curr = entries[i].value
        val returnAmount = curr.netAssets - prev.netAssets
        val returnRate = if (prev.netAssets > 0) (returnAmount / prev.netAssets) * 100 else 0.0
        result.add(MonthlyReturn(entries[i].key, returnAmount, returnRate))
    }
    return result
}
