package com.example.stockdiary.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.stockdiary.utils.AppLogger

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
    val monthlyExpense: String = "5000",
    /**
     * 上次算出的年度分红，以及算它时的持仓代码集合。
     *
     * ViewModel 每次进入持仓页都会重建，不做缓存的话用户每次进来都要看一次
     * 「年度分红：计算中」——数据根本没变，重算纯属等待。
     * 代码集合用来判断缓存还有没有效：持仓一变，旧值立刻作废。
     */
    val annualDividendCache: Double = 0.0,
    val annualDividendSymbols: Set<String> = emptySet()
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
        private const val KEY_ANNUAL_DIVIDEND_SYMBOLS = "annual_dividend_symbols"
        
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
            monthlyExpense = prefs.getString(KEY_MONTHLY_EXPENSE, "5000") ?: "5000",
            annualDividendCache = prefs.getFloat(KEY_ANNUAL_DIVIDEND_CACHE, 0f).toDouble(),
            annualDividendSymbols = prefs.getStringSet(KEY_ANNUAL_DIVIDEND_SYMBOLS, emptySet())?.toSet()
                ?: emptySet()
        )
    }
    
    fun savePreferences(preferences: DashboardPreferences) {
        prefs.edit().apply {
            putBoolean(KEY_SHOW_TOTAL_ASSETS, preferences.showTotalAssets)
            putBoolean(KEY_SHOW_NET_ASSETS, preferences.showNetAssets)
            putBoolean(KEY_SHOW_PROFIT, preferences.showProfit)
            putBoolean(KEY_SHOW_DISTRIBUTION, preferences.showDistribution)
            putBoolean(KEY_SHOW_ASSET_GROWTH, preferences.showAssetGrowth)
            putBoolean(KEY_SHOW_DIVIDEND_PROGRESS, preferences.showDividendProgress)
            putBoolean(KEY_SHOW_NEXT_PAYOUT, preferences.showNextPayout)
            putBoolean(KEY_SHOW_MONTHLY_CASH_FLOW, preferences.showMonthlyCashFlow)
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
            // 用 Float 存：SharedPreferences 没有 putDouble。分红金额到百万级时
            // Float 的 7 位有效数字仍够用，且这里只用于「先显示旧值、后台再校准」
            putFloat(KEY_ANNUAL_DIVIDEND_CACHE, preferences.annualDividendCache.toFloat())
            putStringSet(KEY_ANNUAL_DIVIDEND_SYMBOLS, preferences.annualDividendSymbols)
            apply()
        }
    }
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
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
    fun updatePreferences(mutate: (DashboardPreferences) -> DashboardPreferences): DashboardPreferences {
        val fresh = loadPreferences()
        val updated = mutate(fresh)
        savePreferences(updated)
        return updated
    }
}
