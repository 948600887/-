package com.example.stockdiary.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * 通知权限管理器
 * 三级流程：首次请求前解释 -> 请求权限 -> 永久拒绝后引导到设置页
 */
object NotificationPermissionHelper {

    fun shouldShowRationale(activity: androidx.activity.ComponentActivity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            false
        }
    }

    fun isGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    private const val PREFS_NAME = "notification_permission"
    private const val KEY_PROMPT_DISMISSED = "prompt_dismissed"

    /**
     * 用户是否已经对通知权限弹窗点过「暂不」。
     *
     * 从前弹窗是**阻断式**的：未授权时只渲染弹窗、不渲染 content()，用户面对一个
     * 背后没有首屏的空弹窗；而 dismissal 只存在 `remember` 里，进程一死就忘，
     * 下次冷启动又弹一遍。Android 13 的权限最佳实践是不在启动时拦路 ——
     * 通知是「用到才需要」的能力，用户没表态就先让他正常用 App。
     */
    fun isPromptDismissed(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PROMPT_DISMISSED, false)
        }.getOrDefault(false)

    /** 记住「暂不」，之后不再主动弹（用户可从设置页的通知开关处再开） */
    fun markPromptDismissed(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PROMPT_DISMISSED, true)
                .apply()
        }
    }

    /** 授权成功后清掉「暂不」标记，避免用户后来在系统设置里开了权限却仍被判定为已拒绝 */
    fun clearPromptDismissed(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PROMPT_DISMISSED)
                .apply()
        }
    }
}

@Composable
fun NotificationPermissionGate(
    activity: androidx.activity.ComponentActivity,
    content: @Composable () -> Unit
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        content()
        return
    }

    var permissionState by remember {
        mutableStateOf(
            if (NotificationPermissionHelper.isGranted(activity)) PermissionState.Granted
            // 上次已经点过「暂不」：不再拦着用户用 App，首屏直接给出去
            else if (NotificationPermissionHelper.isPromptDismissed(activity)) PermissionState.Denied
            else PermissionState.NotRequested
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionState = if (isGranted) {
            // 用户改主意开了权限：清掉「暂不」标记，别让它一直压着
            NotificationPermissionHelper.clearPromptDismissed(activity)
            PermissionState.Granted
        } else if (!NotificationPermissionHelper.shouldShowRationale(activity)) {
            PermissionState.PermanentlyDenied
        } else {
            PermissionState.Denied
        }
    }

    LaunchedEffect(Unit) {
        if (permissionState == PermissionState.NotRequested &&
            NotificationPermissionHelper.shouldShowRationale(activity)
        ) {
            permissionState = PermissionState.ShowRationale
        }
    }

    // 弹窗一律**浮在 content() 之上**而不是取代它：从前未授权时只渲染弹窗，
    // 用户面对一个背后空空如也的对话框（首屏被挡在后面根本没渲染），
    // 而且 Android 13 的最佳实践是「用到通知时才请求」，不该在启动拦路
    when (permissionState) {
        PermissionState.Granted -> content()
        PermissionState.NotRequested -> {
            content()
            RequestNotificationPermissionDialog(
                title = "开启通知",
                message = "及时获取 remind、快照完成等提醒，需要通知权限。",
                confirmText = "去开启",
                onConfirm = {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onDismiss = {
                    NotificationPermissionHelper.markPromptDismissed(activity)
                    permissionState = PermissionState.Denied
                }
            )
        }
        PermissionState.ShowRationale -> {
            content()
            RequestNotificationPermissionDialog(
                title = "需要通知权限",
                message = "关闭通知将无法收到重要提醒，是否重新申请？",
                confirmText = "重新申请",
                onConfirm = {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onDismiss = {
                    NotificationPermissionHelper.markPromptDismissed(activity)
                    permissionState = PermissionState.Denied
                }
            )
        }
        PermissionState.Denied -> content()
        PermissionState.PermanentlyDenied -> {
            content()
            RequestNotificationPermissionDialog(
                title = "通知权限被永久拒绝",
                message = "请前往应用设置手动开启通知权限，否则无法收到提醒。",
                confirmText = "去设置",
                onConfirm = {
                    NotificationPermissionHelper.openSettings(activity)
                    permissionState = PermissionState.Denied
                },
                onDismiss = {
                    NotificationPermissionHelper.markPromptDismissed(activity)
                    permissionState = PermissionState.Denied
                }
            )
        }
    }
}

private enum class PermissionState {
    Granted,
    NotRequested,
    ShowRationale,
    Denied,
    PermanentlyDenied
}

@Composable
private fun RequestNotificationPermissionDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("暂不")
            }
        }
    )
}
