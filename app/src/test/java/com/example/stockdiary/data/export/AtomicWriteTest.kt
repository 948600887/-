package com.example.stockdiary.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [writeAtomically] 回归（R03）。
 *
 * 导出与诊断两条链路都走这一个原子写函数：tmp 写完 rename 覆盖目标。
 * 直接写目标中途被杀会留下半截文件（账目缺行 / 诊断缺崩溃前线索），
 * 且没有任何报错——所以这里钉死三件事：
 *   1. 成功写入后目标是**完整**内容、且无 tmp 残留；
 *   2. 覆盖写是整体替换，不会留下旧文件的尾部；
 *   3. 写入失败时 tmp 被清理，不留残件。
 *
 * 用真实临时文件测，不依赖 Android Context / FileProvider。
 */
class AtomicWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `成功写入后目标内容完整且无tmp残留`() {
        val target = File(tmp.root, "assets_1.csv")
        assertTrue(writeAtomically(target, "完整内容\n第二行"))

        assertEquals("完整内容\n第二行", target.readText())
        assertFalse("tmp 必须已被 rename 走", File(tmp.root, "${target.name}.tmp").exists())
    }

    @Test
    fun `覆盖写是整体替换不留旧内容尾部`() {
        val target = File(tmp.root, "report.txt")
        target.writeText("这是一份很长很长的旧文件内容，比新内容长得多得多，用来验证不会残留尾部")
        val newText = "短的新内容"

        assertTrue(writeAtomically(target, newText))

        assertEquals(newText, target.readText())
        assertFalse(File(tmp.root, "${target.name}.tmp").exists())
    }

    @Test
    fun `写入tmp失败时抛异常且清理tmp`() {
        // 让 tmp 路径本身是一个目录 → tmp.writeText 抛异常 → catch 清理 tmp
        val target = File(tmp.root, "report.csv")
        val tmpAsDir = tmp.newFolder("report.csv.tmp")

        assertThrows(Exception::class.java) {
            writeAtomically(target, "内容")
        }
        assertFalse("失败路径也必须清掉 tmp，不留半截残件", tmpAsDir.exists())
    }
}
