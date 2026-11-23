package com.example.stockdiary.data.export

import com.example.stockdiary.common.DateFormats
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.stockdiary.common.Result
import com.example.stockdiary.data.calculator.SnapshotAnalyzer
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.database.perTenSharesToAmount
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.CRYPTO_DECIMALS
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.MONEY_DIGITS
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.pricePrecisionFor
import com.example.stockdiary.utils.quantityPrecisionFor
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.sumDebtValueCny
import com.example.stockdiary.utils.excludingDebts
import com.example.stockdiary.utils.debts
import com.example.stockdiary.utils.toFileTimestamp
import com.example.stockdiary.utils.totalValueCny
import java.io.File
import java.io.StringWriter
import java.io.Writer
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Locale

/**
 * 诊断报告导出文件的文件名前缀。
 *
 * 与 [com.example.stockdiary.utils.LogDiagnostics.export] 共用同一个常量，
 * 保证「清理分组识别到的诊断文件」与「诊断实际写出的文件名」不会各自漂移。
 */
internal const val DIAGNOSTICS_FILE_PREFIX = "goldvault_diagnostics_"

/** 原子写的临时文件后缀。启动清理残留时只认这个后缀，避免误删正常导出。 */
internal const val EXPORT_TEMP_SUFFIX = ".tmp"

/**
 * 导出文件的用途分组。
 *
 * 缓存目录里的导出文件必须**分组**保留：从前不分扩展名只留最近 5 个，
 * 用户一次导出多份 CSV 就会把刚分享出去的诊断报告挤掉——而诊断报告正是
 * 排障唯一线索，丢它比丢 CSV 严重得多。诊断单独成组、独立保底。
 */
internal enum class ExportFileGroup {
    DIAGNOSTICS, CSV, TEXT, OTHER
}

/**
 * 按文件名判定导出文件用途（**纯函数**，可直接单测）。
 *
 * 只认两种稳定特征：诊断前缀 + 扩展名。前缀用 [DIAGNOSTICS_FILE_PREFIX]，
 * 与诊断实际写文件名同源；CSV / 文本按扩展名，避免依赖具体前缀命名。
 */
internal fun classifyExportFile(name: String): ExportFileGroup = when {
    name.startsWith(DIAGNOSTICS_FILE_PREFIX, ignoreCase = true) &&
        name.endsWith(".txt", ignoreCase = true) -> ExportFileGroup.DIAGNOSTICS
    name.endsWith(".csv", ignoreCase = true) -> ExportFileGroup.CSV
    name.endsWith(".txt", ignoreCase = true) -> ExportFileGroup.TEXT
    else -> ExportFileGroup.OTHER
}

/**
 * 计算需要清理的旧文件（**纯函数**，不删文件、不碰 Context，便于单测）。
 *
 * 分组规则：
 * - 诊断、文本、其它按用途分组；
 * - **CSV 再按导出类型前缀细分**（assets / snapshots / dividends / statement / ...），
 *   避免依次导出 6 类 CSV 时互相挤掉最早那份。
 *
 * 每组各自只保留修改时间最新的 [keepPerGroup] 份；[keepPerGroup] = 0 表示清空所有组
 * （供「清除所有数据」调用）。
 */
internal fun filesToPrune(files: List<File>, keepPerGroup: Int = DataExporter.MAX_EXPORT_FILES): List<File> =
    files.groupBy { exportRetentionGroup(it.name) }
        .values
        .flatMap { group ->
            if (group.size <= keepPerGroup) emptyList()
            else group.sortedByDescending { it.lastModified() }.drop(keepPerGroup)
        }

/**
 * 保留策略的分组键。
 *
 * CSV 用「导出类型」区分（文件名形如 `assets_20261119_101500.csv`）；同类型仍共享配额，
 * 不同类型的导出互不影响。诊断/文本/其它保持整组共享。
 *
 * 类型名优先按导出时追加的 `yyyyMMdd_HHmmss` 时间戳剥离；没有时间戳的旧命名
 * （如 `assets_1.csv`）退回「第一个下划线前」推断，避免把旧文件归成一组。
 */
internal fun exportRetentionGroup(name: String): String {
    val group = classifyExportFile(name)
    if (group != ExportFileGroup.CSV) return group.name
    val stem = name.substringBeforeLast('.', name)
    val withoutTimestamp = stem.replace(CSV_TIMESTAMP_SUFFIX, "")
    val prefix = if (withoutTimestamp != stem) {
        // 标准命名（含时间戳）：`full_statement_20261119_101500` → `full_statement`
        withoutTimestamp
    } else {
        // 旧命名没有时间戳：只能按第一段推断类型
        stem.substringBefore('_').ifBlank { stem }
    }
    return "${group.name}:$prefix"
}

/**
 * 导出文件名末尾的时间戳（与 `toFileTimestamp()` 的 pattern 一致），可带毫秒段与去重序号。
 *
 * 毫秒段与序号段都写成 `\d+`（**不锁 3 位/不锁单段**）：[exportFileName] 的序号是
 * `_2`、`_3`… 无限递增的，锁 `_\d{3}` 会从第 1000 次撞名起整段剥不掉，
 * `full_statement_…_1000.csv` 就会被 [exportRetentionGroup] 退回「第一段」推断成
 * `CSV:full`——分组一变，保留配额与其它导出类型互相挤占（QA 已实测复现）。
 * 宁可放宽匹配，也不要让文件名长度去约束解析。
 */
private val CSV_TIMESTAMP_SUFFIX = Regex("_\\d{8}_\\d{6}(?:_\\d+(?:_\\d+)*)?$")

