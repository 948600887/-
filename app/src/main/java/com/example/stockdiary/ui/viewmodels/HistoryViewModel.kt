package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.common.DateFormats
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.calculator.MonthlyReturn
import com.example.stockdiary.data.calculator.SnapshotAmountEditor
import com.example.stockdiary.data.calculator.SnapshotAmountInput
import com.example.stockdiary.data.calculator.SnapshotAnalyzer
import com.example.stockdiary.data.calculator.SnapshotStats
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.prefs.SnapshotManualAdjustmentStore
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.isDisplayEquivalentTo
import com.example.stockdiary.utils.toLocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val assetRepository: AssetRepository,
    private val dashboardPreferencesManager: DashboardPreferencesManager,
    private val snapshotManualAdjustmentStore: SnapshotManualAdjustmentStore
) : ViewModel() {

    companion object {
        private val VALID_PERIODS = setOf("1W", "1M", "3M", "6M", "1Y", "ALL")

        /** 「全部」周期的分页大小：全量数据按此逐批读取/发出，避免首帧拉整表 */
        private const val ALL_PAGE_SIZE = 500

        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(
                    assetRepository = container.assetRepository,
                    dashboardPreferencesManager = container.dashboardPreferencesManager,
                    snapshotManualAdjustmentStore = container.snapshotManualAdjustmentStore
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
        /** 保存/写操作的错误（Snackbar 用），与页面级 [loadError] 分开 */
        val errorMessage: String? = null,
        /** 首次读取（Room 首帧）是否已到；false 时页面显示加载态而不是「暂无历史数据」 */
        val snapshotsFrameReady: Boolean = false,
        /** 正在读取/重试；首次进页与点「重试」时为 true */
        val isLoading: Boolean = true,
        /** 读取流自身的异常（磁盘/Room）。与保存失败无关，页面级重试由 [refresh] 触发 */
        val loadError: String? = null,
        /**
         * 库里是否有**任何**快照（与当前周期无关）。
         *
         * 空态文案要区分「一条快照都没有」和「有快照但被当前周期滤掉了」。
         * [snapshots] 现在只装当前周期的行，用它判断会把「1周内没有」误报成「没有任何历史」，
         * 所以计数单独来自 DAO（见 [SnapshotWindow.totalCount]）。
         */
        val hasAnySnapshot: Boolean = false
    )

    /** 当前周期查询结果 + 全库快照计数（计数只用于空态文案，不参与任何统计） */
    private data class SnapshotWindow(
        /** 当前周期**展示**行：只含窗口内的快照（不含窗口前基准点） */
        val rows: List<AssetSnapshot>,
        /**
         * 窗口前最近的一条基准点，**只参与区间计算**（目前是月度环比的首月基期），
         * 不进图表/列表/期间增值——否则「1周」会重新放出窗口外那一天的数据。
         */
        val base: AssetSnapshot?,
        val totalCount: Int
    )

    private val defaultPeriod = dashboardPreferencesManager.preferences.value.defaultPeriod
        .takeIf { it in VALID_PERIODS } ?: "1M"

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 用户偏好（含 [com.example.stockdiary.data.prefs.DashboardPreferences.showHistoryMonthlyGrowth]
     * 等历史页相关开关）。直接复用 Manager 的全应用唯一偏好流，设置页改完开关立即推送过来，
     * 不再持有本地副本、也不需要 reload 补丁。
     */
    val preferences: StateFlow<com.example.stockdiary.data.prefs.DashboardPreferences> =
        dashboardPreferencesManager.preferences

    /**
     * 快照管理列表只在弹窗打开时订阅全表；关掉即停，不把「全量」带进日常历史页读取。
     */
    private val _snapshotManagerVisible = MutableStateFlow(false)

    val managerSnapshots: StateFlow<List<AssetSnapshot>> = _snapshotManagerVisible
        .flatMapLatest { visible ->
            if (!visible) {
                flowOf(emptyList())
            } else {
                assetRepository.getAllSnapshots().map { SnapshotAnalyzer.normalizeForDisplay(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 手动修正过的快照日期：列表与趋势图据此显示「手动修正」 */
    val manualAdjustedDates: StateFlow<Set<String>> = snapshotManualAdjustmentStore.dates

    private val _selectedPeriod = MutableStateFlow(defaultPeriod)

    /** 用户是否在本次会话内手动选过周期；选过就不再被「默认周期」覆盖（见 [reloadDefaultPeriod]） */
    private var periodChosenByUser = false

    private val dateFormatter = DateFormats.ISO_DATE_FORMATTER

    /**
     * 读取流的重试触发器。每次自增就取消旧订阅、重新订阅一次，
     * 用来把 [refresh]（重试按钮）接进读取链路，而不是只把旧状态重算一遍。
     */
    private val retryTrigger = MutableStateFlow(0)

    init {
        // 资产、快照、周期合并成**一条**流：原来两个 collector 各自写 _uiState，
        // 周期变化与数据变化并发时会互相覆盖（后者拿到的 assets 可能还是旧值），
        // 且周期切换路径无法感知读取异常。合并后只有这一处写业务字段。
        viewModelScope.launch {
            retryTrigger.collectLatest {
                // 周期变化会取消旧周期查询、换一条新的 Room 查询，而不是继续把整表拉回来内存过滤
                _selectedPeriod.collectLatest { period ->
                    // 快照流已按 date 排序、同日去重并丢掉前导空快照（见 snapshotsFlowFor），
                    // 口径与历史页展示完全一致
                    combine(
                        assetRepository.getAllAssets(),
                        snapshotsFlowFor(period)
                    ) { assets, window -> assets to window }
                        // 默认的 distinctUntilChanged 用全字段 equals，而 Asset 含
                        // lastSyncTime/lastUpdateTime——落价时它们必变，于是每次都判定"变了"并走一遍
                        // rebuildState（含月度/波动率/最大回撤等 O(n) 统计）。
                        // 改成按"界面真正依赖的字段"比较，只有内容真变了才重建。
                        .distinctUntilChanged { a, b ->
                            a.first.isDisplayEquivalentTo(b.first) && a.second == b.second
                        }
                        // 读取流异常不能把整页永远停在加载态：写页面级错误后结束本次订阅，
                        // 用户点「重试」由 retryTrigger 重新订阅
                        .catch { e ->
                            if (e is CancellationException) throw e
                            AppLogger.e("读取历史数据失败: ${e.message}", e, "HistoryVM")
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    snapshotsFrameReady = true,
                                    loadError = "读取历史数据失败，请重试"
                                )
                            }
                        }
                        .collect { (assets, window) ->
                            rebuildState(
                                assets = assets,
                                window = window,
                                period = period
                            )
                        }
                }
            }
        }

        // 跨午夜重算：hasTodaySnapshot（「今日已保存」）与周期窗口都以进程内的「今天」为准。
        // App 挂到零点后不重算的话，标记与窗口会停在昨天。
        viewModelScope.launch {
            while (isActive) {
                val now = LocalDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
                delay(Duration.between(now, nextMidnight).toMillis() + 1000)
                refreshForNewDay()
            }
        }
    }

    fun selectPeriod(period: String) {
        // 用户在本页手动选过周期后，本次会话内不再被「默认周期」覆盖
        periodChosenByUser = true
        _selectedPeriod.value = period
    }

    /**
     * 应用设置页的「默认历史周期」。
     *
     * 本 VM 挂 Activity 作用域、默认周期只在构造时读一次；用户在设置里改了默认周期后，
     * 进历史页需要重新应用。偏好的唯一流在设置页写入时已同步更新，这里直接读内存即可，
     * 不需要再读磁盘。用户已经在本次会话内手动点过周期 chip 的，不覆盖他的选择。
     */
    fun reloadDefaultPeriod() {
        if (periodChosenByUser) return
        val period = dashboardPreferencesManager.preferences.value.defaultPeriod
            .takeIf { it in VALID_PERIODS } ?: "1M"
        if (!periodChosenByUser) _selectedPeriod.value = period
    }

    fun saveSnapshot() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveResult = null, errorMessage = null) }
            val result = runCatching {
                val today = LocalDate.now().format(dateFormatter)
                // createSnapshot 在「无持仓」时返回 null 表示**主动跳过**（不再删任何东西），
                // 文案要说出真实原因，而不是笼统的「创建快照失败」让用户以为系统出错、
                // 反复重试。真正的失败会抛异常（createSnapshot 已不再吞异常）。
                // 未知币种拒存也由 createSnapshot 统一校验，这里不重复实现。
                assetRepository.createSnapshot(today) ?: error("当天没有持仓，未保存快照")
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

    /**
     * 页面级「重试」：重新订阅读取流，而不是拿旧状态重算一遍。
     * 只重置加载态与页面错误，资产/快照数据本身由 Room 重新查询。
     */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        retryTrigger.value++
        AppLogger.d("History data refresh requested", "HistoryVM")
    }

    /**
     * 回前台保鲜：跨天后「今日已保存」标记与周期窗口都要跟着新日期重算。
     * 进程被 Doze 冻结时午夜协程可能被推迟，回前台这一刻补查一次。
     */
    fun onScreenResumed() {
        reloadDefaultPeriod()
        refreshForNewDay()
    }

    /**
     * 跨天/回前台：重新订阅读取流，让「周期窗口起点」和「今天」按新日期重算。
     * 仅用内存旧列表重算不够——窗口下界仍是昨天的，Room 查询需要带上新的日期重新执行。
     * 不置 isLoading：已有数据时安静刷新，避免回前台闪一屏加载态。
     */
    private fun refreshForNewDay() {
        if (!_uiState.value.snapshotsFrameReady) return
        retryTrigger.value++
    }

    /**
     * 所选周期对应的快照流。
     *
     * - 非「全部」：**按周期查询**窗口内的行，并额外取窗口前最近的一条做基准点
     *   （用于期间增值/月度环比的基期，不把整表拉回内存再过滤）；
     * - 「全部」：全量 + 分批（分页读取，逐批发出，界面滚动前就能先看到前几批）。
     *
     * date 非法的历史脏数据仍按 timestamp 兜底纳入窗口，不静默丢弃。
     */
    private fun snapshotsFlowFor(period: String): Flow<SnapshotWindow> {
        val startDate = periodWindowStart(period)
        if (startDate == null) return allSnapshotsPagedFlow()

        val start = startDate.toString()
        val today = LocalDate.now().toString()
        return combine(
            assetRepository.getSnapshotsInDateRange(start, today),
            assetRepository.getInvalidDateSnapshots(),
            assetRepository.getSnapshotBeforeDate(start),
            assetRepository.getSnapshotCountFlow()
        ) { inRange, invalidDate, baseline, totalCount ->
            val dirtyInWindow = invalidDate.filter { isSnapshotInWindow(it, period) }
            SnapshotWindow(
                // 只展示窗口内的行；基准点单独放 base，避免「1周」放出窗口外那一天
                rows = SnapshotAnalyzer.normalizeForDisplay(inRange + dirtyInWindow),
                base = baseline,
                totalCount = totalCount
            )
        }
    }

    /**
     * 「全部」周期：全量读取但分批发出。
     *
     * Room 的 Flow 查询会把整表一次性送回来；历史数据多时首帧很重。这里用
     * COUNT/MAX(timestamp) 作失效信号，每次失效后按页（[ALL_PAGE_SIZE]）顺序读取并
     * 逐批 emit，界面可以先用前几批画图。快照写入仍会触发一次整体分页重读，
     * 但计算只在这条周期流上做，不再像旧实现那样对全表反复重算所有周期。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun allSnapshotsPagedFlow(): Flow<SnapshotWindow> =
        combine(
            assetRepository.getSnapshotCountFlow(),
            assetRepository.getSnapshotMaxTimestampFlow()
        ) { count, maxTimestamp -> count to maxTimestamp }
            .flatMapLatest { (count, _) ->
                flow {
                    val accumulated = mutableListOf<AssetSnapshot>()
                    var offset = 0
                    while (true) {
                        val page = assetRepository.getSnapshotsPage(ALL_PAGE_SIZE, offset)
                        if (page.isEmpty()) break
                        accumulated += page
                        val visible = accumulated.filter { isSnapshotInWindow(it, "ALL") }
                        emit(
                            SnapshotWindow(
                                rows = SnapshotAnalyzer.normalizeForDisplay(visible),
                                base = null,
                                totalCount = count
                            )
                        )
                        if (page.size < ALL_PAGE_SIZE) break
                        offset += ALL_PAGE_SIZE
                    }
                    if (accumulated.isEmpty()) {
                        emit(SnapshotWindow(emptyList(), base = null, totalCount = count))
                    }
                }
            }

    private suspend fun rebuildState(
        assets: List<Asset>,
        window: SnapshotWindow,
        period: String
    ) {
        // 过滤 + 统计是 O(n) 计算（排序、波动率、最大回撤），挪到 Default 线程，
        // 不在主线程跑——历史页快照多时主线程会卡顿掉帧（#15）
        val (filtered, monthlyReturns, stats) = withContext(Dispatchers.Default) {
            // 数据流已按「周期查询 + 展示口径归一化」产出，这里不再重复过滤。
            // 唯一用到基准点的是月度环比：窗口首月若没有更早的月内快照，
            // 需要窗口前那一条做基期，否则首月那根柱子算不出来。
            val monthlyInput = window.base
                // 零资产基期没有参考意义（会把首月收益算成 0 → 涨幅），直接不参与
                ?.takeIf { it.totalAssets > 0.0 }
                ?.let { SnapshotAnalyzer.normalizeForDisplay(window.rows + it) }
                ?: window.rows
            val f = window.rows
            Triple(
                f,
                SnapshotAnalyzer.calculateMonthlyReturns(monthlyInput),
                SnapshotAnalyzer.analyze(f)
            )
        }
        val today = LocalDate.now().format(dateFormatter)
        val hasToday = filtered.any { it.date == today }
        _uiState.update { current ->
            current.copy(
                assets = assets,
                snapshots = filtered,
                filteredSnapshots = filtered,
                monthlyReturns = monthlyReturns,
                statistics = stats,
                selectedPeriod = period,
                hasTodaySnapshot = hasToday,
                hasAnySnapshot = window.totalCount > 0,
                snapshotsFrameReady = true,
                isLoading = false,
                loadError = null
            )
        }
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveResult = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** 打开/关闭快照管理列表（弹窗自身不做数据加载，交给 [managerSnapshots] 按需订阅） */
    fun setSnapshotManagerVisible(visible: Boolean) {
        _snapshotManagerVisible.value = visible
    }

    /** 快照管理：删除某一天（同日多行一并清掉），由 UI 二次确认后调用 */
    fun deleteManagedSnapshot(date: String) {
        viewModelScope.launch {
            val result = runCatching { assetRepository.deleteSnapshotsByDate(date) }
            result.onFailure { e ->
                if (e is CancellationException) throw e
                AppLogger.e("删除快照失败: ${e.message}", e, "HistoryVM")
            }
            _uiState.update {
                it.copy(
                    errorMessage = result.exceptionOrNull()?.let { e -> "删除快照失败：${e.message}" }
                )
            }
        }
    }

    /**
     * 快照管理：用当前持仓重存某一天。
     *
     * 与右上角「保存快照」走同一套 createSnapshot：汇率按重存时刻冻结、
     * 未知币种拒存、无持仓返回 null 不落库，保证历史点的口径与当日保存一致。
     */
    fun resaveManagedSnapshot(date: String) {
        viewModelScope.launch {
            val result = runCatching {
                assetRepository.createSnapshot(date) ?: error("当天没有持仓，未保存快照")
            }
            result.onFailure { e ->
                if (e is CancellationException) throw e
                AppLogger.e("重存快照失败: ${e.message}", e, "HistoryVM")
            }
            _uiState.update {
                it.copy(
                    errorMessage = result.exceptionOrNull()?.let { e -> "重存快照失败：${e.message}" }
                )
            }
        }
    }

    /**
     * 快照管理：手动修正某一天的五个分类金额（UI 已做二次确认，这里不再重复确认）。
     *
     * 五项资产全为 0 时拒绝保存：全 0 快照会被「丢弃前导空快照」规则从图表里隐藏，
     * 用户会以为保存丢了。与旧值一致时 repository 直接返回 false，不写库、不打标记。
     */
    fun updateManagedSnapshot(date: String, input: SnapshotAmountInput) {
        viewModelScope.launch {
            val result = runCatching {
                // 与 UI 同一句提示：UI 侧内联校验是第一道，这里是写入边界兜底
                require(SnapshotAmountEditor.isValid(input)) {
                    SnapshotAmountEditor.AT_LEAST_ONE_ASSET_MESSAGE
                }
                assetRepository.updateSnapshotAmounts(date, input)
            }
            result.onFailure { e ->
                if (e is CancellationException) throw e
                AppLogger.e("编辑快照失败: ${e.message}", e, "HistoryVM")
            }
            _uiState.update {
                it.copy(
                    errorMessage = result.exceptionOrNull()?.let { e -> "编辑快照失败：${e.message}" }
                )
            }
        }
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
): List<AssetSnapshot> =
    SnapshotAnalyzer.normalizeForDisplay(
        snapshots.filter { isSnapshotInWindow(it, period, now) }
    )

/** 周期窗口起点；「全部」返回 null 表示不设下界 */
internal fun periodWindowStart(period: String, now: LocalDate = LocalDate.now()): LocalDate? =
    when (period) {
        "1W" -> now.minusDays(7)
        "1M" -> now.minusMonths(1)
        "3M" -> now.minusMonths(3)
        "6M" -> now.minusMonths(6)
        "1Y" -> now.minusYears(1)
        "ALL" -> null
        else -> now.minusMonths(1)
    }

/**
 * 快照是否落在周期窗口内。上界固定为「今天」（[now] 当天结束）：
 * 未来日期（设备时钟回拨、导入脏数据）不属于历史，不能进图表/统计。
 * date 是脏数据时退回创建时刻判断，不静默丢弃。
 */
internal fun isSnapshotInWindow(
    snapshot: AssetSnapshot,
    period: String,
    now: LocalDate = LocalDate.now()
): Boolean {
    val startDate = periodWindowStart(period, now)
    val zone = ZoneId.systemDefault()
    val startTime = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
    // 今天结束（明天 0 点，开区间）——未来日期快照一律不纳入
    val endTime = now.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val snapshotDate = snapshot.date.toLocalDate()
    return if (snapshotDate != null) {
        (startDate == null || !snapshotDate.isBefore(startDate)) && !snapshotDate.isAfter(now)
    } else {
        snapshot.timestamp in startTime until endTime
    }
}
