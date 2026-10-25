package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class AssetDetailViewModel(private val container: AppContainer, private val assetId: Long) : ViewModel() {

    private val repository = container.assetRepository
    private val dividendRepository = container.dividendRepository
    private val transactionDao = container.database.transactionDao()

    /**
     * 资产详情页状态机：一次「进场加载 / 下拉刷新 / 编辑 / 加减仓」先后推进哪些字段，全在这里。
     *
     * 此前是 7 个各自为政的 MutableStateFlow（资产、分红记录、交易流水、首屏 loading、
     * 分红 loading、错误、刷新态），写入点散落在 loadAssetData / loadDividendData /
     * loadTransactionData / refreshData / 异常处理里；UI 同层 collect 六条流，
     * 任一个发射都整体重组。合并不损失重组粒度，只是少六次订阅，
     * 且「加载到底改了什么」一处可答。
     *
     * 七个字段全部由一次性加载/刷新动作驱动（本 VM 没有 Room 派生流：
     * 资产走 getAssetById、流水走 transactionDao.getBySymbol、分红走
     * dividendRepository.getDividendsBySymbol，都是一次性 suspend 查询），
     * 因此用纯 MutableStateFlow 承载，不强行 combine。
     *
     * 注意 [isLoading] 初值为 true：进场即首屏加载中；其余字段的初始值与
     * 迁移前各流初值逐一对应（asset=null、两个列表为空、两个 loading=false、
     * errorMessage=null、isRefreshing=false）。
     */
    data class AssetDetailUiState(
        val asset: Asset? = null,
        val dividends: List<DividendRecord> = emptyList(),
        val transactions: List<Transaction> = emptyList(),
        val isLoading: Boolean = true,
        val isDividendLoading: Boolean = false,
        val errorMessage: String? = null,
        val isRefreshing: Boolean = false,
    )

    private val _uiState = MutableStateFlow(AssetDetailUiState())
    val uiState: StateFlow<AssetDetailUiState> = _uiState.asStateFlow()

    init {
        loadAssetData()
    }

    fun loadAssetData(forceRefreshDividend: Boolean = false) {
        viewModelScope.launch {
            try {
                val foundAsset = repository.getAssetById(assetId)
                _uiState.update { it.copy(asset = foundAsset, errorMessage = null) }
                if (foundAsset != null) {
                    loadDividendData(forceRefresh = forceRefreshDividend)
                    loadTransactionData(foundAsset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "加载失败，请稍后重试") }
                AppLogger.e("加载资产详情失败: ${e.message}", e, "AssetDetailVM")
            } finally {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }

    private suspend fun loadDividendData(forceRefresh: Boolean = false) {
        val currentAsset = _uiState.value.asset
        if (currentAsset == null || (currentAsset.type != AssetType.STOCK && currentAsset.type != AssetType.FUND)) return

        _uiState.update { it.copy(isDividendLoading = true) }
        try {
            val rawSymbol = currentAsset.symbol ?: ""
            val symbol = StockCodeManager.normalizeSymbol(rawSymbol)

            if (symbol.isNotBlank()) {
                val result = dividendRepository.getDividendsBySymbol(symbol, forceRefresh = forceRefresh)
                _uiState.update { it.copy(dividends = result) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("加载分红数据异常: ${e.message}", tag = "AssetDetailVM")
        } finally {
            _uiState.update { it.copy(isDividendLoading = false) }
        }
    }

    private suspend fun loadTransactionData(asset: Asset) {
        val symbol = asset.symbol ?: return
        try {
            _uiState.update { it.copy(transactions = transactionDao.getBySymbol(symbol)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("加载交易记录异常: ${e.message}", tag = "AssetDetailVM")
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val currentAsset = _uiState.value.asset
                // 加密货币详情页同样走单独刷新（同步引擎 CRYPTO 分支见
                // AssetSyncManager.syncSingleAsset:536，币安取价 + 主机熔断 + LD 1:1 计价）。
                // 银行存款无实时行情，不同步
                if (currentAsset != null && (currentAsset.type == AssetType.STOCK || currentAsset.type == AssetType.FUND || currentAsset.type == AssetType.CRYPTO)) {
                    try {
                        AssetSyncManager.syncAllAssets(
                            context = container.context,
                            repository = repository,
                            assets = listOf(currentAsset),
                            onProgress = { },
                        syncTrigger = "资产详情页刷新"
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("资产详情页同步失败: ${e.message}", "AssetDetailViewModel")
                        _uiState.update { it.copy(errorMessage = "价格同步失败: ${e.localizedMessage ?: e.message}") }
                    }
                }
                loadAssetData(forceRefreshDividend = true)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    suspend fun deleteAsset() {
        _uiState.value.asset?.let { repository.deleteAsset(it) }
    }

    suspend fun updateAsset(updatedAsset: Asset) {
        repository.updateAsset(updatedAsset)
        _uiState.update { it.copy(asset = updatedAsset) }
    }

    suspend fun adjustPosition(updatedAsset: Asset) {
        val currentAsset = _uiState.value.asset ?: return
        val delta = updatedAsset.balance - currentAsset.balance
        if (delta > 0) {
            // UI 传进来的 purchasePrice 已经是"加仓后摊薄的新成本"，
            // 而 addOrUpdateStockAsset 会自己再按余额做一次加权平均 ——
            // 直接把它当成交单价传下去就是双重加权，成本会被算错（越加越偏低）。
            // 这里反解出本次这笔的成交单价：新增总金额 / 增量数量。
            val blendedCost = updatedAsset.purchasePrice
            val incrementalPrice = if (blendedCost.isFinite()) {
                val derived = (blendedCost * updatedAsset.balance -
                    currentAsset.balance * currentAsset.purchasePrice) / delta
                if (derived.isFinite() && derived > 0) derived else blendedCost
            } else {
                updatedAsset.currentPrice ?: currentAsset.purchasePrice
            }
            repository.addOrUpdateStockAsset(
                symbol = currentAsset.symbol ?: "",
                name = currentAsset.name,
                balance = delta,
                price = incrementalPrice,
                account = currentAsset.account,
                assetType = currentAsset.type
            )
        } else if (delta < 0) {
            repository.sellStockAsset(currentAsset, kotlin.math.abs(delta))
        } else {
            // 数量没变（用户只改了成本价）。此前落进「减仓」分支，
            // 会用 0 数量写一条卖出流水，而用户真正改动的成本价反而不保存。
            repository.updateAsset(updatedAsset)
        }
        _uiState.update { it.copy(asset = repository.getAssetById(currentAsset.id)) }
    }

    companion object {
        /**
         * 工厂统一用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
         * 免去手写 create(modelClass) 逐个 cast 的样板（原 9 个 VM 各一份，漏改一个就是运行期 ClassCastException）。
         */
        fun factory(container: AppContainer, assetId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { AssetDetailViewModel(container, assetId) }
        }
    }
}