/**
 * 生成**不会互相覆盖**的导出文件名（纯函数，`exists` 由调用方注入，便于单测）。
 *
 * 旧命名只精确到秒（`yyyyMMdd_HHmmss`）。同一秒内连发两次导出（批量「全选导出」、
 * 手快连点）会算出同一个绝对路径：第二次写入覆盖第一次的文件，而第一次已经
 * 分享出去的 URI 指向的**还是同一个路径**——用户点开看到的是第二次的内容。
 * 所以文件名在秒级时间戳后再追加 3 位毫秒；极端到同一毫秒仍撞名（理论上可能，
 * 例如批量循环里连续调用）时再递增序号。
 *
 * 毫秒段与序号段都在 [CSV_TIMESTAMP_SUFFIX] 的剥离范围内，[exportRetentionGroup]
 * 仍能把它们归到同一个导出类型分组，保留策略不受影响。
 *
 * 已知取舍：`exists` 判空与真正落盘之间不是原子的，多线程并发导出同一类型理论上
 * 仍可能撞名；导出是用户主动触发的低频操作、且 [DataExporter] 内无并发入口，
 * 这里不为它引入锁。
 *
 * @param prefix 导出类型前缀（`assets` / `full_statement` 等）
 * @param extension 不带点的扩展名（`csv` / `txt`）
 * @param timestampMillis 导出时刻，注入以便单测
 * @param exists 判断同目录下该文件名是否已存在
 */
internal fun exportFileName(
    prefix: String,
    extension: String,
    timestampMillis: Long,
    exists: (String) -> Boolean = { false }
): String {
    val stamp = timestampMillis.toFileTimestamp()
    val millis = (timestampMillis % 1000L).toString().padStart(3, '0')
    val base = "${prefix}_${stamp}_$millis"
    var name = "$base.$extension"
    var seq = 2
    while (exists(name)) {
        name = "${base}_$seq.$extension"
        seq++
    }
    return name
}

/**
 * 把 [text] **原子地**写入 [target]：先写同目录随机命名的 `.tmp`，成功后用
 * [File.renameTo] 覆盖目标（Linux/Android 上是原子替换）。
 *
 * 正常路径**不会先删目标**：任何时刻目标要么是新的完整版，要么还是旧的完整版，
 * 进程被杀也不会让文件消失。只有 rename 在个别文件系统上不支持覆盖时，才删旧目标
 * 后重试一次；再失败则退回直接写，保住这次导出。
 *
 * [rename] / [moveAside] 可注入，仅用于单测构造 rename / 挪走旧文件失败分支。
 *
 * @return true 表示通过原子 rename 落盘；false 表示走了删后重试或直接写兜底。
 */
internal fun writeAtomically(
    target: File,
    text: String,
    rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    moveAside: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
): Boolean {
    val parent = target.parentFile ?: error("导出目标没有父目录: $target")
    // 随机后缀：同一秒内两次同前缀导出不会争用同一个 .tmp
    val tmp = File.createTempFile("${target.name}.", EXPORT_TEMP_SUFFIX, parent)
    return try {
        tmp.writeText(text)
        if (rename(tmp, target)) return true

        // rename 不支持覆盖已存在目标（部分文件系统）：先把旧目标**改名**成 .bak，
        // 成功替换就删 .bak，失败就还原。
        //
        // 从前这里是「先删旧目标、再 rename」——一旦 rename 与随后的直接写都失败，
        // 用户上一份导出（可能就是唯一的诊断报告）就凭空消失了，而且删的正是排障线索。
        if (target.exists()) {
            val backup = File(parent, "${target.name}.bak")
            backup.delete()
            if (moveAside(target, backup)) {
                if (rename(tmp, target)) {
                    backup.delete()
                    return false
                }
                // 替换仍失败：把旧内容还原回去。还原也失败就保留 .bak 不删——
                // 宁可留两个文件，也不能让旧内容消失。
                if (!moveAside(backup, target)) {
                    AppLogger.w("导出原子替换失败且旧文件未能还原，旧内容保留在 ${backup.name}", "DataExporter")
                }
            }
            // 连改名都做不到（目标被占用 / 权限不足）：不主动删旧文件，
            // 退到下面的直接覆盖写——写坏了也比「先删掉、再写失败」强。
        }

        // 仍失败（权限/占用）：退回直接写，别丢这次导出，再清 tmp
        target.writeText(text)
        tmp.delete()
        false
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}

/**
 * 导出文件生成器。
 *
 * 分两层：
 * - [CsvContent] 只负责「把数据拼成文件内容」，纯函数、无 Android 依赖，可直接单测；
 * - 本类负责落盘、FileProvider URI、清理旧文件。
 *
 * 所有导出方法返回 [Result]：失败时把原因一路带到界面，而不是只回一个 null
 * 让设置页干巴巴地显示「导出失败」。
 */
class DataExporter(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DataExporter? = null

        /** 导出/诊断文件最多保留份数：缓存目录只增不删会一直堆着历史文件 */
        internal const val MAX_EXPORT_FILES = 5

        fun getInstance(context: Context): DataExporter {
            return instance ?: synchronized(this) {
                instance ?: DataExporter(context.applicationContext).also { instance = it }
            }
        }
    }

    private val exportDir: File
        get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * 建一个**带 UTF-8 BOM** 的内存写入器，供所有 CSV / 文本导出构建内容。
     *
     * 不能再用 `FileWriter(file)`：它走平台默认字符集且**不输出 BOM**，
     * Windows 中文版 Excel 打开无 BOM 的 CSV 会按 GBK/ANSI 解码，
     * 表头「名称,类型,代码,账户…」与全部中文资产名/备注直接变乱码 ——
     * 而导出按钮的目的就是给表格软件打开，等于该功能实际不可用。
     * 显式 UTF-8 BOM 后 Excel 能正确识别编码。
     *
     * 内容先在内存拼好，再交给 [writeAtomically] 原子落盘：这样写失败也不会
     * 留下半截目标文件（原来 `outputStream()` 直接写目标是 R03 的根因）。
     * 中文资产名 + 长交易流水通常在几百 KB 量级，内存拼接无 OOM 风险。
     */
    private fun newBomWriter(): Writer {
        val writer = StringWriter()
        writer.write('\uFEFF'.toString()) // UTF-8 BOM
        return writer
    }

    fun exportAssetsToCSV(assets: List<Asset>): Result<Uri> =
        exportFile("assets", "csv") { it.append(CsvContent.assets(assets)) }

    fun exportSnapshotsToCSV(snapshots: List<AssetSnapshot>): Result<Uri> =
        exportFile("snapshots", "csv") { it.append(CsvContent.snapshots(snapshots)) }

    fun exportDividendsToCSV(dividends: List<Dividend>, assets: List<Asset>): Result<Uri> =
        exportFile("dividends", "csv") { it.append(CsvContent.dividends(dividends, assets)) }

    fun exportTransactionsToCSV(assets: List<Asset>, transactions: List<Transaction>): Result<Uri> =
        exportFile("statement", "csv") { it.append(CsvContent.transactions(assets, transactions)) }

    /**
     * 导出综合对账单（资产 + 交易 + 快照 + 分红）。
     *
     * 旧实现把四段拼在一个文件里、每段列数不同，Excel 打开是一张错位表，
     * 程序化解析（pandas 等）会直接报列数不齐。现在统一成**一张长表**：
     * 首列「区块」区分 资产 / 交易 / 快照 / 分红 / 汇总，列定义固定 13 列，
     * 不适用的单元格留空。这样既能当人类可读的对账单，也能被表格软件筛选、透视。
     */
    fun exportStatementCSV(
        assets: List<Asset>,
        transactions: List<Transaction>,
        snapshots: List<AssetSnapshot>,
        dividends: List<Dividend>
    ): Result<Uri> =
        exportFile("full_statement", "csv") {
            it.append(CsvContent.statement(assets, transactions, snapshots, dividends))
        }

    fun exportToText(assets: List<Asset>): Result<Uri> =
        exportFile("asset_report", "txt") { it.append(CsvContent.textReport(assets)) }

    /**
     * 落盘并返回可分享的 content URI。
     *
     * 导出前先清理旧文件：缓存目录里的历史导出没人删，攒久了白占空间；
     * 保留最近 [MAX_EXPORT_FILES] 份，兼顾「刚分享出去的那份还在」。
     */
    private fun exportFile(prefix: String, extension: String, build: (Writer) -> Unit): Result<Uri> {
        return try {
            pruneOldExports()
            val dir = exportDir
            val file = File(dir, exportFileName(prefix, extension, System.currentTimeMillis()) { File(dir, it).exists() })
            val writer = newBomWriter()
            writer.use(build)
            writeAtomically(file, writer.toString())
            Result.success(getUriForFile(file))
        } catch (e: Exception) {
            AppLogger.e("导出失败($prefix): ${e.message}", e, "DataExporter")
            Result.failure(Exception("导出失败：${e.message ?: "未知错误"}"))
        }
    }

    /**
     * 清空所有已导出的文件（「清除所有数据」调用）。
     *
     * 这些 CSV 里是完整的持仓/交易明细，只清数据库和偏好、留着文件，
     * 等于用户以为删干净了、账目还躺在 cacheDir 里。
     */
    fun clearExportedFiles() {
        pruneOldExports(keep = 0)
    }

    /**
     * 触发一次常规清理（按用途分组保留最近若干份）。
     *
     * 供诊断分享等**其它写 exports 的路径**在写完后调用：清理逻辑只留在本类，
     * 不让第二处也各写一套删文件规则，否则两边会互相删对方的产物。
     */
    fun pruneExports() {
        pruneOldExports()
    }

    /**
     * 清掉上次进程被杀留下的 `.tmp` 残件（启动时调用）。
     *
     * 正常路径 tmp 会被 rename 走或显式删除；只有写盘中途被杀才会留下。
     * 这些文件名带随机后缀，不在保留策略的识别范围里，不主动扫就会一直占空间。
     */
    fun cleanupStaleTempExports() {
        try {
            // 不用 exportDir（它带 mkdirs）：从没导出过就不必凭空建目录
            val dir = File(context.cacheDir, "exports")
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(EXPORT_TEMP_SUFFIX)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            AppLogger.w("清理残留临时导出文件失败: ${e.message}", "DataExporter")
        }
    }

    /**
     * 按用途分组清理旧文件：每组各自保留最近 [keep] 份。
     *
     * 关键在「分组」——诊断报告单独成组，哪怕只导出过 1 份，也不会被一次
     * 批量 CSV 导出挤掉。原来不分扩展名、全局只留 5 份，是 R02 的根因。
     */
    private fun pruneOldExports(keep: Int = MAX_EXPORT_FILES) {
        try {
            val files = exportDir.listFiles()?.filter { it.isFile } ?: return
            filesToPrune(files, keep).forEach { it.delete() }
        } catch (e: Exception) {
            AppLogger.w("清理旧导出文件失败: ${e.message}", "DataExporter")
        }
    }

    private fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}

