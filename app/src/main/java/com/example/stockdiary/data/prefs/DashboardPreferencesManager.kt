package com.example.stockdiary.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.dividend.dividendInputFingerprint
import com.example.stockdiary.data.dividend.recordsDigest
import com.example.stockdiary.database.Asset
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 月度生活费的默认值（人民币字符串）。**单一来源**：[DashboardPreferences.monthlyExpense]
 * 的默认值、[DashboardPreferencesManager.loadPreferences] 的读回退、以及
 * `CalendarViewModel` 的 UiState 默认值都引用它，避免散在三处硬编码、改一处漏改另一处
 * 导致 FIRE 计算口径漂移。
 */
const val DEFAULT_MONTHLY_EXPENSE = "5000"

data class DashboardPreferences(
    val showTotalAssets: Boolean = false,
    val showNetAssets: Boolean = true,
    val showProfit: Boolean = true,
    val showDistribution: Boolean = true,
    /**
     * 是否显示「资产增值」卡片（本月 / 今年净资产增加额）。
     * 默认 true：这是后加的开关，老用户升级后不能凭空少一张卡。
     */
    val showAssetGrowth: Boolean = true,
    /**
     * 是否显示「收息进度」卡片（今年已收 / 今年预计）。
     * 默认 true：同上，老用户升级后这张卡还在。
     */
    val showDividendProgress: Boolean = true,
    /** 是否显示「下一笔分红」卡片。默认 true。仅当有下一笔待派息时才会真正出场 */
    val showNextPayout: Boolean = true,
    /** 是否显示「月度现金流」卡片。默认 true */
    val showMonthlyCashFlow: Boolean = true,
    /**
     * 是否显示历史页的「月度增值」卡片（逐月收益柱状图）。默认 true。
     * 与首页的「资产增值」卡片是**两个独立的东西**：那个由 [showAssetGrowth] 控制，
     * 这个只影响历史页面。开关放在自定义仪表盘的「历史页面」分组里，互不干扰。
     */
    val showHistoryMonthlyGrowth: Boolean = true,
    val showBankDeposits: Boolean = true,
    val showStocks: Boolean = true,
    val showFunds: Boolean = true,
    val showCrypto: Boolean = true,
    val showLiabilities: Boolean = true,
    /**
     * 隐藏低价值虚拟货币：开启后，已知价且人民币市值低于 [lowValueThresholdCny] 的
     * 加密货币不在持仓列表显示（总资产仍按全量算）。用于清掉币安账户里一堆 dust。
     * 价格未知（待更新）的不藏，避免误藏真实持仓。
     */
    val hideLowValueCrypto: Boolean = false,
    /** 低价值阈值（人民币，文本存储以便自由输入），非法/负数回退默认 10 */
    val lowValueThresholdCny: String = "10",
    /**
     * 同时也隐藏**无价格**（currentPrice == null，界面显示「价格待更新」/ ¥0.00）的加密货币。
     * 默认关：无行情不等于没持仓，静默丢掉等于弄丢真实资产；开启即表示用户接受这个取舍。
     * 仅当 [hideLowValueCrypto] 开启时才有意义。
     */
    val hideUnknownPriceCrypto: Boolean = false,
    val defaultExpanded: Set<String> = emptySet(),
    val sortOrder: SortOrder = SortOrder.BY_TYPE,
    val defaultPeriod: String = "1M",
    val monthlyExpense: String = DEFAULT_MONTHLY_EXPENSE,
    /**
     * 上次算出的年度分红，以及算它时的持仓代码集合。
     *
     * ViewModel 每次进入持仓页都会重建，不做缓存的话用户每次进来都要看一次
     * 「年度分红：计算中」——数据根本没变，重算纯属等待。
     * 代码集合用来判断缓存还有没有效：持仓一变，旧值立刻作废。
     */
    val annualDividendCache: Double = 0.0,
    val annualDividendSymbols: Set<String> = emptySet(),
    /**
     * 算这个缓存时的**输入指纹**（代码 + 持仓数量 + 存款利率，见
     * [com.example.stockdiary.data.dividend.dividendInputFingerprint]）。
     *
     * 只比对 [annualDividendSymbols] 会漏掉「加仓」和「改存款余额/利率」：代码集合没变，
     * 但年化收入已经变了，缓存却被当成仍然有效。空串表示旧版本写入的数据，一律视为失效。
     */
    val annualDividendFingerprint: String = "",
    /**
     * 算这个缓存时的**分红记录版本摘要**（见
     * [com.example.stockdiary.data.dividend.recordsDigest]）。
     *
     * A1 / A4：原 [annualDividendFingerprint] 只含持仓、不含分红记录本身，于是用户在持仓页
     * 手动增删改一条分红后指纹不变 → 命中旧缓存返回旧值 → 顶部「年度分红」一直停在旧数字，
     * 直到手动全量刷新才纠正（A1b）。补上记录摘要后，记录一变摘要就变、缓存立刻失效。
     * 空串表示旧版本 / 已被 [invalidateAnnualDividendIncome] 主动作废，进场必须重算。
     */
    val annualDividendRecordsVersion: String = ""
)

