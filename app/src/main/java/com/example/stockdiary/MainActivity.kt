package com.example.stockdiary

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.theme.GoldVaultTheme
import com.example.stockdiary.ui.navigation.GoldVaultNavHost
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.GlobalExceptionHandler
import com.example.stockdiary.utils.NotificationPermissionGate

class MainActivity : ComponentActivity() {

    private var isInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initializeApp()

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

                    if (showRecoveryDialog && recoveryMessage != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showRecoveryDialog = false },
                            title = { androidx.compose.material3.Text("应用恢复") },
                            text = {
                                androidx.compose.material3.Text(
                                    "应用之前遇到了问题并已恢复。您可以继续使用或重启应用以获得更好的体验。"
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = { showRecoveryDialog = false }) {
                                    androidx.compose.material3.Text("继续使用")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    showRecoveryDialog = false
                                    recreate()
                                }) {
                                    androidx.compose.material3.Text("重启应用")
                                }
                            }
                        )
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    private fun initializeApp() {
        if (isInitialized) return
        isInitialized = true

        val app = application as StockDiaryApplication
        val crashRecoveryManager = app.container.crashRecoveryManager

        GlobalExceptionHandler.initialize(application) {
            crashRecoveryManager.recordCrash("应用异常退出")
        }

        AppLogger.init(this, debug = BuildConfig.DEBUG)
        AppLogger.i("应用启动 - MainActivity", "AppInit")
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