/**
 * 纯十进制数字（含正负号）。用于把「负数列」从公式注入防护里排除出去：
 * `-12.5`（浮亏、负数量）是合法数值，前置 `'` 会被 Excel/pandas 当文本，
 * 列就再也算不了了。CSV 里本 App 从不给数字补 `+`/千分位，故这个正则够用。
 */
private val PLAIN_DECIMAL = Regex("^[+-]?\\d+(\\.\\d+)?$")

/**
 * 公式引导符（OWASP CSV Injection）：`=` `+` `-` `@` 之外**必须**包含 TAB(0x09) 与 CR(0x0D)。
 *
 * 这两个最容易漏：表格软件解析单元格时会**跳过前导 TAB / CR 再判公式**，于是
 * `\t=SUM(A1)` 与 `=SUM(A1)` 等价。只挡四个符号等于给攻击者留一个字符的绕过空间
 * （payload 前加一个 TAB 即可），而且这种字符肉眼几乎看不见，事后审查也难发现。
 *
 * **承诺范围（只到这里）**：本集合保证「TAB / CR 开头的单元格会被防护」，有测试钉住，
 * 也只有这两个。LF(0x0A)、普通空格(0x20)、NBSP(0xA0) **未纳入**，并且**不**在这里断言
 * 「它们在外部表格软件里不构成绕过」—— 那是对第三方软件行为的猜测，本仓库既无法验证
 * 也没有测试能钉住，写进来就是「注释比实现强」。
 *
 * 真正挡住 LF 的是 [escapeCsv] 的第二道防线：含 `\n` 的字段一律被双引号包裹，
 * `\n=SUM(A1)` 落盘是 `"\n=SUM(A1)"`，`=` 根本不在单元格首位（`DataExporterCsvTest`
 * 有对应断言）。空格 / NBSP 那侧**确实没有**第二道防线：若哪天确认某个表格软件会把
 * 它们当前导空白裁掉，就得把它们加进这个集合，别只靠注释说「已经覆盖」。
 *
 * 也不宜图省事把「所有空白」塞进集合：宽空白集会把 `" -12.5"` 这类带缩进的合法数值
 * 也当成公式候选，多前置一个 `'`，正常账目反而算不了。
 */
