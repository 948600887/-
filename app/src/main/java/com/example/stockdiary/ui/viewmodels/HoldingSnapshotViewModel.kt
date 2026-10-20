package com.example.stockdiary.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.importer.FundEStatementParser
import com.example.stockdiary.data.importer.FundSnapshotParser
import com.example.stockdiary.data.importer.HoldingSnapshotParser
import com.example.stockdiary.data.importer.HoldingSnapshotResult
import com.example.stockdiary.data.importer.HoldingSnapshotService
import com.example.stockdiary.data.importer.ImageTextRecognizer
import com.example.stockdiary.data.importer.XlsxTableReader
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 持仓截图导入 ViewModel。
 *
 *   SELECT_IMAGE(选图，识别中) → PREVIEW(逐条核对 + 填账户名) → COMMITTING → RESULT
 *
 * 比交割单导入简单：截图格式固定，没有列映射这一步；
 * 而且持仓页带证券代码，不需要走「名称→代码」回填。
 *
 * ## 两种来源共用这一套流程
 *
 * [Source.STOCK_SNAPSHOT] 走 OCR，[Source.FUND_EXPORT] 直接读 xlsx——
 * 但「读进来 → 按代码对齐 → 预览 → 落库」这段完全一致，
 * 所以没有另开一个 ViewModel：另开一个迟早两边跑偏，
 * 这正是当初把 ImageTextRecognizer 从交割单服务里抽出来的同一个理由。
 *
 * 状态流与 IO 协程模板已上收至 [ImportWizardViewModel]，这里只留来源特有逻辑。
 */