enum class SortOrder {
    BY_TYPE,
    BY_VALUE_DESC,
    BY_VALUE_ASC,
    BY_NAME
}

/** 低价值阈值（人民币）：把文本偏好解析成数值，非法或负数回退默认 10 */
fun DashboardPreferences.lowValueThreshold(): Double =
    lowValueThresholdCny.toDoubleOrNull()?.takeIf { it >= 0 } ?: 10.0

class DashboardPreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 全应用**唯一**的偏好内存副本。
     *
     * 此前 Dashboard / Portfolio / History / Settings 四个 ViewModel 各自持有一份
     * `MutableStateFlow(loadPreferences())`，再靠「回前台 reloadPreferences」补丁同步磁盘，
     * 于是同一份偏好出现 4 个副本、谁先写谁后写全靠时序。现在只有这里一份：
     * 写入统一走 [updatePreferences]（写盘后同步更新本流），各页面直接订阅本流，
     * 任一页面的修改立即对所有页面生效，不再需要 reload 补丁。
     */
    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<DashboardPreferences> = _preferences.asStateFlow()
    
    companion object {
        private const val PREFS_NAME = "dashboard_prefs"
        
        private const val KEY_SHOW_TOTAL_ASSETS = "show_total_assets"
        private const val KEY_SHOW_NET_ASSETS = "show_net_assets"
        private const val KEY_SHOW_PROFIT = "show_profit"
        private const val KEY_SHOW_DISTRIBUTION = "show_distribution"
        private const val KEY_SHOW_ASSET_GROWTH = "show_asset_growth"
        private const val KEY_SHOW_DIVIDEND_PROGRESS = "show_dividend_progress"
        private const val KEY_SHOW_NEXT_PAYOUT = "show_next_payout"
        private const val KEY_SHOW_MONTHLY_CASH_FLOW = "show_monthly_cash_flow"
        private const val KEY_SHOW_HISTORY_MONTHLY_GROWTH = "show_history_monthly_growth"
        private const val KEY_SHOW_BANK_DEPOSITS = "show_bank_deposits"
        private const val KEY_SHOW_STOCKS = "show_stocks"
        private const val KEY_SHOW_FUNDS = "show_funds"
        private const val KEY_SHOW_CRYPTO = "show_crypto"
        private const val KEY_SHOW_LIABILITIES = "show_liabilities"
        private const val KEY_HIDE_LOW_VALUE_CRYPTO = "hide_low_value_crypto"
        private const val KEY_LOW_VALUE_THRESHOLD = "low_value_threshold"
        private const val KEY_HIDE_UNKNOWN_PRICE_CRYPTO = "hide_unknown_price_crypto"
        private const val KEY_DEFAULT_EXPANDED = "default_expanded"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_DEFAULT_PERIOD = "default_period"
        private const val KEY_MONTHLY_EXPENSE = "monthly_expense"
        private const val KEY_ANNUAL_DIVIDEND_CACHE = "annual_dividend_cache"
        /** 分红缓存的新存储：SharedPreferences 没有 putDouble，旧实现用 Float 会丢精度（约 1 分） */
        private const val KEY_ANNUAL_DIVIDEND_CACHE_V2 = "annual_dividend_cache_v2"
        private const val KEY_ANNUAL_DIVIDEND_SYMBOLS = "annual_dividend_symbols"
        private const val KEY_ANNUAL_DIVIDEND_FINGERPRINT = "annual_dividend_fingerprint"
        private const val KEY_ANNUAL_DIVIDEND_RECORDS_VERSION = "annual_dividend_records_version"
        
        @Volatile
        private var instance: DashboardPreferencesManager? = null
        
        fun getInstance(context: Context): DashboardPreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: DashboardPreferencesManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 容错解析排序方式。
         *
         * 这里是**绝对不能裸 [,valueOf]** 的地方：[DashboardPreferencesManager] 被四个
         * ViewModel 在构造期调用（Dashboard / Settings / Calendar / History）。
         * 一旦 SharedPreferences 里的值是非法的（跨版本改名、备份还原、被外部改写），
         * [valueOf] 会抛 IllegalArgumentException，表现为 Activity 创建即崩溃 —— 用户连
         * 卸载重装之外的自救手段都没有。坏数据应该退化成默认值，而不是炸掉整个 App。
         */
        private fun sortOrderOf(raw: String?): SortOrder {
            if (raw == null) return SortOrder.BY_TYPE
            return runCatching { SortOrder.valueOf(raw) }
                .getOrElse {
                    AppLogger.w("未知的排序偏好 '$raw'，已回退为默认（${SortOrder.BY_TYPE}）", "DashboardPrefs")
                    SortOrder.BY_TYPE
                }
        }
    }
    
    fun loadPreferences(): DashboardPreferences {
        return DashboardPreferences(
            showTotalAssets = prefs.getBoolean(KEY_SHOW_TOTAL_ASSETS, false),
            showNetAssets = prefs.getBoolean(KEY_SHOW_NET_ASSETS, true),
            showProfit = prefs.getBoolean(KEY_SHOW_PROFIT, true),
            showDistribution = prefs.getBoolean(KEY_SHOW_DISTRIBUTION, true),
            // 后加的开关，缺省 true：老用户升级后这张卡还在
            showAssetGrowth = prefs.getBoolean(KEY_SHOW_ASSET_GROWTH, true),
            showDividendProgress = prefs.getBoolean(KEY_SHOW_DIVIDEND_PROGRESS, true),
            showNextPayout = prefs.getBoolean(KEY_SHOW_NEXT_PAYOUT, true),
            showMonthlyCashFlow = prefs.getBoolean(KEY_SHOW_MONTHLY_CASH_FLOW, true),
            showHistoryMonthlyGrowth = prefs.getBoolean(KEY_SHOW_HISTORY_MONTHLY_GROWTH, true),
            showBankDeposits = prefs.getBoolean(KEY_SHOW_BANK_DEPOSITS, true),
            showStocks = prefs.getBoolean(KEY_SHOW_STOCKS, true),
            showFunds = prefs.getBoolean(KEY_SHOW_FUNDS, true),
            showCrypto = prefs.getBoolean(KEY_SHOW_CRYPTO, true),
            showLiabilities = prefs.getBoolean(KEY_SHOW_LIABILITIES, true),
            hideLowValueCrypto = prefs.getBoolean(KEY_HIDE_LOW_VALUE_CRYPTO, false),
            lowValueThresholdCny = prefs.getString(KEY_LOW_VALUE_THRESHOLD, "10") ?: "10",
            hideUnknownPriceCrypto = prefs.getBoolean(KEY_HIDE_UNKNOWN_PRICE_CRYPTO, false),
            defaultExpanded = prefs.getStringSet(KEY_DEFAULT_EXPANDED, emptySet()) ?: emptySet(),
            sortOrder = sortOrderOf(prefs.getString(KEY_SORT_ORDER, null)),
            defaultPeriod = prefs.getString(KEY_DEFAULT_PERIOD, "1M") ?: "1M",
            monthlyExpense = prefs.getString(KEY_MONTHLY_EXPENSE, DEFAULT_MONTHLY_EXPENSE) ?: DEFAULT_MONTHLY_EXPENSE,
            // 优先读字符串版（精度完整）；旧安装只有 Float 版，回退读取，下次写入即升级
            annualDividendCache = prefs.getString(KEY_ANNUAL_DIVIDEND_CACHE_V2, null)
                ?.toDoubleOrNull()
                ?: prefs.getFloat(KEY_ANNUAL_DIVIDEND_CACHE, 0f).toDouble(),
            annualDividendSymbols = prefs.getStringSet(KEY_ANNUAL_DIVIDEND_SYMBOLS, emptySet())?.toSet()
                ?: emptySet(),
            annualDividendFingerprint = prefs.getString(KEY_ANNUAL_DIVIDEND_FINGERPRINT, "") ?: "",
            annualDividendRecordsVersion = prefs.getString(KEY_ANNUAL_DIVIDEND_RECORDS_VERSION, "") ?: ""
        )
    }
    
    /**
     * 直接把一份完整偏好副本整块落盘。**仅供本类内部使用**，业务代码一律走 [updatePreferences]。
     *
     * [updatePreferences] 会先 `loadPreferences()` 拉磁盘最新值、再只套用 mutate 改动的字段，
     * 避免用过期副本把别的页面刚写的值覆盖掉。把本方法收成 private，是为了从编译期杜绝
     * 「拿构造期的陈旧副本整块回写」这类踩踏（历史事故：日历改的月度生活费被持仓页冲回旧值）。
     */
    private fun savePreferences(preferences: DashboardPreferences) {
        prefs.edit().apply {
            putBoolean(KEY_SHOW_TOTAL_ASSETS, preferences.showTotalAssets)
            putBoolean(KEY_SHOW_NET_ASSETS, preferences.showNetAssets)
            putBoolean(KEY_SHOW_PROFIT, preferences.showProfit)
            putBoolean(KEY_SHOW_DISTRIBUTION, preferences.showDistribution)
            putBoolean(KEY_SHOW_ASSET_GROWTH, preferences.showAssetGrowth)
            putBoolean(KEY_SHOW_DIVIDEND_PROGRESS, preferences.showDividendProgress)
            putBoolean(KEY_SHOW_NEXT_PAYOUT, preferences.showNextPayout)
            putBoolean(KEY_SHOW_MONTHLY_CASH_FLOW, preferences.showMonthlyCashFlow)
            putBoolean(KEY_SHOW_HISTORY_MONTHLY_GROWTH, preferences.showHistoryMonthlyGrowth)
            putBoolean(KEY_SHOW_BANK_DEPOSITS, preferences.showBankDeposits)
            putBoolean(KEY_SHOW_STOCKS, preferences.showStocks)
            putBoolean(KEY_SHOW_FUNDS, preferences.showFunds)
            putBoolean(KEY_SHOW_CRYPTO, preferences.showCrypto)
            putBoolean(KEY_SHOW_LIABILITIES, preferences.showLiabilities)
            putBoolean(KEY_HIDE_LOW_VALUE_CRYPTO, preferences.hideLowValueCrypto)
            putString(KEY_LOW_VALUE_THRESHOLD, preferences.lowValueThresholdCny)
            putBoolean(KEY_HIDE_UNKNOWN_PRICE_CRYPTO, preferences.hideUnknownPriceCrypto)
            putStringSet(KEY_DEFAULT_EXPANDED, preferences.defaultExpanded)
            putString(KEY_SORT_ORDER, preferences.sortOrder.name)
            putString(KEY_DEFAULT_PERIOD, preferences.defaultPeriod)
            putString(KEY_MONTHLY_EXPENSE, preferences.monthlyExpense)
            // 用字符串存 Double：SharedPreferences 没有 putDouble，Float 只有 7 位有效数字，
            // 首页与持仓页对同一份缓存会差 1 分。旧 Float 键保留不删（老版本回退用），
            // 读侧优先新键（见 loadPreferences）。
            putString(KEY_ANNUAL_DIVIDEND_CACHE_V2, preferences.annualDividendCache.toString())
            putStringSet(KEY_ANNUAL_DIVIDEND_SYMBOLS, preferences.annualDividendSymbols)
            putString(KEY_ANNUAL_DIVIDEND_FINGERPRINT, preferences.annualDividendFingerprint)
            putString(KEY_ANNUAL_DIVIDEND_RECORDS_VERSION, preferences.annualDividendRecordsVersion)
            apply()
        }
    }
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        // 磁盘已清空，唯一副本也要立刻回到默认值，否则各页面仍按旧副本渲染
        _preferences.value = loadPreferences()
    }

    /**
     * 原子「读-改-写」偏好，并返回写盘后的完整副本。这是所有偏好写入的**统一入口**。
     *
     * 为什么需要它：各 ViewModel 各自持有一份 [DashboardPreferences] 内存副本，若直接用可能过期的
     * 副本整体 [savePreferences]，就会把别的页面刚改的字段覆盖掉——最典型的就是日历 FIRE 算的
     * 「月度生活费」被持仓页冲回旧值（表现为「改了但不保存」）。
     *
     * 本方法强制在写入前先 `loadPreferences()` 拉磁盘最新值，再只套用 [mutate] 里改的字段，
     * 从而保证「只动我改的字段、其余原样保留」。所有写者都走这里，就能从根上消除
     * 「共享整块偏好互相踩踏」这一类 bug，而不必每个 VM 各写一遍 reload+merge、漏一个就出事。
     */
    @Synchronized
    fun updatePreferences(mutate: (DashboardPreferences) -> DashboardPreferences): DashboardPreferences {
        val fresh = loadPreferences()
        val updated = mutate(fresh)
        savePreferences(updated)
        // 写盘成功后同步唯一副本：订阅本流的各页面立即看到新值，无需回前台 reload
        _preferences.value = updated
        return updated
    }

    /**
     * 年度分红缓存的**唯一写入点**（A1「一根多症」统一修复）。
     *
     * 此前年度分红缓存（`annualDividendCache` / `annualDividendSymbols` /
     * `annualDividendFingerprint`）散落在 Dashboard / Portfolio 四个调用点各自 `updatePreferences`
     * 直写，写入口径（资产范围）还不统一 → 跨页两个数、重进「新→旧→新」闪跳。
     * 这里把「算完收入后回写缓存」收口到唯一方法：所有调用点（首页进场、首页刷新 phase-2、
     * 持仓进场、持仓刷新）都经它落盘，写入字段与口径完全一致。
     *
     * 同时写入 [DashboardPreferences.annualDividendRecordsVersion]（分红记录摘要）：
     * 原指纹只含持仓不含记录，手动改分红切回首页 30s 内仍 stale（A1b）——记录摘要让记录一变
     * 缓存立刻失效。
     *
     * @param assets 本次计算年度分红所覆盖的资产（决定指纹与代码集合）
     * @param recordsBySymbol 本次解析出的分红记录（决定记录摘要）
     * @param totalAnnualIncome 算出的年度分红总额
     */
    fun persistAnnualDividendIncome(
        assets: List<Asset>,
        recordsBySymbol: Map<String, List<DividendRecord>>,
        totalAnnualIncome: Double
    ): DashboardPreferences = updatePreferences { prefs ->
        prefs.copy(
            annualDividendCache = totalAnnualIncome,
            annualDividendSymbols = assets.mapNotNull { it.symbol }.toSet(),
            annualDividendFingerprint = dividendInputFingerprint(assets),
            annualDividendRecordsVersion = recordsDigest(recordsBySymbol)
        )
    }

    /**
     * 主动作废年度分红缓存（A1 / A4 的 `invalidateIncome()` 门面）：手动增删改分红、导入后调用，
     * 强制下次进场重算并清掉旧指纹与记录摘要。
     *
     * 旧实现 [PortfolioViewModel.refreshDividendIncomeAfterManualEdit] 只清 `annualDividendFingerprint`，
     * 漏了记录摘要，导致手动改分红后首页重进仍命中旧缓存（A1b）。这里一并清掉两份，
     * 进场判据（指纹匹配 **且** 记录版本非空）随即失效、必走重算。
     */
    fun invalidateAnnualDividendIncome(): DashboardPreferences = updatePreferences { prefs ->
        prefs.copy(annualDividendFingerprint = "", annualDividendRecordsVersion = "")
    }
}