private const val FORMULA_LEAD_CHARS = "=+-@\t\r"

/**
 * 统一 CSV 字段转义。
 *
 * 1. **公式注入防护**：以 [FORMULA_LEAD_CHARS]（`=` `+` `-` `@` `TAB` `CR`）开头的单元格
 *    会被 Excel/WPS 当公式求值（资产名存成 `=HYPERLINK(...)` 就能在别人机器上拉外链、
 *    `=cmd|...` 更糟）。对这类**非纯数字**字段前置一个单引号，让表格软件按文本处理。
 * 2. 含引号、逗号、TAB、回车或换行时用双引号包裹，内部引号转义为两个连续引号。
 *    TAB 若不进引号触发集，`\t=SUM(A1)` 会**无引号**落盘，某些解析器读到的是
 *    未加保护的原始串（RFC 4180 允许 TAB 裸奔，但 Excel 的宽容解析反而更危险）。
 *
 * 已知取舍：前置的 `'` 会随文件进入数据本身，程序化解析（pandas 等）拿到的是
 * `'=SUM(A1)`。这是 OWASP 推荐做法，代价是「恶意/异常文本多一个前导引号」，
 * 比「打开就知道执行了公式」便宜得多；纯数字（负号开头）已排除，正常账目不受影响。
 */
internal fun escapeCsv(field: String): String {
    val guarded = if (needsFormulaGuard(field)) "'$field" else field
    val needsQuoting = guarded.any {
        it == '"' || it == ',' || it == '\n' || it == '\r' || it == '\t'
    }
    return if (needsQuoting) {
        "\"" + guarded.replace("\"", "\"\"") + "\""
    } else {
        guarded
    }
}

/** 是否需要用前置 `'` 挡公式求值：首字符是公式引导符、且不是纯十进制数字。 */
private fun needsFormulaGuard(field: String): Boolean {
    val first = field.firstOrNull() ?: return false
    if (first !in FORMULA_LEAD_CHARS) return false
    return !PLAIN_DECIMAL.matches(field)
}

/** 拼一行 CSV：每个单元格都过一遍 [escapeCsv]，避免调用方漏转义。 */
internal fun csvRow(vararg cells: String): String = cells.joinToString(",") { escapeCsv(it) }

/**
 * 导出用的数字格式：**固定用 `.` 作小数点**。
 *
 * 界面上的 `format()` 小数分隔符跟随系统 FORMAT locale；在逗号作小数点的语言环境
 * （德语、法语等）下会输出 `1234,56`，写进 CSV 直接多切一列。导出是机器可读格式，
 * 必须与界面解耦。
 */
