package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.prefs.DEFAULT_MONTHLY_EXPENSE
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.dividend.cashDateOrNull
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.sync.SyncBudget
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.perTenSharesToAmount
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
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
        /** 日历/分红用的资产（只含股票、基金、存款——它们才可能有分红/利息） */
        val assets: List<Asset> = emptyList(),
        /**
         * FIRE 计算器用的**全量资产**（含加密货币与负债）。
         *
         * 不能复用 [assets]：那份被裁成 STOCK/FUND/BANK_DEPOSIT，传给 FIRE 会漏掉负债
         * （负债恒 0）和加密货币，净资产、收入率、目标全错。按需加载（进页/打开计算器时），
         * 不参与日历签名去重，避免每次价格跳动都重组整页。
         */
        val fireAssets: List<Asset> = emptyList(),
        val isLoading: Boolean = true,
        /**
         * 手动刷新中（顶栏刷新按钮转圈、按钮置灰）。
         *
         * 与 [isLoading] 分开：isLoading 也覆盖首次加载/切月/切 Tab，那些场景下按钮该禁用但
         * **不该转圈**——转圈是「我按了刷新正在跑」的反馈，与资产页/持仓页同一套语义。
         */
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val monthlyExpense: String = DEFAULT_MONTHLY_EXPENSE,
        val realAnnualIncome: Double = 0.0
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 日历数据最近一次成功生成时所处的「今天」。
     *
     * 日历的「今天」格是**生成那一刻**固化的（[buildAssetCalendarMonth] 只在生成时把
     * 实时净值写进 today 格），进程跨天存活时它就过期了：昨天的格挂着旧实时值、
     * 今天这格永远是 0（「暂无数据」），本月总览也停在昨天的值。午夜翻日协程与
     * [onScreenResumed] 都靠它判断要不要重算。
     */
    private var generatedOnDate: LocalDate? = null

    /**
     * 天天基金限流提示（非阻塞）：本轮手动刷新撞过 514/429 时置位，
     * 由界面对应的 LaunchedEffect 弹 Snackbar 后调 [clearDividendWarning] 清空。
     * 与 uiState.errorMessage 分开，避免和「同步失败」混淆——限流只是「部分分红没抓全」。
     */
    private val _dividendWarning = MutableStateFlow<String?>(null)
    val dividendWarning: StateFlow<String?> = _dividendWarning.asStateFlow()

    fun clearDividendWarning() {
        _dividendWarning.value = null
    }

    private val supportedTypes = listOf(AssetType.STOCK, AssetType.FUND, AssetType.BANK_DEPOSIT)

    init {
        val prefs = dashboardPreferencesManager.loadPreferences()
        _uiState.update { state -> state.copy(monthlyExpense = prefs.monthlyExpense) }

        viewModelScope.launch {
            assetRepository.getAllAssets()
                .map { assets -> assets.filter { it.type in supportedTypes } }
                // 日历只依赖「有哪些标的 + 数量/币种」：资产日历按 symbol 取分红，收支日历还按
                // balance/currency 折算金额——都与**实时价无关**。而 getAllAssets() 是 Room 流，
                // 同步时每更新一个 currentPrice 就重发一次，实测一次全量刷新让「生成资产日历」
                // 白跑 20+ 次（快照/分红都没变，结果一模一样）。按日历真正关心的字段做签名去重，
                // 价格抖动不再触发重算；首值直接放行、零延迟，不影响冷启动加载。
                .distinctUntilChanged { old, new -> calendarSignature(old) == calendarSignature(new) }
                .collect { assets ->
                    _uiState.update { state -> state.copy(assets = assets) }
                    generateCalendarData(assets)
                    loadDividendIncome(assets)
                }
        }

        // 跨午夜自动重算。日历的「今天」格与本月总览都以生成那一刻为准，而资产签名去重
        // （上方 distinctUntilChanged）保证纯价格变动不触发重算——于是 App 进程跨天存活时
        // （用户晚上挂着 App 到零点后）没有任何东西会重画日历：新的一天那格永远是 0
        // 「暂无数据」、总览停在昨天的值（2026-10-25 零点后用户实测：25 号无金额、
        // 总览 ¥197773.49 比首页 ¥197827.41 少 53.92，根因即此）。每晚零点过 1 秒重算一次，
        // 代价可忽略（一次本地查询 + 一次实时净资产求和，无网络）。
        viewModelScope.launch {
            while (isActive) {
                val now = java.time.LocalDateTime.now()
                val nextMidnight = LocalDate.now().plusDays(1).atStartOfDay()
                delay(Duration.between(now, nextMidnight).toMillis() + 1000)
                if (generatedOnDate == LocalDate.now()) continue
                rollMonthIfFollowingToday()
                generateCalendarData(_uiState.value.assets)
            }
        }
    }

    /**
     * 界面回到前台时的保鲜检查：进程在后台被冻结（Doze）时，上面的午夜协程可能被推迟
     * 到解锁后才跑。回前台这一刻补查「生成日是否还是今天」，过期就立即重算，
     * 保证用户切回日历页看到的第一眼就是新一天的数。
     */
    fun onScreenResumed() {
        // 先把「今天」格刷成当前实时值：日历数据是按实时价生成后被签名去重冻结的，
        // 别页刷新落了新价，回前台/回 Tab 时这里必须跟上，否则今天格与首页差一截。
        refreshLiveAssets()
        if (generatedOnDate != null && generatedOnDate != LocalDate.now()) {
            rollMonthIfFollowingToday()
            viewModelScope.launch { generateCalendarData(_uiState.value.assets) }
        }
    }

    /**
     * 跨月午夜：如果用户看的就是「生成那天所在的月份」，自动翻到新的当月。
     *
     * 否则 10/31 挂机到 11/1，页面还停在 10 月，11/1 那格永远是「暂无数据」；
     * 用户手动翻去看历史月份时不能动他的视图，所以只在「当前月 = 旧今天所在月」时翻。
     */
    private fun rollMonthIfFollowingToday() {
        val generated = generatedOnDate ?: return
        val nowMonth = YearMonth.now()
        val state = _uiState.value
        if (state.currentMonth == YearMonth.from(generated) && state.currentMonth != nowMonth) {
            _uiState.update { it.copy(currentMonth = nowMonth, selectedDate = null) }
        }
    }

    /**
     * 拉一次**全量资产**（含加密货币与负债），供 FIRE 与「今天」格使用。
     *
     * 两件事：
     * 1. 填 [UiState.fireAssets]——FIRE 不能拿裁剪后的日历资产，否则负债恒 0、加密不计；
     * 2. 用实时净资产覆盖当前月的「今天」格——日历签名去重不含价格，别页同步落价后，
     *    本页常驻进程里今天格不会自己更新（切 Tab 也只是重跑生成，价格仍是签名外的旧值）。
     *
     * 只查一次 Room、只改今天一个格子，不触发分红抓取，开销可以忽略。
     */
    fun refreshLiveAssets() {
        viewModelScope.launch {
            try {
                val all = withContext(Dispatchers.IO) { assetRepository.getAllAssets().first() }
                val liveNetAssets = computeLiveNetAssets(all)
                val today = LocalDate.now()
                _uiState.update { state ->
                    val todayInView = today.year == state.currentMonth.year &&
                        today.monthValue == state.currentMonth.monthValue
                    val data = if (state.selectedTab == "资产日历" &&
                        todayInView &&
                        state.calendarData.isNotEmpty()
                    ) {
                        state.calendarData + (today to liveNetAssets)
                    } else {
                        state.calendarData
                    }
                    state.copy(fireAssets = all, calendarData = data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("刷新日历实时资产失败: ${e.message}", e, "CalendarVM")
            }
        }
    }

    /**
     * 实时净资产：全部非负债资产 totalValueCny 合计 − 负债合计。
     * 与资产页/首页完全同口径，日历「今天」格与 FIRE 都依赖它。
     * 抽出共用，避免 [refreshLiveAssets] 与 [generateAssetCalendarData] 两处各写一遍、
     * 将来改口径时只改一处漏改另一处（今天格就与首页对不上）。
     */
    private fun computeLiveNetAssets(assets: List<Asset>): Double =
        assets.filter { !it.type.isLiability }.sumOf { it.totalValueCny } -
            assets.filter { it.type.isLiability }.sumOf { it.totalValueCny }

    /**
     * 日历输出真正依赖的资产字段签名：symbol（取哪些分红）、type、balance（收支日历折算金额）、
     * currency（外币换算）、interestRate / millionCopiesIncome（FIRE 的年化收入来自这里）。
     *
     * **不含 currentPrice/lastSyncTime 等实时字段**——它们变了不触发整轮重算
     * （见 init 里的 distinctUntilChanged）：一次全量刷新会让 Room 流重发 20+ 次，每轮重算都要
     * 预热分红缓存，纯属浪费。今天那格的实时价改由 [refreshLiveAssets] 单独补，开销只有一次
     * Room 查询。
     */
    private fun calendarSignature(assets: List<Asset>): String =
        assets.sortedBy { it.id }.joinToString(";") { asset ->
            "${asset.symbol}|${asset.type}|${asset.balance}|${asset.currency}|" +
                "${asset.interestRate}|${asset.millionCopiesIncome}"
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
                // 走 Manager 的原子「读-改-写」，只改月度生活费、其余字段原样保留，
                // 避免用陈旧副本整体回写覆盖别的页面刚改的偏好。
                dashboardPreferencesManager.updatePreferences { it.copy(monthlyExpense = expense) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存月度生活费失败: ${e.message}", e, "CalendarVM")
            }
        }
    }

    fun selectTab(tab: String) {
        if (_uiState.value.selectedTab == tab) return
        // 与 selectMonth 一致：清掉选中日期。它是「上一个 Tab 里的某一天」，切 Tab 后详情卡
        // 会落到另一套口径的 calendarData（资产日历 vs 收支日历），取到意外的值或无关明细。
        _uiState.update { state -> state.copy(selectedTab = tab, selectedDate = null) }
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets)
        }
    }

    fun selectMonth(month: YearMonth) {
        if (_uiState.value.currentMonth == month) return
        // 顺手清掉选中日期：它是「上一张月历里的某一天」，翻月后详情卡会显示一个
        // 当前月根本不存在的日期（金额取不到 → 「暂无数据」，收支明细也是无关的）。
        _uiState.update { state -> state.copy(currentMonth = month, selectedDate = null) }
        viewModelScope.launch {
            generateCalendarData(_uiState.value.assets)
        }
    }

    fun selectDate(date: LocalDate?) {
        _uiState.update { state -> state.copy(selectedDate = date) }
    }

    /**
     * 顶栏刷新：强制重抓分红 + 按最新实时价重算「今天」那格。
     *
     * 与资产页/持仓页同一套交互（按钮置灰 + 转圈），也是本页唯一能主动触发
     * `forceRefresh=true` 的入口——没有它，分红缓存过期后只能靠切 Tab / 切月，
     * 今天那格也只能等跨天才会跟上最新价。
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                generateCalendarData(_uiState.value.assets, forceRefresh = true)
                // 手动刷新撞过天天基金限流（514/429）→ 提示「部分场外基金分红没抓全」（非阻塞）
                if (dividendRepository.consumeOtcRateLimitWarning()) {
                    _dividendWarning.value = "今日部分场外基金分红因天天基金限流未能抓取完整，可稍后手动重试"
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
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
                // 复用全量资产（含负债/加密）：资产日历「今天」格的实时净资产需要它，而 fireAssets
                // 已被 refreshLiveAssets 加载过（进页即触发），切月/切 Tab 时直接复用，省一次 Room
                // 全表查询。仅在尚未加载时降级查一次。
                val allAssets = _uiState.value.fireAssets.ifEmpty {
                    assetRepository.getAllAssets().first()
                }
                if (requestState.selectedTab == "资产日历") {
                    generateAssetCalendarData(requestState.currentMonth, assets, forceRefresh, allAssets)
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
            // 生成成功才更新「保鲜日期」：失败的生成不算数，下次回前台还要重试
            generatedOnDate = LocalDate.now()
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
        forceRefresh: Boolean,
        allAssets: List<Asset>
    ): Result<CalendarResult> = try {
        Result.success(withContext(Dispatchers.IO) {
            if (assets.isEmpty()) {
                return@withContext CalendarResult(
                    buildAssetCalendarMonth(month, emptyMap(), liveNetAssetsToday = 0.0, today = LocalDate.now()),
                    emptySet(),
                    emptyList()
                )
            }

            val today = LocalDate.now()
            val viewingCurrentMonth = today.year == month.year && today.monthValue == month.monthValue

            // 只查"显示月 1 号 00:00（系统默认时区）"起的快照，而不是把整张 asset_snapshots
            // 表都 load 进内存再按月筛：老用户积攒几百天每日快照时整表读取纯属浪费 IO，
            // 且月与月之间互不影响。下方仍按 date 精确归到当月，多查到的"当月之后"的行会被丢弃。
            // 用系统默认时区把"当月 1 号 00:00"换算成 epoch millis（与快照 timestamp 同一口径）。
            val monthStartMillis = month.atDay(1)
                .atStartOfDay()
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val monthSnapshots = snapshotDao.getSnapshotsSinceSync(monthStartMillis).mapNotNull { snapshot ->
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

            // 「今天」这一格不读快照，按当前持仓实时算——与资产页/首页完全同口径
            // （全部非负债资产 totalValueCny 合计 − 负债合计，正是两页「净资产」的算法）。
            // 当日快照是过去某时刻按当时价格/汇率固化的值：存下之后汇率（6h TTL 回源）或
            // 加密货币价（默认不自动同步）再动、或同步被时间预算截断而不存快照，资产页早已
            // 换成新值，日历还挂着旧快照——用户实测同一天两页差 0.01 元，根因就在这里。
            // 过去的日子仍用快照：历史记录的本职就是定格那一天。只看当月才算，翻旧月不白查全表。
            // 直接复用传入的 allAssets（含负债/加密），不再重复查 Room——从前这里每次切月/
            // 切 Tab 都发一次全表查询，纯属浪费 IO；算法与 [computeLiveNetAssets] 共用，防漂移。
            val liveNetAssetsToday = if (viewingCurrentMonth) {
                computeLiveNetAssets(allAssets)
            } else {
                0.0
            }
            val data = buildAssetCalendarMonth(
                month = month,
                snapshotNetAssets = monthSnapshots.toMap(),
                liveNetAssetsToday = liveNetAssetsToday,
                today = today
            )

            val symbols = assets.mapNotNull { it.symbol }
            // 冷启动不强制重抓：先用宽口径批量读把库里有记录的标的预热进内存缓存
            //（零网络、2 次 IO），只让「连过期数据都没有」的标的去联网。
            //
            // 从前是无条件 preFetchDividends(全部标的)。而 DividendRepository.cache
            // 是进程内内存 Map、一重启就全丢，预取判据 forceRefresh || ttlExpired
            // 于是对每个标的都成立；更要命的是 getDividendsBySymbol 里
            // 「DB 有记录但 lastFetchedAt 超过 24h」会 return null 转去联网 ——
            // 超过一天没同步，200 个标的全部重抓，本页在 isLoading 里转圈到预算耗尽
            // （70s），而 generateCalendarData 是 suspend 且被 collect 直接调用，
            // 转圈期间整条收集链都阻塞着。详见 primeDividendCache 的 KDoc。
            val needFetch = dividendRepository.primeDividendCache(symbols, forceRefresh)
            if (needFetch.isNotEmpty()) {
                dividendRepository.preFetchDividends(
                    symbols = needFetch,
                    forceRefresh = forceRefresh,
                    timeBudgetMs = SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS
                )
            }

            val dividendDates = mutableSetOf<LocalDate>()
            val dividends = mutableListOf<Pair<String, List<DividendRecord>>>()

            for (asset in assets) {
                val symbol = asset.symbol ?: continue
                try {
                val records = dividendRepository.getDividendsBySymbol(symbol)
                // 照样只算一次日期：筛当月、记分红日、判是否已到账用的是同一个 LocalDate。
                // 按**到账日**（派息日，拿不到才除息日）而不是除息日：除息日只决定资格，
                // 钱 1~4 周后才到账——按除息日打标记会让钱还没到就显示「分」。
                // 并且只记已到账的（到账日不晚于今天）：日历上的「分」表示钱确实到了；
                // 还没到账的待收金额在 Dashboard 收息进度卡的「待收」行和下一笔倒计时里看
                val monthEntries = records.mapNotNull { record ->
                    val date = record.cashDateOrNull()
                    if (date == null) {
                        AppLogger.w("解析分红日期失败: ${record.exDividendDate}", "CalendarViewModel")
                        return@mapNotNull null
                    }
                    if (date.year == month.year && date.monthValue == month.monthValue && !date.isAfter(today)) {
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

            val today = LocalDate.now()
            val symbols = assets.mapNotNull { it.symbol }
            // 同 generateAssetCalendarData：冷启动先宽口径预热、只补抓缺失的标的，
            // 不再无条件全量预取（那正是本页冷启动转圈几十秒的根因）。
            // forceRefresh=true（用户主动刷新）时 primeDividendCache 直接放行全部，
            // 走的仍是原来的全量重抓路径
            val needFetch = dividendRepository.primeDividendCache(symbols, forceRefresh)
            if (needFetch.isNotEmpty()) {
                dividendRepository.preFetchDividends(
                    symbols = needFetch,
                    forceRefresh = forceRefresh,
                    timeBudgetMs = SyncBudget.MANUAL_SYNC_TIME_LIMIT_MS
                )
            }

            val dividendDates = mutableSetOf<LocalDate>()
            val dividends = mutableListOf<Pair<String, List<DividendRecord>>>()

            for (asset in assets) {
                val symbol = asset.symbol ?: continue
                try {
                    val records = dividendRepository.getDividendsBySymbol(symbol)
                    // 同资产日历：按到账日归日、只算已到账的（到账日不晚于今天）。
                    // 原先按除息日归日，钱还没到就把金额计到了除息日那天
                    val monthEntries = records.mapNotNull { record ->
                        val date = record.cashDateOrNull() ?: return@mapNotNull null
                        if (date.year == month.year && date.monthValue == month.monthValue && !date.isAfter(today)) {
                            date to record
                        } else {
                            null
                        }
                    }

                    if (monthEntries.isNotEmpty()) {
                        dividends.add(symbol to monthEntries.map { it.second })

                        monthEntries.groupBy { it.first }.forEach { (date, dayRecords) ->
                            dividendDates.add(date)
                            // 折算成人民币：同一天可能有多只外币基金派息，各自换汇后再累加，
                            // 否则美元/港币金额会按人民币数值直接相加，钱数被严重低估。
                            val totalAmount = ExchangeRateManager.toCny(
                                perTenSharesToAmount(
                                    dayRecords.sumOf { it.second.dividendPerShare.toDoubleOrNull() ?: 0.0 },
                                    asset.balance
                                ),
                                asset.currency
                            )
                            if (totalAmount > 0) {
                                data[date] = (data[date] ?: 0.0) + totalAmount
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

    companion object {
        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CalendarViewModel(
                    assetRepository = container.assetRepository,
                    dividendRepository = container.dividendRepository,
                    snapshotDao = container.database.snapshotDao(),
                    dashboardPreferencesManager = container.dashboardPreferencesManager,
                    dividendIncomeUseCase = container.dividendIncomeUseCase
                )
            }
        }
    }
}

/**
 * 资产日历单月格子数据：日期 → 当日净资产。纯函数，无 IO、无时钟（[today] 由调用方注入）。
 *
 * 口径：
 * - **过去的日子**取当日快照——历史记录的本职就是定格那一天的值；
 * - **今天**取 [liveNetAssetsToday]（实时净资产，与资产页/首页同公式：全部非负债资产
 *   `totalValueCny` 合计 − 负债合计）。当日快照是过去某时刻按当时价格/汇率固化的值，
 *   之后汇率（6h TTL 回源）或加密货币价（默认不自动同步）再动、或同步被时间预算截断
 *   而不存快照，日历这天就会与资产页对不上——用户实测同一天两页差 0.01 元，根因就是
 *   日历这天读的是旧快照而不是实时值。今天跟资产页保持一致，故实时值优先。
 *
 * [today] 不在 [month] 内（翻旧月/看未来月）时不发生覆盖，整月都是快照与 0；
 * 无快照的日子保持 0.0（界面显示「暂无数据」）。
 */
