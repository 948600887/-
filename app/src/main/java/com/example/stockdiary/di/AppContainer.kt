package com.example.stockdiary.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.data.DataExporter
import com.example.stockdiary.data.BrokerageImportService
import com.example.stockdiary.data.DividendRepository
import com.example.stockdiary.data.OcrStatementService
import com.example.stockdiary.data.PriceSyncPreferencesManager
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.utils.CrashRecoveryManager

/**
 * 简易 DI 容器，替代 Service Locator 反模式
 * 在 Application.onCreate() 中初始化，通过 CompositionLocal 提供给 Composable 树
 */
class AppContainer(val context: Context) {
    val assetRepository: AssetRepository by lazy { AssetRepository.getInstance(context) }
    val dividendRepository: DividendRepository by lazy { DividendRepository.getInstance(context) }
    val dashboardPreferencesManager: DashboardPreferencesManager by lazy { DashboardPreferencesManager.getInstance(context) }
    val priceSyncPreferencesManager: PriceSyncPreferencesManager by lazy { PriceSyncPreferencesManager.getInstance(context) }
    val database: StockDatabase by lazy { StockDatabase.getInstance(context) }
    val dataExporter: DataExporter by lazy { DataExporter.getInstance(context) }
    val brokerageImportService: BrokerageImportService by lazy { BrokerageImportService.getInstance(context) }
    val ocrStatementService: OcrStatementService by lazy { OcrStatementService.getInstance(context) }
    val crashRecoveryManager: CrashRecoveryManager by lazy { CrashRecoveryManager.getInstance() }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未初始化，请在 Application 中设置")
}