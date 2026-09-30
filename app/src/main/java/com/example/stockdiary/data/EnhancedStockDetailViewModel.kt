package com.example.stockdiary.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.stockdiary.network.StockAPIManager
import com.example.stockdiary.data.DividendRepository
import com.example.stockdiary.data.DividendRecord
import com.example.stockdiary.data.DividendMetrics
import com.example.stockdiary.utils.AppLogger

/**
 * 增强版股票详情ViewModel，包含分红数据
 * 统一使用DividendRepository获取分红数据（东方财富优先，新浪备用）
 */
class EnhancedStockDetailViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    private val api = StockAPIManager.instance
    private val dividendRepository = DividendRepository.getInstance(application)
    
    // 状态流
    private val _stockDetail = MutableStateFlow<com.example.stockdiary.network.SinaStockData?>(null)
    val stockDetail: StateFlow<com.example.stockdiary.network.SinaStockData?> = _stockDetail.asStateFlow()
    
    private val _dividendData = MutableStateFlow<List<DividendRecord>?>(null)
    val dividendData: StateFlow<List<DividendRecord>?> = _dividendData.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private var currentStockCode: String? = null
    private var currentMarket: String? = null
    
    /**
     * 设置当前股票
     */
    suspend fun setCurrentStock(stockCode: String, market: String) {
        currentStockCode = stockCode
        currentMarket = market
        _errorMessage.value = null
        
        try {
            val fullCode = "${market}$stockCode"
            AppLogger.d("开始设置当前股票: $fullCode", "StockDetailVM")
            
            // 从DividendRepository加载分红数据（强制刷新确保数据最新）
            val dividendData = dividendRepository.getDividendsBySymbol(fullCode, forceRefresh = true)
            
            withContext(Dispatchers.Main) {
                _dividendData.value = dividendData
                AppLogger.d("分红数据加载完成: ${dividendData.size}条, 股票: $fullCode", "StockDetailVM")
            }
            
            AppLogger.d("设置股票完成: $fullCode", "StockDetailVM")
        } catch (e: Exception) {
            AppLogger.e("设置股票失败", e, "StockDetailVM")
            withContext(Dispatchers.Main) {
                _errorMessage.value = "设置股票信息失败"
            }
        }
    }
    
    /**
     * 智能获取股票数据（只获取实时行情，分红数据从DividendRepository读取）
     */
    suspend fun fetchStockDataSmart() {
        val stockCode = currentStockCode ?: return
        val market = currentMarket ?: return
        
        _isLoading.value = true
        _errorMessage.value = null
        
        try {
            val formattedCode = "${market}${stockCode}"
            val detailResult = api.getStockData(formattedCode)
            
            if (detailResult.isSuccess) {
                val detail = detailResult.getOrNull()
                if (detail != null) {
                    _stockDetail.value = detail
                } else {
                    _errorMessage.value = "获取实时行情失败"
                }
            } else {
                _errorMessage.value = "获取实时行情失败: ${detailResult.exceptionOrNull()?.message}"
            }
            
            // 从DividendRepository加载分红数据（包含缓存逻辑）
            val currentDividendData = _dividendData.value
            if (currentDividendData == null || currentDividendData.isEmpty()) {
                val dividendData = dividendRepository.getDividendsBySymbol(formattedCode)
                withContext(Dispatchers.Main) {
                    _dividendData.value = dividendData
                    AppLogger.d("fetchStockDataSmart: 分红数据 ${dividendData.size}条", "StockDetailVM")
                }
            }
            
        } catch (e: Exception) {
            _errorMessage.value = "获取数据失败: ${e.message}"
            AppLogger.e("获取数据异常", e, "StockData")
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 强制刷新分红数据
     */
    suspend fun refreshDividendData() {
        val stockCode = currentStockCode ?: return
        val market = currentMarket ?: return
        
        val formattedCode = "${market}${stockCode}"
        AppLogger.d("强制刷新分红数据: $formattedCode", "StockDetailVM")
        
        _isLoading.value = true
        try {
            val dividendData = dividendRepository.getDividendsBySymbol(formattedCode, forceRefresh = true)
            withContext(Dispatchers.Main) {
                _dividendData.value = dividendData
                AppLogger.d("刷新完成: ${dividendData.size}条", "StockDetailVM")
            }
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 计算分红指标
     */
    fun calculateDividendMetrics(records: List<DividendRecord>?): DividendMetrics {
        if (records.isNullOrEmpty()) return DividendMetrics()

        val sortedRecords = records.sortedByDescending { it.exDividendDate }
        val currentYear = java.time.LocalDate.now().year

        // 按年份汇总每十股分红，用于计算平均年化分红
        val yearlyDividends = records.mapNotNull { dividend ->
            try {
                val year = dividend.exDividendDate.substring(0, 4).toInt()
                val amount = dividend.dividendPerShare.toDoubleOrNull() ?: 0.0
                if (year > 0 && amount > 0) year to amount else null
            } catch (e: Exception) {
                null
            }
        }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }

        val recent5Years = yearlyDividends.filter { it.key >= currentYear - 4 }

        return DividendMetrics(
            totalRecords = records.size,
            recentRecord = sortedRecords.firstOrNull(),
            totalCashDividend = records.sumOf { dividend ->
                dividend.dividendPerShare.toDoubleOrNull() ?: 0.0
            },
            avgAnnualDividend = if (recent5Years.isNotEmpty()) {
                recent5Years.values.average()
            } else if (yearlyDividends.isNotEmpty()) {
                yearlyDividends.values.average()
            } else 0.0,
            dividendFrequency = calculateFrequency(records),
            dividendStability = calculateStability(records)
        )
    }
    
    private fun calculateFrequency(records: List<DividendRecord>): Float {
        if (records.isEmpty()) return 0f
        
        val years = records.mapNotNull { 
            try { it.exDividendDate.substring(0, 4) } catch (e: Exception) { null }
        }.distinct().size
        
        return if (years > 0) records.size.toFloat() / years.toFloat() else 0f
    }
    
    private fun calculateStability(records: List<DividendRecord>): Float {
        if (records.size < 2) return 0f
        
        val sortedRecords = records.sortedBy { it.exDividendDate }
        var stableCount = 0
        
        for (i in 1 until sortedRecords.size) {
            val current = sortedRecords[i].dividendPerShare.toDoubleOrNull() ?: 0.0
            val previous = sortedRecords[i-1].dividendPerShare.toDoubleOrNull() ?: 0.0
            
            if (previous > 0 && current >= previous * 0.8 && current <= previous * 1.2) {
                stableCount++
            }
        }
        
        return stableCount.toFloat() / (sortedRecords.size - 1).toFloat()
    }
}
