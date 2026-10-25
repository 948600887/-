package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.common.DateFormats
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.calculator.MonthlyReturn
import com.example.stockdiary.data.calculator.SNAPSHOT_ORDER
import com.example.stockdiary.data.calculator.SnapshotAnalyzer
import com.example.stockdiary.data.calculator.SnapshotStats
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.isDisplayEquivalentTo
import com.example.stockdiary.utils.toLocalDate
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

        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(
                    assetRepository = container.assetRepository,
                    dashboardPreferencesManager = container.dashboardPreferencesManager
                )
            }
        }
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

    private val dateFormatter = DateFormats.ISO_DATE_FORMATTER

    init {
        // 只在资产或快照数据变化时排序和更新状态
        combine(
            assetRepository.getAllAssets(),
            assetRepository.getAllSnapshots()
        ) { assets, snapshots ->
            // 排序口径必须是 date（用户可见），不能是创建时刻 timestamp：
            // 回填补出来的幽灵快照 date 与 timestamp 错位，按 timestamp 排会把
            // 10/15 的行扔到 10/16 后面，整页顺序全乱。详见 SNAPSHOT_ORDER 注释
            assets to snapshots.sortedWith(SNAPSHOT_ORDER)
        }
            // 默认的 distinctUntilChanged 用 Pair/Asset 全字段 equals，而 Asset 含
            // lastSyncTime/lastUpdateTime——落价时它们必变，于是每次都判定"变了"并走一遍
            // rebuildState（含 3 次排序 + 波动率/最大回撤等 O(n) 统计）。
            // 改成按"界面真正依赖的字段"比较，只有内容真变了才重建。
            .distinctUntilChanged { a, b ->
                a.first.isDisplayEquivalentTo(b.first) && a.second == b.second
            }
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
}

/**
 * 按周期过滤快照。窗口口径是 **date 字段**，不是创建时刻（timestamp）：
 * 用户看到的 X 轴刻度、期间增值起止、日历全是 date，过滤必须与它一致。
 * 按 timestamp 过滤会在两者错位时说谎——回填补出来的幽灵快照
 * （date 是被补的那天、timestamp 是补的时刻）会让「1周」放出 10 天数据
 * （2026-10-24 用户实测复现，快照 10/15、10/16 的 timestamp 晚到 10/17+）。
 *
 * date 解析不了的脏数据退回创建时刻比较，不静默丢弃。
 *
 * @param now 「今天」，可注入以便单测钉死窗口边界
 */
internal fun filterSnapshots(
    snapshots: List<AssetSnapshot>,
    period: String,
    now: LocalDate = LocalDate.now()
): List<AssetSnapshot> {
    if (snapshots.isEmpty()) return emptyList()
    val startDate: LocalDate? = when (period) {
        "1W" -> now.minusDays(7)
        "1M" -> now.minusMonths(1)
        "3M" -> now.minusMonths(3)
        "6M" -> now.minusMonths(6)
        "1Y" -> now.minusYears(1)
        "ALL" -> null
        else -> now.minusMonths(1)
    }
    // date 是脏数据时退回创建时刻比较的窗口起点；ALL（startDate == null）走不到这条
    val startTime = startDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L
    // 先过滤时间范围，再按日期去重保留最新一条（兼容历史脏数据）
    return snapshots
        .filter { snapshot ->
            startDate == null || snapshot.isOnOrAfter(startDate, startTime)
        }
        .sortedByDescending { it.timestamp }
        .distinctBy { it.date }
        // 最终顺序同样按 date（同日多份已去重，理论上只剩创建时刻做次序）
        .sortedWith(SNAPSHOT_ORDER)
        // 最后踢掉开头「正资产为 0」的空快照（无资产时 App 自动补的），
        // 图表/统计/期间增值/月度收益全部从这里取数，一处剔除全页生效
        .let { SnapshotAnalyzer.dropLeadingEmptySnapshots(it) }
}

/** 快照是否落在 [startDate] 当天或之后；date 是脏数据时退回创建时刻判断 */
private fun AssetSnapshot.isOnOrAfter(startDate: LocalDate, startTime: Long): Boolean {
    val snapshotDate = date.toLocalDate()
    return if (snapshotDate != null) {
        !snapshotDate.isBefore(startDate)
    } else {
        timestamp >= startTime
    }
}
