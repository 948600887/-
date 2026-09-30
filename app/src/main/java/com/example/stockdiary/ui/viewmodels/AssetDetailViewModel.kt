package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.data.AssetSyncManager
import com.example.stockdiary.data.DividendRecord
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AssetDetailViewModel(private val container: AppContainer, private val assetId: Long) : ViewModel() {

    private val repository = container.assetRepository
    private val dividendRepository = container.dividendRepository
    private val transactionDao = container.database.transactionDao()

    private val _asset = MutableStateFlow<Asset?>(null)
    val asset: StateFlow<Asset?> = _asset.asStateFlow()

    private val _dividends = MutableStateFlow<List<DividendRecord>>(emptyList())
    val dividends: StateFlow<List<DividendRecord>> = _dividends.asStateFlow()

    private val _transactions = MutableStateFlow<List<com.example.stockdiary.database.Transaction>>(emptyList())
    val transactions: StateFlow<List<com.example.stockdiary.database.Transaction>> = _transactions.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isDividendLoading = MutableStateFlow(false)
    val isDividendLoading: StateFlow<Boolean> = _isDividendLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadAssetData()
    }

    fun loadAssetData(forceRefreshDividend: Boolean = false) {
        viewModelScope.launch {
            try {
                val foundAsset = repository.getAssetById(assetId)
                _asset.value = foundAsset
                _errorMessage.value = null
                if (foundAsset != null) {
                    loadDividendData(forceRefresh = forceRefreshDividend)
                    loadTransactionData(foundAsset)
                }
            } catch (e: Exception) {
                _errorMessage.value = "加载失败，请稍后重试"
                AppLogger.e("加载资产详情失败: ${e.message}", e, "AssetDetailVM")
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadDividendData(forceRefresh: Boolean = false) {
        val currentAsset = _asset.value
        if (currentAsset == null || (currentAsset.type != AssetType.STOCK && currentAsset.type != AssetType.FUND)) return

        _isDividendLoading.value = true
        try {
            val rawSymbol = currentAsset.symbol ?: ""
            val symbol = StockCodeManager.normalizeSymbol(rawSymbol)

            if (symbol.isNotBlank()) {
                val result = dividendRepository.getDividendsBySymbol(symbol, forceRefresh = forceRefresh)
                _dividends.value = result
            }
        } catch (e: Exception) {
            AppLogger.e("加载分红数据异常: ${e.message}", tag = "AssetDetailVM")
        } finally {
            _isDividendLoading.value = false
        }
    }

    private suspend fun loadTransactionData(asset: Asset) {
        val symbol = asset.symbol ?: return
        try {
            _transactions.value = transactionDao.getBySymbol(symbol)
        } catch (e: Exception) {
            AppLogger.e("加载交易记录异常: ${e.message}", tag = "AssetDetailVM")
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val currentAsset = _asset.value
                if (currentAsset != null && (currentAsset.type == AssetType.STOCK || currentAsset.type == AssetType.FUND)) {
                    try {
                        AssetSyncManager.syncAllAssets(
                            context = container.context,
                            repository = repository,
                            assets = listOf(currentAsset),
                            onProgress = { }
                        )
                    } catch (e: Exception) {
                        AppLogger.w("资产详情页同步失败: ${e.message}", "AssetDetailViewModel")
                        _errorMessage.value = "价格同步失败: ${e.localizedMessage ?: e.message}"
                    }
                }
                loadAssetData(forceRefreshDividend = true)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    suspend fun deleteAsset() {
        _asset.value?.let { repository.deleteAsset(it) }
    }

    suspend fun updateAsset(updatedAsset: Asset) {
        repository.updateAsset(updatedAsset)
        _asset.value = updatedAsset
    }

    suspend fun adjustPosition(updatedAsset: Asset) {
        val currentAsset = _asset.value ?: return
        val delta = updatedAsset.balance - currentAsset.balance
        val tradePrice = if (delta > 0) {
            ((currentAsset.balance * currentAsset.purchasePrice) + (delta * updatedAsset.purchasePrice)) / updatedAsset.balance
        } else {
            currentAsset.currentPrice ?: currentAsset.purchasePrice
        }
        if (delta > 0) {
            repository.addOrUpdateStockAsset(
                symbol = currentAsset.symbol ?: "",
                name = currentAsset.name,
                balance = delta,
                price = tradePrice,
                account = currentAsset.account,
                assetType = currentAsset.type
            )
        } else {
            repository.sellStockAsset(currentAsset, kotlin.math.abs(delta))
        }
        _asset.value = repository.getAssetById(currentAsset.id)
    }

    class Factory(private val container: AppContainer, private val assetId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AssetDetailViewModel(container, assetId) as T
        }
    }
}