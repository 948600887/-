package com.example.stockdiary.data.sync

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.example.stockdiary.utils.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 周期任务的「为什么没跑」诊断。
 *
 * 背景：真机上出现过 Worker 只在 App 回到前台时才执行的现象，而 app.log 里看不到
 * 任何系统侧线索。这里把能区分「系统压住」与「我方排期算错」的状态一次性打进日志：
 *
 * - 省电优化是否忽略、后台是否受限、Doze/省电模式、屏幕是否交互：判断系统限制；
 * - WorkInfo 的当前状态 + `nextScheduleTimeMillis`：判断排期是否真的到期。
 *
 * 只在启动调度时与 Worker 真正执行时各打一条，不轮询、不增加后台唤醒。
 * 仅用于诊断，不改变调度参数与约束。
 */
internal object WorkDiagnostics {

    /** 系统电源/后台限制快照 */
    fun logSystemState(context: Context, stage: String) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val ignoringOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activityManager?.isBackgroundRestricted ?: false
        } else {
            false
        }
        AppLogger.i(
            "[分红补齐诊断][$stage] 忽略省电优化=$ignoringOptimizations" +
                " 后台受限=$backgroundRestricted" +
                " Doze空闲=${powerManager?.isDeviceIdleMode ?: false}" +
                " 省电模式=${powerManager?.isPowerSaveMode ?: false}" +
                " 屏幕交互=${powerManager?.isInteractive ?: false}",
            TAG
        )
    }

    /** 该唯一任务的当前状态与下次计划执行时间 */
    suspend fun logWorkInfo(context: Context, workName: String, stage: String) {
        try {
            val info = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(workName)
                .await()
                .firstOrNull()
            if (info == null) {
                AppLogger.w("[分红补齐诊断][$stage] 未找到 WorkInfo（任务未入队？）", TAG)
                return
            }
            AppLogger.i(
                "[分红补齐诊断][$stage] WorkInfo 状态=${info.state}" +
                    " 尝试次数=${info.runAttemptCount}" +
                    " 下次计划=${formatNextSchedule(info)}",
                TAG
            )
        } catch (e: Exception) {
            AppLogger.w("[分红补齐诊断][$stage] 读取 WorkInfo 失败: ${e.message}", TAG)
        }
    }

    private fun formatNextSchedule(info: WorkInfo): String {
        val next = info.nextScheduleTimeMillis
        if (next <= 0L) return "无"
        val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(next))
        val delayedMinutes = (next - System.currentTimeMillis()) / 60_000L
        return "$formatted（距现在 ${delayedMinutes} 分钟）"
    }

    private const val TAG = "WorkDiagnostics"
}
