package com.example.stockdiary.ui.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.HoldingSnapshotParser
import com.example.stockdiary.data.HoldingSnapshotService
import com.example.stockdiary.data.ImageTextRecognizer
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 持仓截图导入 ViewModel。
 *
 *   SELECT_IMAGE(选图，识别中) → PREVIEW(逐条核对 + 填账户名) → COMMITTING → RESULT
 *
 * 比交割单导入简单：截图格式固定，没有列映射这一步；
 * 而且持仓页带证券代码，不需要走「名称→代码」回填。
 */
class HoldingSnapshotViewModel(
    private val repository: AssetRepository,
    private val service: HoldingSnapshotService,
    private val recognizer: ImageTextRecognizer
) : ViewModel() {

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
        val lines: List<String> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // 预填库里最近用过的账户名：让「选一个账户」多数时候只需点一下确认，
        // 而不是每次都重新敲一遍
        viewModelScope.launch {
            val accounts = withContext(Dispatchers.IO) {
                runCatching { repository.getAllAccounts() }.getOrDefault(emptyList())
            }.filter { it.isNotBlank() }

            _uiState.update { state ->
                state.copy(
                    knownAccounts = accounts,
                    account = if (state.account.isBlank()) accounts.firstOrNull().orEmpty() else state.account
                )
            }
        }
    }

    /** 截图选中：OCR 识别 + 解析 + 与库里对齐，直接进预览 */
    fun onImageSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(
                recognizing = true, error = null, preview = null, result = null,
                recognizedText = null, lines = emptyList(), step = Step.SELECT_IMAGE
            ) }
            try {
                val recognized = recognizer.recognize(uri)
                val parsed = HoldingSnapshotParser.parse(recognized.lines)

                if (parsed.holdings.isEmpty()) {
                    _uiState.update { state -> state.copy(
                        recognizing = false,
                        error = "没从这张图里读到持仓。请确认截的是「中国结算 · 一码通持有查询」" +
                            "的持仓列表，并且完整包含名称与代码两行。\n\n" +
                            "可点「查看识别原文」，把内容发出来便于定位格式差异。",
                        recognizedText = recognized.text,
                        lines = recognized.lines
                    ) }
                    return@launch
                }

                val preview = service.buildPreview(parsed)
                AppLogger.i(
                    "持仓截图解析：${parsed.holdings.size} 条 → 合并后 ${preview.items.size} 条",
                    "HoldingSnapshotVM"
                )
                _uiState.update { state -> state.copy(
                    recognizing = false,
                    step = Step.PREVIEW,
                    preview = preview,
                    recognizedText = recognized.text,
                    lines = recognized.lines
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("持仓截图导入失败: ${e.message}", e, "HoldingSnapshotVM")
                _uiState.update { state -> state.copy(
                    recognizing = false,
                    error = "识别失败: ${e.message ?: "未知错误"}"
                ) }
            }
        }
    }

    fun onAccountChange(value: String) {
        _uiState.update { state -> state.copy(account = value) }
    }

    /** 确认导入：按 symbol 覆盖数量、其余新建。未变的不写库。 */
    fun commit() {
        val preview = _uiState.value.preview ?: return
        if (_uiState.value.step == Step.COMMITTING) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(step = Step.COMMITTING, error = null) }
            try {
                val result = service.commit(preview, _uiState.value.account)
                _uiState.update { state -> state.copy(step = Step.RESULT, result = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("持仓快照落库失败: ${e.message}", e, "HoldingSnapshotVM")
                _uiState.update { state -> state.copy(
                    step = Step.PREVIEW,
                    error = "导入失败: ${e.message ?: "未知错误"}"
                ) }
            }
        }
    }

    fun clearError() {
        _uiState.update { state -> state.copy(error = null) }
    }

    /**
     * 复位。
     *
     * ViewModel 挂在宿主 Activity 作用域上，对话框关掉后实例还活着——
     * 不复位的话下次打开会残留上次的预览。账户名与已知账户保留，那是用户上下文。
     */
    fun reset() {
        _uiState.update { state -> UiState(
            account = state.account,
            knownAccounts = state.knownAccounts
        ) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HoldingSnapshotViewModel(
                repository = container.assetRepository,
                service = container.holdingSnapshotService,
                recognizer = container.imageTextRecognizer
            ) as T
        }
    }
}
