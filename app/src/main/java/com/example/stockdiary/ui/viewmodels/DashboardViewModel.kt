package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.data.prefs.DashboardPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.sync.saveTodaySnapshotIfEligible
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.dividend.DividendProgressCalculator
import com.example.stockdiary.data.dividend.daysInCurrentYear
import com.example.stockdiary.data.dividend.dividendInputFingerprint
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.data.sync.SyncProgress
import com.example.stockdiary.data.sync.SyncResult
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.isDisplayEquivalentTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        _uiState.update {
            it.copy(
                errorMessage = "刷新没能完成：${throwable.message ?: "未知错误"}",
                isRefreshing = false
            )
        }
    }

    private val repository = container.assetRepository
    private val preferencesManager = container.dashboardPreferencesManager
    private val incomeUseCase = container.dividendIncomeUseCase
    private val dividendRepository = DividendRepository.getInstance(container.context)

    /**
     * 仪表盘状态机：一次「刷新 / 收入计算」先后推进哪些字段，全在这里。
     *
     * 此前是 11 个各自为政的 MutableStateFlow（刷新态、进度、结果、日预计分红、
     * loading、年收入、状态文案、错误、限流提示、收息进度、有无利息数据），
     * 写入点散落在 loadDividendIncome / refresh / 异常 handler 里；UI 同层
     * collect 十三条流，合并后少十二次订阅，且「刷新到底改了什么」一处可答。
     *
     * 不并进来的两个：[preferences]（回前台 reload 语义独立）、
     * [existingAccounts]（增删资产时才动，与刷新无关）。
     */
    data class DashboardUiState(
        val assets: List<Asset> = emptyList(),
        val snapshots: List<AssetSnapshot> = emptyList(),
        val isRefreshing: Boolean = false,
        val syncProgress: SyncProgress? = null,
        val estimatedDailyDividend: Double = 0.0,
        val dividendLoading: Boolean = false,
        val realAnnualIncome: Double = 0.0,
        val incomeCalculationStatus: String? = null,
        val errorMessage: String? = null,
        val dividendWarning: String? = null,
        val dividendProgress: DividendProgressCalculator.Progress = DividendProgressCalculator.EMPTY,
        val hasInterestData: Boolean = false,
        /**
         * Room 首帧是否已到。false 时 [assets] 还是初值 emptyList，页面不能据此判空，
         * 否则每次冷启动都会闪一下「还没有添加任何资产」的空态（老用户资产并不为空）。
         */
        val assetsFrameReady: Boolean = false,
    )

    private val _uiState = MutableStateFlow(DashboardUiState())

    /**
     * 刷新完成的一次性事件流：替代原本放在 [DashboardUiState] 里的 `syncResult` 持久字段。
     *
     * 旧写法把「刷新完成」做成 UI 状态里的持久字段，依赖 Snackbar 关掉后清除 ——
     * 连点刷新 / 离开页面再回来都会出竞态（第二次弹窗被 cancel、或残留的旧弹窗又弹一次，
     * 因为进场时 `syncResult` 仍在状态里）。改用 [syncDone] 事件流后，刷新完成不再写持久字段、
     * 也不再需要「弹完清掉」，天然不残留、不重叠。
     * 改成 SharedFlow 一次性事件：每次 refresh 收尾只发射一次，屏幕侧
     * `LaunchedEffect(Unit)` 收集，天然不残留、不重叠。replay=1 让刷新期间切走 Tab（收集协程被取消）后回到首页仍能收到这一次结果
     * ——replay=0 时事件直接消失，刷新明明跑完了却永远看不到「刷新完成：成功 N」的提示；
     * extraBufferCapacity=1 让「正在弹 Snackbar 时又刷完一次」也能排上队、不丢。
     */
    private val _syncDone = MutableSharedFlow<SyncResult>(
        replay = 1,
        extraBufferCapacity = 1
    )
    val syncDone: SharedFlow<SyncResult> = _syncDone.asSharedFlow()

    // Room 每次写库都会重发整表；而落价往往只改 lastSyncTime/lastUpdateTime，
    // 界面其实不用重画。不去重 = 一次同步上百次整屏重组。
    // 口径见 Extensions.isDisplayEquivalentTo（含 currentPrice，故现价/市值照常刷新）
    // 「Room 首帧是否已到」：[assetsFlow] 的初值是 emptyList，而页面是按 assets.isEmpty() 判空的，
    // 于是每次冷启动（Room 首帧还没到）老用户也会闪一下「还没有添加任何资产」的空态卡片。
    // 到位前由 DashboardScreen 渲染占位而不是空态。
    private val _assetsFrameReady = MutableStateFlow(false)
    val assetsFrameReady: StateFlow<Boolean> = _assetsFrameReady.asStateFlow()

    private val assetsFlow: StateFlow<List<Asset>> = repository.getAllAssets()
        .distinctUntilChanged { a, b -> a.isDisplayEquivalentTo(b) }
        .onEach { _assetsFrameReady.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 「资产增值」卡片的基准数据：净资产要和本月/今年之前的快照对比。
    // 快照表每次写入（尤其"同日覆盖"会换新 id/timestamp，但 date+netAssets 不变）
    // 都会让 Room 重发整表；而卡片只消费 date 与 netAssets 两列，按显示相关字段去重，
    // 避免与价格/汇率无关的写库触发多余重算与重组。
    private val snapshotsFlow: StateFlow<List<AssetSnapshot>> = repository.getAllSnapshots()
        .map { it.sortedBy { snapshot -> snapshot.date } }
        .distinctUntilChanged { old, new ->
            old.size == new.size && old.zip(new).all { (a, b) ->
                a.date == b.date && a.netAssets == b.netAssets
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<DashboardUiState> = combine(assetsFlow, snapshotsFlow, _uiState) { assets, snapshots, state ->
        // assetsFrameReady：onEach 在 stateIn 之前把标志位置 true，故这里的读一定已经是最新的
        state.copy(
            assets = assets,
            snapshots = snapshots,
            assetsFrameReady = _assetsFrameReady.value
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    private val _preferences = MutableStateFlow(preferencesManager.loadPreferences())
    val preferences: StateFlow<com.example.stockdiary.data.prefs.DashboardPreferences> = _preferences.asStateFlow()

    private val _existingAccounts = MutableStateFlow<List<String>>(emptyList())
    val existingAccounts: StateFlow<List<String>> = _existingAccounts.asStateFlow()

    /**
     * 从磁盘重新加载偏好并推送到 [_preferences]。
     *
     * 偏好只在构造期加载一次（见 [_preferences]），但用户在「设置 → 自定义仪表盘」
     * 改完开关后，`SettingsViewModel` 写入的是 SharedPreferences，本 VM 的内存副本仍是旧值，
     * 导致回到仪表盘界面不刷新、且收入算完用旧值整体回写会把新开关覆盖掉（P0-3）。
     * 仪表盘每次回前台（ON_RESUME）调一次本方法即可与磁盘同步。
     */
    fun reloadPreferences() {
        // 一次要读 20 个 SharedPreferences 键（含 getStringSet），SP 首次访问还要等 XML 载入；
        // 它由 ON_RESUME 触发，放主线程会让「回到仪表盘」那一帧卡一下 → 切 IO 线程读
        viewModelScope.launch(Dispatchers.IO) {
            _preferences.value = preferencesManager.loadPreferences()
        }
    }

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
     * ## 缓存必须和「这批资产」口径一致才能用
     *
     * 首页、持仓页共用同一份持久化缓存，但两者传入的资产范围并不相同（首页含全部
     * 非负债资产、持仓页只含用户勾选可见的类型）。从前这里只判「缓存非空」就把值摆出来，
     * 于是从持仓页切回首页时会短暂显示**子集**的年化收入与每日预估（金额偏小）。
     * 现在要求缓存的代码集合与本批资产完全一致才采用；不一致就等本轮静默取数算完再显示，
     * 正确性优先于「不闪计算中」。
     */
    fun loadDividendIncome(assetsOnly: List<Asset>) {
        if (assetsOnly.isEmpty()) {
            // 删光非负债资产（或只剩负债）时必须把收入类数字**清掉**，而不是留着上一批资产的值：
            // 顶部总资产已经是 0，下面却还挂着旧资产的年化收入 / 每日预估 / 收息进度，
            // 三个数字互相矛盾。从前这里是直接 return，什么都不写。
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        realAnnualIncome = 0.0,
                        estimatedDailyDividend = 0.0,
                        dividendProgress = DividendProgressCalculator.EMPTY,
                        incomeCalculationStatus = "暂无分红/利息数据",
                        hasInterestData = false,
                        dividendLoading = false
                    )
                }
            }
            return
        }

        val symbols = assetsOnly.mapNotNull { it.symbol }.toSet()
        val fingerprint = dividendInputFingerprint(assetsOnly)
        val cached = _preferences.value
        // 只有缓存就是「这一批资产」算出来的才敢当全量展示（见上方 KDoc）。
        // 指纹含数量与利率：加仓 / 改存款余额同样会让旧值作废，不能只看代码集合。
        val hasCached = symbols.isNotEmpty() && cached.annualDividendFingerprint == fingerprint
        val lastValue = cached.annualDividendCache

        viewModelScope.launch {
            if (hasCached) {
                _uiState.update {
                    it.copy(
                        realAnnualIncome = lastValue,
                        estimatedDailyDividend = lastValue / daysInCurrentYear().toDouble(),
                        incomeCalculationStatus = null
                    )
                }
            }
            // 已有可显示的值时不转圈；只有真没值可显示时才转
            if (!hasLoadedIncome && !hasCached) _uiState.update { it.copy(dividendLoading = true) }
            try {
                val bankDeposits = assetsOnly.filter { it.type == AssetType.BANK_DEPOSIT }
                _uiState.update {
                    it.copy(hasInterestData = bankDeposits.any { d -> (d.interestRate ?: 0.0) > 0 })
                }

                // cacheOnly：冷启动只读缓存，TTL 过期也照用（30 天硬上限）。
                // 连过期数据都没有时内部会自己走抓取流程，所以首次安装仍然有数可显示
                val result = incomeUseCase.load(
                    assetsOnly,
                    forceRefresh = false,
                    tag = "DashboardVM",
                    cacheOnly = true
                )
                _uiState.update { state ->
                    state.copy(
                        realAnnualIncome = result.totalAnnualIncome,
                        estimatedDailyDividend = result.totalAnnualIncome / daysInCurrentYear().toDouble(),
                        dividendProgress = withContext(Dispatchers.Default) {
                            DividendProgressCalculator.calculate(
                                assets = assetsOnly,
                                recordsBySymbol = result.recordsBySymbol
                            )
                        },
                        incomeCalculationStatus = when {
                            result.totalAnnualIncome == 0.0 && !result.hasDividendData && !state.hasInterestData -> "暂无分红/利息数据"
                            result.totalAnnualIncome == 0.0 && assetsOnly.none { it.type == AssetType.STOCK || it.type == AssetType.FUND } -> "暂无计息资产"
                            else -> null
                        }
                    )
                }
                hasLoadedIncome = true

                // 存下来：下次进场先摆这个值，不用等这一轮算完。
                // 走 Manager 的原子「读-改-写」：只覆盖两个缓存字段，其余（含用户在设置页
                // 刚改的开关、日历改的月度生活费等）原样保留，杜绝用陈旧副本整体回写覆盖。
                val updated = preferencesManager.updatePreferences { prefs ->
                    prefs.copy(
                        annualDividendCache = result.totalAnnualIncome,
                        annualDividendSymbols = symbols,
                        annualDividendFingerprint = fingerprint
                    )
                }
                _preferences.value = updated
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("计算收入失败: ${e.message}", e, "DashboardVM")
                // 已经摆出来的旧值别抹成 0
                if (!hasCached) {
                    _uiState.update {
                        it.copy(
                            realAnnualIncome = 0.0,
                            estimatedDailyDividend = 0.0,
                            incomeCalculationStatus = "计算失败"
                        )
                    }
                } else {
                    // 有缓存值时从前是完全静默的：界面摆的是旧缓存却被当成最新值，
                    // 用户完全看不出「这一次其实没算成」。
                    _uiState.update {
                        it.copy(incomeCalculationStatus = "本次计算失败，显示的是上次结果")
                    }
                }
            } finally {
                _uiState.update { it.copy(dividendLoading = false) }
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
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }

            // 两个范围，别混（详见方法 KDoc）：
            // - priceAssets 用于同步，只含有行情的类型；
            // - assetsOnly 用于收入计算，必须含银行存款，否则漏掉利息收入。
            val priceAssets = uiState.value.assets.filter { it.type in AssetType.getPriceSyncedTypes() }
            val assetsOnly = uiState.value.assets.filter { !it.type.isLiability }

            // 两个阶段分开收尾，理由见下面两处 finally 的注释
            var syncResult: SyncResult? = null
            var syncError: String? = null
            try {
                syncResult = AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = priceAssets,
                    // 实时进度：刷新要跑完上百个标的，只转圈用户无从判断是在干活还是卡死
                    onProgress = { progress -> _uiState.update { it.copy(syncProgress = progress) } },
                    forceRefresh = true,
                syncTrigger = "仪表盘刷新"
                )
                AppLogger.i(
                    "价格同步完成（成功 ${syncResult.successCount}，失败 ${syncResult.failedCount}）",
                    "DashboardVM"
                )
                // 全量手动刷新成功 → 覆盖当日快照（补齐手动路径，后台 Worker 早已这么做）：
                // ① 只挂全量手动刷新——batchSync/导入后同步/详情页单标的刷新都是子集刷新，
                //    全量快照会混入大量旧价；后台 Worker 有自己的存快照，不重复；
                // ② 截断不存——saveTodaySnapshotIfEligible 内的门槛挡掉 truncatedByTimeLimit，
                //    避免把「部分新价 + 部分旧价」固化成当日快照、污染净值曲线；
                // ③ 放 phase-2（分红计算）前——快照只取决于价格（sync 内已逐标的落库），
                //    sync 一返回就能存、不必等分红；且避开「phase-2 中途协程被取消时，finally 里的
                //    suspend 调用立刻抛 CancellationException」的坑；取消若发生在 phase-1，
                //    上面 catch(CancellationException){复位;throw} 已直接跳出，走不到这里。
                saveTodaySnapshotIfEligible(repository, syncResult, "DashboardVM")
            } catch (e: CancellationException) {
                // 取消（退后台 onStop / VM 销毁）时 rethrow 会跳过下面第二阶段的 finally，
                // _isRefreshing 会永远停在 true、后续刷新全被挡死、界面一直转圈——
                // 这里补一次复位，保证取消路径刷新态也能收起（铁律：取消照旧重抛，不做吞）
                _uiState.update { it.copy(isRefreshing = false) }
                throw e
            } catch (e: Exception) {
                // 不捕获的话 isRefreshing 永远停在 true，后续刷新全被挡死、界面一直转圈
                AppLogger.w("同步失败: ${e.message}", "DashboardVM")
                // 静默是最糟的反馈：用户点了刷新，转圈停下，什么也没发生
                syncError = e.message ?: "未知错误"
            } finally {
                // 价格同步这一段结束就把进度清掉：后面的收入计算是另一件事。
                // 不清的话标题会一直挂着「10 / 10」而转圈还在继续，看着像卡死
                _uiState.update { it.copy(syncProgress = null) }
            }

            try {
                // 这条是「同步完成后再算一次收入」，**不能** cacheOnly：
                // 用户刚刷新过行情，需要的是最新分红，不是缓存里的旧值。
                // 传 assetsOnly（含银行存款）而不是 priceAssets，否则漏掉利息收入。
                val result = incomeUseCase.load(assetsOnly, forceRefresh = false, tag = "DashboardVM")
                _uiState.update { state ->
                    state.copy(
                        realAnnualIncome = result.totalAnnualIncome,
                        estimatedDailyDividend = result.totalAnnualIncome / daysInCurrentYear().toDouble(),
                        dividendProgress = withContext(Dispatchers.Default) {
                            DividendProgressCalculator.calculate(
                                assets = assetsOnly,
                                recordsBySymbol = result.recordsBySymbol
                            )
                        },
                        // 刷新成功后若确实没有分红/利息收入，恢复「暂无」文案，
                        // 否则无条件清空会让「暂无分红/利息数据」在刷新后凭空消失。
                        incomeCalculationStatus = if (result.totalAnnualIncome == 0.0) "暂无分红/利息数据" else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("加载分红收入失败: ${e.message}", e, "DashboardViewModel")
                _uiState.update {
                    it.copy(incomeCalculationStatus = "加载分红收入失败: ${e.localizedMessage ?: e.message}")
                }
            } finally {
                // 结果反馈统一放到最后：转圈停下与提示出现应当同时发生。
                // 原先同步一结束就置 _syncResult，Snackbar 弹「刷新完成」时转圈还在
                // 为收入计算而转，用户会看到「已完成」的提示和一个仍在转的按钮，自相矛盾
                _uiState.update { state ->
                    if (state.errorMessage == null) {
                        state.copy(errorMessage = when {
                            syncError != null -> "刷新失败：$syncError"
                            // 时间预算兜底：超时不抛错，但要让用户知道「这次没跑完」，
                            // 看到的是保留的上次数据，而不是转圈到天荒地老
                            syncResult?.truncatedByTimeLimit == true ->
                                "同步超时，已保留上次数据"
                            else -> null
                        })
                    } else state
                }
                // 手动刷新撞过天天基金限流（514/429）→ 提示「部分场外基金分红没抓全」（非阻塞）
                if (dividendRepository.consumeOtcRateLimitWarning()) {
                    _uiState.update {
                        it.copy(dividendWarning = "今日部分场外基金分红因天天基金限流未能抓取完整，可稍后手动重试")
                    }
                }
                _uiState.update { it.copy(isRefreshing = false) }
                // 刷新成功才发一次性完成事件（syncResult 为 null 表示同步阶段已抛异常，
                // 已走 errorMessage 反馈，不再弹「完成」以免与失败提示打架）
                syncResult?.let { _syncDone.tryEmit(it) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearDividendWarning() {
        _uiState.update { it.copy(dividendWarning = null) }
    }

    suspend fun addAsset(asset: Asset) {
        if ((asset.type == AssetType.STOCK || asset.type == AssetType.FUND) && asset.symbol != null) {
            repository.addOrUpdateStockAsset(
                symbol = asset.symbol,
                name = asset.name,
                balance = asset.balance,
                price = asset.purchasePrice,
                account = asset.account,
                assetType = asset.type,
                currency = asset.currency,
                dueDate = asset.dueDate,
                notes = asset.notes,
                interestRate = asset.interestRate,
                currentPrice = asset.currentPrice
            )
        } else if (asset.type == AssetType.CRYPTO && asset.symbol != null) {
            // 加密货币按 (symbol, account) 去重，避免手动重复添加出现两条持仓（#21）
            repository.upsertCryptoAsset(asset)
        } else {
            repository.insertAsset(asset)
        }
    }

    companion object {
        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardViewModel(container) }
        }
    }
}