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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

object AppLogger {
    private var isDebug = true
    private const val LOG_TAG = "GoldVault"

    /**
     * 一条待落盘日志（**未格式化**）。
     *
     * [epochMillis] 在**入队时**取一次 `System.currentTimeMillis()`，落盘时只做格式化。
     *
     * 为什么不能像从前那样「写盘线程统一取一次时间戳」：flush 是 2 秒合并批次，
     * 一批几百上千行会共用同一个时间戳。真机诊断里实测 **1000 行落在同一毫秒**
     * （51602 行日志只有 8168 个唯一时间戳），同批内的先后顺序、每步耗时全部失真，
     * 而 OkHttp 那行明明写着 `411ms`。入队取毫秒只是一次纳秒级读，代价可忽略。
     */
    internal data class LogRecord(
        val level: String,
        val tag: String,
        val message: String,
        val epochMillis: Long = System.currentTimeMillis()
    ) {
        fun render(timestamp: String): String = "[$timestamp] [$level/$tag] $message"
    }

    /**
     * 内存队列。
     *
     * 用 [ArrayDeque] + [queueLock] 而不是 ConcurrentLinkedQueue：失败回队需要
     * 「插回队首 + 保持顺序」，无锁队列没有 addFirst，靠 clear 重建会与并发 offer 竞争丢日志。
     * 锁内只有几十次内存操作、没有 IO，开销可忽略。
     */
    private val logQueue = ArrayDeque<LogRecord>()
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

