package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.repository.AssetRepository
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
 * 导入向导 ViewModel 公共基类。
 *
 * 持仓截图、币安、交割单三个导入向导是同一个模式写了三遍：Step 枚举 +
 * UiState + 状态流 + `launch(Dispatchers.IO)` 里 try/catch + 复位保留账户
 * 上下文。本类收掉其中**纯机械**的部分，来源特有的字段与步骤留在子类。
 *
 * ## 托管什么
 * - 状态流管道：`_uiState` / [uiState] / [updateState]
 * - IO 协程 catch 模板 [launchIo]：CancellationException 原样重抛 +
 *   AppLogger.e + 调用方落状态
 * - 已知账户预填 [loadKnownAccounts]
 *
 * ## 不托管什么（刻意，克制优先于整齐）
 * - **Step 枚举与来源特有字段**：三者 Step 取值不同（一码通没有 FETCHING、
 *   交割单多一个 MAPPING），UiState 字段重叠不过半。合成一个大 UiState 或
 *   上 sealed 状态机，只会让每个子类多背一堆用不上的字段和 when 分支——
 *   那是造框架，不是消灭复制粘贴。
 * - **reset() 的保留策略**：持仓截图留账户、币安还留密钥、交割单全清外加
 *   取消防抖 Job。三者差异大于共性，各自写清楚比套模板更好读。
 * - **Factory**：三个构造参数不同，留在各自子类；容器侧调用方式不变。
 */
abstract class ImportWizardViewModel<S>(initialState: S) : ViewModel() {

    /** 子类 UiState 的单一事实来源。读走 `_uiState.value`，写走 [updateState] */
    protected val _uiState = MutableStateFlow(initialState)

    /** 暴露给 UI 的状态流。UI 收集到的仍是各子类的 UiState 类型，字段与迁移前一致 */
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    /** 所有状态写入口。集中一处，便于日后统一加日志或断言 */
    protected fun updateState(transform: (S) -> S) {
        _uiState.update(transform)
    }

    /**
     * 统一的 IO 协程模板，取代三个 VM 里重复了十几遍的：
     * ```
     * viewModelScope.launch(Dispatchers.IO) {
     *     try { ... }
     *     catch (e: CancellationException) { throw e }
     *     catch (e: Exception) { AppLogger.e(...); _uiState.update { ... } }
     * }
     * ```
     *
     * @param logTag 日志 tag，按类名字面量（项目惯例）
     * @param logMessage 失败日志文案。日志写给排障的人，可与界面提示不同
     * @param onError 把异常落成用户可见的状态；界面文案在这里说清楚
     * @param block 业务主体，在 Dispatchers.IO 上执行
     */
    protected fun launchIo(
        logTag: String,
        logMessage: (Exception) -> String,
        onError: (Exception) -> Unit,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                // 必须第一个 catch：吞掉取消会出诡异的跨线程 bug（项目铁律）
                throw e
            } catch (e: Exception) {
                AppLogger.e(logMessage(e), e, logTag)
                onError(e)
            }
        }
    }

    /**
     * 读库里已有的账户名，供界面做快捷选择。
     *
     * 查询失败降级为空列表：预填不成功不该挡住导入本身。
     */
    protected suspend fun loadKnownAccounts(repository: AssetRepository): List<String> =
        withContext(Dispatchers.IO) {
            runCatching { repository.getAllAccounts() }.getOrDefault(emptyList())
        }.filter { it.isNotBlank() }
}
