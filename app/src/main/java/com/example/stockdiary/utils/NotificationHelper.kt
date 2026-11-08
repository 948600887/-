package com.example.stockdiary.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.stockdiary.MainActivity
import com.example.stockdiary.utils.format
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

object NotificationHelper {
    // 不同通知类型使用不同 ID，避免互相覆盖
    private const val NOTIFICATION_ID_GENERAL = 1
    private const val NOTIFICATION_ID_STOCK_UPDATE = 2
    private const val NOTIFICATION_ID_DIVIDEND = 3
    private const val NOTIFICATION_ID_PRICE_SYNC = 4
    private const val NOTIFICATION_ID_PRICE_SYNC_FAILURE = 6

    // 通知渠道：按业务类型拆分，方便用户分别控制
    private const val CHANNEL_ID_PRICE = "price_updates"
    private const val CHANNEL_ID_DIVIDEND = "dividend_updates"
    private const val CHANNEL_ID_SYNC = "sync_status"
    private const val CHANNEL_ID_GENERAL = "general"

    // ---- 「每日查看提醒」已下线：以下标识只用于升级后清理残留，不要新增使用方 ----
    private const val LEGACY_REMINDER_RECEIVER_CLASS =
        "com.example.stockdiary.utils.NotificationHelper\$ReminderReceiver"
    private const val LEGACY_REMINDER_ALARM_REQUEST_CODE = 1001
    private const val LEGACY_REMINDER_NOTIFICATION_ID = 5
    private const val LEGACY_CHANNEL_ID_REMINDER = "daily_reminder"
    private const val LEGACY_PREFS_NAME = "notification_prefs"
    private const val LEGACY_KEY_REMINDER_ENABLED = "reminder_enabled"
    private const val LEGACY_KEY_REMINDER_HOUR = "reminder_hour"
    private const val LEGACY_KEY_REMINDER_MINUTE = "reminder_minute"

    private var notificationManager: NotificationManager? = null

    fun init(context: Context) {
        try {
            notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.let {
                createNotificationChannels()
            }
        } catch (e: CancellationException) {
            throw e
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
            } catch (e: CancellationException) {
                throw e
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
                NotificationChannel(CHANNEL_ID_GENERAL, "一般通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
            channels.forEach { channel ->
                channel.description = when (channel.id) {
                    CHANNEL_ID_PRICE -> "股票价格变动通知"
                    CHANNEL_ID_DIVIDEND -> "股息到账通知"
                    CHANNEL_ID_SYNC -> "数据同步结果通知"
                    else -> "金库应用的其他通知"
                }
            }
            it.createNotificationChannels(channels)
        }
    }

    /**
     * 清理已下线的「每日查看提醒」遗留状态（升级自愈）。
     *
     * 旧版本把闹钟注册在 [AlarmManager] 里，AlarmManager 的排期不会随应用升级自动消失：
     * 若不主动取消，已升级用户的旧闹钟仍会按旧时间触发 `ReminderReceiver`。
     * 但该 Receiver 已删除，触发后只会静默失败；更重要的是偏好键与渠道残留会让
     * 用户看到已不存在的「每日提醒」渠道。
     *
     * 这里在每次启动时幂等执行：按旧组件名匹配 PendingIntent（[Intent.filterEquals]
     * 不含 extras，组件 + requestCode 即可命中），取消后删除渠道、通知与旧偏好键。
     * 旧组件类已不存在并不影响匹配——PendingIntent 记录保存在系统进程里。
     */
    fun cleanupRemovedDailyReminder(context: Context) {
        try {
            // 1. 取消旧的每日提醒闹钟
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null) {
                // 只按旧身份查找、不存在时不创建（FLAG_NO_CREATE），避免清理动作自己排出一个新闹钟
                val legacyIntent = Intent().setClassName(context, LEGACY_REMINDER_RECEIVER_CLASS)
                val legacyPendingIntent = PendingIntent.getBroadcast(
                    context,
                    LEGACY_REMINDER_ALARM_REQUEST_CODE,
                    legacyIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (legacyPendingIntent != null) {
                    alarmManager.cancel(legacyPendingIntent)
                    legacyPendingIntent.cancel()
                    AppLogger.i("已取消遗留的每日提醒闹钟", "NotificationHelper")
                }
            }

            // 2. 删除旧通知渠道，并撤掉可能仍留在通知栏的旧通知
            getNotificationManager(context)?.let {
                it.deleteNotificationChannel(LEGACY_CHANNEL_ID_REMINDER)
                it.cancel(LEGACY_REMINDER_NOTIFICATION_ID)
            }

            // 3. 清除旧偏好键（该文件历史上只存这三个键，逐个删除以免误伤未来新增项）
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(LEGACY_KEY_REMINDER_ENABLED)
                .remove(LEGACY_KEY_REMINDER_HOUR)
                .remove(LEGACY_KEY_REMINDER_MINUTE)
                .apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 清理失败不影响应用运行；下次启动会再试一次
            AppLogger.e("清理每日提醒遗留状态失败: ${e.message}", e, "NotificationHelper")
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
        // Android 13+ 未授予 POST_NOTIFICATIONS 时 notify() 会抛 SecurityException。
        // 这里必须拦在前面：PriceSyncWorker 在同步成功/失败后都会发通知，
        // 一旦抛异常，doWork 会把它当成"同步失败"，导致
        // 「明明同步成功却报失败 + 无法自我续期 → 周期任务彻底停摆」。
        // 用户拒绝了通知权限不应该连累价格同步，所以直接静默跳过即可。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationPermissionHelper.isGranted(context)
        ) {
            AppLogger.w("未授予通知权限，跳过通知: $title", "NotificationHelper")
            return
        }

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

    /**
     * 一轮价格同步的结果通知。
     *
     * @param skippedCount 本轮跳过总数（[com.example.stockdiary.data.sync.SyncResult.skippedCount]）。
     *   旧文案完全没有这一项：0 成功 / 0 失败 / N 跳过时会写成「成功同步 0 个资产」，
     *   用户以为刷新生效、实际一个价格都没动。
     * @param skippedByReason 跳过原因分布，拼进文案后缀，让用户能判断「该不该管」
     *   （130 个行情源不可达 vs 1 个退市股，处置完全不同）。
     */
    fun showPriceSyncNotification(
        context: Context,
        successCount: Int,
        failedCount: Int,
        skippedCount: Int = 0,
        skippedByReason: Map<String, Int> = emptyMap()
    ) {
        // 标题/正文统一由纯函数生成（见 PriceSyncMessages）：通知只负责投递，
        // 如实性判定集中在可单测的一处，且与 Worker 日志共用同一份正文文案。
        val text = buildPriceSyncNotificationText(
            successCount,
            failedCount,
            skippedCount,
            skippedByReason
        )
        showNotification(
            context,
            text.title,
            text.message,
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
}
