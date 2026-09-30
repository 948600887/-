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
            else PermissionState.NotRequested
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionState = if (isGranted) {
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

    when (permissionState) {
        PermissionState.Granted -> content()
        PermissionState.NotRequested -> {
            RequestNotificationPermissionDialog(
                title = "开启通知",
                message = "及时获取 remind、快照完成等提醒，需要通知权限。",
                confirmText = "去开启",
                onConfirm = {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onDismiss = {
                    permissionState = PermissionState.Granted
                }
            )
        }
        PermissionState.ShowRationale -> {
            RequestNotificationPermissionDialog(
                title = "需要通知权限",
                message = "关闭通知将无法收到重要提醒，是否重新申请？",
                confirmText = "重新申请",
                onConfirm = {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onDismiss = {
                    permissionState = PermissionState.Granted
                }
            )
        }
        PermissionState.Denied -> content()
        PermissionState.PermanentlyDenied -> {
            RequestNotificationPermissionDialog(
                title = "通知权限被永久拒绝",
                message = "请前往应用设置手动开启通知权限，否则无法收到提醒。",
                confirmText = "去设置",
                onConfirm = {
                    NotificationPermissionHelper.openSettings(activity)
                    permissionState = PermissionState.Granted
                },
                onDismiss = {
                    permissionState = PermissionState.Granted
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
