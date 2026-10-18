package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.calculator.MonthlyReturn
import com.example.stockdiary.data.calculator.SnapshotAnalyzer
import com.example.stockdiary.data.calculator.SnapshotStats
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val statistics: SnapshotStats? = null,
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
        // 统计口径全部委托 SnapshotAnalyzer：按真实天数归一，缺快照的日子不再被当成连续数据处理
        val monthlyReturns = SnapshotAnalyzer.calculateMonthlyReturns(filtered)
        val stats = SnapshotAnalyzer.analyze(filtered)
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
