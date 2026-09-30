package com.example.stockdiary.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.stockdiary.MainActivity
import com.example.stockdiary.utils.format
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

object NotificationHelper {
    private const val ALARM_REQUEST_CODE = 1001
    private const val PREFS_NAME = "notification_prefs"
    private const val KEY_REMINDER_ENABLED = "reminder_enabled"

    // 不同通知类型使用不同 ID，避免互相覆盖
    private const val NOTIFICATION_ID_GENERAL = 1
    private const val NOTIFICATION_ID_STOCK_UPDATE = 2
    private const val NOTIFICATION_ID_DIVIDEND = 3
    private const val NOTIFICATION_ID_PRICE_SYNC = 4
    private const val NOTIFICATION_ID_REMINDER = 5
    private const val NOTIFICATION_ID_PRICE_SYNC_FAILURE = 6

    // 通知渠道：按业务类型拆分，方便用户分别控制
    private const val CHANNEL_ID_PRICE = "price_updates"
    private const val CHANNEL_ID_DIVIDEND = "dividend_updates"
    private const val CHANNEL_ID_SYNC = "sync_status"
    private const val CHANNEL_ID_REMINDER = "daily_reminder"
    private const val CHANNEL_ID_GENERAL = "general"

    private var notificationManager: NotificationManager? = null

    fun init(context: Context) {
        try {
            notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.let {
                createNotificationChannels()
            }
        } catch (e: Exception) {
            AppLogger.e("初始化通知管理器失败: ${e.message}", e, "NotificationHelper")
            // 通知管理器初始化失败，不影响应用运行
        }
    }

    private fun getNotificationManager(context: Context): NotificationManager? {
        if (notificationManager == null) {
            try {
                notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.let {
                    createNotificationChannels()
                }
            } catch (e: Exception) {
                AppLogger.e("获取通知管理器失败: ${e.message}", e, "NotificationHelper")
                // 通知管理器初始化失败，返回null
            }
        }
        return notificationManager
    }

    fun createNotificationChannels() {
        notificationManager?.let {
            val channels = listOf(
                NotificationChannel(CHANNEL_ID_PRICE, "价格更新", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ID_DIVIDEND, "股息到账", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ID_SYNC, "同步状态", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_ID_REMINDER, "每日提醒", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ID_GENERAL, "一般通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
            channels.forEach { channel ->
                channel.description = when (channel.id) {
                    CHANNEL_ID_PRICE -> "股票价格变动通知"
                    CHANNEL_ID_DIVIDEND -> "股息到账通知"
                    CHANNEL_ID_SYNC -> "数据同步结果通知"
                    CHANNEL_ID_REMINDER -> "每日资产查看提醒"
                    else -> "金库应用的其他通知"
                }
            }
            it.createNotificationChannels(channels)
        }
    }

    fun showNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = NOTIFICATION_ID_GENERAL,
        requestCode: Int = notificationId,
        channelId: String = CHANNEL_ID_GENERAL
    ) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getNotificationManager(context)?.notify(notificationId, notification)
    }

    fun showStockUpdateNotification(context: Context, stockName: String, price: Double, change: Double) {
        val title = "股票价格更新"
        val message = "$stockName: ¥${price.format(2)} ${if (change > 0) "↑" else "↓"} ${abs(change).format(2)}%"
        showNotification(
            context,
            title,
            message,
            notificationId = NOTIFICATION_ID_STOCK_UPDATE,
            requestCode = NOTIFICATION_ID_STOCK_UPDATE,
            channelId = CHANNEL_ID_PRICE
        )
    }

    fun showDividendNotification(context: Context, stockName: String, amount: Double) {
        val title = "股息到账"
        val message = "$stockName 发放股息: ¥${amount.format(2)}"
        showNotification(
            context,
            title,
            message,
            notificationId = NOTIFICATION_ID_DIVIDEND,
            requestCode = NOTIFICATION_ID_DIVIDEND,
            channelId = CHANNEL_ID_DIVIDEND
        )
    }

    fun showPriceSyncNotification(context: Context, successCount: Int, failedCount: Int) {
        val title = "价格同步完成"
        val message = when {
            failedCount == 0 -> "成功同步 $successCount 个资产"
            successCount == 0 -> "同步失败，请检查网络"
            else -> "成功 $successCount 个，失败 $failedCount 个"
        }
        showNotification(
            context,
            title,
            message,
            notificationId = NOTIFICATION_ID_PRICE_SYNC,
            requestCode = NOTIFICATION_ID_PRICE_SYNC,
            channelId = CHANNEL_ID_SYNC
        )
    }

