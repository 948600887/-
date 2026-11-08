package com.example.stockdiary.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「哪些日期的快照是手动修正过的」轻量持久层。
 *
 * ## 为什么不用 Room 加字段
 *
 * `asset_snapshots` 加一列会把 DB 版本从 18 提到 19，而 v19 迁移没有真机可验证
 * （`tools/check_invariants.py` 的 A7 锁 Room v18）。这个标记只影响列表与趋势图上的
 * 一句「手动修正」，丢了也不会导致金额错误，用 SharedPreferences 存一组日期即可，
 * 零迁移、旧数据默认「无人手动修正」。
 *
 * ## 清理时机（与写入路径严格对应）
 *
 * - 编辑保存成功 → [mark]；
 * - 用当前持仓重存 / 自动快照覆盖同日 → [clear]（机器重算，旧修正不再成立）；
 * - 删除某日快照、清空全部数据 → [clear] / [clearAll]。
 *
 * SharedPreferences 与 Room 不共享事务：极端情况下 DB 写成功而标记写失败，只会少一个
 * 标签，不会出现金额与标记互相矛盾的持久状态。
 */
class SnapshotManualAdjustmentStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _dates = MutableStateFlow(loadDates())
    val dates: StateFlow<Set<String>> = _dates.asStateFlow()

    @Synchronized
    fun mark(date: String) {
        update(_dates.value + date)
    }

    @Synchronized
    fun clear(date: String) {
        if (date !in _dates.value) return
        update(_dates.value - date)
    }

    @Synchronized
    fun clearAll() {
        update(emptySet())
    }

    private fun update(next: Set<String>) {
        if (next == _dates.value) return
        val snapshot = next.toSet()
        _dates.value = snapshot
        // apply()：标记只是展示状态，不阻塞编辑保存的返回
        prefs.edit().putStringSet(KEY_DATES, snapshot).apply()
    }

    private fun loadDates(): Set<String> =
        prefs.getStringSet(KEY_DATES, emptySet())?.toSet() ?: emptySet()

    companion object {
        private const val PREFS_NAME = "snapshot_manual_adjustment_prefs"
        private const val KEY_DATES = "manual_adjusted_dates"

        @Volatile
        private var instance: SnapshotManualAdjustmentStore? = null

        fun getInstance(context: Context): SnapshotManualAdjustmentStore =
            instance ?: synchronized(this) {
                instance ?: SnapshotManualAdjustmentStore(context.applicationContext)
                    .also { instance = it }
            }
    }
}