open class HoldingSnapshotViewModel(
    private val context: Context,
    private val repository: AssetRepository,
    private val service: HoldingSnapshotService,
    private val recognizer: ImageTextRecognizer,
    private val source: Source
) : ImportWizardViewModel<HoldingSnapshotViewModel.UiState>(HoldingSnapshotViewModel.UiState()) {

    /** 导入来源。决定选文件的方式与解析走哪条路 */
    enum class Source {
        /** 中国结算「一码通持有查询」：场内持仓，只能截图，走 OCR */
        STOCK_SNAPSHOT,

        /** 基金E账户：场外基金，页面可导出 xlsx 明细，直接读文件 */
        FUND_EXPORT
    }

    enum class Step { SELECT_IMAGE, PREVIEW, COMMITTING, RESULT }

    data class UiState(
        val step: Step = Step.SELECT_IMAGE,
        /** OCR 进行中：识别通常 1~3 秒，期间停留在选图页显示进度 */
        val recognizing: Boolean = false,
        val preview: HoldingSnapshotService.Preview? = null,
        /** 导入到哪个账户下。预填库里已有的账户，也可以直接改成新名字 */
        val account: String = "",
        /** 库里已有的账户名，供界面做快捷选择 */
        val knownAccounts: List<String> = emptyList(),
        val result: HoldingSnapshotService.CommitResult? = null,
        val error: String? = null,
        /** 识别原文：解析结果不对时用来排障 */
        val recognizedText: String? = null,
        val lines: List<String> = emptyList(),
        /** 行情同步进行中 */
        val syncing: Boolean = false,
        /** 同步进度与结果文案 */
        val syncMessage: String? = null
    )

    init {
        // 预填库里最近用过的账户名：让「选一个账户」多数时候只需点一下确认，
        // 而不是每次都重新敲一遍
        viewModelScope.launch {
            val accounts = loadKnownAccounts(repository)

            updateState { state ->
                state.copy(
                    knownAccounts = accounts,
                    account = if (state.account.isBlank()) accounts.firstOrNull().orEmpty() else state.account
                )
            }
        }
    }

    /**
     * 截图选中：OCR 识别 + 解析 + 与库里对齐，直接进预览。
     *
     * 走哪条解析器由 [source] 决定：一码通那张是「名称/代码/数量」三条独立行，
     * 基金E账户那张是「名称带代码、表头、数值」三行一组的卡片。
     */
    fun onImageSelected(uri: Uri) {
        launchIo(
            logTag = "HoldingSnapshotVM",
            logMessage = { e -> "持仓截图导入失败: ${e.message}" },
            onError = { e ->
                updateState { state ->
                    state.copy(
                        recognizing = false,
                        error = "识别失败: ${e.message ?: "未知错误"}"
                    )
                }
            },
            onCancel = { updateState { it.copy(recognizing = false, step = Step.SELECT_IMAGE) } }
        ) {
            updateState { state ->
                state.copy(
                    recognizing = true, error = null, preview = null, result = null,
                    recognizedText = null, lines = emptyList(), step = Step.SELECT_IMAGE
                )
            }
            val recognized = recognizer.recognize(uri)
            val parsed = when (source) {
                Source.STOCK_SNAPSHOT -> HoldingSnapshotParser.parse(recognized.lines)
                Source.FUND_EXPORT -> FundSnapshotParser.parse(recognized.lines)
            }

            if (parsed.holdings.isEmpty()) {
                updateState { state ->
                    state.copy(
                        recognizing = false,
                        error = when (source) {
                            Source.STOCK_SNAPSHOT ->
                                "没从这张图里读到持仓。请确认截的是「中国结算 · 一码通持有查询」" +
                                    "的持仓列表，并且完整包含名称与代码两行。"
                            Source.FUND_EXPORT ->
                                "没从这张图里读到持仓。请确认截的是基金E账户「公募基金查询」" +
                                    "的资产列表，并且每张卡片完整包含名称、代码、份额和净值。"
                        } + "\n\n可点「查看识别原文」，把内容发出来便于定位格式差异。",
                        recognizedText = recognized.text,
                        lines = recognized.lines
                    )
                }
                return@launchIo
            }

            val preview = service.buildPreview(parsed)
            AppLogger.i(
                "持仓截图解析（$source）：${parsed.holdings.size} 条 → 合并后 ${preview.items.size} 条",
                "HoldingSnapshotVM"
            )
            updateState { state ->
                state.copy(
                    recognizing = false,
                    step = Step.PREVIEW,
                    preview = preview,
                    recognizedText = recognized.text,
                    lines = recognized.lines
                )
            }
        }
    }

    fun onAccountChange(value: String) {
        updateState { state -> state.copy(account = value) }
    }

    /**
     * 选中基金E账户导出的 xlsx。
     *
     * 解析出来的 [HoldingSnapshotResult] 与截图那条路**完全同构**，
     * 所以后面 buildPreview / commit 一行都不用改。
     * 区别只在数据来源：这里是官方导出的数字，比 OCR 少了一整层不确定。
     */
    fun onFileSelected(uri: Uri) {
        launchIo(
            logTag = "HoldingSnapshotVM",
            logMessage = { e -> "读取基金E账户导出失败: ${e.message}" },
            onError = { e ->
                updateState { state ->
                    state.copy(
                        recognizing = false,
                        error = "读取失败：${e.message ?: "未知错误"}\n\n" +
                            "请确认选的是基金E账户「公募基金查询」导出的 .xlsx 文件。"
                    )
                }
            },
            onCancel = { updateState { it.copy(recognizing = false, step = Step.SELECT_IMAGE) } }
        ) {
            updateState { state ->
                state.copy(
                    recognizing = true, error = null, preview = null, result = null,
                    recognizedText = null, lines = emptyList(), step = Step.SELECT_IMAGE
                )
            }
            val rows = context.contentResolver.openInputStream(uri)?.use { input ->
                XlsxTableReader.read(input).rows
            } ?: throw IllegalStateException("打不开这个文件")

            val parsed = FundEStatementParser.parse(rows)
            if (parsed.holdings.isEmpty()) {
                // 空持仓时：若解析器留下了「问题」说明（如「没找到基金代码这一列」），
                // 把它们拼进错误文案，否则用户只看到一句泛泛的「没读到持仓」，
                // 无从判断到底是格式不对还是截错了页面
                val problems = parsed.problems
                val base = "没从这个文件里读到持仓。请确认导出自基金E账户的「公募基金查询」页（右上角导出）。"
                val separator = "；"
                val errorMsg = if (problems.isNotEmpty()) {
                    "$base\n\n" + problems.joinToString(separator)
                } else {
                    base
                }
                updateState { state ->
                    state.copy(
                        recognizing = false,
                        error = errorMsg,
                        recognizedText = null,
                        lines = emptyList()
                    )
                }
                return@launchIo
            }

            val preview = service.buildPreview(
                HoldingSnapshotResult(
                    holdings = parsed.holdings,
                    dataDate = parsed.navDate,
                    incomplete = parsed.problems
                )
            )
            AppLogger.i(
                "基金E账户导入解析：${parsed.holdings.size} 条 → 合并后 ${preview.items.size} 条，" +
                    "数据日期 ${parsed.navDate}",
                "HoldingSnapshotVM"
            )
            updateState { state ->
                state.copy(
                    recognizing = false,
                    step = Step.PREVIEW,
                    preview = preview,
                    recognizedText = null,
                    lines = emptyList()
                )
            }
        }
    }

    /** 确认导入：按 symbol 覆盖数量、其余新建。未变的不写库。 */
    fun commit() {
        val preview = _uiState.value.preview ?: return
        if (_uiState.value.step == Step.COMMITTING) return

        launchIo(
            logTag = "HoldingSnapshotVM",
            logMessage = { e -> "持仓快照落库失败: ${e.message}" },
            onError = { e ->
                updateState { state ->
                    state.copy(
                        step = Step.PREVIEW,
                        error = "导入失败: ${e.message ?: "未知错误"}"
                    )
                }
            },
            onCancel = { updateState { it.copy(step = Step.PREVIEW) } }
        ) {
            updateState { state -> state.copy(step = Step.COMMITTING, error = null) }
            val result = service.commit(preview, _uiState.value.account)
            updateState { state -> state.copy(step = Step.RESULT, result = result) }
        }
    }

    /**
     * 同步行情，把刚导入的持仓市值填上。
     *
     * ## 为什么必须由用户点，而不是导入后自动跑
     *
     * 截图里**没有价格**，参考单价刻意留 0（给个假数字会凭空造出市值），
     * 所以导入完市值是空的，得等一次行情同步。但这件事不能替用户决定：
     * 他点「导入 14 条」时预期的是写一次库，不是发起一次网络请求。
     *
     * ## 为什么只同步本次涉及的代码
     *
     * 不做全量。用户刚导完这十几条，没必要顺带把他另外几百条持仓也拉一遍——
     * 那是「持仓页刷新」那个按钮的职责。
     *
     * 注意：这里不是 launchIo 场景——同步入口要在主线程先落「同步中」状态，
     * 再切 IO 查库，与三个 VM 共用的 IO 模板形态不同，保留私有实现。
     */
    fun syncPrices() {
        if (_uiState.value.syncing) return
        val symbols = _uiState.value.preview?.items?.map { it.symbol }.orEmpty()
        if (symbols.isEmpty()) return

        viewModelScope.launch {
            updateState { state -> state.copy(syncing = true, syncMessage = "正在同步行情…") }
            try {
                // 存款/负债没有行情可查，过滤掉，别白发请求
                val assets = withContext(Dispatchers.IO) {
                    repository.getAssetsBySymbols(symbols).filter {
                        it.type == AssetType.STOCK || it.type == AssetType.FUND
                    }
                }
                if (assets.isEmpty()) {
                    updateState { state ->
                        state.copy(syncing = false, syncMessage = "这次导入的持仓没有可查的行情")
                    }
                    return@launch
                }

                val result = AssetSyncManager.syncAllAssets(
                    context = context,
                    repository = repository,
                    assets = assets,
                    onProgress = { progress ->
                        updateState { state -> state.copy(syncMessage = progress.message) }
                    },
                    forceRefresh = true,
                    // 这次同步的是刚导入的持仓：名字大概率是 OCR 认的，形近字易错
                    // （华电REIT→怯电REIT、科创50→料创50）。让同步按代码对应的行情标准名
                    // 纠偏。仅限本链路，其它同步不传，避免冲掉用户自己改过的名字。
                    correctNames = true
                )

                val message = buildString {
                    append("同步完成：成功 ${result.successCount}")
                    if (result.failedCount > 0) append("，失败 ${result.failedCount}")
                    if (result.truncatedByTimeLimit) append("（已到时间上限，剩下的下次再同步）")
                }
                updateState { state -> state.copy(syncing = false, syncMessage = message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("持仓导入后同步行情失败: ${e.message}", e, "HoldingSnapshotVM")
                updateState { state ->
                    state.copy(syncing = false, syncMessage = "同步失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun clearError() {
        updateState { state -> state.copy(error = null) }
    }

    /**
     * 复位。
     *
     * ViewModel 挂在宿主 Activity 作用域上，对话框关掉后实例还活着——
     * 不复位的话下次打开会残留上次的预览。账户名与已知账户保留，那是用户上下文。
     */
    fun reset() {
        cancelActiveJob()
        updateState { state -> UiState(
            account = state.account,
            knownAccounts = state.knownAccounts
        ) }
    }

    class Factory(
        private val container: AppContainer,
        private val source: Source
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HoldingSnapshotViewModel(
                context = container.context,
                repository = container.assetRepository,
                service = container.holdingSnapshotService,
                recognizer = container.imageTextRecognizer,
                source = source
            ) as T
        }
    }
}
