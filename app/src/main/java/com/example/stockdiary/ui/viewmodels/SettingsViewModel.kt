package com.example.stockdiary.ui.viewmodels

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stockdiary.common.Result
import com.example.stockdiary.data.backup.BackupCodec
import com.example.stockdiary.data.backup.BackupManager
import com.example.stockdiary.data.backup.InvalidBackupException
import com.example.stockdiary.data.backup.UnsupportedBackupVersionException
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.DashboardPreferencesManager
import com.example.stockdiary.data.export.DataExporter
import com.example.stockdiary.data.price.PriceCacheMaintenance
import com.example.stockdiary.data.sync.AssetSyncFailureStore
import com.example.stockdiary.data.sync.PriceSyncPreferencesManager
import com.example.stockdiary.data.sync.PriceSyncScheduler
import com.example.stockdiary.data.sync.SnapshotScheduler
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.repository.TransactionRepository
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.DataSourceHealth
import com.example.stockdiary.utils.DataSourceHealthChecker
import com.example.stockdiary.utils.LogDiagnostics
import com.example.stockdiary.utils.ExchangeRateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File

class SettingsViewModel(
    private val appContext: Context,
    private val assetRepository: AssetRepository,
    private val dividendRepository: DividendRepository,
    private val transactionRepository: TransactionRepository,
    private val dataExporter: DataExporter,
    private val dashboardPreferencesManager: DashboardPreferencesManager,
    private val dataSourceHealthChecker: DataSourceHealthChecker,
    private val priceSyncPreferencesManager: PriceSyncPreferencesManager,
    private val priceCacheMaintenance: PriceCacheMaintenance,
    private val backupManager: BackupManager
) : ViewModel() {


    private val _uiState = MutableStateFlow(
        SettingsUiState(
            autoPriceSyncEnabled = priceSyncPreferencesManager.isAutoSyncEnabled(),
            lastPriceSyncTime = priceSyncPreferencesManager.getLastSyncTimeMillis(),
            stockIntradaySyncEnabled = priceSyncPreferencesManager.isStockIntradaySyncEnabled(),
            cryptoAutoSyncEnabled = priceSyncPreferencesManager.isCryptoAutoSyncEnabled(),
            usdRate = ExchangeRateManager.getRate("USD"),
            usdRateUpdatedAt = ExchangeRateManager.getLastRefreshTimeMillis()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * 自定义仪表盘偏好。直接复用 Manager 的全应用唯一偏好流：本页写入后 Manager 同步更新流，
     * 设置页 / 仪表盘 / 持仓页 / 历史页看到的是同一份值，不需要各持副本再回前台重读。
     */
    val dashboardPreferences: StateFlow<DashboardPreferences> =
        dashboardPreferencesManager.preferences

    init {
        // Room Flow 在数据库异常时会向收集者抛错，不兜住会让 viewModelScope 崩溃
        // 只收集计数，不把整表塞进 SettingsUiState（见 SettingsUiState.assetCount 的 KDoc）。
        // Room 仍会发全量列表，但 map 成 Int 后立刻丢弃，不跨重组持有。
        viewModelScope.launch {
            try {
                assetRepository.getAllAssets()
                    .map { it.size }
                    .distinctUntilChanged()
                    .collect { count -> _uiState.update { state -> state.copy(assetCount = count) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("资产计数流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
        viewModelScope.launch {
            try {
                assetRepository.getAllSnapshots()
                    .map { it.size }
                    .distinctUntilChanged()
                    .collect { count -> _uiState.update { state -> state.copy(snapshotCount = count) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("快照计数流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
        viewModelScope.launch {
            try {
                transactionRepository.getAllFlow()
                    .map { it.size }
                    .distinctUntilChanged()
                    .collect { count -> _uiState.update { state -> state.copy(transactionCount = count) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("交易计数流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
        viewModelScope.launch {
            try {
                dividendRepository.getAllDividendsFlow()
                    .map { it.size }
                    .distinctUntilChanged()
                    .collect { count -> _uiState.update { state -> state.copy(dividendCount = count) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("分红计数流收集异常: ${e.message}", e, "SettingsVM")
            }
        }
    }

    enum class ExportType {
        TEXT,
        CSV_ASSETS,
        CSV_SNAPSHOTS,
        CSV_TRANSACTIONS,
        CSV_STATEMENT,
        CSV_DIVIDENDS
    }

    fun exportData(context: Context, type: ExportType) {
        // 导出中再次点击（比如分享面板取消后立刻重试）直接忽略，避免两份并发落盘、状态互相覆盖。
        // 标记必须在 launch 之前同步置位，否则连点两下时第二个调用还看不到 true，守卫形同虚设。
        if (_uiState.value.isExporting) return
        _uiState.update { state -> state.copy(isExporting = true, exportResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 按类型只查需要的表：原先不管导哪种都读 assets + snapshots + transactions 三张全表，
                // 老用户（几千条快照、上万条交易）导出「资产」时会白读两遍无关数据。
                val result = when (type) {
                    ExportType.TEXT -> dataExporter.exportToText(assetRepository.getAllAssets().first())
                    ExportType.CSV_ASSETS -> dataExporter.exportAssetsToCSV(assetRepository.getAllAssets().first())
                    ExportType.CSV_SNAPSHOTS ->
                        dataExporter.exportSnapshotsToCSV(assetRepository.getAllSnapshotsSync())
                    ExportType.CSV_TRANSACTIONS -> dataExporter.exportTransactionsToCSV(
                        assetRepository.getAllAssets().first(),
                        transactionRepository.getAllFlow().first()
                    )
                    ExportType.CSV_STATEMENT -> dataExporter.exportStatementCSV(
                        assetRepository.getAllAssets().first(),
                        transactionRepository.getAllFlow().first(),
                        assetRepository.getAllSnapshotsSync(),
                        dividendRepository.getAllDividendsSync()
                    )
                    ExportType.CSV_DIVIDENDS -> dataExporter.exportDividendsToCSV(
                        dividendRepository.getAllDividendsSync(),
                        assetRepository.getAllAssets().first()
                    )
                }
                result.fold(
                    onSuccess = { uri ->
                        withContext(Dispatchers.Main) { shareExport(context, uri, exportTitle(type)) }
                        // 成功也给一条提示：此前只弹分享面板，用户取消后不知道文件在哪儿
                        _uiState.update { state ->
                            state.copy(
                                isExporting = false,
                                exportResult = Result.success(
                                    "已生成 ${uri.lastPathSegment?.substringAfterLast('/') ?: "导出文件"}"
                                )
                            )
                        }
                    },
                    onFailure = { e ->
                        _uiState.update { state ->
                            state.copy(
                                isExporting = false,
                                exportResult = Result.failure(e.message ?: "导出失败")
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("导出失败: ${e.message}", e, "SettingsVM")
                _uiState.update { state -> state.copy(isExporting = false, exportResult = Result.failure(e)) }
            }
        }
    }

    private fun exportTitle(type: ExportType): String = when (type) {
        ExportType.TEXT -> "资产报告"
        ExportType.CSV_ASSETS -> "资产数据"
        ExportType.CSV_SNAPSHOTS -> "历史快照"
        ExportType.CSV_TRANSACTIONS -> "交易对账单"
        ExportType.CSV_STATEMENT -> "综合对账单"
        ExportType.CSV_DIVIDENDS -> "分红明细"
    }

    /**
     * 拉起系统分享面板。
     *
     * [ClipData] 与 `EXTRA_STREAM` 必须同时给：部分接收方（旧版微信、邮件客户端）只读
     * ClipData，只设 EXTRA_STREAM 时面板里选得中却发不出附件。
     */
    private fun shareExport(context: Context, uri: Uri, title: String) {
        val mime = context.contentResolver.getType(uri)
            ?: if (uri.lastPathSegment?.endsWith(".txt") == true) "text/plain" else "text/csv"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, title))
    }

    // ==================================================================
    // 全量备份 / 恢复（SAF）
    //
    // 为什么走 SAF 而不是复用既有 FileProvider 分享：CreateDocument / OpenDocument
    // 让用户自己选保存位置与文件，脱离 cacheDir/exports —— 那份目录会被
    // DataExporter.pruneOldExports 只留 5 份、还会误删诊断 txt，留底根本不可靠。
    // 备份是可回灌的结构化 JSON（Room 7 张表），与 6 种给人看的 CSV 对账单是两回事。
    // ==================================================================

    /**
     * 导出全量备份到用户在 SAF 里选定的 [uri]。
     *
     * 先在内存里拼好完整 JSON 字符串再一次性写出：边拼边写一旦中途失败，用户拿到的是
     * 半截文件，看着像备份成功、恢复时却解析失败。失败时删掉 CreateDocument 已建好的
     * 空文件/残件，避免误以为备份成功。
     */
    fun exportBackup(uri: Uri) {
        // 与既有 6 种导出共用 isExporting 守卫（连点只跑一次）；isBackingUp 额外挡住
        // 「恢复进行中又点导出」这类跨操作并发写库。标记必须在 launch 之前同步置位。
        if (_uiState.value.isExporting || _uiState.value.isBackingUp) return
        _uiState.update {
            it.copy(isExporting = true, isBackingUp = true, exportResult = null, backupResult = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = backupManager.buildPayload()
                val json = BackupCodec.encode(payload)
                val output = appContext.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法打开所选保存位置，请重新选择目录")
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val message = "全量备份已保存（资产 ${payload.assets.size} · " +
                    "快照 ${payload.snapshots.size} · 交易 ${payload.transactions.size} · " +
                    "分红 ${payload.dividends.size}）"
                AppLogger.i("全量备份导出完成：7 张表已写入用户所选位置", "SettingsVM")
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        isBackingUp = false,
                        exportResult = Result.success(message),
                        backupResult = Result.success(message)
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isExporting = false, isBackingUp = false) }
                throw e
            } catch (e: Exception) {
                // 清掉残件：CreateDocument 已经把空文件建出来了
                runCatching { appContext.contentResolver.delete(uri, null, null) }
                AppLogger.e("全量备份导出失败: ${e.message}", e, "SettingsVM")
                val failure: Result<String> = Result.failure(
                    IllegalStateException("备份导出失败：${e.message ?: e.javaClass.simpleName}", e)
                )
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        isBackingUp = false,
                        exportResult = failure,
                        backupResult = failure
                    )
                }
            }
        }
    }

    /**
     * 只读解析所选备份：把摘要放进 UiState 供二次确认框展示，**绝不写库**。
     *
     * 只读这一层能拦住坏文件 / 高版本备份，用户不会先看到「将覆盖全部账目」的确认框、
     * 点下去才发现文件根本读不了。
     */
    fun readBackup(uri: Uri) {
        if (_uiState.value.isBackingUp) return
        _uiState.update { it.copy(isBackingUp = true, pendingRestore = null, backupResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val summary = BackupCodec.summary(readBackupText(uri))
                AppLogger.i(
                    "已读取备份摘要：格式版本 ${summary.formatVersion}，等待用户二次确认",
                    "SettingsVM"
                )
                _uiState.update { it.copy(isBackingUp = false, pendingRestore = summary) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isBackingUp = false) }
                throw e
            } catch (e: Exception) {
                AppLogger.w("读取备份失败: ${e.message}", "SettingsVM")
                val failure: Result<String> = Result.failure(readableBackupError("读取备份失败", e))
                _uiState.update {
                    it.copy(
                        isBackingUp = false,
                        pendingRestore = null,
                        exportResult = failure,
                        backupResult = failure
                    )
                }
            }
        }
    }

    /**
     * 用户二次确认后执行恢复。
     *
     * 刻意**再次读流 + decode**：不信任 [readBackup] 缓存的摘要 —— 用户在确认框停留期间，
     * 所选文件可能被别的应用改写或删除。解码与格式校验全在 [BackupManager.restore] 的
     * 事务之外完成；事务内任一步抛错由 Room 整体回滚，绝不留下半清状态。
     * 失败写 Result.failure 且记录原始堆栈，绝不静默吞掉。
     */
    fun restoreBackup(uri: Uri) {
        if (_uiState.value.isBackingUp) return
        _uiState.update {
            it.copy(
                isBackingUp = true,
                pendingRestore = null,
                exportResult = null,
                backupResult = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = BackupCodec.decode(readBackupText(uri))
                backupManager.restore(payload)
                // 事务成功后才复位进程内内存态：库回滚时旧缓存仍与旧库一致，不能提前清
                resetInMemoryStateAfterRestore()
                val message = "已恢复 ${payload.assets.size} 项资产、" +
                    "${payload.snapshots.size} 条历史快照、${payload.transactions.size} 笔交易、" +
                    "${payload.dividends.size} 笔分红"
                AppLogger.i("从备份恢复完成：7 张表整表替换，未自动触发行情同步", "SettingsVM")
                _uiState.update {
                    it.copy(
                        isBackingUp = false,
                        exportResult = Result.success(message),
                        backupResult = Result.success(message)
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isBackingUp = false) }
                throw e
            } catch (e: Exception) {
                AppLogger.e("从备份恢复失败: ${e.message}", e, "SettingsVM")
                val failure: Result<String> = Result.failure(readableBackupError("恢复失败", e))
                _uiState.update {
                    it.copy(
                        isBackingUp = false,
                        exportResult = failure,
                        backupResult = failure
                    )
                }
            }
        }
    }

    fun clearBackupResult() {
        _uiState.update { it.copy(backupResult = null) }
    }

    /**
     * Snackbar 消费动作结果时**一次 update 同时清两个字段**。
     *
     * 备份动作会把同一结果写进 exportResult 与 backupResult（前者复用既有导出提示通道、
     * 后者是备份动作自己的状态）。若分两次 clear，中间态仍有一个字段非 null，
     * 界面上 `exportResult ?: backupResult` 会短暂保持「同一个结果」而依赖
     * Result.equals 才不重复弹提示；一次清干净，不留这种隐式耦合。
     */
    fun clearActionResult() {
        _uiState.update { it.copy(exportResult = null, backupResult = null) }
    }

    /** 确认框确认 / 取消后清掉待恢复摘要，避免切页回来又弹一次 */
    fun clearPendingRestore() {
        _uiState.update { it.copy(pendingRestore = null) }
    }

    /** 读整个备份文件为 UTF-8 文本；去掉可能存在的 BOM，避免 JSONObject 解析失败 */
    private fun readBackupText(uri: Uri): String {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw InvalidBackupException("无法打开所选文件，请确认文件仍存在且可被读取")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        return text.removePrefix("\uFEFF")
    }

    /**
     * 自定义备份异常的 message 已经是给用户看的中文，原样透传；其余系统异常包一层
     * 中文前缀但同时保留 cause（不吞异常，AppLogger 也记了完整堆栈）。
     */
    private fun readableBackupError(prefix: String, e: Exception): Throwable =
        when (e) {
            is InvalidBackupException, is UnsupportedBackupVersionException -> e
            else -> IllegalStateException("$prefix：${e.message ?: e.javaClass.simpleName}", e)
        }

    /**
     * 恢复成功后复位进程内内存态（QA R06）。
     *
     * 账目整表换掉了，旧的分红缓存 / 派生行情缓存 / 汇率 / 同步失败标记都指向旧数据，
     * 不清会显示错值。逐项尽力而为：某项缓存单例抛错不该把「账目已恢复」报成失败，
     * 但取消必须原样向外抛，不能吞掉。
     *
     * 刻意不走「偏好整块回写」（A3 铁律）：这里只调
     * [DashboardPreferencesManager.invalidateAnnualDividendIncome] 让年度分红缓存失效，
     * 显示偏好与月度生活费等字段原样保留 —— 备份本就不含偏好，恢复也不该动它。
     */
    private suspend fun resetInMemoryStateAfterRestore() {
        safelyReset("年度分红缓存") { dashboardPreferencesManager.invalidateAnnualDividendIncome() }
        safelyReset("分红内存缓存") { dividendRepository.clearCache() }
        safelyReset("分红抓取状态") { dividendRepository.clearFetchState() }
        safelyReset("派生行情缓存") { priceCacheMaintenance.clearDerivedCaches() }
        safelyReset("汇率") { ExchangeRateManager.resetToDefaultRates() }
        safelyReset("同步失败集合") { AssetSyncFailureStore.getInstance(appContext).clear() }
    }

    private suspend fun safelyReset(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("恢复后复位 $label 失败: ${e.message}", "SettingsVM")
        }
    }

    fun deleteAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state -> state.copy(isDeleting = true, deleteResult = null) }
            try {
                assetRepository.deleteAllData()
                clearAllPreferences()
                // 数据清了，后台任务和内存缓存不能留着：否则用户以为关干净了，
                // 后台还在按旧偏好跑同步。最后从磁盘回读真实状态。
                PriceSyncScheduler.cancelDailySync(appContext)
                // 每日快照任务同样要取消：原先只取消了价格同步，漏了快照。
                // 于是清库后 SnapshotWorker 仍在每天 15:10 运行，对已清空的组合继续建快照
                // （createSnapshot 不判空），历史净值曲线会多出一串净资产为 0 的点。
                SnapshotScheduler.cancelDailySnapshot(appContext)
                // 本地文件同样要清：导出的 CSV、诊断报告都在 cacheDir/exports，运行日志在
                // externalFilesDir。不清的话「清除所有数据」之后，账目快照还躺在手机里。
                runCatching { dataExporter.clearExportedFiles() }
                runCatching { LogDiagnostics.clear(appContext) }
                // 派生缓存也要清：Room/SharedPreferences 清掉不代表内存与磁盘缓存清掉。
                // 否则用户清库后重新添加同一代码，旧行情、旧分红、旧基金净值会原样回来。
                // 统一走数据层门面：设置页不再直接认识各个数据源单例。
                priceCacheMaintenance.clearDerivedCaches()
                runCatching { dividendRepository.clearCache() }
                // 汇率单例还持有内存里的旧汇率与刷新时间（磁盘偏好已清），
                // 不同步复位的话设置页会一直显示清除前的值，直到重启进程
                ExchangeRateManager.resetToDefaultRates()
                // 磁盘已清，Manager 的唯一偏好流复位成默认值：
                // 否则各页面仍按旧值渲染，再拨任何一个开关就会把「已清除的旧偏好」写回磁盘。
                dashboardPreferencesManager.resetToDefaults()
                refreshPriceSyncState()
                _uiState.update { state -> state.copy(isDeleting = false, deleteResult = Result.success(Unit)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("删除数据失败: ${e.message}", e, "SettingsVM")
                _uiState.update { state -> state.copy(isDeleting = false, deleteResult = Result.failure(e)) }
            }
        }
    }

    /**
     * 清掉所有 SharedPreferences。
     *
     * `deleteAllData` 原先只清数据库：显示偏好、畅想 api 配置、
     * 「名称→代码」记忆、崩溃记录、通知去重记录全都留在磁盘上。用户以为"清干净了"，
     * 重新录入时旧记忆/旧设置还在，而且散落七处，出问题时根本想不到是残留造成的。
     *
     * 直接扫 shared_prefs 目录而不是逐个列名字：将来新增任何 Preferences 都会被自动覆盖。
     */
    private fun clearAllPreferences() {
        // commit() 而非 apply()：清除后用户大概率立刻重启 App，异步落盘可能来不及写。
        val dir = File(appContext.applicationInfo?.dataDir ?: appContext.filesDir.parent, "shared_prefs")
        val files = try {
            dir.listFiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        var cleared = 0
        for (file in files) {
            if (!file.name.endsWith(".xml")) continue
            val name = file.name.removeSuffix(".xml")
            // 别动 WorkManager 自己的偏好：里面存着「被强制停止后需要重排」的标记，
            // 清掉它会漏掉一次后台任务重排。那不是用户数据，不该跟着一起清。
            if (name.startsWith("androidx.work")) continue
            try {
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().commit()
                cleared++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("清除偏好失败: $name - ${e.message}", "SettingsVM")
            }
        }
        AppLogger.i("已清除 $cleared 项偏好设置", "SettingsVM")
    }

    fun checkDataSourceHealth() {
        // 连点入口行 / 弹窗里重复点「刷新」时不要再起一轮：每次体检要并发跑 4~5 个 HTTP 探测，
        // 重叠执行既浪费流量，也让最终落到界面上的结果来自哪一轮变得不确定
        if (_uiState.value.isCheckingHealth) return
        // 守卫必须在 launch 之前同步置位：写在 IO 协程里的话，快速连点两下时
        // 第二个调用仍会看到 false，于是并发跑两轮体检、结果互相覆盖。
        _uiState.update { state -> state.copy(isCheckingHealth = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val health = dataSourceHealthChecker.checkAllHealth()
                // 结果摘要回流到列表行的 subtitle：不点开弹窗也能看到各域几个源可用
                _uiState.update { state ->
                    state.copy(
                        isCheckingHealth = false,
                        dataSourceHealth = health,
                        dataSourceHealthSummary = summarizeHealth(health)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("检查数据源健康失败: ${e.message}", e, "SettingsVM")
                // 摘要与结果一起清掉：否则 Snackbar 说「失败」，同一行却还挂着上次的
                // 「全部正常」，两个信息互相打架；且旧摘要没有时间戳、看不出有多旧
                _uiState.update { state -> state.copy(
                    isCheckingHealth = false,
                    dataSourceHealth = null,
                    dataSourceHealthSummary = "上次体检失败，点击重试",
                    errorMessage = "检查数据源状态失败"
                ) }
            }
        }
    }

    /**
     * 体检摘要按资产域分组，例如「股票 3/3 · 基金 1/1 · 加密 0/1（部分源降级）」。
     *
     * 不混算成「x/y 个源可用」：币安不可用只影响加密货币，不该把股票一并报成降级。
     */
    private fun summarizeHealth(health: List<DataSourceHealth>): String = when {
        health.isEmpty() -> "未获取到数据源状态"
        health.all { it.isAvailable } -> "全部正常（${health.size} 个源）"
        else -> health.groupBy { it.category }
            .entries
            .joinToString(" · ") { (category, sources) ->
                "$category ${sources.count { it.isAvailable }}/${sources.size}"
            } + "（部分源降级）"
    }

    fun clearExportResult() {
        _uiState.update { state -> state.copy(exportResult = null) }
    }

    fun clearDeleteResult() {
        _uiState.update { state -> state.copy(deleteResult = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { state -> state.copy(errorMessage = null) }
    }

    /**
     * 保存「自定义仪表盘」偏好。
     *
     * ## 铁律：必须走原子「读-改-写」，禁止整块回写
     *
     * 原先直接 `savePreferences(preferences)` 把 sheet 累积的完整副本整块落盘。
     * 该副本的初值取自本 VM **构造期**加载的偏好快照——用户在设置页
     * 停留期间，日历页可能改了月度生活费、仪表盘/持仓刷新可能更新年度分红缓存，
     * 整块回写会把这些字段冲回旧值（MEMORY.md 记录过同类事故：持仓页用陈旧副本
     * 整体回写踩掉日历的月度生活费）。因此改走 Manager 的
     * [DashboardPreferencesManager.updatePreferences]：先拉磁盘最新值，只把
     * DashboardConfigSheet 负责的显示/排序字段合并上去，其余字段
     * （月度生活费 / 年度分红缓存 / 分组展开态）以 fresh 为准原样保留。
     */
    fun saveDashboardPreferences(preferences: DashboardPreferences) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dashboardPreferencesManager.updatePreferences { fresh ->
                    fresh.copy(
                        showTotalAssets = preferences.showTotalAssets,
                        showNetAssets = preferences.showNetAssets,
                        showProfit = preferences.showProfit,
                        showDistribution = preferences.showDistribution,
                        showAssetGrowth = preferences.showAssetGrowth,
                        showDividendProgress = preferences.showDividendProgress,
                        showNextPayout = preferences.showNextPayout,
                        showMonthlyCashFlow = preferences.showMonthlyCashFlow,
                        showHistoryMonthlyGrowth = preferences.showHistoryMonthlyGrowth,
                        showBankDeposits = preferences.showBankDeposits,
                        showStocks = preferences.showStocks,
                        showFunds = preferences.showFunds,
                        showCrypto = preferences.showCrypto,
                        showLiabilities = preferences.showLiabilities,
                        hideLowValueCrypto = preferences.hideLowValueCrypto,
                        lowValueThresholdCny = preferences.lowValueThresholdCny,
                        hideUnknownPriceCrypto = preferences.hideUnknownPriceCrypto,
                        sortOrder = preferences.sortOrder,
                        defaultPeriod = preferences.defaultPeriod
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存仪表盘偏好失败: ${e.message}", e, "SettingsVM")
                // 开关按状态渲染，失败后会弹回旧值；必须告诉用户为什么没生效
                _uiState.update {
                    it.copy(errorMessage = "保存仪表盘设置失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun setAutoPriceSyncEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                priceSyncPreferencesManager.setAutoSyncEnabled(enabled)
                if (!enabled) {
                    priceSyncPreferencesManager.setConsecutiveFailureCount(0)
                }
                _uiState.update { it.copy(autoPriceSyncEnabled = enabled) }
                // 调度收敛到 VM：UI 只管拨开关，避免以后新增入口忘了重排/取消后台任务
                if (enabled) {
                    PriceSyncScheduler.scheduleDailySync(appContext)
                } else {
                    PriceSyncScheduler.cancelDailySync(appContext)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存自动价格同步设置失败: ${e.message}", e, "SettingsVM")
                // 落盘或调度失败：开关是按状态渲染的，会弹回原值；必须告诉用户为什么没生效，
                // 否则只是一次「点了没反应」
                _uiState.update {
                    it.copy(errorMessage = "保存自动刷新设置失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 盘中是否也自动刷股票/场内基金。关=仅盘后刷(默认)。改完由调用方重排后台周期 */
    fun setStockIntradaySyncEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                priceSyncPreferencesManager.setStockIntradaySyncEnabled(enabled)
                _uiState.update { it.copy(stockIntradaySyncEnabled = enabled) }
                // 仅在总开关开启时重排周期；总开关关着只存偏好，等开启再生效
                if (_uiState.value.autoPriceSyncEnabled) {
                    PriceSyncScheduler.scheduleDailySync(appContext)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存盘中同步股票设置失败: ${e.message}", e, "SettingsVM")
                _uiState.update {
                    it.copy(errorMessage = "保存盘中同步设置失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 是否自动刷虚拟货币。关=只手动刷(默认)。改完由调用方重排后台周期 */
    fun setCryptoAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                priceSyncPreferencesManager.setCryptoAutoSyncEnabled(enabled)
                _uiState.update { it.copy(cryptoAutoSyncEnabled = enabled) }
                if (_uiState.value.autoPriceSyncEnabled) {
                    PriceSyncScheduler.scheduleDailySync(appContext)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存自动同步虚拟货币设置失败: ${e.message}", e, "SettingsVM")
                _uiState.update {
                    it.copy(errorMessage = "保存自动同步虚拟货币设置失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /**
     * 从磁盘重读价格同步的快照状态。
     *
     * StateFlow 初值只在 ViewModel 创建时读一次：别处（后台任务、系统设置、
     * 清除数据）改过磁盘后，内存里的值就过期了。进入设置页或清除数据后调用，
     * 保证界面显示的是真实值。
     *
     * 四个字段已在同一 SettingsUiState 里，整组刷新合并为**一次** update：原先多条流各写一次，
     * UI 要连收多个状态、整页重组多遍（设置页的 remember 块同时 key 着其中四个值）。
     */
    fun refreshPriceSyncState() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                readSettingsSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("重读设置快照失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    /**
     * 进入页面时一次性重读「磁盘镜像」：价格同步 + 汇率。
     *
     * 偏好本身不再需要重读：全应用唯一偏好流在任一页面写入时就同步更新了，
     * 本页订阅的 [dashboardPreferences] 自然已是最新值。
     */
    fun refreshOnEnter() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                readSettingsSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("进入设置页重读状态失败: ${e.message}", e, "SettingsVM")
            }
        }
    }

    /** 同一次 IO 内读齐所有磁盘镜像，一次 update 写回（含汇率快照） */
    private suspend fun readSettingsSnapshot() {
        val rate = ExchangeRateManager.getRate("USD")
        val rateAt = ExchangeRateManager.getLastRefreshTimeMillis()
        _uiState.update { state ->
            state.copy(
                autoPriceSyncEnabled = priceSyncPreferencesManager.isAutoSyncEnabled(),
                lastPriceSyncTime = priceSyncPreferencesManager.getLastSyncTimeMillis(),
                stockIntradaySyncEnabled = priceSyncPreferencesManager.isStockIntradaySyncEnabled(),
                cryptoAutoSyncEnabled = priceSyncPreferencesManager.isCryptoAutoSyncEnabled(),
                usdRate = rate,
                usdRateUpdatedAt = rateAt
            )
        }
    }

    /**
     * 手动刷新汇率（6 小时 TTL，用户看到旧值时能自己刷）。
     *
     * 汇率是单例里的普通字段，界面原先直接 `ExchangeRateManager.getRate()` 读——
     * 刷新后值变了但没有任何可观察状态，页面不会重组。这里把结果写回 SettingsUiState。
     */
    fun refreshExchangeRate() {
        if (_uiState.value.isRefreshingRate) return
        _uiState.update { it.copy(isRefreshingRate = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // refresh() 失败时**返回 false 而不是抛异常**：不能只看异常，
                // 否则网络不通/汇率源异常时用户点了「刷新」完全没有任何反馈
                val ok = ExchangeRateManager.refresh(force = true)
                if (ok) {
                    _uiState.update {
                        it.copy(
                            isRefreshingRate = false,
                            usdRate = ExchangeRateManager.getRate("USD"),
                            usdRateUpdatedAt = ExchangeRateManager.getLastRefreshTimeMillis()
                        )
                    }
                } else {
                    AppLogger.w("刷新汇率返回失败（网络或汇率源异常）", "SettingsVM")
                    _uiState.update {
                        it.copy(
                            isRefreshingRate = false,
                            errorMessage = "汇率刷新失败，仍沿用当前汇率（网络不通或汇率源异常）"
                        )
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isRefreshingRate = false) }
                throw e
            } catch (e: Exception) {
                AppLogger.e("刷新汇率失败: ${e.message}", e, "SettingsVM")
                _uiState.update {
                    it.copy(
                        isRefreshingRate = false,
                        errorMessage = "汇率刷新失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }
}

