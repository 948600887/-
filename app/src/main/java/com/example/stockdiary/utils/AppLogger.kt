package com.example.stockdiary.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

object AppLogger {
    private var isDebug = true
    private const val LOG_TAG = "GoldVault"
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val isWriting = AtomicBoolean(false)
    private const val MAX_QUEUE_SIZE = 100
    private const val FLUSH_INTERVAL_MS = 2000L
    private val logTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    private var lastFlushTime = System.currentTimeMillis()
    private var appContext: Context? = null
    
    fun init(context: Context, debug: Boolean = true) {
        appContext = context.applicationContext
        isDebug = debug
        AppLogger.d("AppLogger initialized", "AppInit")
    }
    
    fun d(message: String, tag: String = LOG_TAG) {
        if (isDebug) Log.d(tag, message)
        queueLog("DEBUG", tag, message)
    }
    
    fun i(message: String, tag: String = LOG_TAG) {
        if (isDebug) Log.i(tag, message)
        queueLog("INFO", tag, message)
    }
    
    fun w(message: String, tag: String = LOG_TAG) {
        if (isDebug) Log.w(tag, message)
        queueLog("WARN", tag, message)
    }
    
    fun e(message: String, throwable: Throwable? = null, tag: String = LOG_TAG) {
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        if (isDebug) Log.e(tag, fullMessage)
        queueLog("ERROR", tag, fullMessage)
    }
    
    fun api(request: String, response: String?, duration: Long, tag: String = "API") {
        val safeRequest = sanitizeUrl(request)
        val msg = "$safeRequest | ${response?.take(100) ?: "FAILED"} | ${duration}ms"
        if (isDebug) Log.d(tag, msg)
        queueLog("API", tag, msg)
    }

    /**
     * 记录 API 请求 URL，已做截断和 query 参数脱敏
     */
    fun apiUrl(method: String, url: String, tag: String = "API") {
        val safeUrl = sanitizeUrl(url)
        d("$method $safeUrl", tag)
    }

    /**
     * 对 URL 进行安全处理：截断长度并移除 query 参数中的敏感字段
     */
    private fun sanitizeUrl(url: String): String {
        if (url.length <= MAX_LOG_URL_LENGTH) return url
        return url.take(MAX_LOG_URL_LENGTH) + "..."
    }

    private const val MAX_LOG_URL_LENGTH = 120
    
    fun db(operation: String, table: String, duration: Long, tag: String = "Database") {
        val msg = "$operation on $table | ${duration}ms"
        if (isDebug) Log.d(tag, msg)
        queueLog("DB", tag, msg)
    }
    
    private fun queueLog(level: String, tag: String, message: String) {
        try {
            val timestamp = LocalDateTime.now().format(logTimestampFormatter)
            val logLine = "[$timestamp] [$level/$tag] $message"
            
            if (logQueue.size >= MAX_QUEUE_SIZE) {
                logQueue.poll()
            }
            logQueue.offer(logLine)
            
            if (System.currentTimeMillis() - lastFlushTime > FLUSH_INTERVAL_MS || level == "ERROR") {
                flushLogs()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "队列日志写入失败", e)
        }
    }
    
    private fun flushLogs() {
        if (!isWriting.compareAndSet(false, true)) {
            return
        }
        
        try {
            val logsToWrite = mutableListOf<String>()
            while (true) {
                val log = logQueue.poll() ?: break
                logsToWrite.add(log)
            }
            
            if (logsToWrite.isEmpty()) {
                return
            }
            
            val logFile = getLogFileSafe()
            if (logFile == null) {
                return
            }
            
            FileWriter(logFile, true).use { fileWriter ->
                BufferedWriter(fileWriter).use { writer ->
                    for (log in logsToWrite) {
                        try {
                            writer.write(log)
                            writer.newLine()
                        } catch (e: IOException) {
                            break
                        }
                    }
                }
            }
            
            lastFlushTime = System.currentTimeMillis()
            trimLogFile(logFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "日志刷新失败", e)
        } finally {
            isWriting.set(false)
        }
    }
    
    private fun getLogFileSafe(): File? {
        return try {
            val context = appContext ?: return null
            
            val dir = context.getExternalFilesDir(null)
                ?.let { File(it, "GoldVault") }
                ?: return null
            
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    return null
                }
            }
            File(dir, "app.log")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    
    private fun trimLogFile(file: File, maxLines: Int = 5000) {
        try {
            // 按行数截断，避免一次性读取大文件导致 OOM
            var lineCount = 0
            file.bufferedReader().use { reader ->
                while (reader.readLine() != null) {
                    lineCount++
                    if (lineCount > maxLines * 2) break
                }
            }
            if (lineCount > maxLines) {
                val linesToKeep = mutableListOf<String>()
                var skipped = 0
                val keepCount = lineCount - maxLines
                file.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (skipped < keepCount) {
                            skipped++
                        } else {
                            linesToKeep.add(line)
                        }
                    }
                }
                file.writeText(linesToKeep.joinToString("\n"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 忽略
        }
    }
    
    fun getLogFile(): File? {
        return getLogFileSafe()
    }
    
    fun clearLog() {
        try {
            val logFile = getLogFileSafe()
            logFile?.writeText("")
            logQueue.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "清空日志失败", e)
        }
    }
    
    fun forceFlush() {
        flushLogs()
    }
}

inline fun <T> measureTime(label: String, tag: String = "Performance", block: () -> T): T {
    val start = System.currentTimeMillis()
    return try {
        block()
    } finally {
        val duration = System.currentTimeMillis() - start
        AppLogger.d("$label: ${duration}ms", tag)
    }
}

suspend fun <T> measureTimeSuspend(label: String, tag: String = "Performance", block: suspend () -> T): T {
    val start = System.currentTimeMillis()
    return try {
        block()
    } finally {
        val duration = System.currentTimeMillis() - start
        AppLogger.d("$label: ${duration}ms", tag)
    }
}
