package com.example.stockdiary.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.stockdiary.data.price.PriceCacheManager
import com.example.stockdiary.network.BinanceHostGate
import com.example.stockdiary.data.sync.AssetSyncManager
import com.example.stockdiary.utils.ExchangeRateManager
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * 把「崩溃报告 + 运行日志」从手机里取出来的能力。
 *
 * ## 为什么必须有这个
 *
 * 日志写在 `getExternalFilesDir()` 下：
 *
 * ```
 * Android/data/com.example.stockdiary/files/GoldVault/app.log
 * Android/data/com.example.stockdiary/files/crash_reports/crash_<ts>.log
 * ```
 *
 * 而**从 Android 11（API 30）起 `Android/data` 对用户不可见**：系统文件管理器进不去，
 * 第三方文件管理器进不去，USB 传文件时也不显示。于是「导出一份 app.log 给我」
 * 是一句用户根本执行不了的话——他不是没找到，是根本看不见。
 *
 * 但 App 自己读写这个目录是**完全正常**的。所以正确的做法不是教用户挖文件，
 * 而是 App 自己把内容读出来、显示出来、并通过系统分享送出去（微信/邮件/蓝牙都行）。
 *
 * ## 文本拼装刻意做成纯函数
 *
 * [composeReport] 不碰 Context、不碰文件，输入输出都是字符串，
 * 因此可以直接 JVM 单测——见 `LogDiagnosticsTest`。
 * 涉及磁盘的部分（[collect] / [export] / [clear]）保持薄，只做搬运。
 */
object LogDiagnostics {

    /**
     * 界面上一次最多显示多少行 app.log。
     *
     * 日志按轮转保留 4 份、单份 8MB，全塞进一个 `Text` 会明显卡顿；默认展示最近 5000 行
     * （约覆盖近几轮同步），再配合级别/关键词/轮次过滤定位。分享出去的是多份全文（上限 12MB）。
     */
    const val DISPLAY_TAIL_LINES = 5000

    /**
     * 诊断导出的内容上限 12MB。
     *
     * 轮转后磁盘上最多 32MB 日志，一次性读进内存再拼成字符串会在低端机上触发 OOM
     * （读 + 拼接是两份）。超出时只导**最新的部分**，并在报告里明确写出省略了多少，
     * 绝不静默丢历史：静默截断会让「崩溃前那几行」看起来像真的没有。
     */
    const val EXPORT_MAX_BYTES = 12L * 1024 * 1024

    /** 一份日志文件的内容，[omittedBytes] 为该文件因 12MB 上限被截掉的字节数 */
    data class LogFile(
        val name: String,
        val content: String,
        val omittedBytes: Long = 0L
    )

    /**
     * 单份崩溃报告在**界面**上保留的堆栈行数。
     *
     * 一份完整堆栈几十 KB、几百~几千行，5 份全塞进 LazyColumn 会拖慢渲染；
     * 界面上只留尾部若干行并标注「分享的是完整版」，完整堆栈始终通过分享出口流出。
     */
    const val DISPLAY_CRASH_STACK_LINES = 300

    /**
     * 分享时带几份崩溃报告。
     *
     * 只带最近几份：一份完整堆栈就有几十 KB，50 份（[GlobalExceptionHandler] 的上限）
     * 拼起来会超过很多分享渠道的附件上限，而大多数时候只需要最近那一次。
     */
    const val MAX_CRASH_REPORTS = 5