internal fun buildAssetCalendarMonth(
    month: YearMonth,
    snapshotNetAssets: Map<LocalDate, Double>,
    liveNetAssetsToday: Double,
    today: LocalDate
): Map<LocalDate, Double> {
    val data = mutableMapOf<LocalDate, Double>()
    for (day in 1..month.lengthOfMonth()) {
        data[month.atDay(day)] = 0.0
    }
    snapshotNetAssets.forEach { (date, value) ->
        if (date.year == month.year && date.monthValue == month.monthValue) {
            data[date] = value
        }
    }
    if (today.year == month.year && today.monthValue == month.monthValue) {
        data[today] = liveNetAssetsToday
    }
    return data
}

/**
 * 分红明细用的「代码 → 资产」索引：**同代码多账户先把数量合并**。
 *
 * 日历金额是逐条资产累加的（同一只股票在两个账户各有一笔，两笔都该计入），
 * 而详情卡要靠持仓数量反查金额。若直接 `associateBy`，只会留下最后一条，
 * 明细金额就会比格子/月汇总少一个账户。这里按代码分组、balance 求和；
 * 名称/币种取第一条（同一代码这两个属性一致）。
 */
internal fun aggregateAssetsBySymbol(assets: List<Asset>): Map<String, Asset> =
    assets.mapNotNull { asset -> asset.symbol?.let { it to asset } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, group) ->
            val first = group.first()
            if (group.size == 1) first else first.copy(balance = group.sumOf { it.balance })
        }
