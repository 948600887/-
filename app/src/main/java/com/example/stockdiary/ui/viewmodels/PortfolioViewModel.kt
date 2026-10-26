package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.sync.saveTodaySnapshotIfEligible
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.data.sync.SyncProgress
import com.example.stockdiary.data.sync.SyncResult
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.isDisplayEquivalentTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers

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
        _uiState.update {
            it.copy(
                errorMessage = "刷新没能完成：${throwable.message ?: "未知错误"}",
                isRefreshing = false
            )
        }
    }

    private val repository = container.assetRepository
    private val incomeUseCase = container.dividendIncomeUseCase
    private val dividendRepository = DividendRepository.getInstance(container.context)

    /**
     * 持仓页状态机：一次「刷新 / 分红加载」会先后推进哪些字段，全在这里。
     *
     * 此前是 9 个各自为政的 MutableStateFlow（刷新态、进度、结果、分红汇总、
     * 分红 loading、状态文案、错误、限流提示），写入点散落在 refresh /
     * batchSync / loadDividendIncome / 异常 handler 里，「刷新到底改了什么」
     * 要翻遍半个文件才能答上；且 UI 同层 collect 九个流，任一个发射都整体重组，
     * 合并不损失重组粒度，只是少九次订阅。
     *
     * 不并进来的三个：[existingAccounts]（增删资产时才动，与刷新无关）、
     * [preferences]（回前台 reload 语义独立）、[expandedGroups]（纯 UI 交互态）。
     */
    data class PortfolioUiState(
        val assets: List<Asset> = emptyList(),
        val isRefreshing: Boolean = false,
        val syncProgress: SyncProgress? = null,
        val estimatedAnnualDividend: Double = 0.0,
        val dividendLoading: Boolean = false,
        val incomeCalculationStatus: String? = null,
        val errorMessage: String? = null,
        val dividendWarning: String? = null,
    )

    private val _uiState = MutableStateFlow(PortfolioUiState())

    /**
     * 刷新完成的一次性事件流：替代原本放在 [PortfolioUiState] 里的 `syncResult` 持久字段。
     * 旧写法把「刷新完成」做成 UI 状态里的持久字段，依赖 Snackbar 关掉后清除 ——
     * 连点刷新 / 离开页面再回来都会出竞态（第二次弹窗被 cancel、或残留旧弹窗又弹一次）。
     * 改用 [syncDone] 事件流后，刷新完成不再写持久字段、也不再需要「弹完清掉」，
     * 天然不残留、不重叠。replay=0 保证新进场不会收到历史事件，
     * extraBufferCapacity=1 让「正在弹 Snackbar 时又刷完一次」也能排上队、不丢。
     */
    private val _syncDone = MutableSharedFlow<SyncResult>(extraBufferCapacity = 1)
    val syncDone: SharedFlow<SyncResult> = _syncDone.asSharedFlow()

    /**
     * assets 由 Room Flow 驱动、其余字段由刷新动作驱动，用 combine 汇成一条流。
     *
     * 去重口径与原来单独一条时完全相同（[Asset.isDisplayEquivalentTo]：
     * 按界面真正依赖的字段去重，避免落价只改时间戳也整屏重组）。
     */
    val uiState: StateFlow<PortfolioUiState> = repository.getAllAssets()
        .distinctUntilChanged { a, b -> a.isDisplayEquivalentTo(b) }
        .combine(_uiState) { assets, state -> state.copy(assets = assets) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PortfolioUiState())

    fun clearDividendWarning() {
        _uiState.update { it.copy(dividendWarning = null) }
    }

    private val _existingAccounts = MutableStateFlow<List<String>>(emptyList())

    val existingAccounts: StateFlow<List<String>> = _existingAccounts.asStateFlow()

    // 资产列表已从资产页并入这里，显示开关与排序偏好随之接管
    private val _preferences = MutableStateFlow(
        container.dashboardPreferencesManager.loadPreferences()
    )
    val preferences: StateFlow<DashboardPreferences> = _preferences.asStateFlow()

    /**
     * 回前台时从磁盘重新加载偏好，推送到 [_preferences]。
     *
     * 与 DashboardViewModel.reloadPreferences 是同一类问题：本 VM 挂在 Activity 作用域、
     * 跨标签页长期存活，而偏好只在构造期加载一次（见 [_preferences]）。用户在
     * 「设置 → 自定义仪表盘」改完开关后，设置页只写磁盘，本 VM 的内存副本仍是旧值，
     * 表现为「回持仓页设置没生效」——负债照旧显示、隐藏低价值开关改不动；
     * 而首页因为有 ON_RESUME reload 却是正常的，同一份设置两个页面表现不一致。
     */
    fun reloadPreferences() {
        // 与 Dashboard 一致：一次要读 20 个 SharedPreferences 键（含 getStringSet），
        // 放主线程会让「回到持仓页」那一帧卡一下 → 切 IO 线程读
        viewModelScope.launch(Dispatchers.IO) {
            _preferences.value = container.dashboardPreferencesManager.loadPreferences()
        }
    }

    /**
     * 持仓页各分组的展开/收起状态。
     *
     * 提升到 ViewModel 而非 composable 的 remember：后者只活在这个页面的组合期，
     * 用户切到别的标签页 → 页面退出组合 → remember 记忆被清；再回来时「首屏全展开」
     * 逻辑会把用户手动收起的分组重新展开（梦瑶反馈"折叠了退出再进又展开"）。
     * VM 挂在 Activity 作用域、跨标签页存活，收起状态因此留得住。
     */
    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    /** 已见过的分组 key，用于「只自动展开新出现的分组」 */
    private var knownGroups: Set<String> = emptySet()
    private var groupsInitialized = false

    /**
     * 页面带入当前分组 key 时调用。
     *
     * @param marketKeys 一级（市场大区）分组的 key。**这些永远不自动展开**：
     *        首屏组合时 `assets` 往往还是空的（Room 的 Flow 要等查询才吐数据），
     *        市场头是数据到位那一帧才出现的。若照常规「补新」逻辑处理，
     *        它们会被当成新分组**全部自动展开**，「默认折叠到一级」当场失效
     *        （实测首次进去仍全展开，根因就是这个）。
     *
     * 首次进场：只展开一级以下的（账户头），一级头全收起
     *          → 只看到 场内 / 场外 / 加密货币 / 其他 四个概览头（各带合计）。
     * 之后：只补"新出现"的分组，不动用户已收起的
     *       （梦瑶反馈过"折叠了退出再进又展开"，手动状态必须留得住）。
     *
     * 连带效果：展开某个市场后它下面的账户头是新出现的 key，会被自动补成展开，
     * 所以点一级标题会一路展开到资产，不用再逐个点账户。
     */
    fun syncGroups(currentKeys: Set<String>, marketKeys: Set<String> = emptySet()) {
        if (!groupsInitialized) {
            _expandedGroups.value = currentKeys - marketKeys
            groupsInitialized = true
        } else {
            // 一级头必须排除在「补新」之外，理由见 KDoc：否则数据到位那帧会把它们全展开
            val added = (currentKeys - knownGroups) - marketKeys
            if (added.isNotEmpty()) _expandedGroups.update { it + added }
        }
        knownGroups = currentKeys
    }

    fun toggleGroup(key: String) {
        _expandedGroups.update { if (key in it) it - key else it + key }
    }

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
            _uiState.update {
                it.copy(
                    estimatedAnnualDividend = cached.annualDividendCache,
                    incomeCalculationStatus = if (cached.annualDividendCache == 0.0) "暂无分红数据" else null,
                    dividendLoading = false
                )
            }
            hasLoadedDividend = true
            return
        }

        viewModelScope.launch(refreshExceptionHandler) {
            if (!hasLoadedDividend) _uiState.update { it.copy(dividendLoading = true) }
            try {
                // cacheOnly：冷启动只读缓存，不为了「预计分红」去敲几十个接口。
                // 下面的批量同步 / 下拉刷新路径不带这个参数，走的是最新数据
                val result = incomeUseCase.load(
                    stockAssets,
                    forceRefresh = false,
                    tag = "PortfolioVM",
                    cacheOnly = true
                )
                _uiState.update {
                    it.copy(
                        estimatedAnnualDividend = result.totalAnnualIncome,
                        incomeCalculationStatus = if (result.totalAnnualIncome == 0.0 && !result.hasDividendData) {
                            "暂无分红数据"
                        } else null
                    )
                }
                hasLoadedDividend = true

                // 抓到手就存缓存：下次进场同一批持仓直接命中
                persistPreferences { state ->
                    state.copy(
                        annualDividendCache = result.totalAnnualIncome,
                        annualDividendSymbols = symbols
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("计算分红失败: ${e.message}", e, "PortfolioVM")
            } finally {
                _uiState.update { it.copy(dividendLoading = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(refreshExceptionHandler) {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            // 两个范围，别混（与资产页 DashboardViewModel 同一套规则）：
            // - priceAssets 用于同步，只含有行情的类型。原先只含 STOCK+FUND，
            //   把加密货币漏了——而持仓页是显示加密货币的，于是在本页点刷新，
            //   币价永远不更新，得跑去资产页刷；
            // - assetsOnly 用于收入计算，必须含银行存款，否则漏掉利息收入。
            val priceAssets = uiState.value.assets.filter { it.type in AssetType.getPriceSyncedTypes() }
            val assetsOnly = uiState.value.assets.filter { !it.type.isLiability }
            // 阶段日志：闪退发生在「运行一段时间后」，只有知道走到哪一步才能定位
            AppLogger.i(
                "刷新开始：待同步 ${priceAssets.size} 项，分红预取 ${priceAssets.mapNotNull { it.symbol }.size} 个代码",
                "PortfolioVM"
            )
            // 两个阶段分开收尾，理由见下面两处 finally 的注释
            var syncResult: SyncResult? = null
            var syncError: String? = null
            try {
                syncResult = AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = priceAssets,
                    onProgress = { progress -> _uiState.update { it.copy(syncProgress = progress) } },
                    forceRefresh = true,
                syncTrigger = "持仓页刷新"
                )
                AppLogger.i(
                    "价格同步完成（成功 ${syncResult.successCount}，失败 ${syncResult.failedCount}），开始计算分红",
                    "PortfolioVM"
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
                saveTodaySnapshotIfEligible(repository, syncResult, "PortfolioVM")
            } catch (e: CancellationException) {
                // 取消（退后台 onStop / VM 销毁）时 rethrow 会跳过下面第二阶段的 finally，
                // _isRefreshing 会永远停在 true、后续刷新全被挡死、界面一直转圈——
                // 这里补一次复位，保证取消路径刷新态也能收起（铁律：取消照旧重抛，不做吞）
                _uiState.update { it.copy(isRefreshing = false) }
                throw e
            } catch (e: Exception) {
                AppLogger.w("同步失败: ${e.message}", "PortfolioVM")
                // 静默是最糟的反馈：用户点了刷新，转圈停下，什么也没发生
                syncError = e.message ?: "未知错误"
            } finally {
                // 价格同步这一段结束就把进度清掉：后面的收入计算是另一件事。
                // 不清的话标题会一直挂着「10 / 10」而转圈还在继续，看着像卡死
                _uiState.update { it.copy(syncProgress = null) }
            }

            try {
                val result = incomeUseCase.load(assetsOnly, forceRefresh = false, tag = "PortfolioVM")
                _uiState.update {
                    it.copy(
                        estimatedAnnualDividend = result.totalAnnualIncome,
                        incomeCalculationStatus = null
                    )
                }
                AppLogger.i(
                    "刷新完成：年度分红 ${result.totalAnnualIncome}",
                    "PortfolioVM"
                )

                // 手动刷新说明用户想看最新数，抓到就更新缓存
                val symbols = assetsOnly.mapNotNull { it.symbol }.toSet()
                persistPreferences { state ->
                    state.copy(
                        annualDividendCache = result.totalAnnualIncome,
                        annualDividendSymbols = symbols
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("刷新失败: ${e.message}", e, "PortfolioVM")
                _uiState.update { it.copy(errorMessage = "加载分红收入失败：${e.message ?: "未知错误"}") }
            } finally {
                // 结果反馈统一放到最后：转圈停下与提示出现应当同时发生。
                // 原先在同步一结束就弹「刷新完成」，那时转圈还在为收入计算而转，
                // 用户会看到「已完成」的提示和一个仍在转的按钮，自相矛盾
                _uiState.update { state ->
                    if (state.errorMessage == null) {
                        state.copy(errorMessage = when {
                            syncError != null -> "刷新失败：$syncError"
                            // 时间预算兜底：超时不是失败，但要让用户知道「这次没跑完」、
                            // 界面上是保留的上次数据，而不是转圈到天荒地老
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
        } else {
            repository.insertAsset(asset)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // 「设置 → 仪表盘显示」里把某类资产全关掉后，这里会整片空白，
    // 而开关只在设置页 —— 必须给一个就地恢复的出口。
    fun showAllAssetTypes() {
        // 先拉磁盘最新偏好再合并，避免用陈旧副本整体回写（见 persistPreferences 说明）
        persistPreferences { state -> state.copy(
            showBankDeposits = true,
            showStocks = true,
            showFunds = true,
            showCrypto = true,
            showLiabilities = true
        ) }
    }

    fun updateSortOrder(sortOrder: SortOrder) {
        persistPreferences { state -> state.copy(sortOrder = sortOrder) }
    }

    /**
     * 持久化偏好：统一走 [com.example.stockdiary.data.prefs.DashboardPreferencesManager.updatePreferences]，
     * 即「先拉磁盘最新值 → 只合并要改的字段 → 整体回写」，并同步内存副本。
     *
     * 必须的修复点：原先直接 `savePreferences(_preferences.value)` 把**构造期一次性加载、
     * 可能已过期**的内存副本整体回写，会覆盖别的页面刚写盘的值——最典型的就是
     * 日历页 FIRE 计算里改的 [DashboardPreferences.monthlyExpense]：用户在日历改了月度生活费，
     * 一旦持仓页（或任何持有一份陈旧偏好的页面）因为刷新/排序/恢复显示等无关操作触发保存，
     * 就会把 monthlyExpense 冲回旧值，表现为「改了但不保存」。
     *
     * 各页面共享同一块 DashboardPreferences，任一页面用陈旧副本整体回写都会踩掉别的页面刚改的字段。
     * 现在统一收敛到 Manager 的原子「读-改-写」入口（Calendar / Dashboard 同样走这里），
     * 从根上保证「只动我改的字段」，杜绝互相踩踏。
     */
    private fun persistPreferences(mutate: (DashboardPreferences) -> DashboardPreferences) {
        val updated = container.dashboardPreferencesManager.updatePreferences(mutate)
        _preferences.value = updated
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
                _uiState.update { it.copy(errorMessage = "批量删除失败") }
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
                _uiState.update { it.copy(errorMessage = "批量移动账户失败") }
            }
        }
    }

    fun batchSync(assets: List<Asset>) {
        viewModelScope.launch(refreshExceptionHandler) {
            _uiState.update { it.copy(isRefreshing = true) }
            AppLogger.i("批量同步开始：${assets.size} 项", "PortfolioVM")
            try {
                val result = AssetSyncManager.syncAllAssets(
                    context = container.context,
                    repository = repository,
                    assets = assets,
                    onProgress = { },
                    forceRefresh = true,
                syncTrigger = "持仓页批量刷新"
                )
                // 批量同步同样可能被时间预算截断：勾选的标的只同步了一部分，
                // 不提示的话用户会以为整批都更新了
                if (result.truncatedByTimeLimit) {
                    _uiState.update { it.copy(errorMessage = "同步超时，已保留上次数据") }
                }
                // 同步完刷新年度分红——但必须按**全量**持仓算，不能用本次勾选的子集。
                // 原来这里直接 incomeUseCase.load(assets /* 仅勾选项 */) 并把子集合计赋给
                // _estimatedAnnualDividend，而这个值平时是全量口径（loadDividendIncome/refresh
                // 都用 assetsOnly）。于是勾选 2~3 只点「同步」后，顶部年度分红变成了这几只的合计；
                // 更糟的是 dividendSymbolsKey 没变、LaunchedEffect 不会重跑，错值会一直挂着，
                // 直到全量刷新或持仓集合变化才恢复。
                // 注意：本函数入参也叫 assets（勾选项），会遮蔽同名的全量 StateFlow，
                // 故这里必须写 this.assets 显式取全量
                val allAssets = uiState.value.assets.filter { !it.type.isLiability }
                if (allAssets.isNotEmpty()) {
                    val income = incomeUseCase.load(allAssets, forceRefresh = false, tag = "PortfolioVM")
                    _uiState.update { it.copy(estimatedAnnualDividend = income.totalAnnualIncome) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("批量同步失败: ${e.message}", "PortfolioVM")
                _uiState.update { it.copy(errorMessage = "批量同步失败") }
            } finally {
                // 批量同步同样可能撞天天基金限流：收尾消费一次提示（与 refresh 同一套）
                if (dividendRepository.consumeOtcRateLimitWarning()) {
                    _uiState.update {
                        it.copy(dividendWarning = "今日部分场外基金分红因天天基金限流未能抓取完整，可稍后手动重试")
                    }
                }
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    companion object {
        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { PortfolioViewModel(container) }
        }
    }
}