    /** 一次收集到的全部日志内容 */
    data class Report(
        /** 崩溃报告（文件名 → 内容），**新的在前** */
        val crashes: List<Pair<String, String>>,
        /** app.log 全文。文件不存在时为 null（不是空字符串——两者含义不同） */
        val appLog: String?,
        /** app.log 的磁盘路径，给用户看；拿不到则 null */
        val appLogPath: String?,
        /** 磁盘上一共有几份崩溃报告（界面显示可能只列了最近几份） */
        val crashFileCount: Int,
        /**
         * 设备状态快照（数据源健康度 / 汇率 / 价格缓存 / 最近一轮同步汇总）。
         * 空串 = 没采集到，头部不显示该段。默认空串保既有调用方与单测不变。
         */
        val deviceState: String = "",
        /**
         * 轮转后的全部日志文件，**新的在前**；每份内容已按 12MB 总上限裁剪。
         * 空 = 只有旧字段 [appLog]（兼容旧调用方与单测）。
         */
        val logFiles: List<LogFile> = emptyList(),
        /** 因 12MB 上限被省略的总字节数；0 = 没有截断 */
        val omittedLogBytes: Long = 0L
    )

    /**
     * 一行环境信息：App 版本 + 机型 + 系统。
     *
     * 诊断报告头部没有它，排障时连用户装的是哪个版本、什么机型系统都不知道，
     * 第一步就卡住（从前报告里只有生成时间和行数）。版本读取方式与
     * [GlobalExceptionHandler] 的 getAppVersion 一致（PackageManager，
     * 不用 BuildConfig——AGP 8 起不保证生成 VERSION_NAME）。
     *
     * 碰 Context / Build，**不能进 [composeReport]**（那个要保纯给单测用），
     * 由调用方取好转交。
     */
    fun environmentInfo(context: Context): String {
        // 复用 GlobalExceptionHandler.readAppVersionLabel：崩溃文件与诊断报告头部
        // 同源，避免两处各自写一遍 PackageManager 取值逻辑。
        val version = try {
            readAppVersionLabel(context) ?: "Unknown"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Unknown"
        }
        return "金库 $version · ${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})"
    }

    /**
     * 把日志从磁盘读出来。**会做文件 IO，必须在 Dispatchers.IO 上调用。**
     */
    fun collect(context: Context): Report {
        val crashFiles = GlobalExceptionHandler.crashReportsDir(context)
            .let { dir -> dir.listFiles()?.toList() ?: emptyList() }
            // 文件名是 crash_<定长时间戳>.log，字典序即时间序，倒序取最近几份
            .sortedByDescending { it.name }

        val crashes = crashFiles.take(MAX_CRASH_REPORTS).mapNotNull { file ->
            runCatching { file.name to file.readText() }.getOrNull()
        }

        val (logFiles, omittedBytes) = collectLogFiles()

        return Report(
            crashes = crashes,
            appLog = logFiles.firstOrNull()?.content,
            appLogPath = AppLogger.getLogFiles().firstOrNull()?.absolutePath,
            crashFileCount = crashFiles.size,
            deviceState = collectDeviceState(context),
            logFiles = logFiles,
            omittedLogBytes = omittedBytes
        )
    }

