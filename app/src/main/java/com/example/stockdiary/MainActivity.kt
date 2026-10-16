package com.example.stockdiary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.navigation.GoldVaultNavHost
import com.example.stockdiary.ui.theme.GoldVaultTheme
import com.example.stockdiary.utils.GlobalExceptionHandler
import com.example.stockdiary.utils.NotificationPermissionGate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化已全部收敛到 StockDiaryApplication.onCreate（AppLogger / 崩溃处理 / DI 容器）。
        // 原先这里还有一份 initializeApp()：重复 init GlobalExceptionHandler + AppLogger。
        // 因 GlobalExceptionHandler.initialize 有幂等守卫、Application 先跑，那次调用是空操作——
        // 已删除。深色/配置变化改由 Compose 经 LocalConfiguration 重组平滑处理，
        // 不再 onConfigurationChanged→recreate()（见 manifest 的 configChanges="uiMode"）。

        setContent {
            val container = StockDiaryApplication.instance.container
            GoldVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showRecoveryDialog by remember { mutableStateOf(false) }
                    var recoveryMessage by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        val recoveryManager = container.crashRecoveryManager
                        val crashState = recoveryManager.getCrashState()

                        if (crashState.hasCrash && System.currentTimeMillis() - crashState.lastCrashTime < 60000) {
                            recoveryMessage = crashState.lastCrashInfo
                            showRecoveryDialog = true
                            recoveryManager.clearCrashState()
                        }
                    }

                    // 局部 val 捕获以便智能转型：recoveryMessage 是 by-remember 委托属性，
                    // 直接判空无法 smart-cast
                    val recoveryMsg = recoveryMessage
                    if (showRecoveryDialog && recoveryMsg != null) {
                        CrashRecoveryDialog(message = recoveryMsg, onDismiss = { showRecoveryDialog = false })
                    }

                    CompositionLocalProvider(LocalAppContainer provides container) {
                        NotificationPermissionGate(activity = this) {
                            GoldVaultNavHost()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GlobalExceptionHandler.logSafe("Lifecycle", "MainActivity onResume")
    }

    override fun onPause() {
        super.onPause()
        GlobalExceptionHandler.logSafe("Lifecycle", "MainActivity onPause")
    }
}

/**
 * 上次异常退出的恢复弹窗：展示堆栈、支持复制。
 *
 * 从 MainActivity.setContent 内联块抽出——原来整段 AlertDialog 连同一堆全限定名
 * （androidx.compose.material3.Text 等）堆在 Activity 里，既难读又多噪音。
 */
@Composable
private fun CrashRecoveryDialog(
    message: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用上次异常退出") },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(message))
                    onDismiss()
                }
            ) {
                Text("复制错误信息")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
