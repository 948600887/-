package com.example.stockdiary.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.importer.BinanceCredentialsManager
import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.importer.mergeEarnIntoBalances
import com.example.stockdiary.data.importer.priceKeyFor
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.sync.refreshTodaySnapshotAfterImport
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.network.BinanceAccountAPI
import com.example.stockdiary.network.BinanceBalance
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.ExchangeRateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 币安资产导入 ViewModel。
 *
 *   INPUT(填密钥) → FETCHING(拉取) → PREVIEW(逐条核对 + 填账户名) → COMMITTING → RESULT
 *
 * 与 [HoldingSnapshotViewModel] 同一套状态机思路，只是数据来源从 OCR/xlsx
 * 换成签名 HTTP。按需触发：**只在用户点「拉取」时发一次网络请求**，
 * 不做后台自动同步——密钥在用户手里，什么时候用由用户决定。
 *
 * 状态流与 IO 协程模板已上收至 [ImportWizardViewModel]，这里只留来源特有逻辑。
 */
open class BinanceImportViewModel(
    private val context: Context,
    private val repository: AssetRepository,
    private val service: BinanceImportService,
    private val credentialsManager: BinanceCredentialsManager
) : ImportWizardViewModel<BinanceImportViewModel.UiState>(BinanceImportViewModel.UiState()) {

    enum class Step { INPUT, FETCHING, PREVIEW, COMMITTING, RESULT }

    data class UiState(
        val step: Step = Step.INPUT,
        val apiKey: String = "",
        val apiSecret: String = "",
        /** 拉取中：请求通常 1~3 秒，期间停在校验页显示进度 */
        val fetching: Boolean = false,
        val preview: BinanceImportService.Preview? = null,
        /** 导入到哪个账户下。可留空，落库时走 [BinanceImportService.DEFAULT_ACCOUNT] */
        val account: String = "",
        /** 库里已有的账户名，供界面做快捷选择 */
        val knownAccounts: List<String> = emptyList(),
        val result: BinanceImportService.CommitResult? = null,
        val error: String? = null,
        /** 本次拉取用到的交易对最新价，供预览行估算市值 */
        val prices: Map<String, Double> = emptyMap(),
        /**
         * 理财（Simple Earn）接口没拉全时的提示。
         *
         * 非 null 表示这次导入只拿到了现货口径，总资产会比币安账户少一块
         * （定期/锁仓都在 Earn 里，现货账户看不到）。必须在预览页明示，
         * 否则用户只能看到「数字比对账单少」却不知道为什么。
         */
        val earnWarning: String? = null,
        /**
         * 借币（Flexible Loan）负债接口没拉全时的提示。
         *
         * 非 null 表示这次导入没计入借币负债，净资产会虚高（只记借来的钱、不记欠的钱）。
         * 必须在预览页明示，否则用户看到的总资产比对账单高、却不知道差在借币上。
         */
        val loanWarning: String? = null
    ) {
        /** 密钥都填了才能拉取 */
        val canFetch: Boolean
            get() = apiKey.isNotBlank() && apiSecret.isNotBlank() && !fetching
    }

    init {
        // 进入时预填已存密钥 + 已有账户名：老用户不必每次重敲 Key/Secret
        viewModelScope.launch {
            val (savedKey, savedSecret) = withContext(Dispatchers.IO) {
                credentialsManager.getApiKey() to credentialsManager.getApiSecret()
            }
            val accounts = loadKnownAccounts(repository)

            updateState { state ->
                state.copy(
                    apiKey = savedKey.orEmpty(),
                    apiSecret = savedSecret.orEmpty(),
                    knownAccounts = accounts
                )
            }
        }
    }

    fun onApiKeyChange(value: String) {
        updateState { it.copy(apiKey = value) }
    }

    fun onApiSecretChange(value: String) {
        updateState { it.copy(apiSecret = value) }
    }

    fun onAccountChange(value: String) {
        updateState { it.copy(account = value) }
    }

    /**
     * 拉取账户余额 → 与库里对齐 → 进预览。
     *
     * 拉取成功后把密钥存下来，下次进入自动预填。**只在成功后存**：
     * 存一个无效密钥没有意义，还会让下次带着错密钥直接拉取。
     */
    fun fetch() {
        val current = _uiState.value
        if (current.fetching) return
        if (current.apiKey.isBlank() || current.apiSecret.isBlank()) {
            updateState { it.copy(error = "请先填写 API Key 与 Secret") }
            return
        }

        launchIo(
            logTag = "BinanceImportVM",
            logMessage = { e -> "拉取币安持仓失败: ${e.message}" },
            onError = { e ->
                updateState { state ->
                    state.copy(fetching = false, step = Step.INPUT, error = "拉取失败: ${e.message ?: "未知错误"}")
                }
            },
            // 拉取进行中若协程被取消（关弹窗/离开/作用域销毁），复位进度态，
            // 否则 fetching 永不复位、弹窗卡在「正在拉取…」。取消不吞（仍重抛）。
            onCancel = { updateState { it.copy(fetching = false, step = Step.INPUT, error = null) } }
        ) {
            updateState { state ->
                state.copy(fetching = true, error = null, preview = null, result = null, step = Step.FETCHING)
            }
            val accountResult = BinanceAccountAPI.getAccount(current.apiKey, current.apiSecret)

            val balances = accountResult.getOrElse { err ->
                // 币安的拒绝原因（密钥无效/IP 不在白名单/权限不足）就在 message 里，
                // 原样带给用户，这是排障的唯一线索
                updateState { state ->
                    state.copy(fetching = false, step = Step.INPUT, error = err.message ?: "拉取失败")
                }
                return@launchIo
            }

            // 拉到了再存密钥：见方法注释
            credentialsManager.save(current.apiKey, current.apiSecret)

            // 理财持仓单拉：官方对账单证实钱可能在 Earn 里而不在现货余额里，只导现货会少算。
            // 失败（含 API Key 未开通权限）内部降级为空列表，绝不中断导入。
            //
            // 借币（Flexible Loan）负债单拉：币安不把借币计入现货余额，只走独立的
            // /sapi/v2/loan/... 接口。不拉这笔，导入的资产只有「借来的钱」、没有「欠的钱」，
            // 净资产被虚高（诊断实锤用户有 USDT 活期借币 -50.8 而此前完全没导入）。
            //
            // 汇率强制刷新、批量取价、理财持仓、借币负债四条互不依赖，并发执行省掉三次串行往返。
            // 汇率用 force=true：默认走 6 小时 TTL，实测 00:04 那次导入就沿用了 19:01 抓的
            // 6.6702（比真实价低约 6%），导入是用户主动触发的低频动作，值得多打这一次请求。
            val spotPriceMap: Map<String, Double>
            val mergedBalances: List<BinanceBalance>
            val earnWarning: String?
            val loanWarning: String?
            coroutineScope {
                val refreshDeferred = async(Dispatchers.IO) { ExchangeRateManager.refresh(force = true) }
                val earnDeferred = async(Dispatchers.IO) {
                    BinanceAccountAPI.getEarnPositions(current.apiKey, current.apiSecret)
                }
                val loanDeferred = async(Dispatchers.IO) {
                    BinanceAccountAPI.getLoans(current.apiKey, current.apiSecret)
                }
                val pairs = balances.mapNotNull { priceKeyFor(it.asset) }.distinct()
                val pricesDeferred = async(Dispatchers.IO) { BinanceAccountAPI.getPrices(pairs) }
                refreshDeferred.await()
                val earn = earnDeferred.await()
                val loan = loanDeferred.await()
                spotPriceMap = pricesDeferred.await()
                // 先合并理财（取最大值、绝不重复计），再**追加**借币负债：
                // 借币负债表示成负余额追加在 earn 合并之后，避免被 mergeEarnIntoBalances 的
                // max 规则误当成「待补齐的理财量」覆盖掉（负债必须作为独立的负余额进入
                // SnapshotAlignment 的按代码 sumOf，才能与同代码持仓自然抵消 / 落成纯负资产）。
                val loanBalances = loan.debts.map {
                    BinanceBalance(asset = it.asset, free = -it.debt, locked = 0.0)
                }
                mergedBalances = mergeEarnIntoBalances(balances, earn.positions) + loanBalances
                // 理财没拉全 = 总资产会少算，必须带到预览页说清楚（不能静默降级）
                earnWarning = earn.warning
                // 借币没拉到 = 净资产会虚高，同样必须明示
                loanWarning = loan.warning
            }

            // 理财独有的资产在上面那批取价时还不知道，补一次小额批量请求（通常个位数）。
            // 补不到价也不阻断：估不出价的资产按「保留」处理，市值等行情同步再补。
            val missingPairs = mergedBalances
                .mapNotNull { priceKeyFor(it.asset) }
                .distinct()
                .filterNot { spotPriceMap.containsKey(it) }
            val priceMap = if (missingPairs.isEmpty()) {
                spotPriceMap
            } else {
                spotPriceMap + BinanceAccountAPI.getPrices(missingPairs)
            }
            // 只取库里已有的加密货币资产来对齐：导入只可能命中这一类
            val existing = repository.getAssetsByTypes(listOf(AssetType.CRYPTO))
            // 不做任何市值过滤：零碎资产也一并导入。
            // 「按金额隐藏小币」属于展示层偏好（仪表盘的隐藏低价值虚拟货币），
            // 不应该在导入环节静默丢掉用户真实持仓——导入是记账，不是展示。
            val preview = service.buildPreview(mergedBalances, existing)

            AppLogger.i(
                "币安账户拉取：${balances.size} 项 → 合并后 ${preview.items.size} 条",
                "BinanceImportVM"
            )

            if (preview.items.isEmpty()) {
                // 一条不剩：账户为空或只剩 dust 零头。
                // 停在 INPUT 说清楚原因，别给个空预览页让用户以为出错了
                val hint = buildString {
                    append("可能账户为空或只剩dust零头。")
                    // 理财没拉全时，'账户为空' 很可能只是现货为空（钱都在 Earn 里），必须点明
                    if (earnWarning != null) append(earnWarning)
                    // 借币没拉到时，净资产会比真实偏低（没扣负债），同样要点明
                    if (loanWarning != null) append(loanWarning)
                }
                updateState { state ->
                    state.copy(
                        fetching = false,
                        step = Step.INPUT,
                        error = "账户里没有可导入的持仓。$hint",
                        prices = emptyMap()
                    )
                }
                return@launchIo
            }

            updateState { state ->
                state.copy(
                    fetching = false,
                    step = Step.PREVIEW,
                    preview = preview,
                    prices = priceMap,
                    earnWarning = earnWarning,
                    loanWarning = loanWarning
                )
            }
        }
    }

    /**
     * 确认导入：按交易对覆盖数量、其余新建。未变的不写库。
     *
     * 把拉取阶段取到的公开价一起交给落库：用户确认的那一刻就把价格预填进
     * currentPrice，行情同步还没跑（或暂时连不上）时总资产也已经是对的。
     * 价格是拉取那一刻的，用户核对预览期间可能略有偏差，下一轮行情同步即刷新。
     */
    fun commit() {
        val preview = _uiState.value.preview ?: return
        if (_uiState.value.step == Step.COMMITTING) return

        launchIo(
            logTag = "BinanceImportVM",
            logMessage = { e -> "币安持仓落库失败: ${e.message}" },
            onError = { e ->
                updateState { state ->
                    state.copy(step = Step.PREVIEW, error = "导入失败: ${e.message ?: "未知错误"}")
                }
            },
            // 写入进行中被取消时回到预览（保留 preview 让用户重试），避免停在 COMMITTING 转圈。
            onCancel = { updateState { it.copy(step = Step.PREVIEW) } }
        ) {
            updateState { state -> state.copy(step = Step.COMMITTING, error = null) }
            val result = service.commit(preview, _uiState.value.account, _uiState.value.prices)
            updateState { state -> state.copy(step = Step.RESULT, result = result) }
            // 落库成功后自动补一轮行情：预填价只覆盖拉取时取到价的标的，
            // 冷门币/新币（取不到价）与核对期间变动的价格靠这轮补齐，
            // 用户不必再手动点「同步行情」。失败只记日志——结果页已经显示了成功落库。
            syncImportedPrices(preview)
        }
    }

    /**
     * 导入后补一轮行情同步。
     *
     * 只同步**这次导入的加密资产**（按 symbol 回查，避免对几百个标的全量刷新）；
     * 失败一律吞掉记日志：落库已成功，同步失败不该把结果页打回预览。
     */
    private suspend fun syncImportedPrices(preview: BinanceImportService.Preview) {
        try {
            val imported = withContext(Dispatchers.IO) {
                repository.getAssetsBySymbols(preview.items.map { it.symbol })
                    .filter { it.type == AssetType.CRYPTO }
            }
            if (imported.isEmpty()) return
            val result = AssetSyncManager.syncAllAssets(
                context = context,
                repository = repository,
                assets = imported,
                onProgress = { /* 结果页不展示进度：这轮是后台补价，用户看到的是「导入成功」 */ },
                forceRefresh = false,
                syncTrigger = "币安导入保存"
            )
            AppLogger.i(
                "币安导入后行情同步：成功 ${result.successCount}，失败 ${result.failedCount}",
                "BinanceImportVM"
            )
            // 导入改了资产集合：历史页只读快照，不刷新的话「今天」还停在导入前
            refreshTodaySnapshotAfterImport(repository, result.successCount, "BinanceImportVM")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("币安导入后行情同步失败: ${e.message}", e, "BinanceImportVM")
        }
    }

    fun clearError() {
        updateState { it.copy(error = null) }
    }

    /**
     * 复位到输入步。
     *
     * ViewModel 挂在宿主 Activity 作用域上，对话框关掉后实例还活着——不复位
     * 下次打开会残留上次的预览。密钥与已知账户保留，那是用户上下文。
     */
    fun reset() {
        cancelActiveJob()
        updateState { state ->
            state.copy(
                step = Step.INPUT,
                fetching = false,
                preview = null,
                result = null,
                error = null,
                prices = emptyMap()
            )
        }
    }

    companion object {
        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BinanceImportViewModel(
                    context = container.context,
                    repository = container.assetRepository,
                    service = container.binanceImportService,
                    credentialsManager = container.binanceCredentialsManager
                )
            }
        }
    }
}