    /**
     * 读轮转后的全部日志，**新的在前**，总字节数不超过 [EXPORT_MAX_BYTES]。
     *
     * 超限时对边界文件只读尾部，并把被截掉的字节数带回去（报告里必须写明省略量，
     * 静默截断会让人误以为日志真的只有这些）。读文件有 IO，调用方在 IO 线程。
     */
    private fun collectLogFiles(): Pair<List<LogFile>, Long> {
        val files = AppLogger.getLogFiles()
        var budget = EXPORT_MAX_BYTES
        var omitted = 0L
        val result = ArrayList<LogFile>(files.size)
        for (file in files) {
            val length = runCatching { file.length() }.getOrDefault(0L)
            if (length <= 0L) continue
            if (budget <= 0L) {
                omitted += length
                continue
            }
            if (length <= budget) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                result += LogFile(file.name, text)
                budget -= length
            } else {
                // 只读尾部 budget 字节：低端机上避免把 32MB 全读进内存再拼字符串
                val text = runCatching { readTailBytes(file, budget) }.getOrNull() ?: continue
                result += LogFile(file.name, text, omittedBytes = length - budget)
                omitted += length - budget
                budget = 0L
            }
        }
        return result to omitted
    }

    /** 读取文件尾部至多 [maxBytes] 字节（可能从半行开始，属刻意的「最新优先」） */
    private fun readTailBytes(file: File, maxBytes: Long): String {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val skip = (raf.length() - maxBytes).coerceAtLeast(0L)
            raf.seek(skip)
            return raf.readUTF8(maxBytes)
        }
    }

    private fun java.io.RandomAccessFile.readUTF8(maxBytes: Long): String {
        val size = minOf(maxBytes, length() - filePointer).toInt().coerceAtLeast(0)
        val buffer = ByteArray(size)
        readFully(buffer)
        return String(buffer, Charsets.UTF_8)
    }

    /**
     * 采集一份设备状态快照，拼进诊断报告头部。
     *
     * 为什么放在报告里而不是只在线程日志里：用户分享一份报告过来，第一秒就能看到
     * 「哪个数据源是坏的、币安主机是否暂不可达（地区封锁/连不上）、汇率多久没刷新、
     * 价格缓存还有多少、最近一轮同步成了多少」——这几个问题覆盖了绝大多数"数据不对"
     * 的排查起点，不必先去几百行日志里 grep。币安那一行尤其关键：虚拟货币全空、全是
     * 跳过(币安熔断)时，它是「这用户被地区封锁了」的第一答案。
     *
     * 全部读进程内内存态（熔断器相位 / 汇率表 / 价格缓存 / 最近一轮汇总），
     * 不碰数据库不碰网络；[collect] 本就在 IO 线程上，成本可忽略。
     * **任何一项取不到都只跳过该项**，绝不因此让整份报告失败——快照是增强，不是关键路径。
     */
    private fun collectDeviceState(context: Context): String {
        val lines = mutableListOf<String>()

        runCatching {
            val status = com.example.stockdiary.data.price.StockDataManager.getInstance(context)
                .getDataSourceStatus()
            if (status.isNotEmpty()) {
                lines += "数据源: " + status.joinToString(" · ") { (name, ok) ->
                    "$name=${if (ok) "正常" else "熔断中"}"
                }
            }
        }

        // 币安主机级熔断状态：地区封锁 / 连不上的用户，这一行是「虚拟货币为啥全空」的第一答案。
        runCatching {
            lines += "币安主机: ${BinanceHostGate.statusText()}"
        }

        runCatching {
            val at = ExchangeRateManager.getLastRefreshTimeMillis()
            val atText = if (at > 0L) {
                java.time.Instant.ofEpochMilli(at)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime().toString().replace('T', ' ')
            } else {
                "从未刷新（用静态默认值）"
            }
            lines += "汇率: USD=${ExchangeRateManager.getRate("USD")} · 上次刷新=$atText"
        }

        runCatching {
            val stats = PriceCacheManager.getCacheStats()
            lines += "价格缓存: 共${stats.totalCount}条 / 有效${stats.validCount}条"
        }

        runCatching {
            AssetSyncManager.lastRoundSummaryForDiagnostics()?.let { lines += it }
        }

        // 最近一次体检的结论：用户说「数据源坏了」时，这一行能直接对上是他侧的封锁还是全局故障
        runCatching {
            DataSourceHealthChecker.lastHealthSummaryForDiagnostics()?.let {
                lines += "健康检查(最近一次): $it"
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * 拼出给人看的报告全文（**纯函数**，不碰磁盘）。
     *
     * 三条硬性要求，都是被现实教出来的：
     *
     * 1. **崩溃报告必须排在 app.log 前面**——用户打开它的目的九成是看崩溃，
     *    让他在几百行日志里翻找是纯粹的浪费。
     * 2. **app.log 只留尾部**，并且要**明确写出丢了多少行**。
     *    静默截断会让「崩溃前那几行」看起来像是真的没有。
     * 3. **什么都没有时不能给一个空页面**，要说清楚为什么没有——
     *    否则用户只会觉得这个功能也是坏的。
     *
     * @param envInfo 环境信息（版本/机型/系统），由调用方经 [environmentInfo] 取好转交；
     *   空串则头部不显示该行（纯函数不碰 BuildConfig/Build，这是保可测性的代价）
     * @param tailLines app.log 保留的行数；传 [Int.MAX_VALUE] 就是不截断（分享用）
     * @param crashMaxLines 单份崩溃报告在**界面**上保留的堆栈行数；
     *   传 [Int.MAX_VALUE] 就是不截断（分享用）。界面传一个较小值避免长堆栈
     *   拖垮 LazyColumn 渲染，分享出口始终传 [Int.MAX_VALUE] 保证完整。
     */
    internal fun composeReport(
        report: Report,
        envInfo: String = "",
        tailLines: Int = DISPLAY_TAIL_LINES,
        crashMaxLines: Int = Int.MAX_VALUE
    ): String {
        // 组件化拼装而不是一大段字符串模板：三段的存在与否相互独立，
        // 写成模板要处理 8 种组合，实际只有 3 段
        return buildList {
            add(headerOf(report, envInfo))
            crashesSectionOf(report, crashMaxLines)?.let { add(it) }
            add(appLogSectionOf(report, tailLines))
        }.joinToString("\n\n")
    }

    private fun headerOf(report: Report, envInfo: String): String = buildString {
        appendLine("=== 金库诊断报告 ===")
        appendLine("生成时间: ${java.time.LocalDateTime.now()}")
        // 环境信息：排障时第一件要确认的事就是「这是什么版本、什么机型」——
        // 从前报告里没有，每次都要来回问，现在报告自己说
        if (envInfo.isNotBlank()) appendLine("环境: $envInfo")
        appendLine("崩溃报告: ${report.crashFileCount} 份" + if (report.crashFileCount > 0) "（下面列出最近 ${report.crashes.size} 份）" else "")
        val files = report.logFiles.ifEmpty { report.appLog?.let { listOf(LogFile("app.log", it)) } ?: emptyList() }
        appendLine(
            "运行日志: " + if (files.isEmpty()) "无"
            else "${files.size} 份（新的在前），共 ${files.sumOf { it.content.lineCount }} 行"
        )
        if (report.omittedLogBytes > 0) {
            appendLine(
                "注意: 超过 ${EXPORT_MAX_BYTES / 1024 / 1024}MB 导出上限，已省略更旧内容 ${report.omittedLogBytes} 字节"
            )
        }
        report.appLogPath?.let { appendLine("日志路径: $it") }
        // 排障摘要放在设备状态快照之前：第一屏就给出「最近一轮发生了什么」，不必再从
        // 几 MB 日志里 grep + 手工算分类计数。界面展示与分享出口都走 composeReport，自动生效。
        val triageLog = report.logFiles.joinToString("\n") { it.content }
            .ifEmpty { report.appLog.orEmpty() }
        if (triageLog.isNotBlank()) {
            appendLine(buildTriageSummary(triageLog))
            appendLine()
        }
        // 设备状态快照：排障第一秒就能看到「哪个源坏了、汇率多久没刷、最近一轮成了多少」，
        // 不必先去几百行日志里 grep（见 [collectDeviceState]）
        if (report.deviceState.isNotBlank()) {
            appendLine("--- 设备状态快照 ---")
            appendLine(report.deviceState)
        }
    }

    private fun crashesSectionOf(report: Report, crashMaxLines: Int = Int.MAX_VALUE): String? {
        if (report.crashes.isEmpty()) return null
        return buildString {
            appendLine("--- 崩溃报告（新的在前）---")
            report.crashes.forEach { (name, content) ->
                appendLine()
                appendLine("########## $name ##########")
                append(truncateCrashForDisplay(content, crashMaxLines))
                appendLine()
            }
        }.trimEnd()
    }

    /**
     * 在**界面**上展示的单份崩溃报告：保留头部元数据（版本/机型/异常类，定位必看），
     * 只截断后面的堆栈为尾部 [crashMaxLines] 行，并明确标注「分享的是完整版」。
     *
     * 不截断（[crashMaxLines] = [Int.MAX_VALUE]）则原样返回——分享出口走这条路径。
     */
    private fun truncateCrashForDisplay(content: String, crashMaxLines: Int): String {
        if (crashMaxLines >= Int.MAX_VALUE) return content.trimEnd()
        val lines = content.split('\n')
        val sepIdx = lines.indexOfFirst { it.trim().startsWith("Stack Trace:") }
        // 没有 "Stack Trace:" 分隔（或本身就短）：原样返回，不做破坏性截断
        if (sepIdx < 0 || lines.size <= crashMaxLines) return content.trimEnd()

        val header = lines.take(sepIdx + 1) // 含 "Stack Trace:" 行
        val stack = lines.drop(sepIdx + 1).joinToString("\n")
        val (keptStack, dropped) = tail(stack, crashMaxLines)
        return buildString {
            append(header.joinToString("\n"))
            append('\n')
            append(keptStack.trimEnd())
            append("\n\n... 此崩溃报告的堆栈在界面上仅显示最后 $crashMaxLines 行（共省略 $dropped 行）；点「分享」出去的是完整堆栈 ...")
        }
    }

    private fun appLogSectionOf(report: Report, tailLines: Int): String = buildString {
        appendLine("--- 运行日志 ---")
        val files = report.logFiles.ifEmpty {
            report.appLog?.let { listOf(LogFile("app.log", it)) } ?: emptyList()
        }
        if (files.isEmpty()) {
            appendLine("（app.log 不存在）")
            appendLine()
            appendLine("可能的原因：")
            appendLine("1. 本次启动还没写够一批日志（AppLogger 每 2 秒 flush 一次，ERROR 级立即 flush）")
            appendLine("2. 上次崩溃后进程被立刻杀死，守护线程没来得及落盘")
            return@buildString
        }
        files.forEach { file ->
            appendLine()
            appendLine("########## ${file.name} ##########")
            if (file.omittedBytes > 0) {
                appendLine("（该文件超过导出上限，仅保留最新部分，省略 ${file.omittedBytes} 字节）")
            }
            val total = file.content.lineCount
            if (file.content.isBlank()) {
                appendLine("（该文件是空的）")
                return@forEach
            }
            val (shown, dropped) = tail(file.content, tailLines)
            if (dropped > 0) {
                appendLine(
                    "（全文 $total 行，这里只显示最后 $tailLines 行，前面 $dropped 行已省略；" +
                        "分享出去的是未按行截断的内容，但仍受 12MB 上限）"
                )
            }
            append(shown.trimEnd())
            appendLine()
        }
    }

    /**
     * 一轮同步在日志里留下的痕迹。
     *
     * [lines] 该轮相关行数；[failures]/[skips] 其中「失败/跳过」行数。
     * 诊断界面先给出索引，用户点某一轮再过滤，比在几千行里翻找快得多。
     */
    data class RoundIndexEntry(
        val roundId: Int,
        val lines: Int,
        val failures: Int,
        val skips: Int
    )

    /**
     * 从日志文本构建轮次索引（**纯函数**）。按轮次号升序。
     *
     * 只认统一前缀 `[轮次#N]`；非同步路径的 `#0` 同样列出，便于确认「前台查价也在刷日志」。
     */
    fun buildRoundIndex(logText: String): List<RoundIndexEntry> {
        val counts = sortedMapOf<Int, IntArray>()
        logText.lineSequence().forEach { line ->
            val id = ROUND_ID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val c = counts.getOrPut(id) { intArrayOf(0, 0, 0) }
            c[0]++
            if (line.contains("] 失败:")) c[1]++
            if (line.contains("] 跳过:")) c[2]++
        }
        return counts.map { (id, c) -> RoundIndexEntry(id, c[0], c[1], c[2]) }
    }

    /**
     * 按级别 / 关键词 / 轮次过滤日志行（**纯函数**）。
     *
     * @param levels 级别集合（DEBUG/INFO/WARN/ERROR/API/DB）；空集合 = 不按级别过滤
     * @param keyword 关键词（忽略大小写，子串匹配）；null/空白 = 不过滤
     * @param roundId 只看某一轮；null = 全部
     * @param maxLines 过滤后仍最多显示多少行（保留尾部，保最新）
     */
    fun filterLog(
        text: String,
        levels: Set<String> = emptySet(),
        keyword: String? = null,
        roundId: Int? = null,
        maxLines: Int = DISPLAY_TAIL_LINES
    ): List<String> {
        val kw = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val filtered = text.lineSequence().filter { line ->
            (levels.isEmpty() || levels.any { lv -> line.contains("[$lv/") }) &&
                (kw == null || line.contains(kw, ignoreCase = true)) &&
                (roundId == null ||
                    ROUND_ID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() == roundId)
        }.toList()
        return if (maxLines <= 0 || filtered.size <= maxLines) filtered else filtered.takeLast(maxLines)
    }

    private val ROUND_ID_REGEX = Regex("\\[轮次#(\\d+)]")

    // ======================================================================
    // 排障摘要（2026-11-13）：把「每次都要 grep 几 MB 日志手工还原」的事实写进报告头部
    // ======================================================================

    /**
     * 为什么需要这一段：真机诊断动辄 3~4MB / 3 万行，而判断一次刷新是否健康所需的
     * 事实（真实 514 次数、未取到里混着退避短路/预算顺延、最近一次手动刷新时间、
     * 哪些链路根本没被测到）全埋在逐标的 WARN 里，且这些 WARN 会被 app.log 轮转冲掉。
     * 排查者只能靠正则捞 + 手工做加法还原（例如 6 次真 514 + 48 行退避短路 = 未取到 54），
     * 既慢又容易把「未取到」误读成「修复变差」。
     *
     * 设计三条硬原则：
     * 1. **准确值只取代码内计数**：分红预取/价格同步的汇总行是 Repository 用 AtomicInteger
     *    计出来的，可信；逐标的 WARN 行数只作为「整份日志可见下界」单独标注，不冒充精确值。
     * 2. **缺失 ≠ 0**：日志格式变更或本份日志不含刷新轮次时写「未解析到」，绝不写 0
     *    —— 0 会被误读成「514 已归零、修复成功」，那是最危险的误判。
     * 3. **纯函数**：不碰 Context / 磁盘 / 时钟，输入只有日志文本，可直接 JVM 单测。
     *
     * 报告头部已有 [environmentInfo] 给出版本/机型，这里补出「这轮数据来自哪次刷新」，
     * 两者合起来才能判断「这份诊断是不是装了新包之后刷出来的」。
     */
    private val LOG_TS_REGEX = Regex("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})]")
    private const val MARK_PRICE_SUMMARY = "=== 同步轮次 "
    private const val MARK_DIVIDEND_SUMMARY = "[分红预取轮次#"
    private const val MARK_MANUAL_REFRESH = "价格同步开始 [轮次#"

    /** 取一行日志的时间戳（ISO 形态，字典序即时间序）；非标准行返回 null。 */
    private fun tsOf(line: String): String? = LOG_TS_REGEX.find(line)?.groupValues?.get(1)

    /**
     * 取含 [mark] 的**时间最新**一行。
     *
     * 不能直接 `last{}`：轮转日志 `logFiles` 是「新的文件在前」，但每个文件内部是时间
     * 正序，简单拼接后「最后一行」未必最新。按时间戳比较才不受文件顺序影响。
     * 同一时间戳取后出现者（`>=`），让汇总行盖过同毫秒的开始/结束行。
     */
    private fun latestLine(lines: List<String>, mark: String, require: String? = null): Pair<String, String>? {
        var bestTs = ""
        var bestLine: String? = null
        for (line in lines) {
            if (!line.contains(mark)) continue
            if (require != null && !line.contains(require)) continue
            val ts = tsOf(line) ?: continue
            if (ts >= bestTs) {
                bestTs = ts
                bestLine = line
            }
        }
        return bestLine?.let { bestTs to it }
    }

    /** 取数字指标。解析不到必须写「未解析到」——写 0 会被误读成「修复成功」。 */
    private fun metric(line: String?, pattern: String, label: String): String {
        val value = line?.let { Regex(pattern).find(it)?.groupValues?.get(1) }
        return "$label=${value ?: "未解析到"}"
    }

    private fun countLines(lines: List<String>, vararg marks: String): Int =
        lines.count { line -> marks.any { line.contains(it) } }

    /** 生成排障摘要（纯函数，见上方设计说明）。空日志也给出明确说明，不给空页面。 */
    internal fun buildTriageSummary(logText: String): String {
        val lines = if (logText.isBlank()) emptyList() else logText.lineSequence().toList()
        return buildString {
            appendLine("--- 排障摘要（自动解析，供排查用；「未解析到」≠ 0）---")
            if (lines.isEmpty()) {
                appendLine("（app.log 为空或不存在，无法解析）")
                return@buildString
            }
            val timestamps = lines.mapNotNull { tsOf(it) }
            appendLine(
                "日志覆盖: ${timestamps.minOrNull() ?: "未解析到"} ~ ${timestamps.maxOrNull() ?: "未解析到"}"
            )
            appendLine(
                "最近一次手动刷新: ${latestLine(lines, MARK_MANUAL_REFRESH)?.first ?: "未解析到（本份日志可能不含手动刷新）"}"
            )

            val price = latestLine(lines, MARK_PRICE_SUMMARY)
            appendLine(
                "最近一轮价格同步 [${price?.first ?: "未解析到"}]: " + listOf(
                    metric(price?.second, "资产 (\\d+)", "资产"),
                    metric(price?.second, "成功 (\\d+)", "成功"),
                    metric(price?.second, "失败 (\\d+)", "失败"),
                    metric(price?.second, "跳过 (\\d+)", "跳过"),
                    metric(price?.second, "总耗时 ([\\d.]+)s", "耗时_s")
                ).joinToString(" ")
            )

            val dividend = latestLine(lines, MARK_DIVIDEND_SUMMARY, require = "汇总")
            appendLine(
                "最近一轮分红预取 [${dividend?.first ?: "未解析到"}]: " + listOf(
                    metric(dividend?.second, "参与 (\\d+) 只", "参与"),
                    metric(dividend?.second, "本轮发起抓取 (\\d+) 只", "发起"),
                    metric(dividend?.second, "源应答并写下结论 (\\d+) 只", "源应答"),
                    metric(dividend?.second, "未取到 (\\d+) 只", "未取到"),
                    metric(dividend?.second, "缓存或TTL有效无需重抓 (\\d+) 只", "TTL豁免")
                ).joinToString(" ")
            )
            val realRateLimit = dividend?.second
                ?.let { Regex("本轮真实 514/429 响应 (\\d+) 次").find(it)?.groupValues?.get(1) }
            appendLine(
                "★ 本轮真实 514/429 响应: ${realRateLimit ?: "未解析到（不可当作 0）"} 次" +
                    "  ← 判定 514 修复是否生效只看这一项；「未取到」含退避短路/预算顺延，不代表变差"
            )
            appendLine("口径: 上面各项来自代码内计数（准确）；下面逐标的 WARN 行数因 app.log 轮转只是下界。")
            appendLine(
                "日志可见计数（整份日志累计，下界）: 限流退避短路=" +
                    countLines(lines, "天天基金限流退避中，跳过") +
                    " / 514请求失败=" + countLines(lines, "OkHttp HTTP 请求失败: 514") +
                    " / 预算顺延=" + countLines(lines, "分红预取达到时间预算")
            )
            val brokerage = countLines(lines, "ImportBrokerage", "交割单")
            val binance = countLines(lines, "BinanceImport", "币安导入")
            appendLine(
                "链路覆盖: 交割单/ImportBrokerage=$brokerage 行" +
                    (if (brokerage == 0) " ⚠️本次导出未覆盖" else "") +
                    " / 币安导入=$binance 行" +
                    (if (binance == 0) " ⚠️本次导出未覆盖" else "")
            )
        }.trimEnd()
    }

    /**
     * 用 [String.lineCount] 先数再切：直接 `lines().takeLast` 会先把整段切成
     * 几千个字符串对象，而这里经常要处理 5000 行的日志。
     */
    internal fun tail(text: String, keep: Int): Pair<String, Int> {
        if (keep <= 0) return "" to text.lineCount
        val total = text.lineCount
        if (total <= keep) return text to 0

        // 要往前跳过几个 '\n'。**末尾那个换行符不能算作分隔符**：
        // "a\nb\nc\n" 的换行在行尾而不在行前，把它当成一行分隔符数进去，
        // 保留 2 行会只得到 "c\n"——每遇一次末尾换行就少一行，日志越长偏得越多。
        val needed = if (text.endsWith('\n')) keep + 1 else keep

        var index = text.length
        var found = 0
        while (index > 0 && found < needed) {
            index--
            if (text[index] == '\n') found++
        }
        // 停在第 needed 个 '\n' 之后，正好是保留部分的开头
        return text.substring(index + 1) to (total - keep)
    }

    /** 行数。末尾没有换行符时最后一行也要算进去。 */
    private val String.lineCount: Int
        get() {
            if (isEmpty()) return 0
            var count = 1
            for (ch in this) if (ch == '\n') count++
            return if (endsWith('\n')) count - 1 else count
        }

    /**
     * 把报告写成文件并返回一个可分享的 [Uri]。
     *
     * 落在 `cache/exports/` 是刻意的：`file_paths.xml` 只对外暴露了这一个目录，
     * 复用它就不用给 FileProvider 开新的路径（少一处"顺手把整个 files 目录共享出去"的风险）。
     */
    fun export(context: Context, report: Report): Uri? {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "goldvault_diagnostics_${System.currentTimeMillis().toFileTimestamp()}.txt")
        // 分享用**全文**，不截断：界面上截是为了不卡，给别人看时少一行都可能少一条线索
        file.writeText(composeReport(report, envInfo = environmentInfo(context), tailLines = Int.MAX_VALUE))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            AppLogger.e("导出诊断报告失败: ${e.message}", e, "LogDiagnostics")
            null
        }
    }

    /** 系统分享。走 chooser，用户自己挑微信/邮件/蓝牙。 */
    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 除了 EXTRA_STREAM 还要带 ClipData：部分接收端（旧版微信/邮件客户端）
            // 只读 ClipData，不设的话附件会静默丢失、只发出去一条空文本。
            // 导出 CSV 的分享同样踩过这个坑，两处口径保持一致。
            clipData = ClipData.newUri(context.contentResolver, "诊断报告", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享诊断报告（含个人资产信息，请谨慎）"))
    }

    /**
     * 清空日志与崩溃报告。
     *
     * 只清这两样，不动 SharedPreferences 里的崩溃状态——
     * 那个是下次启动要不要弹恢复框的依据，清了会让用户以为崩溃从没发生过。
     */
    fun clear(context: Context) {
        AppLogger.clearLog()
        runCatching {
            GlobalExceptionHandler.crashReportsDir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
