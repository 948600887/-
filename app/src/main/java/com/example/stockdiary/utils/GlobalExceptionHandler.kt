package com.example.stockdiary.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

object GlobalExceptionHandler {
    
    private var isInitialized = false
    private var defaultUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    private var onCrashListener: (() -> Unit)? = null
    
    private val crashLogs = ConcurrentHashMap<String, String>()
    private const val MAX_CRASH_LOGS = 50
    
    fun initialize(application: Application, onCrash: (() -> Unit)? = null) {
        if (isInitialized) return
        
        appContext = application.applicationContext
        onCrashListener = onCrash
        
        defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }
        
        isInitialized = true
        AppLogger.i("GlobalExceptionHandler 已初始化", "CrashHandler")
    }
    
    private fun handleUncaughtException(thread: Thread?, throwable: Throwable?) {
        if (throwable == null) {
            invokeDefaultHandler(thread, null)
            return
        }

        val crashInfo = buildCrashInfo(thread, throwable)
        saveCrashLog(crashInfo)

        // 把完整堆栈写入崩溃恢复状态，下次启动的恢复弹窗直接展示（listener 只记录一句概述，定位不了问题）
        try {
            CrashRecoveryManager.getInstance().recordCrash(crashInfo)
        } catch (e: Exception) {
            AppLogger.e("记录崩溃详情失败", e, "CrashHandler")
        }

        AppLogger.e("未捕获异常: ${throwable.message}", throwable, "CrashHandler")

        try {
            showCrashNotification(throwable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("记录崩溃通知失败", e, "CrashHandler")
        }

        // 交给系统默认处理器，让应用按正常流程终止，不掩盖崩溃信号
        invokeDefaultHandler(thread, throwable)
    }
    
    private fun buildCrashInfo(thread: Thread?, throwable: Throwable): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        
        return buildString {
            appendLine("=== Crash Report ===")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread?.name ?: "Unknown"}")
            appendLine("App Version: ${getAppVersion()}")
            appendLine("Android Version: ${Build.VERSION.RELEASE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Exception: ${throwable::class.java.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("Stack Trace:")
            appendLine(writer.toString())
            
            throwable.cause?.let { cause ->
                appendLine()
                appendLine("Caused by: ${cause::class.java.name}")
                appendLine("Message: ${cause.message}")
                val causeWriter = StringWriter()
                cause.printStackTrace(PrintWriter(causeWriter))
                appendLine(causeWriter.toString())
            }
        }
    }
    
    private fun saveCrashLog(crashInfo: String) {
        try {
            val timestamp = System.currentTimeMillis().toFileTimestamp()
            val crashId = "crash_$timestamp"
            
            crashLogs[crashId] = crashInfo
            
            if (crashLogs.size > MAX_CRASH_LOGS) {
                // key 格式为 "crash_<timestamp>"，按字典序即按时间排序，删除最旧记录
                crashLogs.keys().toList().minOrNull()?.let { oldestKey ->
                    crashLogs.remove(oldestKey)
                }
            }
            
            val crashFile = File(appContext?.getExternalFilesDir(null), "crash_reports")
            if (!crashFile.exists()) crashFile.mkdirs()
            
            val logFile = File(crashFile, "$crashId.log")
            logFile.writeText(crashInfo)

            // 磁盘目录不受 MAX_CRASH_LOGS 约束：只删内存里的旧记录而文件一直留着，
            // 反复崩溃会把外部存储慢慢写满，且用户毫无感知。
            pruneCrashFiles(crashFile)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("保存崩溃日志失败", e, "CrashHandler")
        }
    }

    /** 只保留最近 [MAX_CRASH_LOGS] 份崩溃日志，其余删除 */
    private fun pruneCrashFiles(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_CRASH_LOGS) return
        // 文件名是 "crash_<timestamp>.log"，字典序即时间序
        files.sortedByDescending { it.name }
            .drop(MAX_CRASH_LOGS)
            .forEach { file ->
                runCatching { file.delete() }
                    .onFailure { AppLogger.w("删除旧崩溃日志失败: ${file.name}", "CrashHandler") }
            }
    }
    
    private fun showCrashNotification(throwable: Throwable) {
        val message = when {
            throwable is OutOfMemoryError -> "内存不足，应用即将关闭"
            throwable is StackOverflowError -> "程序执行超时，应用即将关闭"
            throwable is NullPointerException -> "应用遇到错误，正在重启..."
            throwable.message?.contains("Network", true) == true -> "网络连接失败，请检查网络"
            else -> "应用遇到问题，正在重启..."
        }
        
        AppLogger.w(message, "CrashHandler")
    }
    
    private fun invokeDefaultHandler(thread: Thread?, throwable: Throwable?) {
        try {
            onCrashListener?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Crash listener failed", e, "CrashHandler")
        }

        if (thread != null && throwable != null) {
            defaultUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
    }
    
    private fun getAppVersion(): String {
        return try {
            val context = appContext ?: return "Unknown"
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            versionCode.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    fun getCrashLogs(): List<Pair<String, String>> {
        return crashLogs.toList().sortedByDescending { it.first }
    }
    
    fun clearCrashLogs() {
        crashLogs.clear()
        try {
            val crashDir = File(appContext?.getExternalFilesDir(null), "crash_reports")
            crashDir.listFiles()?.forEach { it.delete() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("清除崩溃日志失败", e, "CrashHandler")
        }
    }
    
    fun logSafe(tag: String, message: String) {
        try {
            AppLogger.d(message, tag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默处理，避免日志写入失败
        }
    }
}

data class CrashRecoveryState(
    val hasCrash: Boolean = false,
    val lastCrashTime: Long = 0,
    val crashCount: Int = 0,
    val lastCrashInfo: String? = null
)

class CrashRecoveryManager private constructor() {
    
    private var prefs = appContext?.getSharedPreferences("crash_recovery", Context.MODE_PRIVATE)
    private var crashState = CrashRecoveryState()
    
    companion object {
        @Volatile
        private var instance: CrashRecoveryManager? = null
        
        fun getInstance(): CrashRecoveryManager {
            return instance ?: synchronized(this) {
                instance ?: CrashRecoveryManager().also { instance = it }
            }
        }
        
        private var appContext: Context? = null
        
        fun init(context: Context) {
            appContext = context.applicationContext
        }
    }
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences("crash_recovery", Context.MODE_PRIVATE)
        loadCrashState()
        // 不再在启动时增加 crash_count，避免正常启动被误判为连续崩溃
    }
    
    private fun loadCrashState() {
        val prefs = this.prefs ?: return
        
        crashState = CrashRecoveryState(
            hasCrash = prefs.getBoolean("has_crash", false),
            lastCrashTime = prefs.getLong("last_crash_time", 0),
            crashCount = prefs.getInt("crash_count", 0),
            lastCrashInfo = prefs.getString("last_crash_info", null)
        )
    }
    
    fun recordCrash(crashInfo: String) {
        try {
            val prefs = this.prefs ?: return
            val currentTime = System.currentTimeMillis()
            
            prefs.edit().apply {
                putBoolean("has_crash", true)
                putLong("last_crash_time", currentTime)
                putInt("crash_count", crashState.crashCount + 1)
                // 堆栈较长，截断太少会丢掉关键帧，4K 足够覆盖常见堆栈
                putString("last_crash_info", crashInfo.take(4000))
                // 必须是 commit() 而不是 apply()：写完后紧接着就会 invokeDefaultHandler 终止进程，
                // apply() 的异步落盘与 kill 存在竞态，崩溃堆栈经常还没落地进程就没了
                // —— 表现为"下次启动有时弹、有时不弹"。
                // 一次崩溃只写一次，同步写几十 KB 完全可接受。
                commit()
            }

            crashState = crashState.copy(
                hasCrash = true,
                lastCrashTime = currentTime,
                crashCount = crashState.crashCount + 1,
                lastCrashInfo = crashInfo.take(4000)
            )
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GlobalExceptionHandler.logSafe("CrashRecovery", "记录崩溃失败: ${e.message}")
        }
    }
    
    fun clearCrashState() {
        try {
            val prefs = this.prefs ?: return
            
            prefs.edit().apply {
                putBoolean("has_crash", false)
                putLong("last_crash_time", 0)
                remove("last_crash_info")
                apply()
            }
            
            crashState = CrashRecoveryState()
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GlobalExceptionHandler.logSafe("CrashRecovery", "清除崩溃状态失败: ${e.message}")
        }
    }
    
    fun incrementCrashCount() {
        try {
            val prefs = this.prefs ?: return
            val count = prefs.getInt("crash_count", 0)
            prefs.edit().putInt("crash_count", count + 1).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 忽略
        }
    }
    
    fun getCrashState(): CrashRecoveryState = crashState
    
    fun shouldShowRecoveryDialog(): Boolean {
        return crashState.hasCrash && 
               System.currentTimeMillis() - crashState.lastCrashTime < 60000
    }
    
    fun getRecoveryAction(): RecoveryAction {
        val state = crashState
        
        return when {
            state.crashCount > 10 -> RecoveryAction.REQUIRE_CLEAN_START
            state.crashCount > 5 -> RecoveryAction.SHOW_RECOVERY_OPTIONS
            state.hasCrash -> RecoveryAction.AUTO_RECOVER
            else -> RecoveryAction.NONE
        }
    }
    
    enum class RecoveryAction {
        NONE,
        AUTO_RECOVER,
        SHOW_RECOVERY_OPTIONS,
        REQUIRE_CLEAN_START
    }
}

inline fun <T> safeLet(vararg elements: T?, block: (List<T>) -> Unit) {
    if (elements.all { it != null }) {
        block(elements.filterNotNull())
    }
}
