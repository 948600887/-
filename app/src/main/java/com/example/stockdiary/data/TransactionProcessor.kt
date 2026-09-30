package com.example.stockdiary.data

import com.example.stockdiary.database.Portfolio
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.utils.AppLogger

/**
 * 交易记录处理器
 * 负责从交易记录计算持仓、获取实时价格、构建并保存投资组合
 */
object TransactionProcessor {

    /**
     * 从交易记录中计算各股票的持仓数据
     * @return Triple(有持仓的股票代码列表, 代码→(数量, 成本价), 代码→名称)
     */
    fun calculatePositionsFromTransactions(
        transactions: List<Transaction>
    ): Triple<List<String>, Map<String, Pair<Double, Double>>, Map<String, String>> {
        val stockGroups = transactions.groupBy { it.symbol }
        val symbolsWithPositions = mutableListOf<String>()
        val symbolData = mutableMapOf<String, Pair<Double, Double>>()
        val symbolNames = mutableMapOf<String, String>()

        for ((symbol, symbolTransactions) in stockGroups) {
            var totalQuantity = 0.0
            var totalCost = 0.0

            for (transaction in symbolTransactions) {
                val type = TransactionType.fromValue(transaction.type) ?: continue
                when (type) {
                    TransactionType.BUY -> {
                        totalQuantity += transaction.quantity
                        totalCost += transaction.quantity * transaction.price + transaction.fee
                    }
                    TransactionType.SELL -> {
                        // 按卖出数量占总持仓比例减少成本，保持成本价一致（加权平均法）
                        val ratio = if (totalQuantity > 0) transaction.quantity / totalQuantity else 0.0
                        totalQuantity -= transaction.quantity
                        totalCost -= totalCost * ratio
                        // 防止浮点误差导致成本为负
                        if (totalCost < 0) {
                            totalCost = 0.0
                        }
                    }
                }
            }

            if (totalQuantity > 0) {
                val purchasePrice = totalCost / totalQuantity
                symbolsWithPositions.add(symbol)
                symbolData[symbol] = Pair(totalQuantity, purchasePrice)
                symbolNames[symbol] = symbolTransactions.first().name
            }
        }

        return Triple(symbolsWithPositions, symbolData, symbolNames)
    }

    /**
     * 批量获取实时价格
     */
    suspend fun fetchCurrentPricesForSymbols(symbols: List<String>): Map<String, Double> {
        if (symbols.isEmpty()) return emptyMap()

        val currentPrices = mutableMapOf<String, Double>()
        try {
            val result = SinaStockAPI.getRealtimeData(symbols)
            if (result.isSuccess) {
                val data = result.getOrNull() ?: emptyMap()
                data.forEach { (symbol, stockData) ->
                    currentPrices[symbol] = stockData.price
                }
            }
        } catch (e: Exception) {
            AppLogger.w("批量获取实时价格失败: ${e.message}", "TransactionProcessor")
        }
        return currentPrices
    }

    /**
     * 根据持仓数据构建 Portfolio 对象列表
     */
    suspend fun buildPortfoliosFromPositions(
        db: StockDatabase,
        symbolsWithPositions: List<String>,
        symbolData: Map<String, Pair<Double, Double>>,
        symbolNames: Map<String, String>,
        currentPrices: Map<String, Double>
    ): List<Portfolio> {
        val portfoliosToUpdate = mutableListOf<Portfolio>()
        for (symbol in symbolsWithPositions) {
            val (quantity, purchasePrice) = symbolData[symbol] ?: continue
            val currentPrice = currentPrices[symbol] ?: purchasePrice
            val name = symbolNames[symbol] ?: symbol

            var portfolio = Portfolio(
                symbol = symbol,
                name = name,
                quantity = quantity,
                purchasePrice = purchasePrice,
                currentPrice = currentPrice
            )

            val existing = db.portfolioDao().getBySymbol(symbol)
            if (existing != null) {
                portfolio = portfolio.copy(id = existing.id)
            }

            portfoliosToUpdate.add(portfolio)
        }
        return portfoliosToUpdate
    }

    /**
     * 批量保存 Portfolio 到数据库（自动区分插入和更新）
     */
    suspend fun savePortfoliosToDb(db: StockDatabase, portfolios: List<Portfolio>) {
        val existingPortfolios = db.portfolioDao().getAll()
        val existingSymbols = existingPortfolios.map { it.symbol }.toSet()
        val existingSymbolToId = existingPortfolios.associate { it.symbol to it.id }

        val toInsert = mutableListOf<Portfolio>()
        val toUpdate = mutableListOf<Portfolio>()

        for (portfolio in portfolios) {
            if (existingSymbols.contains(portfolio.symbol)) {
                toUpdate.add(portfolio.copy(id = existingSymbolToId[portfolio.symbol] ?: portfolio.id))
            } else {
                toInsert.add(portfolio)
            }
        }

        if (toInsert.isNotEmpty()) {
            db.portfolioDao().insertAll(toInsert)
        }
        if (toUpdate.isNotEmpty()) {
            db.portfolioDao().updateAll(toUpdate)
        }
    }
}