internal fun csvNumber(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

/**
 * 导出用的「可反算」数字：最多 [digits] 位小数、去掉尾随 0（例如 `100`、`0.4`、`12.3456`）。
 *
 * 与 [csvNumber] 的固定位数不同，这里不补零——列宽不重要，用户要拿数量 × 单价复算市值。
 * 唯一例外：**非 0 值被舍成 0 时退回原值**，正负一视同仁：
 * `0.00001` 不会写成 `0`，`-0.00001` 也不会被写成 `0`（旧实现把 `-0` 直接并进「返回 0」，
 * 负微小值被静默吞掉，与正数不对称、也与本 KDoc 矛盾）。否则又会出现
 * 「数量 0、市值有钱」或「-0.00001 股凭空消失」的自相矛盾行。
 */
private fun csvExportNumber(value: Double, digits: Int): String {
    val fixed = String.format(Locale.US, "%.${digits}f", value)
    // 只有含小数点时才 trim 尾随 0：`digits == 0` 时输出的是纯整数（`1000`），
    // 直接 trimEnd('0').trimEnd('.') 会削成 `1` —— 数量差一个数量级（与 formatTrim 同款坑）。
    val trimmed = if (fixed.indexOf('.') < 0) fixed else fixed.trimEnd('0').trimEnd('.')
    // 只有真正的 0（含 -0.0，IEEE 下 -0.0 == 0.0）或空串才写 "0"。
    // 负数被舍成 "-0" 时必须继续往下走「退回原值」分支，不能提前吞成 "0"。
    if (value == 0.0 || trimmed.isEmpty()) return "0"
    if (trimmed.toDoubleOrNull() == 0.0 && value != 0.0) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
    return trimmed
}

/**
 * 把交易行关联回资产，供币种折算/数量精度/账户列使用。
 *
 * 旧实现 `assets.associateBy { it.symbol }` 有两个静默错误：
 * 1. 同一代码在多个账户（多账户是 App 的核心能力）时后者覆盖前者，账户与人民币金额张冠李戴；
 * 2. 资产已删除时查不到，币种回退 "CNY"，美元交易被 1:1 当人民币汇总。
 *
 * 现在显式区分「唯一命中 / 多账户同名 / 找不到」，找不到或多账户无法确定币种时，
 * 人民币列留空而不是猜。
 */
internal class AssetLookup(assets: List<Asset>) {

    private val byKey = HashMap<String, MutableList<Asset>>()

    init {
        assets.forEach { asset ->
            val symbol = asset.symbol ?: return@forEach
            index(symbol, asset)
            // 兼容历史未归一的加密代码（BTC/USDT vs BTCUSDT）
            index(cryptoKey(symbol), asset)
        }
    }

    private fun index(key: String, asset: Asset) {
        if (key.isBlank()) return
        val list = byKey.getOrPut(key) { mutableListOf() }
        if (list.none { it === asset }) list.add(asset)
    }

    private fun candidates(symbol: String): List<Asset> {
        val raw = symbol.trim()
        return byKey[raw]
            ?: byKey[StockCodeManager.normalizeSymbol(raw)]
            ?: byKey[cryptoKey(raw)]
            ?: emptyList()
    }

    fun candidates(tx: Transaction): List<Asset> = candidates(tx.symbol)

    /**
     * 分红导出需要“当前持仓合计”：同一代码可能在多个账户各有一笔，
     * 只取一条会低估折算金额。币种无法取得共识时返回 null，由调用方留空而不是猜。
     */
    fun holdingFor(symbol: String): ResolvedHolding? {
        val list = candidates(symbol)
        if (list.isEmpty()) return null
        val currencies = list.map { it.currency.uppercase() }.distinct()
        return ResolvedHolding(
            asset = list.first(),
            balance = list.sumOf { it.balance },
            currency = currencies.singleOrNull(),
            accounts = list.map { it.account }.distinct()
        )
    }

    fun resolve(tx: Transaction): ResolvedTxAsset {
        val candidates = candidates(tx)
        if (candidates.isEmpty()) return ResolvedTxAsset(asset = null, account = "", currency = null)
        if (candidates.size == 1) {
            val only = candidates.first()
            return ResolvedTxAsset(only, only.account, only.currency)
        }
        // 多账户持有同一代码：能按名称唯一确定的才认，否则账户标「多账户」
        candidates.filter { it.name == tx.name }.singleOrNull()?.let {
            return ResolvedTxAsset(it, it.account, it.currency)
        }
        val currencies = candidates.map { it.currency.uppercase() }.distinct()
        return ResolvedTxAsset(
            // asset 只用于取数量/价格精度，同代码的多条类型基本一致，取第一条即可
            asset = candidates.first(),
            account = "多账户",
            currency = if (currencies.size == 1) currencies.first() else null
        )
    }

    private fun cryptoKey(symbol: String): String =
        symbol.uppercase().filter { it.isLetterOrDigit() }
}

/** 交易行解析结果；[currency] 为 null 表示无法确定币种（资产已删 / 多账户不同币种）。 */
internal data class ResolvedTxAsset(val asset: Asset?, val account: String, val currency: String?)

/** 代码对应的当前持仓合计；[currency] 为 null 表示多账户币种不一致，不能静默折算。 */
internal data class ResolvedHolding(
    val asset: Asset?,
    val balance: Double,
    val currency: String?,
    val accounts: List<String>
)

/**
 * 各导出文件的**内容构建**。纯函数，不碰文件系统，单测直接调。
 */
internal object CsvContent {

    val ASSET_HEADER = listOf(
        "名称", "类型", "代码", "账户", "数量", "参考单价", "当前价", "总价值(原币)",
        "总价值(人民币)", "币种", "汇率", "备注", "到期日", "参考利率(%)", "万份收益"
    )

    private val SNAPSHOT_HEADER = listOf(
        "日期", "总资产", "总负债", "净资产", "股票", "基金", "虚拟货币", "银行存款"
    )

    /** 每10份派息率的导出精度：基金分红常见 4 位小数，不能按金额 2 位截断。 */
    private const val DIVIDEND_RATE_DIGITS = 4

    /**
     * 导出用的数量精度：股票最多 4 位小数。
     *
     * 展示层股票按整数（[quantityPrecisionFor] = 0），但美股等分数股 0.4 股导出成 `0` 后，
     * 「数量 × 单价 = 市值」这一列就再也对不上了。导出是对账文件，精度必须够反算。
     */
    private const val EXPORT_STOCK_QUANTITY_DIGITS = 4

    /** 导出用的单价精度：股票 4 位、基金/ETF 6 位（净值/低价股常见 4~6 位小数）。 */
    private const val EXPORT_STOCK_PRICE_DIGITS = 4
    private const val EXPORT_FUND_PRICE_DIGITS = 6

    private val DIVIDEND_HEADER = listOf(
        "代码", "名称", "每10份派息(元)", "当前持仓", "折算金额(原币)",
        "币种", "折算金额(人民币)", "除息日", "到账日", "是否手动添加"
    )

    private val TRANSACTION_HEADER = listOf(
        "日期", "代码", "名称", "交易类型", "数量", "价格", "手续费", "成交金额(原币)",
        "币种", "成交金额(人民币)", "账户", "资产备注"
    )

    /**
     * 综合对账单的固定列定义。所有区块（资产/交易/快照/分红/汇总）共用这一套列，
     * 不适用的单元格留空 —— 这样整份文件是**一张合法 CSV 表**。
     */
    private val STATEMENT_HEADER = listOf(
        "区块", "日期", "名称", "代码", "类别", "账户", "数量", "单价",
        "金额(原币)", "币种", "汇率", "金额(人民币)", "备注"
    )

    private val SNAPSHOT_METRICS: List<Pair<String, (AssetSnapshot) -> Double>> = listOf(
        "总资产" to AssetSnapshot::totalAssets,
        "总负债" to AssetSnapshot::totalLiabilities,
        "净资产" to AssetSnapshot::netAssets,
        "股票" to AssetSnapshot::stockValue,
        "基金" to AssetSnapshot::fundValue,
        "虚拟货币" to AssetSnapshot::cryptoValue,
        "银行存款" to AssetSnapshot::bankDepositValue
    )

    // ---------------------------------------------------------------- 资产

    fun assets(assets: List<Asset>): String {
        val sb = StringBuilder()
        sb.appendLine(csvRow(*ASSET_HEADER.toTypedArray()))
        assets.forEach { sb.appendLine(assetRow(it)) }

        val assetsOnly = assets.excludingDebts()
        val liabilities = assets.debts()
        val totalAssets = assetsOnly.sumTotalValueCny()
        val totalLiabilities = liabilities.sumDebtValueCny()

        sb.appendLine()
        sb.appendLine("汇总信息")
        sb.appendLine("总资产(人民币),${csvNumber(totalAssets, MONEY_DIGITS)}")
        sb.appendLine("总负债(人民币),${csvNumber(totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine("净资产(人民币),${csvNumber(totalAssets - totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine("资产数量,${assets.size}")
        return sb.toString()
    }

    internal fun assetRow(asset: Asset): String {
        val quoted = isQuoted(asset.type)
        return csvRow(
            asset.name,
            asset.type.displayName,
            asset.symbol ?: "",
            asset.account,
            // 数量/单价按导出精度（股票 4 位、基金单价 6 位）并去掉尾随 0，
            // 保证「数量 × 单价 ≈ 总价值」能在表内直接反算；金额列仍固定 2 位。
            csvExportNumber(asset.balance, exportQuantityDigits(asset.type, asset.symbol)),
            // 存款/负债没有行情：单价列留空，避免出现「数量 10 万、单价 0、总价值 10 万」的自相矛盾行
            if (quoted) csvExportNumber(
                asset.purchasePrice, exportPriceDigits(asset.type, asset.symbol)
            ) else "",
            if (quoted) csvExportNumber(
                asset.currentPrice ?: asset.purchasePrice,
                exportPriceDigits(asset.type, asset.symbol)
            ) else "",
            csvNumber(asset.totalValue, MONEY_DIGITS),
            csvNumber(asset.totalValueCny, MONEY_DIGITS),
            asset.currency,
            csvNumber(ExchangeRateManager.getRate(asset.currency), 4),
            asset.notes ?: "",
            asset.dueDate ?: "",
            asset.interestRate?.let { csvNumber(it, 2) } ?: "",
            asset.millionCopiesIncome?.let { csvNumber(it, 4) } ?: ""
        )
    }

    // ---------------------------------------------------------------- 快照

    fun snapshots(snapshots: List<AssetSnapshot>): String {
        val sb = StringBuilder()
        sb.appendLine(csvRow(*SNAPSHOT_HEADER.toTypedArray()))
        // 与历史页共用同一套「展示口径」：同日去重（保留最新）+ 丢前导空快照。
        // 导出是对用户的历史对账，不能出现页面看不到的重复行/空行；
        // 原始存档（同日多行等脏数据）另有修复任务负责清理，不在导出里复现。
        SnapshotAnalyzer.normalizeForDisplay(snapshots).forEach { s ->
            sb.appendLine(
                csvRow(
                    s.date,
                    csvNumber(s.totalAssets, MONEY_DIGITS),
                    csvNumber(s.totalLiabilities, MONEY_DIGITS),
                    csvNumber(s.netAssets, MONEY_DIGITS),
                    csvNumber(s.stockValue, MONEY_DIGITS),
                    csvNumber(s.fundValue, MONEY_DIGITS),
                    csvNumber(s.cryptoValue, MONEY_DIGITS),
                    csvNumber(s.bankDepositValue, MONEY_DIGITS)
                )
            )
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 分红

    fun dividends(dividends: List<Dividend>, assets: List<Asset> = emptyList()): String {
        val sb = StringBuilder()
        sb.appendLine(csvRow(*DIVIDEND_HEADER.toTypedArray()))
        if (dividends.isEmpty()) {
            sb.appendLine("(暂无分红记录)")
            return sb.toString()
        }

        val lookup = AssetLookup(assets)
        var perTenTotal = 0.0
        var cnyTotal = 0.0
        var unconvertible = 0
        dividends.forEach { d ->
            val holding = lookup.holdingFor(d.symbol)
            val amountOriginal = holding?.let { h ->
                if (h.currency != null && h.balance > 0.0) {
                    perTenSharesToAmount(d.amount, h.balance)
                } else {
                    null
                }
            }
            val amountCny = amountOriginal?.let { amount ->
                holding?.currency?.let { currency -> ExchangeRateManager.toCny(amount, currency) }
            }
            perTenTotal += d.amount
            if (amountCny != null) {
                cnyTotal += amountCny
            } else {
                unconvertible++
            }
            sb.appendLine(
                csvRow(
                    d.symbol,
                    d.name,
                    // 这是每 10 份的派息率，不是实际到账金额，列名必须说清楚
                    csvNumber(d.amount, DIVIDEND_RATE_DIGITS),
                    holding?.let { h ->
                        csvNumber(
                            h.balance,
                            h.asset?.let { quantityPrecisionFor(it.type, it.symbol) } ?: MONEY_DIGITS
                        )
                    } ?: "",
                    amountOriginal?.let { csvNumber(it, amountDigitsOf(holding?.asset)) } ?: "",
                    holding?.currency ?: "",
                    amountCny?.let { csvNumber(it, MONEY_DIGITS) } ?: "",
                    d.date,
                    d.payDate ?: "",
                    if (d.isManual) "是" else "否"
                )
            )
        }
        sb.appendLine()
        sb.appendLine("汇总信息")
        sb.appendLine("每10份派息合计(元),${csvNumber(perTenTotal, DIVIDEND_RATE_DIGITS)}")
        sb.appendLine("折算金额合计(人民币),${csvNumber(cnyTotal, MONEY_DIGITS)}")
        sb.appendLine("无法折算的分红记录数,$unconvertible")
        sb.appendLine("记录数,${dividends.size}")
        return sb.toString()
    }

    // ---------------------------------------------------------------- 交易

    fun transactions(assets: List<Asset>, transactions: List<Transaction>): String {
        val lookup = AssetLookup(assets)
        val sb = StringBuilder()
        sb.appendLine(csvRow(*TRANSACTION_HEADER.toTypedArray()))
        transactions.sortedByDescending { it.date }.forEach { tx ->
            sb.appendLine(transactionRow(tx, lookup))
        }

        val totals = TxTotals.of(transactions, lookup)
        sb.appendLine()
        sb.appendLine("汇总信息")
        sb.appendLine("买入总金额(人民币),${csvNumber(totals.buy, MONEY_DIGITS)}")
        sb.appendLine("卖出总金额(人民币),${csvNumber(totals.sell, MONEY_DIGITS)}")
        sb.appendLine("总手续费(人民币),${csvNumber(totals.fee, MONEY_DIGITS)}")
        sb.appendLine("交易笔数,${transactions.size}")
        if (totals.unconvertible > 0) {
            // 明说而不是静默当人民币：资产被删/多账户不同币种时无法折算
            sb.appendLine("无法折算的交易(资产已删除或币种不明),${totals.unconvertible}")
        }
        return sb.toString()
    }

    internal fun transactionRow(tx: Transaction, lookup: AssetLookup): String {
        val resolved = lookup.resolve(tx)
        val amount = tx.quantity * tx.price
        val amountCny = resolved.currency?.let { ExchangeRateManager.toCny(amount, it) }
        return csvRow(
            tx.date,
            tx.symbol,
            tx.name,
            TransactionType.fromValue(tx.type)?.name ?: tx.type,
            // 精度跟资产类型走：旧实现硬编码 2/3 位，加密货币 0.00012345 会被导成 0.00
            // 去掉尾随 0 是为让「数量 × 价格 = 成交金额」能在表内直接复算
            csvExportNumber(tx.quantity, quantityDigitsOf(resolved.asset)),
            csvExportNumber(tx.price, priceDigitsOf(resolved.asset)),
            csvNumber(tx.fee, MONEY_DIGITS),
            csvNumber(amount, amountDigitsOf(resolved.asset)),
            resolved.currency ?: "",
            amountCny?.let { csvNumber(it, MONEY_DIGITS) } ?: "",
            resolved.account,
            resolved.asset?.notes ?: ""
        )
    }

    private class TxTotals(var buy: Double = 0.0, var sell: Double = 0.0, var fee: Double = 0.0, var unconvertible: Int = 0) {
        companion object {
            fun of(transactions: List<Transaction>, lookup: AssetLookup): TxTotals {
                val totals = TxTotals()
                transactions.forEach { tx ->
                    val currency = lookup.resolve(tx).currency
                    if (currency == null) {
                        totals.unconvertible++
                        return@forEach
                    }
                    val amountCny = ExchangeRateManager.toCny(tx.quantity * tx.price, currency)
                    when (TransactionType.fromValue(tx.type)) {
                        TransactionType.BUY -> totals.buy += amountCny
                        TransactionType.SELL -> totals.sell += amountCny
                        null -> Unit
                    }
                    // 手续费同样是原币：旧实现直接 sum，把 5 美元和 10 元加在一起
                    totals.fee += ExchangeRateManager.toCny(tx.fee, currency)
                }
                return totals
            }
        }
    }

    // ---------------------------------------------------------------- 综合对账单

    fun statement(
        assets: List<Asset>,
        transactions: List<Transaction>,
        snapshots: List<AssetSnapshot>,
        dividends: List<Dividend>,
        generatedAt: LocalDateTime = LocalDateTime.now()
    ): String {
        val lookup = AssetLookup(assets)
        val sb = StringBuilder()
        sb.appendLine(csvRow(*STATEMENT_HEADER.toTypedArray()))
        sb.appendLine(
            csvRow(
                "对账单",
                generatedAt.format(DateFormats.ISO_DATETIME_FORMATTER),
                "综合对账单",
                "", "", "", "", "", "", "", "", "", ""
            )
        )

        assets.forEach { asset ->
            val quoted = isQuoted(asset.type)
            sb.appendLine(
                csvRow(
                    "资产",
                    "",
                    asset.name,
                    asset.symbol ?: "",
                    asset.type.displayName,
                    asset.account,
                    // 与资产 CSV 同一口径：数量/单价按导出精度并可反算市值
                    csvExportNumber(asset.balance, exportQuantityDigits(asset.type, asset.symbol)),
                    if (quoted) csvExportNumber(
                        asset.currentPrice ?: asset.purchasePrice,
                        exportPriceDigits(asset.type, asset.symbol)
                    ) else "",
                    csvNumber(asset.totalValue, MONEY_DIGITS),
                    asset.currency,
                    csvNumber(ExchangeRateManager.getRate(asset.currency), 4),
                    csvNumber(asset.totalValueCny, MONEY_DIGITS),
                    asset.notes ?: ""
                )
            )
        }

        transactions.sortedByDescending { it.date }.forEach { tx ->
            val resolved = lookup.resolve(tx)
            val amount = tx.quantity * tx.price
            val amountCny = resolved.currency?.let { ExchangeRateManager.toCny(amount, it) }
            sb.appendLine(
                csvRow(
                    "交易",
                    tx.date,
                    tx.name,
                    tx.symbol,
                    TransactionType.fromValue(tx.type)?.name ?: tx.type,
                    resolved.account,
                    csvExportNumber(tx.quantity, quantityDigitsOf(resolved.asset)),
                    csvExportNumber(tx.price, priceDigitsOf(resolved.asset)),
                    csvNumber(amount, amountDigitsOf(resolved.asset)),
                    resolved.currency ?: "",
                    resolved.currency?.let { csvNumber(ExchangeRateManager.getRate(it), 4) } ?: "",
                    amountCny?.let { csvNumber(it, MONEY_DIGITS) } ?: "",
                    resolved.asset?.notes ?: ""
                )
            )
        }

        // 同「快照 CSV」：与历史页同一展示口径（同日去重 + 丢前导空快照）
        SnapshotAnalyzer.normalizeForDisplay(snapshots).forEach { snapshot ->
            // 每个快照拆成 7 行（一行一个指标），保持与其余区块同一套列
            SNAPSHOT_METRICS.forEach { (label, metric) ->
                sb.appendLine(
                    csvRow(
                        "快照",
                        snapshot.date,
                        label,
                        "", "", "", "", "", "", "", "",
                        csvNumber(metric(snapshot), MONEY_DIGITS),
                        ""
                    )
                )
            }
        }

        var dividendCnyTotal = 0.0
        var unconvertibleDividends = 0
        dividends.forEach { d ->
            val holding = lookup.holdingFor(d.symbol)
            val amountOriginal = holding?.let { h ->
                if (h.currency != null && h.balance > 0.0) {
                    perTenSharesToAmount(d.amount, h.balance)
                } else {
                    null
                }
            }
            val amountCny = amountOriginal?.let { amount ->
                holding?.currency?.let { currency -> ExchangeRateManager.toCny(amount, currency) }
            }
            if (amountCny != null) {
                dividendCnyTotal += amountCny
            } else {
                unconvertibleDividends++
            }
            val note = buildString {
                append("每10份派息 ${csvNumber(d.amount, DIVIDEND_RATE_DIGITS)}；")
                append("到账日: ${d.payDate ?: "—"}")
                if (amountCny == null) {
                    append("；无对应持仓或币种不明，未折算")
                }
            }
            sb.appendLine(
                csvRow(
                    "分红",
                    d.date,
                    d.name,
                    d.symbol,
                    if (d.isManual) "手动" else "自动",
                    holding?.accounts?.joinToString("/") ?: "",
                    holding?.let { h ->
                        csvNumber(
                            h.balance,
                            h.asset?.let { quantityPrecisionFor(it.type, it.symbol) } ?: MONEY_DIGITS
                        )
                    } ?: "",
                    // 单价列按“每10份派息率”展示，与金额（实际折算）区分
                    csvNumber(d.amount, DIVIDEND_RATE_DIGITS),
                    amountOriginal?.let { csvNumber(it, amountDigitsOf(holding?.asset)) } ?: "",
                    holding?.currency ?: "",
                    holding?.currency?.let { csvNumber(ExchangeRateManager.getRate(it), 4) } ?: "",
                    amountCny?.let { csvNumber(it, MONEY_DIGITS) } ?: "",
                    note
                )
            )
        }

        val assetsOnly = assets.excludingDebts()
        val liabilities = assets.debts()
        val totalAssets = assetsOnly.sumTotalValueCny()
        val totalLiabilities = liabilities.sumDebtValueCny()
        sb.appendLine(summaryRow("总资产(人民币)", totalAssets))
        sb.appendLine(summaryRow("总负债(人民币)", totalLiabilities))
        sb.appendLine(summaryRow("净资产(人民币)", totalAssets - totalLiabilities))
        sb.appendLine(summaryRow("资产数量", assets.size.toDouble()))
        sb.appendLine(summaryRow("交易笔数", transactions.size.toDouble()))
        sb.appendLine(summaryRow("快照天数", snapshots.map { it.date }.distinct().size.toDouble()))
        sb.appendLine(summaryRow("分红折算合计(人民币)", dividendCnyTotal))
        sb.appendLine(summaryRow("无法折算的分红记录数", unconvertibleDividends.toDouble()))
        return sb.toString()
    }

    private fun summaryRow(label: String, value: Double): String =
        csvRow("汇总", "", label, "", "", "", "", "", "", "", "", csvNumber(value, MONEY_DIGITS), "")

    // ---------------------------------------------------------------- 文本报告

    fun textReport(assets: List<Asset>, generatedAt: LocalDateTime = LocalDateTime.now()): String {
        val sb = StringBuilder()
        sb.appendLine("=== 资产报告 ===")
        sb.appendLine("生成时间: ${generatedAt.format(DateFormats.CN_DATETIME_FORMATTER)}")
        sb.appendLine()

        val assetsOnly = assets.excludingDebts()
        val liabilities = assets.debts()
        val totalAssets = assetsOnly.sumTotalValueCny()
        val totalLiabilities = liabilities.sumDebtValueCny()

        sb.appendLine("【净资产】")
        sb.appendLine("  ¥${csvNumber(totalAssets - totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine()
        sb.appendLine("【总资产】: ¥${csvNumber(totalAssets, MONEY_DIGITS)}")
        sb.appendLine("【总负债】: ¥${csvNumber(totalLiabilities, MONEY_DIGITS)}")
        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()

        if (assetsOnly.isNotEmpty()) {
            sb.appendLine("【资产详情】")
            sb.appendLine()
            assetsOnly.groupBy { it.type }.forEach { (type, typeAssets) ->
                sb.appendLine("  ${type.icon} ${type.displayName}")
                typeAssets.forEach { asset ->
                    sb.appendLine("    - ${asset.name}")
                    // 账户必须出现：多账户用户看不出某笔资产在哪个账户，这份报告就没法对账
                    sb.appendLine("      代码: ${asset.symbol ?: "-"}  账户: ${asset.account}")
                    sb.appendLine(
                        "      数量: ${csvNumber(asset.balance, quantityPrecisionFor(type, asset.symbol))}"
                    )
                    sb.appendLine(
                        "      当前价值: ${asset.currency} ${csvNumber(asset.totalValue, MONEY_DIGITS)}" +
                            " (¥${csvNumber(asset.totalValueCny, MONEY_DIGITS)})"
                    )
                    if (asset.currency != "CNY") {
                        sb.appendLine("      汇率: ${csvNumber(ExchangeRateManager.getRate(asset.currency), 4)}")
                    }
                    sb.appendLine()
                }
            }
        }

        if (liabilities.isNotEmpty()) {
            sb.appendLine("【负债详情】")
            sb.appendLine()
            liabilities.groupBy { it.type }.forEach { (type, typeAssets) ->
                sb.appendLine("  ${type.icon} ${type.displayName}")
                typeAssets.forEach { asset ->
                    sb.appendLine("    - ${asset.name}")
                    sb.appendLine("      账户: ${asset.account}")
                    sb.appendLine(
                        "      金额: ${asset.currency} ${csvNumber(asset.totalValue, MONEY_DIGITS)}" +
                            " (¥${csvNumber(asset.totalValueCny, MONEY_DIGITS)})"
                    )
                    asset.notes?.let { sb.appendLine("      备注: $it") }
                    sb.appendLine()
                }
            }
        }

        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("报告生成完毕")
        return sb.toString()
    }

    // ---------------------------------------------------------------- 公共

    /** 有行情概念的资产才展示单价：存款/负债没有价格，只有金额 */
    private fun isQuoted(type: AssetType): Boolean = !type.isLiability && type != AssetType.BANK_DEPOSIT

    private fun quantityDigitsOf(asset: Asset?): Int =
        asset?.let { exportQuantityDigits(it.type, it.symbol) } ?: UNKNOWN_ASSET_DIGITS

    private fun priceDigitsOf(asset: Asset?): Int =
        asset?.let { exportPriceDigits(it.type, it.symbol) } ?: UNKNOWN_ASSET_DIGITS

    /** 导出数量精度：只抬高股票的 0 位限制，其余沿用展示精度（加密货币 10 位等不被收窄）。 */
    private fun exportQuantityDigits(type: AssetType, symbol: String?): Int =
        if (type == AssetType.STOCK) EXPORT_STOCK_QUANTITY_DIGITS else quantityPrecisionFor(type, symbol)

    /** 导出单价精度：股票 4 位、基金/ETF 6 位、加密货币沿用 [pricePrecisionFor]。 */
    private fun exportPriceDigits(type: AssetType, symbol: String?): Int = when {
        type == AssetType.CRYPTO -> pricePrecisionFor(type, symbol)
        type == AssetType.FUND || StockCodeManager.isETF(symbol) -> EXPORT_FUND_PRICE_DIGITS
        else -> EXPORT_STOCK_PRICE_DIGITS
    }

    private fun amountDigitsOf(asset: Asset?): Int = when {
        asset == null -> UNKNOWN_ASSET_DIGITS
        asset.type == AssetType.CRYPTO -> CRYPTO_DECIMALS
        else -> MONEY_DIGITS
    }

    /** 资产找不到时的精度兜底：给足 8 位，宁可多留也不要截断 */
    private const val UNKNOWN_ASSET_DIGITS = 8
}
