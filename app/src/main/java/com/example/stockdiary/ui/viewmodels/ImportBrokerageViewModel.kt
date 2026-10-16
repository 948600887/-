package com.example.stockdiary.ui.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.importer.BrokerageImportService
import com.example.stockdiary.data.importer.OcrStatementService
import com.example.stockdiary.data.importer.SymbolResolver
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException

/**
 * 交割单导入 ViewModel：支持 CSV 文件 和 截图 OCR 两种来源。
 *
 *   SELECT_FILE(选来源) → [CSV: MAPPING →] PREVIEW → COMMITTING → RESULT
 *                         [OCR: 直接到 PREVIEW]
 */
class ImportBrokerageViewModel(
    private val service: BrokerageImportService,
    private val ocrService: OcrStatementService
) : ViewModel() {

    enum class Step { SELECT_FILE, MAPPING, PREVIEW, COMMITTING, RESULT }
    enum class Source { CSV, OCR }

    data class UiState(
        val step: Step = Step.SELECT_FILE,
        val source: Source? = null,
        val uri: Uri? = null,
        val parseResult: BrokerageImportService.ParseResult? = null,
        val transactions: List<BrokerageImportService.ParsedTransaction> = emptyList(),
        val committing: Boolean = false,
        val commitResult: BrokerageImportService.CommitResult? = null,
        val error: String? = null,
        val charsetName: String = "GBK",
        val ocrYear: Int? = null,
        // OCR 识别出的完整原文（预览页可随时查看/复制，便于反馈识别问题）
        val ocrRawText: String? = null,
        /**
         * 重建出的「视觉行」（同一行的多列已合并）。解析结果不对时，
         * 这一份比原始文本更能说明问题出在行还原还是行配对。
         */
        val ocrLines: List<String> = emptyList(),
        /**
         * 每行「你可能想找的是」候选代码列表（key = rowNumber）。
         * 只有无法自动回填的行才会有候选——已经匹配好的行不需要占空间。
         */
        val suggestions: Map<Int, List<SymbolResolver.Candidate>> = emptyMap(),
        /** 本次自动回填了多少行（预览页顶部展示「自动匹配 N 行」） */
        val autoFilledCount: Int = 0,
        /** OCR 识别进行中：识别通常要 1~3 秒，期间停留在来源选择页显示进度 */
        val recognizing: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 逐行的「补填证券代码」校验任务，用于防抖 */
    private val symbolCheckJobs = mutableMapOf<Int, Job>()

    private companion object {
        /** 输入停顿多久后才做校验与查库，避免每敲一个字符查一次库、也避免半截输入闪错误 */
        const val SYMBOL_CHECK_DEBOUNCE_MS = 450L

        /** 6 位数字（可带 sh/sz/bj 前缀），或 5 位港股代码 */
        val SYMBOL_PATTERN = Regex("^(?:(?:sh|sz|bj)\\d{6}|\\d{6}|\\d{5})$", RegexOption.IGNORE_CASE)

        const val ERR_SYMBOL_FORMAT_PREFIX = "证券代码格式不正确"
        const val SYMBOL_HINT = "应为 6 位数字，如 515080"
    }

    val supportedCharsets: List<Pair<String, Charset>> =
        BrokerageImportService.SUPPORTED_CHARSETS

    /** 列映射字段名（UI 下拉用） */
    val mappingFields = listOf("日期", "代码", "名称", "方向", "数量", "价格", "手续费", "账户", "不映射")

    /** CSV 文件被选中后：解析，自动探测编码与列映射，进入映射步骤 */
    fun onFileSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(
                uri = uri, source = Source.CSV, step = Step.MAPPING, error = null,
                transactions = emptyList(), commitResult = null
            ) }
            try {
                val result = service.parse(uri)
                _uiState.update { state -> state.copy(
                    parseResult = result,
                    charsetName = result.charset.name()
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("交割单解析失败: ${e.message}", e, "ImportVM")
                _uiState.update { state -> state.copy(
                    step = Step.SELECT_FILE,
                    error = "解析失败: ${e.message ?: "未知错误"}"
                ) }
            }
        }
    }

    /** 截图被选中后：OCR 识别并解析，直接进入预览（截图格式固定，无需列映射） */
    fun onImageSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // 停留在选择页展示进度条，不要直接跳 PREVIEW：
            // OCR 通常要 1~3 秒，先跳过去会闪一个「共 0 行」的空预览页。
            _uiState.update { state -> state.copy(
                uri = uri, source = Source.OCR, error = null, step = Step.SELECT_FILE,
                transactions = emptyList(), commitResult = null, ocrRawText = null,
                ocrLines = emptyList(),
                suggestions = emptyMap(), autoFilledCount = 0, recognizing = true
            ) }
            try {
                val result = ocrService.recognizeAndParse(uri)
                if (result.transactions.isEmpty()) {
                    // OCR 成功但没匹配到交易 — 把完整原文存起来，用可滚动弹窗展示 + 复制按钮排障
                    _uiState.update { state -> state.copy(
                        step = Step.SELECT_FILE,
                        recognizing = false,
                        error = "未识别到交易记录。当前解析规则适配【中国银河证券】对账单截图，" +
                            "请确认截图内容完整、清晰，且包含「证券买入-名称 价格 金额」与" +
                            "「买/卖 日期 数量 费用」两行结构。\n\n可点「复制原文」把 OCR 原文发出来，便于定位格式差异。",
                        ocrRawText = result.recognizedText
                    ) }
                } else {
                    // 截图没有证券代码：先用「记忆/持仓/历史流水/证券库」把能自动回填的都填上，
                    // 用户只需处理剩下的少数行（且每记一次都写进记忆，下次连这步也省了）
                    val filled = service.autoFillSymbols(result.transactions)
                    _uiState.update { state -> state.copy(
                        step = Step.PREVIEW,
                        recognizing = false,
                        transactions = filled,
                        ocrYear = result.year,
                        // 保留原文与还原后的行：解析结果不对时，预览页能当场核对是哪一步的问题
                        ocrRawText = result.recognizedText,
                        ocrLines = result.lines,
                        autoFilledCount = filled.count {
                            it.symbol.isNotBlank() && it.symbolSource.isNotEmpty()
                        }
                    ) }
                    // 自动回填的行也要跑一遍查重，与库内重复的照样标黄跳过
                    filled.filter { it.symbol.isNotBlank() && it.symbolSource.isNotEmpty() }
                        .forEach { tx ->
                            runCatching { validateSymbolRow(tx.rowNumber) }
                                .onFailure { AppLogger.w("自动回填行校验失败 row=${tx.rowNumber}: ${it.message}", "ImportBrokerage") }
                        }
                    // 剩下的空代码行给候选，点一下就填上
                    refreshSuggestions()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("OCR 识别失败: ${e.message}", e, "ImportVM")
                val msg = when {
                    e.message?.contains("model") == true ->
                        "OCR 模型加载失败: ${e.message}\n请尝试重新安装 App。"
                    e is java.io.IOException ->
                        "图片读取失败: ${e.message}\n请重新选择图片。"
                    else ->
                        "OCR 识别失败: ${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
                }
                _uiState.update { state -> state.copy(
                    step = Step.SELECT_FILE,
                    recognizing = false,
                    error = msg
                ) }
            }
        }
    }

    /** 切换编码后重新解析（保留用户已调的列映射索引） */
    fun changeCharset(charsetName: String) {
        val uri = _uiState.value.uri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val charset = supportedCharsets.firstOrNull { it.first == charsetName }?.second
                    ?: return@launch
                val oldMapping = _uiState.value.parseResult?.mapping
                val result = service.reparse(uri, charset)
                val headers = result.csv.headers
                val clamped = result.mapping.copy(
                    dateCol = oldMapping?.dateCol?.takeIf { it < headers.size } ?: result.mapping.dateCol,
                    symbolCol = oldMapping?.symbolCol?.takeIf { it < headers.size } ?: result.mapping.symbolCol,
                    nameCol = oldMapping?.nameCol?.takeIf { it < headers.size } ?: result.mapping.nameCol,
                    typeCol = oldMapping?.typeCol?.takeIf { it < headers.size } ?: result.mapping.typeCol,
                    quantityCol = oldMapping?.quantityCol?.takeIf { it < headers.size } ?: result.mapping.quantityCol,
                    priceCol = oldMapping?.priceCol?.takeIf { it < headers.size } ?: result.mapping.priceCol,
                    feeCol = oldMapping?.feeCol?.takeIf { it < headers.size } ?: result.mapping.feeCol,
                    accountCol = oldMapping?.accountCol?.takeIf { it < headers.size } ?: result.mapping.accountCol
                )
                _uiState.update { state -> state.copy(
                    parseResult = result.copy(mapping = clamped),
                    charsetName = charset.name()
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("重新解析失败: ${e.message}", e, "ImportVM")
                _uiState.update { state -> state.copy(error = "重新解析失败: ${e.message}") }
            }
        }
    }

    /** 更新某个字段映射的列索引 */
    fun updateMapping(field: String, colIndex: Int) {
        val current = _uiState.value.parseResult ?: return
        val mapping = current.mapping
        val idx = if (field == "不映射") -1 else colIndex
        val newMapping = when (field) {
            "日期" -> mapping.copy(dateCol = idx)
            "代码" -> mapping.copy(symbolCol = idx)
            "名称" -> mapping.copy(nameCol = idx)
            "方向" -> mapping.copy(typeCol = idx)
            "数量" -> mapping.copy(quantityCol = idx)
            "价格" -> mapping.copy(priceCol = idx)
            "手续费" -> mapping.copy(feeCol = idx)
            "账户" -> mapping.copy(accountCol = idx)
            else -> mapping
        }
        _uiState.update { state -> state.copy(parseResult = current.copy(mapping = newMapping)) }
    }

    /** 确认列映射 → mapRows → 进入预览 */
    fun applyMapping() {
        val parsed = _uiState.value.parseResult ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mapped = service.autoFillSymbols(service.mapRows(parsed))
                _uiState.update { state -> state.copy(
                    transactions = mapped,
                    step = Step.PREVIEW,
                    autoFilledCount = mapped.count {
                        it.symbol.isNotBlank() && it.symbolSource.isNotEmpty()
                    }
                ) }
                refreshSuggestions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("转换交易记录失败: ${e.message}", e, "ImportVM")
                _uiState.update { state -> state.copy(error = "转换失败: ${e.message}") }
            }
        }
    }

    /** 在预览页切换某行的跳过勾选 */
    fun toggleSkip(rowNumber: Int) {
        val txs = _uiState.value.transactions.map {
            if (it.rowNumber == rowNumber) it.copy(skip = !it.skip) else it
        }
        _uiState.update { state -> state.copy(transactions = txs) }
    }

    /**
     * 在预览页编辑某行的证券代码。
     *
     * 截图本身没有代码，绝大多数行会先由 [BrokerageImportService.autoFillSymbols]
     * 自动回填；这里处理的是**没自动匹配上**的那部分行。
     *
     * 这一次手填不会白填：代码校验通过后立刻做两件事——
     * 1. 写进记忆库（`名称 → 代码`），**下次同名标的直接自动回填，不用再填**；
     * 2. 同步给同一张截图里剩下的同名行（一张单子里同一标的常出现多次）。
     *
     * 输入即时回显；格式校验与查重做 [SYMBOL_CHECK_DEBOUNCE_MS] 防抖，
     * 既避免每敲一个字符就查一次库，也避免"5"这种半截输入立刻闪出格式错误。
     */
    fun updateSymbol(rowNumber: Int, raw: String) {
        val value = raw.trim()
        updateTransaction(rowNumber) { tx ->
            tx.copy(
                symbol = value,
                // 用户动过手就把"自动匹配"标签摘掉，避免用户以为这是系统猜的
                symbolSource = "",
                errors = tx.errors.filterNot { isSymbolNote(it) },
                isDuplicate = false // 代码变了，上一次的查重结论作废
            )
        }
        symbolCheckJobs.remove(rowNumber)?.cancel()
        symbolCheckJobs[rowNumber] = viewModelScope.launch {
            delay(SYMBOL_CHECK_DEBOUNCE_MS)
            try {
                val valid = validateSymbolRow(rowNumber)
                if (valid) {
                    val tx = _uiState.value.transactions.firstOrNull { it.rowNumber == rowNumber }
                    if (tx != null && tx.symbol.isNotBlank() && tx.name.isNotBlank()) {
                        // 记住这次手填，下次同名标的直接自动回填
                        service.rememberSymbol(tx.name, tx.symbol)
                        propagateToSameName(rowNumber)
                        refreshSuggestions()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 校验失败（如数据库异常）只记日志，不能让防抖任务崩掉整个作用域
                AppLogger.w("代码校验失败 row=$rowNumber: ${e.message}", "ImportBrokerage")
            }
        }
    }

    /**
     * 把某一行的代码同步给同一张截图里**同名但还空着**的行。
     *
     * 一份对账单里同一个标的出现多次很常见（分批买入），让用户一行行填同样的
     * 6 位数字毫无意义。填一行，其余自动跟随（标记来源「本批同名」，用户一眼看得出是联动的）。
     */
    private suspend fun propagateToSameName(originRow: Int) {
        val snapshot = _uiState.value.transactions
        val origin = snapshot.firstOrNull { it.rowNumber == originRow } ?: return
        val key = SymbolResolver.key(origin.name)
        if (key.isBlank() || origin.symbol.isBlank()) return

        val targets = snapshot.filter {
            it.rowNumber != originRow && it.symbol.isBlank() && SymbolResolver.key(it.name) == key
        }
        if (targets.isEmpty()) return

        val targetRows = targets.map { it.rowNumber }.toSet()
        _uiState.update { state -> state.copy(
            transactions = _uiState.value.transactions.map { tx ->
                if (tx.rowNumber !in targetRows) tx
                else tx.copy(
                    symbol = origin.symbol,
                    symbolSource = BrokerageImportService.SYMBOL_SRC_SAME_ROW,
                    errors = tx.errors.filterNot { isSymbolNote(it) }
                )
            }
        ) }
        // 联动填上的行同样要查一次重，避免把已导入过的流水重复写一遍
        targetRows.forEach { row ->
            runCatching { validateSymbolRow(row) }
                .onFailure { AppLogger.w("联动行校验失败 row=$row: ${it.message}", "ImportBrokerage") }
        }
    }

    /**
     * 刷新「候选代码」列表：只给还没填上代码的行查，同名标的只查一次库。
     * 候选以 chips 形式展示在预览页，用户点一下即可填入（并顺带写进记忆）。
     */
    private suspend fun refreshSuggestions() {
        val pending = _uiState.value.transactions.filter {
            it.symbol.isBlank() && it.name.isNotBlank()
        }
        if (pending.isEmpty()) {
            if (_uiState.value.suggestions.isNotEmpty()) {
                _uiState.update { state -> state.copy(suggestions = emptyMap()) }
            }
            return
        }
        val cache = LinkedHashMap<String, List<SymbolResolver.Candidate>>()
        val next = LinkedHashMap<Int, List<SymbolResolver.Candidate>>()
        for (tx in pending) {
            val key = SymbolResolver.key(tx.name)
            if (key.isBlank()) continue
            val cands = cache.getOrPut(key) { service.suggestSymbols(tx.name) }
            if (cands.isNotEmpty()) next[tx.rowNumber] = cands
        }
        _uiState.update { state -> state.copy(suggestions = next) }
    }

    /**
     * 校验单行的证券代码，并刷新它的重复标记。
     *
     * @return 代码是否合法（非空且格式正确）且不与库内记录重复。调用方据此决定是否写入记忆。
     */
    private suspend fun validateSymbolRow(rowNumber: Int): Boolean {
        val tx = _uiState.value.transactions.firstOrNull { it.rowNumber == rowNumber } ?: return false
        var duplicate = false
        val notes = when {
            tx.symbol.isBlank() -> listOf(BrokerageImportService.ERR_SYMBOL_REQUIRED)
            !SYMBOL_PATTERN.matches(tx.symbol) -> listOf(symbolFormatError(tx.symbol))
            else -> {
                duplicate = runCatching { service.isDuplicate(tx) }.getOrDefault(false)
                emptyList()
            }
        }
        updateTransaction(rowNumber) { current ->
            current.copy(
                errors = current.errors.filterNot { isSymbolNote(it) } + notes,
                isDuplicate = duplicate
            )
        }
        return notes.isEmpty() && !duplicate
    }

    private fun isSymbolNote(msg: String): Boolean =
        msg == BrokerageImportService.ERR_SYMBOL_REQUIRED || msg.startsWith(ERR_SYMBOL_FORMAT_PREFIX)

    private fun symbolFormatError(symbol: String): String =
        "$ERR_SYMBOL_FORMAT_PREFIX：$symbol（$SYMBOL_HINT）"

    private fun updateTransaction(
        rowNumber: Int,
        transform: (BrokerageImportService.ParsedTransaction) -> BrokerageImportService.ParsedTransaction
    ) {
        val txs = _uiState.value.transactions.map {
            if (it.rowNumber == rowNumber) transform(it) else it
        }
        _uiState.update { state -> state.copy(transactions = txs) }
    }

    /** 确认提交：CSV 与 OCR 两条路径统一走 BrokerageImportService.commit */
    fun commit() {
        // 防抖窗口内用户可能直接按了提交，此时校验结论还没落到状态里。
        // 提交前把 OCR 行的代码同步校验一遍，避免把格式错误的代码写进库。
        // 注意：本段运行在点击回调线程（主线程）上，没有协程兜底，
        // 任何异常都会直接闪退，必须自己兜住。
        val preValidated = try {
            _uiState.value.transactions.map { tx ->
                if (_uiState.value.source != Source.OCR) tx
                else {
                    val notes = tx.errors.filterNot { isSymbolNote(it) }
                    when {
                        tx.symbol.isBlank() ->
                            tx.copy(errors = notes + BrokerageImportService.ERR_SYMBOL_REQUIRED)
                        !SYMBOL_PATTERN.matches(tx.symbol) ->
                            tx.copy(errors = notes + symbolFormatError(tx.symbol))
                        else -> tx.copy(errors = notes)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("提交前校验异常: ${e.message}", e, "ImportVM")
            _uiState.update { state -> state.copy(error = "提交前校验失败: ${e.message}") }
            return
        }
        _uiState.update { state -> state.copy(transactions = preValidated) }

        val txs = preValidated
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(step = Step.COMMITTING, committing = true) }
            try {
                val result = service.commit(txs)
                _uiState.update { state -> state.copy(
                    committing = false, commitResult = result, step = Step.RESULT
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("交割单提交失败: ${e.message}", e, "ImportVM")
                _uiState.update { state -> state.copy(
                    committing = false,
                    step = Step.PREVIEW,
                    error = "提交失败: ${e.message}"
                ) }
            }
        }
    }

    /** 返回映射步骤重新调整（仅 CSV 流程） */
    fun backToMapping() {
        _uiState.update { state -> state.copy(step = Step.MAPPING, transactions = emptyList()) }
    }

    /** 重置整个流程 */
    fun reset() {
        symbolCheckJobs.values.forEach { it.cancel() }
        symbolCheckJobs.clear()
        _uiState.value = UiState()
    }

    fun clearError() {
        _uiState.update { state -> state.copy(error = null) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ImportBrokerageViewModel(
                container.brokerageImportService,
                container.ocrStatementService
            ) as T
        }
    }
}
