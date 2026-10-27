package com.example.stockdiary.utils

import com.example.stockdiary.common.DateFormats
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.io.IOException
import java.util.ArrayDeque
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
    /**
     * 内存队列上限。
     *
     * 从前是 100：一次全量同步（几百个标的、每个标的一段 DEBUG/INFO 日志）在 2 秒
     * flush 窗口内可以轻松超过 100 条，于是队列持续满、最旧的日志被 poll 掉——
     * 排障最想看的「崩溃前那几百行」恰恰在同步爆发期被静默丢弃。
     * 提到 1000：把窗口内的丢失概率压到极低；每条日志几十~一百多字节，
     * 1000 条约百 KB，内存完全可接受。仍保留「超上限丢最旧、保最新」策略，
     * 因为崩溃前后最新那段日志才是定位问题的关键。
     */
    private const val MAX_QUEUE_SIZE = 1000
    private const val FLUSH_INTERVAL_MS = 2000L
    private val logTimestampFormatter = DateFormats.ISO_DATETIME_MILLIS_FORMATTER
    
    private var lastFlushTime = System.currentTimeMillis()
    private var appContext: Context? = null

    // 日志落盘专用后台线程：flush 含文件读写与截断，直接在主线程跑会掉帧
    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLoggerWriter").apply { isDaemon = true }
    }
    
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
     * 对 URL 进行安全处理：先移除 `?` 及其后的整个 query 串（含 signature、token
     * 等敏感字段，绝不落盘），再截断到 [MAX_LOG_URL_LENGTH] 安全长度。
     */
    private fun sanitizeUrl(url: String): String {
        return url.substringBefore('?').take(MAX_LOG_URL_LENGTH)
    }

    /**
     * 通用脱敏：把日志行里的 URL query 段整段去掉再截断。
     *
     * [sanitizeUrl] 只 cover「整条消息就是个 URL」的场景；OkHttp 的日志是一整行
     * （`--> GET https://api.binance.com/api/v3/account?timestamp=…&signature=…`），
     * 必须按「`?` 到下一个空白」切。否则币安的 signature 会原样落进 app.log ——
     * debug 包分享日志出去时，等于把一次在时间窗内可重放的签名交出去。
     */
    internal fun sanitizeForLog(message: String): String =
        QUERY_IN_LOG_REGEX.replace(message, "").take(MAX_LOG_URL_LENGTH)

    /** 匹配 URL 的 `?` 到下一个空白字符（日志行里 query 后面是空格/换行/协议尾巴） */
    private val QUERY_IN_LOG_REGEX = Regex("\\?[^\\s]*")

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
            
            // 达到上限：丢弃最旧、保留最新（崩溃前后最新段最该保留），并立即落盘一次，
            // 把「爆发期持续丢最早日志」的窗口压到最小——否则要等 2 秒周期 flush。
            val reachedLimit = logQueue.size >= MAX_QUEUE_SIZE
            if (reachedLimit) {
                logQueue.poll()
            }
            logQueue.offer(logLine)
            
            val overdue = System.currentTimeMillis() - lastFlushTime > FLUSH_INTERVAL_MS
            if (overdue || level == "ERROR" || reachedLimit) {
                scheduleFlush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "队列日志写入失败", e)
        }
    }

    /**
     * 把积压日志提交给专用后台线程落盘。
     * 执行器被拒绝（极端情况）只丢这次落盘，不影响调用方。
     */
    private fun scheduleFlush() {
        try {
            logExecutor.execute { flushLogs() }
        } catch (e: Exception) {
            // 执行器被拒绝（极端情况）只丢这次落盘，不影响调用方
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
    
    /**
     * 截断预判的字节下限。
     *
     * 5000 行常规日志（每行几十到一百多字节）不可能小于 250KB——没超过它就连读都不读。
     * 从前每次 flush 都全量重读两遍（先数行数、超限再读一遍重写），5000 行的文件
     * 每 2 秒白读约 500KB，纯浪费。file.length() 是一次 stat，微秒级。
     */
    private const val TRIM_CHECK_FLOOR_BYTES = 250_000L

    /**
     * 从行序列里保留最后 [maxLines] 行（单遍，内存上限 [maxLines] 行）。
     *
     * 用定长 [ArrayDeque] 滑动窗口：每读一行 addLast，超过 [maxLines] 就 removeFirst。
     * 一次遍历完成，不需要先数总行数再读第二遍（旧实现最坏读两遍文件）。
     */
    internal fun trimToLastLines(lines: Sequence<String>, maxLines: Int): String {
        if (maxLines <= 0) return ""
        val window = ArrayDeque<String>(maxLines)
        for (line in lines) {
            window.addLast(line)
            if (window.size > maxLines) window.removeFirst()
        }
        // 末尾补一个换行：否则裁剪后文件最后一行缺 \n，下次追加的第一条日志会
        // 接在最后一行屁股后面造成粘连（日志持续超上限时每次 flush 都触发裁剪，累积成片）。
        // 空集合时 joinToString 仍会追加 postfix，需特判返回空串。
        return if (window.isEmpty()) "" else window.joinToString("\n", postfix = "\n")
    }

    private fun trimLogFile(file: File, maxLines: Int = 5000) {
        try {
            // 廉价预判：没超字节下限直接返回，一次内容读都不做
            if (file.length() < TRIM_CHECK_FLOOR_BYTES) return
            // 流式单遍截断：useLines 逐行喂给 trimToLastLines，只保留最后 maxLines 行，
            // 内存峰值=maxLines 行，避免先数行再整段读两遍。
            val kept = file.useLines { trimToLastLines(it, maxLines) }
            file.writeText(kept)
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
            // 必须和 flushLogs 串行：二者都在 logExecutor 单线程上跑，submit 排队执行，
            // 避免「flush 的 append」与「这里 writeText("")」交错覆盖
            // —— 否则可能把刚落盘的日志又清空，或清空后又被 append 覆盖成非空。
            // .get() 阻塞到任务完成，保证调用方 reload 时文件已确实清空（同步语义）。
            logExecutor.submit {
                try {
                    logQueue.clear()
                    getLogFileSafe()?.writeText("")
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "清空日志失败", e)
                }
            }.get()
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
