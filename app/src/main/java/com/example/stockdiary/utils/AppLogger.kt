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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

object AppLogger {
    private var isDebug = true
    private const val LOG_TAG = "GoldVault"

    /**
     * 内存队列。
     *
     * 用 [ArrayDeque] + [queueLock] 而不是 ConcurrentLinkedQueue：失败回队需要
     * 「插回队首 + 保持顺序」，无锁队列没有 addFirst，靠 clear 重建会与并发 offer 竞争丢日志。
     * 锁内只有几十次内存操作、没有 IO，开销可忽略。
     */
    private val logQueue = ArrayDeque<String>()
    private val queueLock = Any()
    private val isWriting = AtomicBoolean(false)

    /** 延迟 flush 是否已排期（合并 2 秒窗口内的多次入队，避免每来一条日志就换一次线程） */
    private val flushScheduled = AtomicBoolean(false)

    /** 文件写入锁：后台 writer 与崩溃路径的 forceFlush / clearLog 必须串行，否则会写坏文件 */
    private val fileLock = Any()

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

    /** 崩溃路径等待后台 flush 收尾的上限：够写完一两个批次，又不至于把闪退拖成 ANR */
    private const val FORCE_FLUSH_WAIT_MS = 500L

    private val logTimestampFormatter = DateFormats.ISO_DATETIME_MILLIS_FORMATTER

    private var appContext: Context? = null

