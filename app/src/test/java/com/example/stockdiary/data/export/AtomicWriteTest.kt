package com.example.stockdiary.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [writeAtomically] 回归（R03 / C2）。
 *
 * 导出与诊断两条链路都走这一个原子写函数：tmp 写完 rename 覆盖目标。
 * 直接写目标中途被杀会留下半截文件（账目缺行 / 诊断缺崩溃前线索），
 * 且没有任何报错——所以这里钉死这几件事：
 *   1. 成功写入后目标是**完整**内容、且无 tmp 残留；
 *   2. 覆盖写是整体替换，不会留下旧文件的尾部；
 *   3. **正常路径不先删目标**：目标任何时刻要么是新完整版、要么还是旧完整版；
 *   4. rename 失败才回退删除重试；delete 失败则退回直接写，不丢这次导出；
 *   5. 写入失败时 tmp 被清理，不留残件。
 *
 * 用真实临时文件测，不依赖 Android Context / FileProvider。
 */
class AtomicWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 目录下是否还有原子写的临时残件（名字带随机后缀，按后缀扫） */
    private fun lingeringTmpFiles(): List<String> =
        tmp.root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXPORT_TEMP_SUFFIX) }
            ?.map { it.name }
            ?: emptyList()

    @Test
    fun `成功写入后目标内容完整且无tmp残留`() {
        val target = File(tmp.root, "assets_1.csv")
        assertTrue(writeAtomically(target, "完整内容\n第二行"))

        assertEquals("完整内容\n第二行", target.readText())
        assertTrue("tmp 必须已被 rename 走", lingeringTmpFiles().isEmpty())
    }

    @Test
    fun `覆盖写是整体替换不留旧内容尾部`() {
        val target = File(tmp.root, "report.txt")
        target.writeText("这是一份很长很长的旧文件内容，比新内容长得多得多，用来验证不会残留尾部")
        val newText = "短的新内容"

        // 不锁返回值：Linux 上 rename 可直接覆盖（true），Windows 上走删后重试（false），
        // 两者都要求最终内容是新版整体替换。
        writeAtomically(target, newText)

        assertEquals(newText, target.readText())
        assertTrue(lingeringTmpFiles().isEmpty())
    }

    @Test
    fun `rename能覆盖成功时不会先删目标`() {
        val target = File(tmp.root, "report.txt").apply { writeText("旧的完整版") }

        val ok = writeAtomically(
            target,
            "新的完整版",
            // 用 NIO 的覆盖式 move 模拟「文件系统支持 rename 覆盖」，
            // 这样才真正走到「rename 成功」分支
            rename = { from, to ->
                java.nio.file.Files.move(
                    from.toPath(),
                    to.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                true
            },
            // 只要清理路径被走到就失败：正常覆盖不应删除目标
            deleteTarget = { throw AssertionError("rename 成功时不应删除目标文件") }
        )

        assertTrue(ok)
        assertEquals("新的完整版", target.readText())
        assertTrue(lingeringTmpFiles().isEmpty())
    }

    @Test
    fun `rename失败时删除旧目标并重试_最终仍是完整新版`() {
        val target = File(tmp.root, "assets_1.csv").apply { writeText("旧的完整版") }
        var renameAttempts = 0

        val ok = writeAtomically(
            target,
            "新的完整版",
            rename = { from, to ->
                renameAttempts++
                // 第一次模拟「不支持覆盖已存在目标」，第二次正常 rename
                if (renameAttempts == 1) false else from.renameTo(to)
            }
        )

        assertFalse("走了删除重试，不是纯 rename 路径", ok)
        assertEquals(2, renameAttempts)
        assertEquals("新的完整版", target.readText())
        assertTrue(lingeringTmpFiles().isEmpty())
    }

    @Test
    fun `rename失败且delete失败时退回直接写_不丢本次导出`() {
        val target = File(tmp.root, "assets_1.csv").apply { writeText("旧的完整版") }
        var deleteCalled = false

        val ok = writeAtomically(
            target,
            "新的完整版",
            rename = { _, _ -> false },
            deleteTarget = { deleteCalled = true; false }
        )

        assertFalse(ok)
        assertTrue("delete 失败分支必须真的尝试过删除", deleteCalled)
        assertEquals("新的完整版", target.readText())
        assertTrue("直接写兜底后 tmp 不能残留", lingeringTmpFiles().isEmpty())
    }

    @Test
    fun `rename两次都失败时退回直接写_目标仍是完整新版`() {
        val target = File(tmp.root, "report.txt").apply { writeText("旧的完整版") }

        val ok = writeAtomically(
            target,
            "新的完整版",
            rename = { _, _ -> false },
            deleteTarget = { it.delete() }
        )

        assertFalse(ok)
        assertEquals("新的完整版", target.readText())
        assertTrue(lingeringTmpFiles().isEmpty())
    }

    @Test
    fun `写tmp阶段异常时抛出并清理tmp`() {
        val target = File(tmp.root, "report.csv")

        assertThrows(IOException::class.java) {
            // tmp 已写好，rename 阶段抛异常 → catch 必须清 tmp 再抛
            writeAtomically(target, "内容", rename = { _, _ -> throw IOException("rename boom") })
        }
        assertFalse("异常路径不能留下目标半截文件", target.exists())
        assertTrue("失败路径也必须清掉 tmp，不留半截残件", lingeringTmpFiles().isEmpty())
    }
}