    fun showPriceSyncFailureNotification(context: Context, failureCount: Int) {
        val title = "价格同步多次失败"
        val message = "已连续 $failureCount 次自动同步失败，请检查网络或数据源状态"
        showNotification(
            context,
            title,
            message,
            notificationId = NOTIFICATION_ID_PRICE_SYNC_FAILURE,
            requestCode = NOTIFICATION_ID_PRICE_SYNC_FAILURE,
            channelId = CHANNEL_ID_SYNC
        )
    }

    /**
     * 检查当前应用是否可以设置精确闹钟。
     * Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限且用户未在系统设置中关闭。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    /**
     * 引导用户前往系统设置开启精确闹钟权限。
     * SCHEDULE_EXACT_ALARM 为特殊权限，无法通过普通请求弹窗获取。
     */
    fun openAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e("打开闹钟设置失败: ${e.message}", e, "NotificationHelper")
            }
        }
    }

    // 设置每日提醒
    fun setDailyReminder(context: Context, hour: Int, minute: Int): Boolean {
        return try {
            if (!canScheduleExactAlarms(context)) {
                AppLogger.w("无精确闹钟权限，无法设置每日提醒", "NotificationHelper")
                return false
            }
            setReminderEnabled(context, true)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, ReminderReceiver::class.java).apply {
                    putExtra("reminder_hour", hour)
                    putExtra("reminder_minute", minute)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 设置提醒时间
                val now = LocalDateTime.now()
                var scheduledTime = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

                // 如果设置的时间已经过去，则设置为明天的同一时间
                if (scheduledTime.isBefore(now)) {
                    scheduledTime = scheduledTime.plusDays(1)
                }

                val triggerMillis = scheduledTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                // 已通过 canScheduleExactAlarms() 检查 SCHEDULE_EXACT_ALARM 权限
                @Suppress("MissingPermission")
                val alarmInfo = AlarmManager.AlarmClockInfo(triggerMillis, pendingIntent)
                @Suppress("MissingPermission")
                alarmManager.setAlarmClock(alarmInfo, pendingIntent)
            }
            true
        } catch (e: Exception) {
            AppLogger.e("设置每日提醒失败: ${e.message}", e, "NotificationHelper")
            // 提醒设置失败，不影响应用运行
            false
        }
    }

    // 取消提醒
    fun cancelReminder(context: Context) {
        try {
            setReminderEnabled(context, false)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                val intent = Intent(context, ReminderReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
            }
        } catch (e: Exception) {
            AppLogger.e("取消提醒失败: ${e.message}", e, "NotificationHelper")
            // 取消提醒失败，不影响应用运行
        }
    }

    fun isReminderEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMINDER_ENABLED, false)
    }
    
    private fun setReminderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMINDER_ENABLED, enabled)
            .apply()
    }

    // 提醒接收器
    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                // 读取保存的提醒时间
                val hour = intent.getIntExtra("reminder_hour", 9)
                val minute = intent.getIntExtra("reminder_minute", 0)
                
                // 初始化通知管理器
                val localNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (localNotificationManager != null) {
                    // 创建通知通道（minSdk 26，无需版本判断）
                    val channel = NotificationChannel(
                        CHANNEL_ID_REMINDER,
                        "每日提醒",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "每日资产查看提醒"
                    }
                    localNotificationManager.createNotificationChannel(channel)

                    // 显示每日提醒
                    val notificationIntent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        NOTIFICATION_ID_REMINDER,
                        notificationIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val notification = NotificationCompat.Builder(context, CHANNEL_ID_REMINDER)
                        .setContentTitle("金库提醒")
                        .setContentText("记得查看今日股票行情和资产状况")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()

                    localNotificationManager.notify(NOTIFICATION_ID_REMINDER, notification)
                }

                // 设置下一天的提醒（保留原始时间），Android 12+ 需先检查权限
                if (canScheduleExactAlarms(context)) {
                    val tomorrowScheduledTime = LocalDateTime.now().plusDays(1)
                        .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    val tomorrowMillis = tomorrowScheduledTime
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    if (alarmManager != null) {
                        val nextIntent = Intent(context, ReminderReceiver::class.java).apply {
                            putExtra("reminder_hour", hour)
                            putExtra("reminder_minute", minute)
                        }
                        val reminderPendingIntent = PendingIntent.getBroadcast(
                            context,
                            ALARM_REQUEST_CODE,
                            nextIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        // 已通过 canScheduleExactAlarms() 检查 SCHEDULE_EXACT_ALARM 权限
                        @Suppress("MissingPermission")
                        val alarmInfo = AlarmManager.AlarmClockInfo(tomorrowMillis, reminderPendingIntent)
                        @Suppress("MissingPermission")
                        alarmManager.setAlarmClock(alarmInfo, reminderPendingIntent)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("处理提醒失败: ${e.message}", e, "NotificationHelper")
                // 处理提醒失败，不影响应用运行
            }
        }
    }
}