    /** epoch 毫秒 → 日志时间戳（`yyyy-MM-dd HH:mm:ss.SSS`），按设备本地时区 */
    internal fun formatEpochMillis(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(logTimestampFormatter)

    /**
     * 给一批记录分配时间戳，**逐条**对应各自入队时刻。
     *
     * 连续同毫秒的条目复用上一次的格式化结果（省掉重复 format），跨毫秒必重新格式化 ——
     * 这样既不会退化成「整批一个时间戳」，也不会为几百条同毫秒日志各做一次格式化。
     *
     * 抽成 internal 纯函数是为了能被单测钉住：同一批里不同毫秒必须得到不同时间戳、
     * 顺序必须与入队顺序一致。这条正是诊断里丢掉的属性。
     */
    internal fun allocateTimestamps(records: List<LogRecord>): List<String> {
        if (records.isEmpty()) return emptyList()
        val result = ArrayList<String>(records.size)
        var lastMillis = Long.MIN_VALUE
        var lastTimestamp = ""
        for (record in records) {
            if (record.epochMillis != lastMillis) {
                lastTimestamp = formatEpochMillis(record.epochMillis)
                lastMillis = record.epochMillis
            }
            result.add(lastTimestamp)
        }
        return result
    }

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

    /**
     * 带异常栈的 WARN。
     *
     * 单次请求失败按语义是「已降级、可自愈」（W），但排查时仍然需要栈；
     * 从前只能降级成 e 才带栈，级别口径就被迫跑偏。
     */
    fun w(message: String, throwable: Throwable?, tag: String = LOG_TAG) {
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        if (isDebug) Log.w(tag, fullMessage)
        queueLog("WARN", tag, fullMessage)
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
            // 时间戳在构造 LogRecord 时取（毫秒）：交给写盘线程统一取会把整批压成同一时刻
            val record = LogRecord(level, tag, message)

            // 达到上限：丢弃最旧、保留最新（崩溃前后最新段最该保留），并立即落盘一次，
            // 把「爆发期持续丢最早日志」的窗口压到最小——否则要等 2 秒窗口。
            val reachedLimit = synchronized(queueLock) {
                val full = logQueue.size >= MAX_QUEUE_SIZE
                if (full) logQueue.removeFirst()
                logQueue.addLast(record)
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
        val logsToWrite = ArrayList<LogRecord>()
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
                // 超过 8MB 先轮转（归档保留 4 份），再追加本批日志
                prepareLogFile(logFile)
                try {
                    // 时间戳逐条取各自入队时刻（见 LogRecord）：同批 1000 行曾共用同一毫秒，
                    // 同批内的先后与每步耗时全部失真。格式化仍在写盘线程做，不占调用方线程。
                    val timestamps = allocateTimestamps(logsToWrite)
                    FileWriter(logFile, true).use { fileWriter ->
                        BufferedWriter(fileWriter).use { writer ->
                            for (index in logsToWrite.indices) {
                                writer.write(logsToWrite[index].render(timestamps[index]))
                                writer.newLine()
                            }
                            writer.flush()
                            // written 的语义必须是「已 flush 成功的行数」，不是「已塞进
                            // BufferedWriter 缓冲区的行数」。默认 8KB 缓冲下，1000 行完全可能
                            // 一行都还没真正落盘：此时 flush() 抛 IOException 而 written 已经
                            // 是 size，下面的 `if (written < size)` 不成立 → 整批静默丢弃，
                            // 日志里看不出任何异常（复盘时最要命的正是这条）。
                            written = logsToWrite.size
                        }
                    }
                } catch (e: IOException) {
                    // 磁盘满 / 文件被占用：缓冲区里到底落了几行无从得知，
                    // 一律整批回队。宁可下次重复几行（日志重复无害），也绝不丢行。
                    written = 0
                }
                if (written < logsToWrite.size) {
                    requeue(logsToWrite, written)
                    // 写失败（磁盘满/文件被占用）也排一次重试：否则回队的行要等下一条日志才再尝试
                    scheduleFlushDelayed()
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 必须是 Throwable 而不是 Exception：写盘线程上抛出的 OOM / StackOverflow
            // 不属于 Exception，会逃到 JVM 的默认未捕获处理器（= GlobalExceptionHandler），
            // 于是「记一条日志失败」被判成 App 崩溃并走完整个崩溃流程/终止进程 ——
            // 本该可存活的低内存状态变成闪退，且崩溃现场还会二次触发日志与落盘。
            // 日志绝不能成为崩溃源：Error 在这里就地吞掉，只把没写下去的回队。
            Log.w(LOG_TAG, "日志刷新失败", t)
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
    private fun requeue(logs: List<LogRecord>, written: Int) {
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
        val dir = getLogDirSafe() ?: return null
        return logFileAt(dir, 0)
    }
    
    /** 单份日志文件上限 8MB：超过即轮转，保证单个文件不会无限膨胀 */
    internal const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 保留份数：app.log + app.log.1/.2/.3，合计磁盘占用上限 32MB */
    internal const val KEEP_LOG_FILES = 4

    /**
     * 单份文件的行数兜底。
     *
     * 字节上限管不住「行很短」的日志（纯坐标/时间戳行可能只有十几字节，8MB 能装几十万行），
     * 界面过滤、导出、grep 都会被行数拖慢。轮转时把刚归档那份裁到最后 [MAX_LOG_LINES] 行：
     * 归档文件已不再追加，裁一次即可，不会造成持续写放大。
     */
    internal const val MAX_LOG_LINES = 80_000

    /** 活跃日志文件名（其余归档为 app.log.1 ... app.log.N） */
    private const val LOG_FILE_NAME = "app.log"

    /**
     * [clearLog] 等待清理任务完成的超时（秒）。
     *
     * 正常清空只是清队列 + delete 几个小文件，毫秒级；给 2 秒是留足余量。
     * 之所以不无限等：调用方在界面线程上（清空日志 / 清除所有数据），
     * writer 正卡在大批量写盘时无限等会直接 ANR。
     */
    internal const val CLEAR_LOG_TIMEOUT_SECONDS = 2L

    /**
     * 截断预判的字节下限。
     *
     * **必须大于「轮转阈值内的常规体积」**。从前取 250KB：裁完仍是 400KB+、高于下限，于是
     * **每次** flush 都全量读 + 重写整个文件（约 800KB I/O / 2 秒），把「廉价预判」做成了写放大。
     * 现在行数兜底只在轮转时做（见 [prepareLogFile]），此下限只用于「轮转失败时就地裁剪」的
     * 退化路径，取一个远高于常规文件大小的值，避免退化路径上也反复重写。
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

    private fun trimLogFile(file: File, maxLines: Int = MAX_LOG_LINES, force: Boolean = false) {
        try {
            // 廉价预判：没超字节下限直接返回，一次内容读都不做
            if (!force && file.length() < TRIM_CHECK_FLOOR_BYTES) return
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

    /**
     * 写盘前检查活跃文件是否超出上限，超了就轮转。
     *
     * **必须在 [fileLock] 内调用**，且只能由后台 writer 或崩溃路径的同步 flush 触发。
     *
     * 轮转顺序：删除最旧的 `app.log.3` → `.2`→`.3` → `.1`→`.2` → `app.log`→`.1`，
     * 随后把归档的 `.1` 裁到最后 [MAX_LOG_LINES] 行（归档文件不再追加，裁一次即可）。
     * 轮转失败（例如文件被占用无法 rename）时就地裁剪活跃文件，保证不会无限增长。
     */
    internal fun prepareLogFile(logFile: File) {
        if (logFile.length() < MAX_FILE_BYTES) return
        if (rotateLogFiles(logFile)) return
        // 退化路径：轮转失败时就地保留尾部行数，至少不至于涨到磁盘满
        trimLogFile(logFile, MAX_LOG_LINES, force = true)
    }

    /**
     * 轮转日志文件：`app.log` → `app.log.1`，其余依次后移，最旧的丢弃。
     *
     * 刻意做成「入参只有 File」的纯磁盘操作，便于 JVM 单测（无需 Context）。
     * @return true = 至少完成了从 `app.log` 到 `app.log.1` 的改名
     */
    internal fun rotateLogFiles(logFile: File): Boolean {
        val dir = logFile.parentFile ?: return false
        return try {
            // 先删最旧的一份（保留 KEEP_LOG_FILES 份，含活跃文件）
            logFileAt(dir, KEEP_LOG_FILES - 1).delete()
            // 由旧到新依次后移：.2→.3、.1→.2
            for (index in KEEP_LOG_FILES - 2 downTo 1) {
                val from = logFileAt(dir, index)
                if (from.exists()) from.renameTo(logFileAt(dir, index + 1))
            }
            val rotated = logFile.renameTo(logFileAt(dir, 1))
            if (rotated) {
                // 刚归档的这份裁到行数上限：归档后不再追加，不存在持续写放大
                trimArchivedLog(logFileAt(dir, 1))
            }
            rotated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 归档文件的行数裁剪。
     *
     * 与 [trimLogFile] 的区别：归档文件已是「历史负担」，无论大小都按行数裁一次，
     * 否则行很短的日志会留下几十万行，拖慢诊断界面与导出。
     */
    private fun trimArchivedLog(file: File) {
        if (!file.exists()) return
        try {
            // 行数不超上限就完全不动：先读完再写会让 8MB 文件白白多一次 I/O。
            // 计数与写回必须在两次独立的读关闭之后（同一文件同时读+写，Windows 上会失败）
            val over = file.useLines { lines -> lines.take(MAX_LOG_LINES + 1).count() > MAX_LOG_LINES }
            if (!over) return
            val kept = file.useLines { trimToLastLines(it, MAX_LOG_LINES) }
            file.writeText(kept)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 裁剪失败不影响归档文件本身可用
        }
    }

    /** `index=0` 是活跃文件 `app.log`，`index=1..3` 是归档文件 */
    internal fun logFileAt(dir: File, index: Int): File =
        File(dir, if (index == 0) LOG_FILE_NAME else "$LOG_FILE_NAME.$index")

    /**
     * 磁盘上现有的全部日志文件，**新的在前**（活跃文件 → .1 → .2 → .3）。
     *
     * 诊断导出、界面浏览都要跨文件读：只读 app.log 会丢掉崩溃前刚轮转出去的那份。
     */
    fun getLogFiles(): List<File> {
        val dir = getLogDirSafe() ?: return emptyList()
        return (0 until KEEP_LOG_FILES).map { logFileAt(dir, it) }.filter { it.exists() }
    }

    private fun getLogDirSafe(): File? {
        return try {
            val context = appContext ?: return null
            val dir = context.getExternalFilesDir(null)?.let { File(it, "GoldVault") } ?: return null
            if (!dir.exists() && !dir.mkdirs()) return null
            dir
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    
    fun getLogFile(): File? {
        return getLogFileSafe()
    }
    
    /**
     * 清空日志（活跃文件 + 全部轮转归档 + 内存待写队列）。
     *
     * @param timeoutSeconds 等待清理任务完成的秒数，默认 [CLEAR_LOG_TIMEOUT_SECONDS]
     * @return **true = 已确认清空**（任务在等待期内跑完，且**每一个**日志文件都确实删掉了）；
     *         **false = 没清完**——超时（任务仍在后台排队）、被中断，或任务跑完了但有
     *         文件删不掉（被占用 / 只读）。调用方必须区分这两种结果：
     *         从前返回 Unit，超时静默返回后调用方立刻 reload，读到的是**旧日志**，
     *         用户以为没清掉、反复点；另一处则直接提示「清除成功」而日志其实还在。
     *         为了让 true 名副其实（UI 已经拿它决定 reload 还是提示「仍在清理」），
     *         [clearLogNow] 一旦有文件删失败就返 false —— 宁可多给一次「仍在清理」，
     *         也不谎称已清空。注意 false 不等于永久失败——任务已在单线程队列里排队，
     *         迟早会执行。
     */
    fun clearLog(timeoutSeconds: Long = CLEAR_LOG_TIMEOUT_SECONDS): Boolean =
        clearLogAwaiting(logExecutor.submit(Callable { clearLogNow() }), timeoutSeconds)

    /**
     * 复位进程内状态（**仅测试用**）。
     *
     * [init] 写的是 object 级的 [appContext]，用例之间不清就会串味：上一个用例把日志
     * 目录指到临时目录、用例结束又把目录删了，后续用例的 `clearLog` / `getLogFiles`
     * 看到的世界就全错（且错得静默）。AI_GUIDE §8 要求进程内 object 必须
     * `@Before`/`@After` 复位，这就是它的钩子。
     */
    internal fun resetForTest() {
        appContext = null
    }

    /**
     * 等待清理任务完成，超时只记日志、**绝不抛给调用方**（清空是尽力而为，
     * 抛异常会让「清除所有数据」整条流程失败）。
     *
     * 返回值直接透传 [Future.get] 的结果：任务**跑完且删干净**才 true；任务本身返
     * false（有文件删不掉）、超时、被中断、执行时抛异常，全部收敛成 false。
     *
     * 单独抽出来的唯一理由是**可测**：直接断言 `clearLog(0)` 的布尔值是竞态的
     * ——任务可能在 `get()` 之前就跑完，测试会偶发飘红。注入一个「必然未完成」的
     * [Future] 才能确定性覆盖「0 超时 → 返回 false 且不抛」这条语义。
     */
    internal fun clearLogAwaiting(task: Future<Boolean>, timeoutSeconds: Long): Boolean {
        return try {
            // 删除失败已在 clearLogNow 里折成 false，这里只负责「等待 + 透传」
            task.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // 必须留痕——否则下次看到「日志没清掉」会以为是自己代码写错了
            Log.w(LOG_TAG, "清空日志超时(${timeoutSeconds}s)，清理任务仍在后台排队")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            // 先恢复中断标志再返回：`get()` 抛出时会**清掉**线程的中断状态，吞掉它等于
            // 让调用方线程后续的阻塞点再也收不到「请收手」的信号（标准做法是补打标记，
            // 不重新抛——本方法的契约就是「绝不抛给调用方」）。
            Thread.currentThread().interrupt()
            Log.w(LOG_TAG, "等待清空日志时被中断，清理任务仍在后台排队")
            false
        } catch (e: Exception) {
            // ExecutionException：清理任务内部抛了没接住的异常
            Log.w(LOG_TAG, "清空日志失败", e)
            false
        }
    }

    /**
     * 真正的清理动作：清内存队列 + 删全部日志文件。
     *
     * @return true = 内存队列已清空**且**每个日志文件都删掉了；false = 有文件删不掉、
     *         清队列时抛异常，或**取不到日志目录**（外部存储不可用 = 无法确认）。
     *         **不中途 return**：一个文件删不掉也要把余下的尽力删完，
     *         否则「有一份被占用就整批留下」，下次未必还占用，用户却一直看到旧日志。
     *
     * 必须和 flushLogs 串行：二者都在 logExecutor 单线程上跑，submit 排队执行，
     * 避免「flush 的 append」与「这里 writeText(\"\")」交错覆盖
     * —— 否则可能把刚落盘的日志又清空，或清空后又被 append 覆盖成非空。
     */
    private fun clearLogNow(): Boolean {
        var queueCleared = true
        try {
            synchronized(queueLock) { logQueue.clear() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 队列都没清掉就别谎称成功，但文件仍要尽量删
            queueCleared = false
            Log.w(LOG_TAG, "清空日志时清理内存队列失败", e)
        }

        // **先确认日志目录真的拿到了**，不能靠 getLogFiles() 的返回值反推：
        // 拿不到目录时它返 emptyList()，而 deleteFiles(emptyList()) 的 forEach 空转
        // 会返回 true —— 空集合恒真，于是「外部存储不可用」被报成「已确认清空」，
        // 调用方（LogDiagnostics.clear）据此提示「已清空」并 reload，用户又看到旧日志。
        // 注意区分两种情况：「目录在、本来就没文件」仍算真的空（继续走下面的删除，返 true）。
        if (getLogDirSafe() == null) {
            Log.w(LOG_TAG, "清空日志找不到日志目录（外部存储不可用？），无法确认已清空")
            return false
        }

        // 文件写入同样要拿 fileLock：崩溃路径的 forceFlush 不走这个执行器，
        // 两者若只靠单线程排队串行，forceFlush 会和这里的删除交错
        // 归档文件一并清掉：只清 app.log 会让用户以为「清空」了、分享里却还带着旧轮次日志
        val filesDeleted = try {
            synchronized(fileLock) { deleteFiles(getLogFiles()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "清空日志时枚举日志文件失败", e)
            false
        }
        return queueCleared && filesDeleted
    }

    /**
     * 逐个删除 [files] 并如实汇报，**不中途 return**——一份删不掉也要把余下的删完。
     *
     * 通用「删一堆文件」动作，目前两个调用点：[clearLogNow] 删日志、
     * `LogDiagnostics.deleteCrashReports` 删崩溃报告（故不叫 deleteLogFiles）。
     *
     * @return true = **全部**删掉。任一 `delete()` 返回 false（被占用 / 只读 / 已被别人
     *         先删掉）或抛异常都记 false。从前这里丢弃了 `delete()` 的返回值，
     *         删失败时 Future 仍正常完成 → [clearLog] 返 true → UI 走 reload →
     *         用户又看到旧日志，提示变成谎话。
     *
     * 已知取舍：并发下「文件已被别人先删掉」也会记 false（`delete()` 对不存在的路径返
     * false）。这是有意偏保守：多给一次「仍在清理」的提示，好过谎称已清空。
     * 因此 false 只代表「没确认删干净」，**不代表**权限或路径有问题。
     *
     * 本函数自身不持锁。日志文件由 [clearLogNow] 在 [fileLock] 内调用；
     * 崩溃报告目录没有进程内并发写入（只在崩溃现场写，那时进程已在退出），直接调用即可。
     */
    internal fun deleteFiles(files: List<File>): Boolean {
        var allDeleted = true
        files.forEach { file ->
            try {
                if (!file.delete()) {
                    allDeleted = false
                    // 文案要涵盖所有已知原因：「已被别人先删掉」同样会返 false，
                    // 只说「占用/只读」会把看 logcat 的人引向权限问题这条错路
                    Log.w(LOG_TAG, "文件删除失败（已被占用、只读，或已被其他进程先删掉）: ${file.name}")
                }
            } catch (e: Exception) {
                allDeleted = false
                Log.w(LOG_TAG, "删除文件异常: ${file.name}", e)
            }
        }
        return allDeleted
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
