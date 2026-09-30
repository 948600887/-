package com.example.stockdiary.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启接收器。
 *
 * AlarmManager 的闹钟在设备重启后会被系统清空，每日提醒如果只依赖
 * App 打开时设置，重启手机后提醒就永久丢失且用户无感知。
 * 这里在开机完成后按已保存的开关与时间重新排一次闹钟。
 * （WorkManager 任务自身有持久化，无需在此重排。）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            if (NotificationHelper.isReminderEnabled(context)) {
                val (hour, minute) = NotificationHelper.getReminderTime(context)
                val ok = NotificationHelper.setDailyReminder(context, hour, minute)
                AppLogger.i("开机重排每日提醒: ${hour}:${"%02d".format(minute)}, 结果=$ok", "BootReceiver")
            }
        } catch (e: Exception) {
            AppLogger.e("开机重排每日提醒失败: ${e.message}", e, "BootReceiver")
        }
    }
}
