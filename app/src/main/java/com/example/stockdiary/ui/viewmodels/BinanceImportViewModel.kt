package com.example.stockdiary.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.importer.BinanceCredentialsManager
import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.importer.filterDustByValue
import com.example.stockdiary.data.importer.priceKeyFor
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.network.BinanceAccountAPI
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
 * 币安资产导入 ViewModel。
 *
 *   INPUT(填密钥) → FETCHING(拉取) → PREVIEW(逐条核对 + 填账户名) → COMMITTING → RESULT
 *
 * 与 [HoldingSnapshotViewModel] 同一套状态机思路，只是数据来源从 OCR/xlsx
 * 换成签名 HTTP。按需触发：**只在用户点「拉取」时发一次网络请求**，
 * 不做后台自动同步——密钥在用户手里，什么时候用由用户决定。
 */
class BinanceImportViewModel(
    private val context: Context,
    private val repository: AssetRepository,
    private val service: BinanceImportService,
    private val credentialsManager: BinanceCredentialsManager
) : ViewModel() {

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
        /**
         * 零碎资产阈值的原始输入（人民币）。存文本而非 Double：
         * 数字输入框编辑中途会出现 "10." 这类非法值，文本权威、按需解析更稳。
         */
        val thresholdText: String = DEFAULT_THRESHOLD_CNY.toInt().toString(),
        /** 本次拉取用到的交易对最新价，供预览行估算市值 */
        val prices: Map<String, Double> = emptyMap(),
        /** 本次拉取因市值低于阈值被过滤掉的资产数量 */
        val filteredCount: Int = 0
    ) {
        /** 密钥都填了才能拉取 */
        val canFetch: Boolean
            get() = apiKey.isNotBlank() && apiSecret.isNotBlank() && !fetching

        /** 生效的市值阈值（人民币）。输入为空/非法时回落到默认值 */
        val minValueCny: Double
            get() = thresholdText.toDoubleOrNull() ?: DEFAULT_THRESHOLD_CNY
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // 进入时预填已存密钥 + 已有账户名：老用户不必每次重敲 Key/Secret
        viewModelScope.launch {
            val (savedKey, savedSecret) = withContext(Dispatchers.IO) {
                credentialsManager.getApiKey() to credentialsManager.getApiSecret()
            }
            val accounts = withContext(Dispatchers.IO) {
                runCatching { repository.getAllAccounts() }.getOrDefault(emptyList())
            }.filter { it.isNotBlank() }

            _uiState.update { state ->
                state.copy(
                    apiKey = savedKey.orEmpty(),
                    apiSecret = savedSecret.orEmpty(),
                    knownAccounts = accounts
                )
            }
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value) }
    }

    fun onApiSecretChange(value: String) {
        _uiState.update { it.copy(apiSecret = value) }
    }

    fun onAccountChange(value: String) {
        _uiState.update { it.copy(account = value) }
    }

    fun onThresholdChange(value: String) {
        _uiState.update { it.copy(thresholdText = value) }
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
            _uiState.update { it.copy(error = "请先填写 API Key 与 Secret") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state ->
                state.copy(fetching = true, error = null, preview = null, result = null, step = Step.FETCHING)
            }
            try {
                val accountResult = BinanceAccountAPI.getAccount(current.apiKey, current.apiSecret)

                val balances = accountResult.getOrElse { err ->
                    // 币安的拒绝原因（密钥无效/IP 不在白名单/权限不足）就在 message 里，
                    // 原样带给用户，这是排障的唯一线索
                    _uiState.update { state ->
                        state.copy(fetching = false, step = Step.INPUT, error = err.message ?: "拉取失败")
                    }
                    return@launch
                }

                // 拉到了再存密钥：见方法注释
                credentialsManager.save(current.apiKey, current.apiSecret)

                // 批量取公开价，用于按人民币市值过滤零碎。
                // 交易对列表从余额生成（稳定币无需查价）；取价失败降级为空 map
                // → priceOf 全 null → 一个都滤不掉、全量导入，绝不阻断导入
                val pairs = balances.mapNotNull { priceKeyFor(it.asset) }.distinct()
                val priceMap = BinanceAccountAPI.getPrices(pairs)
                val priceOf: (String) -> Double? = { priceMap[it] }

                // 按市值过滤零碎（阈值用户可设，默认 ¥10）；估不出价的一律保留
                val (kept, filteredCount) = filterDustByValue(balances, priceOf, current.minValueCny)

                // 只取库里已有的加密货币资产来对齐：导入只可能命中这一类
                val existing = repository.getAssetsByTypes(listOf(AssetType.CRYPTO))
                val preview = service.buildPreview(kept, existing)

                AppLogger.i(
                    "币安账户拉取：${balances.size} 项 → 市值过滤 $filteredCount → 合并后 ${preview.items.size} 条",
                    "BinanceImportVM"
                )

                if (preview.items.isEmpty()) {
                    // 滤完一条不剩：账户为空、只剩dust、或全被市值阈值挡掉。
                    // 停在 INPUT 说清楚原因，别给个空预览页让用户以为出错了
                    val hint = if (filteredCount > 0) {
                        "有 $filteredCount 项市值低于 ¥${current.minValueCny.toInt()} 被过滤，可调低阈值重试。"
                    } else {
                        "可能账户为空或只剩dust零头。"
                    }
                    _uiState.update { state ->
                        state.copy(
                            fetching = false,
                            step = Step.INPUT,
                            error = "账户里没有可导入的持仓。$hint",
                            filteredCount = 0,
                            prices = emptyMap()
                        )
                    }
                    return@launch
                }

                _uiState.update { state ->
                    state.copy(
                        fetching = false,
                        step = Step.PREVIEW,
                        preview = preview,
                        filteredCount = filteredCount,
                        prices = priceMap
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("拉取币安持仓失败: ${e.message}", e, "BinanceImportVM")
                _uiState.update { state ->
                    state.copy(fetching = false, step = Step.INPUT, error = "拉取失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 确认导入：按交易对覆盖数量、其余新建。未变的不写库。 */
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
                AppLogger.e("币安持仓落库失败: ${e.message}", e, "BinanceImportVM")
                _uiState.update { state ->
                    state.copy(step = Step.PREVIEW, error = "导入失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 复位到输入步。
     *
     * ViewModel 挂在宿主 Activity 作用域上，对话框关掉后实例还活着——不复位
     * 下次打开会残留上次的预览。密钥与已知账户保留，那是用户上下文。
     */
    fun reset() {
        _uiState.update { state ->
            state.copy(
                step = Step.INPUT,
                fetching = false,
                preview = null,
                result = null,
                error = null,
                filteredCount = 0,
                prices = emptyMap()
            )
        }
    }

    companion object {
        /** 零碎资产市值阈值默认值（人民币） */
        const val DEFAULT_THRESHOLD_CNY = 10.0
    }

    class Factory(
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BinanceImportViewModel(
                context = container.context,
                repository = container.assetRepository,
                service = container.binanceImportService,
                credentialsManager = container.binanceCredentialsManager
            ) as T
        }
    }
}
