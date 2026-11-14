package com.example.stockdiary.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.stockdiary.data.backup.BackupManager
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.repository.AssetQuoteRepository
import com.example.stockdiary.data.importer.BinanceCredentialsManager
import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.export.DataExporter
import com.example.stockdiary.data.importer.BrokerageImportService
import com.example.stockdiary.data.dividend.DividendIncomeCache
import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.repository.TransactionRepository
import com.example.stockdiary.data.importer.HoldingSnapshotService
import com.example.stockdiary.data.importer.ImageTextRecognizer
import com.example.stockdiary.data.importer.OcrStatementService
import com.example.stockdiary.data.sync.AssetSyncFailureStore
import com.example.stockdiary.data.prefs.SnapshotManualAdjustmentStore
import com.example.stockdiary.data.sync.PriceSyncPreferencesManager
import com.example.stockdiary.data.fund.FundNavCacheStoreImpl
import com.example.stockdiary.data.price.DefaultPriceCacheMaintenance
import com.example.stockdiary.data.price.PriceCacheMaintenance
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.network.FundNavCacheStore
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.CrashRecoveryManager

/**
 * 简易 DI 容器，替代 Service Locator 反模式
 * 在 Application.onCreate() 中初始化，通过 CompositionLocal 提供给 Composable 树
 */
class AppContainer(private val context: Context) {
    /**
     * 对外暴露的 Application Context。
     *
     * 为什么把构造参数 context 收成 private：容器只由 Application 创建一次，外部
     * （ViewModel / Factory）不该再从容器里掏原始 Context 去各自 `getInstance(...)`
     * ——那是绕过 DI 的 Service Locator 用法。需要 Context 的地方统一取本属性；
     * 它与构造时传入的 Application 是同一个对象，容器内部各 `by lazy` 仍用原 context，
     * **单例来源完全不变**。
     */
    val applicationContext: Context = context.applicationContext

    val assetRepository: AssetRepository by lazy { AssetRepository.getInstance(context) }
    val dividendRepository: DividendRepository by lazy { DividendRepository.getInstance(context) }
    /** 交易流水的唯一访问入口，收口对 TransactionDao 的直接访问（与 AssetRepository/DividendRepository 同一架构边界） */
    val transactionRepository: TransactionRepository by lazy { TransactionRepository.getInstance(context) }
    // 跨 VM 共享的单一入口：分红收入抓取经此收口，进程内 in-flight 单飞，避免多屏重复抓同一份数据
    // 注入 DividendIncomeCache：把算好的「预计收入」持久化，同日刷新复用、仅底层数据变化或跨日才重算
    private val dividendIncomeLoader by lazy {
        DividendIncomeLoader(dividendRepository, DividendIncomeCache(context))
    }
    val dividendIncomeUseCase: DividendIncomeUseCase by lazy {
        DividendIncomeUseCase { assets, forceRefresh, tag, cacheOnly ->
            dividendIncomeLoader.loadIncome(assets, forceRefresh, tag, cacheOnly)
        }
    }
    val dashboardPreferencesManager: DashboardPreferencesManager by lazy { DashboardPreferencesManager.getInstance(context) }
    val priceSyncPreferencesManager: PriceSyncPreferencesManager by lazy { PriceSyncPreferencesManager.getInstance(context) }
    /**
     * 按资产的「上次行情刷新是否失败」。界面与同步编排器必须共用这一份单例：
     * 卡片据此只在**真失败**时显示「上次更新未成功」，成功落价即清除。
     * 纯展示状态、存 SharedPreferences，不占 Room 列（避免 DB 版本迁移）。
     */
    val assetSyncFailureStore: AssetSyncFailureStore by lazy {
        AssetSyncFailureStore.getInstance(context)
    }
    /** 手动修正过的快照日期（列表/趋势图标记）。同样走轻量存储，不占 Room 列 */
    val snapshotManualAdjustmentStore: SnapshotManualAdjustmentStore by lazy {
        SnapshotManualAdjustmentStore.getInstance(context)
    }
    /** 派生缓存清理门面：设置页只认它，不直接调各数据源单例的清除方法 */
    val priceCacheMaintenance: PriceCacheMaintenance by lazy {
        DefaultPriceCacheMaintenance(applicationContext)
    }
    val database: StockDatabase by lazy { StockDatabase.getInstance(context) }
    /** 全量 JSON 备份 / 恢复的唯一入口（Room 7 张表，走 DAO + withTransaction）。 */
    val backupManager: BackupManager by lazy { BackupManager(database) }
    /**
     * 基金净值持久化端口。DAO 在这里构造、异常在这里兜住（与旧 FundAPI.init 的降级语义一致）：
     * 构造失败返回 null，FundAPI 自动退回纯内存缓存，不打断取价链路。
     */
    val fundNavCacheStore: FundNavCacheStore? by lazy {
        try {
            FundNavCacheStoreImpl(database.fundNavCacheDao())
        } catch (e: Exception) {
            AppLogger.w("基金净值持久化缓存未启用（DAO 注入失败）: ${e.message}", "AppContainer")
            null
        }
    }
    val dataExporter: DataExporter by lazy { DataExporter.getInstance(context) }
    val brokerageImportService: BrokerageImportService by lazy { BrokerageImportService.getInstance(context) }
    val ocrStatementService: OcrStatementService by lazy { OcrStatementService.getInstance(context) }
    /** 「图片→视觉行」的通用能力，持仓快照导入与交割单导入共用同一份实现 */
    val imageTextRecognizer: ImageTextRecognizer by lazy { ImageTextRecognizer.getInstance(context) }
    val holdingSnapshotService: HoldingSnapshotService by lazy { HoldingSnapshotService(assetRepository) }
    /** 币安只读密钥的本地存储 */
    val binanceCredentialsManager: BinanceCredentialsManager by lazy {
        BinanceCredentialsManager.getInstance(context)
    }
    /** 币安现货持仓导入：拉余额 → 按交易对合并 → 落库（按需触发，不后台自动） */
    val binanceImportService: BinanceImportService by lazy { BinanceImportService(assetRepository) }
    val crashRecoveryManager: CrashRecoveryManager by lazy { CrashRecoveryManager.getInstance() }
    /** 行情 / 基金查询收口层（审计 H1：UI 通过它取行情，不直接调 network 层） */
    val assetQuoteRepository: AssetQuoteRepository by lazy { AssetQuoteRepository() }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未初始化，请在 Application 中设置")
}