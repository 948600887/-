package com.example.stockdiary.navigation

sealed class Screen(
    val route: String
) {
    object Dashboard : Screen("dashboard")
    object Portfolio : Screen("portfolio")
    object Report : Screen("report")
    object Dividend : Screen("dividend/{market}/{stockCode}/{stockName}")
    object StockDetail : Screen("stockDetail/{symbol}")
    object AssetDetail : Screen("assetDetail/{assetId}")
    object Calendar : Screen("calendar")
    object Profile : Screen("profile")
    object History : Screen("history")
    object Settings : Screen("settings")
    
    companion object {
        fun getDividendRoute(market: String, stockCode: String, stockName: String): String {
            return "dividend/$market/$stockCode/$stockName"
        }
        
        fun getStockDetailRoute(symbol: String): String {
            return "stockDetail/$symbol"
        }

        fun getAssetDetailRoute(assetId: Long): String {
            return "assetDetail/$assetId"
        }
    }
}
