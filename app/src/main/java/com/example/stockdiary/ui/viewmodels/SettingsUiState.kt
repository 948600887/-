package com.example.stockdiary.ui.viewmodels

import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.DataSourceHealth

/**
 * 设置页状态机（原 [SettingsViewModel] 的嵌套 UiState，抽出为独立顶层类型）。
 *
 * 原为「SettingsUiState(10 字段) + 9 个附加流」混用：数据类列表/导出/删除/体检走 SettingsUiState，
 * 而价格同步与每日提醒的 8 个快照字段各自为政（开关 ×4、上次同步时间、提醒开关、
 * 时/分、提醒错误），写入点散落在 setReminderEnabled / setReminderTime /
 * setAutoPriceSyncEnabled / refreshPriceSyncState 等处；UI 同层 collect 十条流，
 * 一次 refreshPriceSyncState 要连发八次、整页重组八遍。
 *
 * 合流判断：
 * - 8 个同步/提醒快照字段并入本 SettingsUiState——它们同属「持久化设置的内存镜像」，
 *   由 [SettingsViewModel.refreshPriceSyncState] 整组刷新、由设置页同一个 remember 块整组消费，
 *   合起来后一次刷新只发射一次（原来八次）；
 * - [SettingsViewModel.dashboardPreferences] **刻意保留独立流**：它是跨页面共享的整块偏好
 *   （日历写月度生活费、仪表盘/持仓写分红缓存），与 Dashboard/Portfolio 两个 VM
 *   保持同样的「preferences 独立流」先例；且它的写入路径（config sheet 整块提交）
 *   与 SettingsUiState 其余字段（一次性动作驱动）语义不同，混在一起只会让「谁改了什么」更难读。
 */
data class SettingsUiState(
    /**
     * 只保留计数，不保留整表。
     *
     * 设置页真正只需要「清除数据」确认框里的资产条数；导出时再按需查库。
     * 原先把 assets/snapshots/transactions 三张全量表常驻在这里，
     * 老用户（几百天快照、上千条交易）会白占内存、且每次发信整页重组。
     */
    val assetCount: Int = 0,
    val snapshotCount: Int = 0,
    val transactionCount: Int = 0,
    /** 分红条数：导出对话框用它决定「分红明细」是否可点（空库不该导出一个空文件） */
    val dividendCount: Int = 0,
    val isExporting: Boolean = false,
    val isDeleting: Boolean = false,
    val isCheckingHealth: Boolean = false,
    val dataSourceHealth: List<DataSourceHealth>? = null,
    /** 体检结果摘要（列表行 subtitle 用）；未体检时为 null */
    val dataSourceHealthSummary: String? = null,
    /** 汇率快照：放大后的可观察状态，刷新后界面才会重组（原先直接读单例，刷新不反映） */
    val usdRate: Double = 0.0,
    val usdRateUpdatedAt: Long = 0L,
    val isRefreshingRate: Boolean = false,
    val exportResult: Result<String>? = null,
    val deleteResult: Result<Unit>? = null,
    val errorMessage: String? = null,
    // ---- 价格同步快照：与 PriceSyncPreferencesManager 磁盘值一一对应 ----
    val autoPriceSyncEnabled: Boolean = false,
    val lastPriceSyncTime: Long = 0L,
    /** 盘中是否也自动刷股票/场内基金。关=仅盘后刷(默认) */
    val stockIntradaySyncEnabled: Boolean = false,
    /** 是否自动刷虚拟货币。关=只手动刷(默认) */
    val cryptoAutoSyncEnabled: Boolean = false,
    // ---- 每日提醒快照：与 NotificationHelper 内部状态一一对应 ----
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 8,
    val reminderMinute: Int = 0,
    /** 提醒是否真的排上了（无精确闹钟权限时会失败，需要给用户可见反馈） */
    val reminderError: String? = null
)
