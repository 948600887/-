package com.example.stockdiary.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.stockdiary.data.AssetRepository
import com.example.stockdiary.data.DashboardPreferencesManager
import com.example.stockdiary.data.DataExporter
import com.example.stockdiary.data.BrokerageImportService
import com.example.stockdiary.data.DividendIncomeLoader
import com.example.stockdiary.data.DividendIncomeUseCase
import com.example.stockdiary.data.DividendRepository
import com.example.stockdiary.data.HoldingSnapshotService
import com.example.stockdiary.data.ImageTextRecognizer
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
    // 跨 VM 共享的单一入口：分红收入抓取经此收口，进程内 in-flight 单飞，避免多屏重复抓同一份数据
    private val dividendIncomeLoader by lazy { DividendIncomeLoader(dividendRepository) }
    val dividendIncomeUseCase: DividendIncomeUseCase by lazy {
        DividendIncomeUseCase { assets, forceRefresh, tag, cacheOnly ->
            dividendIncomeLoader.loadIncome(assets, forceRefresh, tag, cacheOnly)
        }
    }
    val dashboardPreferencesManager: DashboardPreferencesManager by lazy { DashboardPreferencesManager.getInstance(context) }
    val priceSyncPreferencesManager: PriceSyncPreferencesManager by lazy { PriceSyncPreferencesManager.getInstance(context) }
    val database: StockDatabase by lazy { StockDatabase.getInstance(context) }
    val dataExporter: DataExporter by lazy { DataExporter.getInstance(context) }
    val brokerageImportService: BrokerageImportService by lazy { BrokerageImportService.getInstance(context) }
    val ocrStatementService: OcrStatementService by lazy { OcrStatementService.getInstance(context) }
    /** 「图片→视觉行」的通用能力，持仓快照导入与交割单导入共用同一份实现 */
    val imageTextRecognizer: ImageTextRecognizer by lazy { ImageTextRecognizer.getInstance(context) }
    val holdingSnapshotService: HoldingSnapshotService by lazy { HoldingSnapshotService(assetRepository) }
    val crashRecoveryManager: CrashRecoveryManager by lazy { CrashRecoveryManager.getInstance() }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未初始化，请在 Application 中设置")
}