    // 日志落盘专用后台线程：flush 含文件读写与截断，直接在主线程跑会掉帧
    private val logExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLoggerWriter").apply { isDaemon = true }
    }

    /**
     * 延迟 flush 定时器。
     *
     * 旧实现只在「下一条日志到来且距上次 flush > 2s」时才调度：最后一条日志之后如果
     * 再没有日志，它就会一直躺在内存里，进程被杀时丢掉——而崩溃前最后几行恰恰最关键。
     * 这里在每条日志入队时排一个 2 秒后的 flush（[flushScheduled] 合并窗口内多次入队），
     * 保证不超过 2 秒必然落盘一次。
     */
    private val flushScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AppLoggerTimer").apply { isDaemon = true }
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

    /**
     * 只去 URL query、**不截断**的脱敏版（崩溃报告这类需要保留完整异常文案的场景）。
     *
     * 崩溃报告会被用户主动分享，异常 message 里可能带请求 URL（含 signature/token），
     * 去掉 `?` 到空白之间的整段即可，其余信息一字不丢。
     */
    internal fun sanitizeMessage(message: String): String =
        QUERY_IN_LOG_REGEX.replace(message, "")

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
            // 把「爆发期持续丢最早日志」的窗口压到最小——否则要等 2 秒窗口。
            val reachedLimit = synchronized(queueLock) {
                val full = logQueue.size >= MAX_QUEUE_SIZE
                if (full) logQueue.removeFirst()
                logQueue.addLast(logLine)
                full
            }

            // ERROR 与爆队列立即落盘；其余走 2 秒延迟窗口（每条日志都排一次，合并成一次 flush）
            if (level == "ERROR" || reachedLimit) {
                scheduleFlush()
            } else {
                scheduleFlushDelayed()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "队列日志写入失败", e)
        }
    }

    /**
     * 把积压日志提交给专用后台线程立即落盘。
     * 执行器被拒绝（极端情况）只丢这次落盘，不影响调用方。
     */
    private fun scheduleFlush() {
        try {
            logExecutor.execute { flushLogs() }
        } catch (e: Exception) {
            // 执行器被拒绝（极端情况）只丢这次落盘，不影响调用方
        }
    }

    /**
     * 排一次 2 秒后的 flush。
     *
     * 用 [flushScheduled] 合并窗口：2 秒内来一万条日志也只排一次任务，不会把定时器打爆。
     */
    private fun scheduleFlushDelayed() {
        if (!flushScheduled.compareAndSet(false, true)) return
        try {
            flushScheduler.schedule(
                {
                    flushScheduled.set(false)
                    flushLogs()
                },
                FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (e: Exception) {
            // 定时器被拒绝（进程正在退出）：放弃这次延迟落盘，不影响调用方
            flushScheduled.set(false)
        }
    }

    /**
     * 把队列里的日志写到 app.log。
     *
     * @return true = 抢到了写入资格（无论有没有内容可写）；false = 另一个线程正在写
     *   （崩溃路径的 [forceFlush] 据此等待并补一次，而不是直接放弃）
     *
     * 失败语义：写文件失败 / 文件不可用时，**把没写下去的行放回队首**，等下一次 flush；
     * 绝不静默丢弃（从前 `poll` 完就 return，一次磁盘异常等于整批日志蒸发）。
     */
    private fun flushLogs(): Boolean {
        if (!isWriting.compareAndSet(false, true)) {
            return false
        }

        // 这两项必须在 try 之外声明：意外异常时要把「还没写下去的行」放回队列，
        // 否则一次非 IO 异常（如 OOM 之外的意外）就等于整批日志蒸发。
        val logsToWrite = ArrayList<String>()
        var written = 0
        try {
            synchronized(queueLock) {
                while (logQueue.isNotEmpty()) {
                    logsToWrite.add(logQueue.removeFirst())
                }
            }

            if (logsToWrite.isEmpty()) {
                return true
            }

            val logFile = getLogFileSafe()
            if (logFile == null) {
                requeue(logsToWrite, written = 0)
                // 目录还没就绪（init 之前 / 外部存储暂不可用）：排一次重试，别等下一批日志
                scheduleFlushDelayed()
                return true
            }

            synchronized(fileLock) {
                try {
                    FileWriter(logFile, true).use { fileWriter ->
                        BufferedWriter(fileWriter).use { writer ->
                            for (log in logsToWrite) {
                                writer.write(log)
                                writer.newLine()
                                written++
                            }
                            writer.flush()
                        }
                    }
                } catch (e: IOException) {
                    // 磁盘满 / 文件被占用：已写下去的保留，剩下的回队等下次
                }
                if (written < logsToWrite.size) {
                    requeue(logsToWrite, written)
                    // 写失败（磁盘满/文件被占用）也排一次重试：否则回队的行要等下一条日志才再尝试
                    scheduleFlushDelayed()
                }
                if (written > 0) trimLogFile(logFile)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "日志刷新失败", e)
            // 意外异常（非 IOException）：没写下去的照样回队，下一轮重试
            requeue(logsToWrite, written)
            return true
        } finally {
            isWriting.set(false)
        }
    }

    /**
     * 把没写下去的行放回队首，保持原有顺序。
     *
     * 回队也受 [MAX_QUEUE_SIZE] 约束：超限时丢最旧的（即这批里最靠前的），
     * 与「保最新」策略一致，避免磁盘长期异常把内存撑爆。
     */
    private fun requeue(logs: List<String>, written: Int) {
        val pending = logs.subList(written, logs.size)
        if (pending.isEmpty()) return
        synchronized(queueLock) {
            for (i in pending.indices.reversed()) {
                logQueue.addFirst(pending[i])
            }
            while (logQueue.size > MAX_QUEUE_SIZE) {
                logQueue.removeFirst()
            }
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
     * **必须大于「5000 行」的实际体积**（带时间戳/tag 的日志行约 90~130 字节，
     * 5000 行约 450~650KB）。从前取 250KB：裁完仍是 400KB+、高于下限，于是**每次**
     * flush 都全量读 + 重写整个文件（约 800KB I/O / 2 秒），把「廉价预判」做成了写放大。
     * 取 1MB：文件长到 1MB 才裁一次，裁到约 500KB，之后有约 500KB 的余量再触发。
     * file.length() 只是一次 stat，微秒级。
     */
    private const val TRIM_CHECK_FLOOR_BYTES = 1_000_000L

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
                    synchronized(queueLock) { logQueue.clear() }
                    // 文件写入同样要拿 fileLock：崩溃路径的 forceFlush 不走这个执行器，
                    // 两者若只靠单线程排队串行，forceFlush 会和这里的 writeText("") 交错
                    synchronized(fileLock) { getLogFileSafe()?.writeText("") }
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
    
    /**
     * 同步强制落盘——崩溃路径专用。
     *
     * 与后台 writer 竞争时的正确处理：CAS 失败说明对方正在写，**等它写完再补一次**，
     * 而不是像从前那样直接 return。崩溃现场只有一次机会，最后几行丢了就永远没有。
     * 等待上限 [FORCE_FLUSH_WAIT_MS]：宁可少几行，也不能把闪退拖成 ANR。
     */
    fun forceFlush() {
        if (flushLogs()) return
        val deadline = System.currentTimeMillis() + FORCE_FLUSH_WAIT_MS
        while (isWriting.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        // 后台那次 flush 已把队列抽干；这次补的是它写盘期间新入队的行
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
