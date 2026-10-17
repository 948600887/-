package com.example.stockdiary.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.stockdiary.utils.AppLogger

data class DashboardPreferences(
    val showTotalAssets: Boolean = false,
    val showNetAssets: Boolean = true,
    val showProfit: Boolean = true,
    val showDistribution: Boolean = true,
    val showBankDeposits: Boolean = true,
    val showStocks: Boolean = true,
    val showFunds: Boolean = true,
    val showCrypto: Boolean = true,
    val showLiabilities: Boolean = true,
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

enum class DashboardComponent(val key: String, val title: String) {
    TOTAL_ASSETS("total_assets", "总资产"),
    NET_ASSETS("net_assets", "净资产"),
    /**
     * 「收入预估」卡片。
     * key 沿用旧名 profit —— 它是 SharedPreferences 的键，改了老用户的显示开关会失效。
     * 显示名已经从「盈亏」改成「收入预估」，界面上不再出现盈亏字样。
     */
    PROFIT("profit", "收入预估"),
    DISTRIBUTION("distribution", "资产分布"),
    BANK_DEPOSITS("bank_deposits", "银行存款"),
    STOCKS("stocks", "股票"),
    FUNDS("funds", "基金"),
    CRYPTO("crypto", "虚拟货币"),
    LIABILITIES("liabilities", "负债")
}

class DashboardPreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "dashboard_prefs"
        
        private const val KEY_SHOW_TOTAL_ASSETS = "show_total_assets"
        private const val KEY_SHOW_NET_ASSETS = "show_net_assets"
        private const val KEY_SHOW_PROFIT = "show_profit"
        private const val KEY_SHOW_DISTRIBUTION = "show_distribution"
        private const val KEY_SHOW_BANK_DEPOSITS = "show_bank_deposits"
        private const val KEY_SHOW_STOCKS = "show_stocks"
        private const val KEY_SHOW_FUNDS = "show_funds"
        private const val KEY_SHOW_CRYPTO = "show_crypto"
        private const val KEY_SHOW_LIABILITIES = "show_liabilities"
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
            showBankDeposits = prefs.getBoolean(KEY_SHOW_BANK_DEPOSITS, true),
            showStocks = prefs.getBoolean(KEY_SHOW_STOCKS, true),
            showFunds = prefs.getBoolean(KEY_SHOW_FUNDS, true),
            showCrypto = prefs.getBoolean(KEY_SHOW_CRYPTO, true),
            showLiabilities = prefs.getBoolean(KEY_SHOW_LIABILITIES, true),
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
            putBoolean(KEY_SHOW_BANK_DEPOSITS, preferences.showBankDeposits)
            putBoolean(KEY_SHOW_STOCKS, preferences.showStocks)
            putBoolean(KEY_SHOW_FUNDS, preferences.showFunds)
            putBoolean(KEY_SHOW_CRYPTO, preferences.showCrypto)
            putBoolean(KEY_SHOW_LIABILITIES, preferences.showLiabilities)
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
    
    fun updateComponentVisibility(component: DashboardComponent, visible: Boolean) {
        val key = when (component) {
            DashboardComponent.TOTAL_ASSETS -> KEY_SHOW_TOTAL_ASSETS
            DashboardComponent.NET_ASSETS -> KEY_SHOW_NET_ASSETS
            DashboardComponent.PROFIT -> KEY_SHOW_PROFIT
            DashboardComponent.DISTRIBUTION -> KEY_SHOW_DISTRIBUTION
            DashboardComponent.BANK_DEPOSITS -> KEY_SHOW_BANK_DEPOSITS
            DashboardComponent.STOCKS -> KEY_SHOW_STOCKS
            DashboardComponent.FUNDS -> KEY_SHOW_FUNDS
            DashboardComponent.CRYPTO -> KEY_SHOW_CRYPTO
            DashboardComponent.LIABILITIES -> KEY_SHOW_LIABILITIES
        }
        prefs.edit().putBoolean(key, visible).apply()
    }
    
    fun isComponentVisible(component: DashboardComponent): Boolean {
        val key = when (component) {
            DashboardComponent.TOTAL_ASSETS -> KEY_SHOW_TOTAL_ASSETS
            DashboardComponent.NET_ASSETS -> KEY_SHOW_NET_ASSETS
            DashboardComponent.PROFIT -> KEY_SHOW_PROFIT
            DashboardComponent.DISTRIBUTION -> KEY_SHOW_DISTRIBUTION
            DashboardComponent.BANK_DEPOSITS -> KEY_SHOW_BANK_DEPOSITS
            DashboardComponent.STOCKS -> KEY_SHOW_STOCKS
            DashboardComponent.FUNDS -> KEY_SHOW_FUNDS
            DashboardComponent.CRYPTO -> KEY_SHOW_CRYPTO
            DashboardComponent.LIABILITIES -> KEY_SHOW_LIABILITIES
        }
        return prefs.getBoolean(key, component != DashboardComponent.TOTAL_ASSETS)
    }
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
    
    fun getVisibleComponents(): List<DashboardComponent> {
        return DashboardComponent.entries.filter { isComponentVisible(it) }
    }
}
