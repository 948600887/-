package com.example.stockdiary.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [filesToPrune] / [classifyExportFile] 回归（R02）。
 *
 * 这里测的是「清理旧导出」的分组保底逻辑：从前不分扩展名、全局只留最近 5 份，
 * 一次导出多份 CSV 就会把刚分享出去的诊断报告挤掉。诊断报告是排障唯一线索，
 * 丢它比丢 CSV 严重得多，所以必须单独成组、独立保留。
 *
 * 用真实临时文件 + [File.setLastModified] 控制新旧，测的是纯函数本身，
 * 不依赖 Android Context / FileProvider（那些在 JVM 单测里是 stub）。
 */
class DataExporterPruneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个带指定修改时间的真实文件（时间越小越旧） */
    private fun fileAt(name: String, modifiedAt: Long): File =
        tmp.newFile(name).apply { assertTrue(setLastModified(modifiedAt)) }

    @Test
    fun `按文件名正确归类四种用途`() {
        assertEquals(ExportFileGroup.DIAGNOSTICS, classifyExportFile("goldvault_diagnostics_20261115_120000.txt"))
        assertEquals(ExportFileGroup.CSV, classifyExportFile("assets_20261115_120000.csv"))
        assertEquals(ExportFileGroup.CSV, classifyExportFile("full_statement_20261115.csv"))
        assertEquals(ExportFileGroup.TEXT, classifyExportFile("asset_report_20261115.txt"))
        assertEquals(ExportFileGroup.OTHER, classifyExportFile("mystery.dat"))
        // 扩展名大小写不敏感：避免大写后缀文件被误归 OTHER 而挤占别的组
        assertEquals(ExportFileGroup.CSV, classifyExportFile("assets_x.CSV"))
        assertEquals(ExportFileGroup.DIAGNOSTICS, classifyExportFile("GOLDVAULT_DIAGNOSTICS_x.TXT"))
    }

    @Test
    fun `诊断报告单独保底_不被大量CSV挤掉`() {
        val base = 1_700_000_000_000L
        val files = mutableListOf<File>()
        // 1 份很旧的诊断报告
        files += fileAt("goldvault_diagnostics_old.txt", base)
        // 8 份都比诊断新的 CSV
        repeat(8) { i -> files += fileAt("assets_$i.csv", base + (i + 1) * 1_000L) }

        val toPrune = filesToPrune(files, keepPerGroup = 5)

        // CSV 组 8 份 → 删最旧 3 份；诊断组只有 1 份 → 一份都不删
        assertEquals(3, toPrune.size)
        assertTrue("诊断报告绝不能被清理", toPrune.none { it.name.startsWith(DIAGNOSTICS_FILE_PREFIX) })
        assertTrue("删的必须全是 CSV", toPrune.all { it.name.endsWith(".csv", ignoreCase = true) })
        assertEquals(
            setOf("assets_0.csv", "assets_1.csv", "assets_2.csv"),
            toPrune.map { it.name }.toSet()
        )
    }

    @Test
    fun `各组各自保留互不影响_文本与CSV不互相挤占`() {
        val base = 1_700_000_000_000L
        val files = mutableListOf<File>()
        repeat(7) { i -> files += fileAt("assets_$i.csv", base + i * 1_000L) }
        repeat(7) { i -> files += fileAt("asset_report_$i.txt", base + i * 1_000L) }
        repeat(2) { i -> files += fileAt("goldvault_diagnostics_$i.txt", base + i * 1_000L) }

        val toPrune = filesToPrune(files, keepPerGroup = 5)

        // CSV 删 2、文本删 2、诊断 2 份全留 → 共删 4
        assertEquals(4, toPrune.size)
        assertEquals(2, toPrune.count { it.name.endsWith(".csv", ignoreCase = true) })
        assertEquals(2, toPrune.count { classifyExportFile(it.name) == ExportFileGroup.TEXT })
        assertTrue("诊断组 2 份都不该被删", toPrune.none { classifyExportFile(it.name) == ExportFileGroup.DIAGNOSTICS })
    }

    @Test
    fun `组内只删最旧的_保留修改时间最新的`() {
        val base = 1_700_000_000_000L
        val files = listOf(
            fileAt("assets_old.csv", base + 1_000L),
            fileAt("assets_new.csv", base + 9_000L),
            fileAt("assets_mid.csv", base + 5_000L)
        )
        val toPrune = filesToPrune(files, keepPerGroup = 2)
        assertEquals(listOf("assets_old.csv"), toPrune.map { it.name })
    }

    @Test
    fun `keep为零时清空所有组_供清除所有数据使用`() {
        val base = 1_700_000_000_000L
        val files = listOf(
            fileAt("goldvault_diagnostics_1.txt", base),
            fileAt("assets_1.csv", base + 1_000L),
            fileAt("asset_report_1.txt", base + 2_000L)
        )
        assertEquals(files.toSet(), filesToPrune(files, keepPerGroup = 0).toSet())
    }

    @Test
    fun `每组不超过保留份数时什么都不删`() {
        val base = 1_700_000_000_000L
        val files = listOf(
            fileAt("goldvault_diagnostics_1.txt", base),
            fileAt("assets_1.csv", base + 1_000L),
            fileAt("assets_2.csv", base + 2_000L)
        )
        assertTrue(filesToPrune(files, keepPerGroup = 5).isEmpty())
    }

    @Test
    fun `CSV按导出类型分组_依次导出6类不会挤掉最早那份`() {
        val base = 1_700_000_000_000L
        // 5 个真实导出前缀 + 1 个历史/新增类型：总量超过旧的全局配额 5，
        // 但每类只有 1 份，分组后任何一份都不该被删。
        val names = listOf(
            "assets_1.csv",
            "snapshots_1.csv",
            "dividends_1.csv",
            "statement_1.csv",
            "full_statement_1.csv",
            "transactions_1.csv"
        )
        val files = names.mapIndexed { i, name -> fileAt(name, base + i * 1_000L) }

        assertTrue(
            "不同导出类型各自成组，6 类 CSV 互不挤占",
            filesToPrune(files, keepPerGroup = 5).isEmpty()
        )
    }

    @Test
    fun `同类型CSV仍共享配额_只删最旧的`() {
        val base = 1_700_000_000_000L
        val files = listOf(
            fileAt("assets_old.csv", base + 1_000L),
            fileAt("assets_new.csv", base + 9_000L),
            fileAt("snapshots_old.csv", base + 2_000L),
            fileAt("snapshots_new.csv", base + 8_000L)
        )

        // 每类 2 份、配额 1：各类各删最旧的一份，跨类互不影响
        assertEquals(
            setOf("assets_old.csv", "snapshots_old.csv"),
            filesToPrune(files, keepPerGroup = 1).map { it.name }.toSet()
        )
    }

    @Test
    fun `保留分组键能区分CSV导出类型_非CSV按用途分组`() {
        assertEquals("CSV:assets", exportRetentionGroup("assets_20261119_101500.csv"))
        assertEquals("CSV:full_statement", exportRetentionGroup("full_statement_20261119_101500.csv"))
        assertEquals("DIAGNOSTICS", exportRetentionGroup("goldvault_diagnostics_20261119_101500.txt"))
        assertEquals("TEXT", exportRetentionGroup("asset_report_20261119_101500.txt"))
    }

    /**
     * 带毫秒/序号后缀的新命名必须仍归到**同一个**导出类型分组，
     * 否则「同类型共享保留配额」的口径会被文件名格式变化悄悄破坏。
     */
    @Test
    fun `毫秒与序号后缀不影响保留分组`() {
        assertEquals("CSV:assets", exportRetentionGroup("assets_20261119_101500_123.csv"))
        assertEquals("CSV:full_statement", exportRetentionGroup("full_statement_20261119_101500_123.csv"))
        assertEquals("CSV:full_statement", exportRetentionGroup("full_statement_20261119_101500_007_2.csv"))
    }

    /**
     * 导出文件名必须唯一。
     *
     * 旧命名只到秒：同一秒两次导出算出同一个绝对路径，后者覆盖前者，
     * 而先分享出去的 URI 指向的仍是那个路径 —— 用户点开看到的是后一份内容。
     */
    @Test
    fun `同一秒内两次连续导出得到不同文件名`() {
        val millis = 1_800_000_000_000L
        val first = exportFileName("assets", "csv", millis) { false }
        val second = exportFileName("assets", "csv", millis + 137L) { false }

        assertNotEquals(first, second)
        assertTrue(first.endsWith(".csv"))

        // 极端情况：同一毫秒也撞名（批量循环里连续调用）时递增序号，不再指向同一个路径
        val taken = mutableSetOf(first)
        val third = exportFileName("assets", "csv", millis) { it in taken }
        assertNotEquals(first, third)
        assertTrue(third, third.endsWith("_2.csv"))
        taken += third
        assertTrue(exportFileName("assets", "csv", millis) { it in taken }.endsWith("_3.csv"))
    }
}
