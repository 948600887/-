package com.example.stockdiary.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GalaxyStatementParser] 底层 OCR 后处理纯函数的单测。
 *
 * 这四个函数不碰 Context / 数据库 / 时钟，是「券商对账单 OCR 准确率」的命脉：
 * 全角数字、中文负号、千分位、名称夹空格，任何一种没归一化都会让整笔交易解析失败
 * 或金额错乱。原先只有 [OcrBlockMergeTest] 通过 `parse` 做端到端覆盖，底层清洗没单独钉死，
 * 一旦有人改 `toHalfWidth` / 正则就可能悄无声息地劣化解析。这里把它们（private→internal）
 * 直接喂极端输入，逐条钉死边界。
 */
class GalaxyStatementParserCleanseTest {

    // ── toHalfWidth：全角 → 半角 ──────────────────────────────────────────

    @Test
    fun `toHalfWidth_全角数字与小数点转半角`() {
        assertEquals("1.556", GalaxyStatementParser.toHalfWidth("１.５５６"))
    }

    @Test
    fun `toHalfWidth_全角字母转半角`() {
        assertEquals("ABC", GalaxyStatementParser.toHalfWidth("ＡＢＣ"))
    }

    @Test
    fun `toHalfWidth_全角空格U+3000转普通空格`() {
        assertEquals("中 证", GalaxyStatementParser.toHalfWidth("中\u3000证"))
    }

    @Test
    fun `toHalfWidth_半角与中文原样不变`() {
        // 半角 ASCII 与中文（不在 0xFF01..0xFF5E 范围）不受影响
        assertEquals("1.556 ABC 中证红利", GalaxyStatementParser.toHalfWidth("1.556 ABC 中证红利"))
    }

    // ── toNumber：数字清洗 + OCR 负号还原 ──────────────────────────────────

    @Test
    fun `toNumber_正常小数`() {
        assertEquals(486.40, GalaxyStatementParser.toNumber("486.40")!!, 1e-9)
    }

    @Test
    fun `toNumber_去千分位`() {
        assertEquals(1082.30, GalaxyStatementParser.toNumber("1,082.30")!!, 1e-9)
    }

    @Test
    fun `toNumber_OCR中文负号一还原`() {
        // 真实截图里 `-486.40` 被认成「一486.40」，必须还原成负
        assertEquals(-486.40, GalaxyStatementParser.toNumber("一486.40")!!, 1e-9)
    }

    @Test
    fun `toNumber_全角减号还原`() {
        assertEquals(-486.40, GalaxyStatementParser.toNumber("－486.40")!!, 1e-9)
    }

    @Test
    fun `toNumber_无法解析返回null`() {
        assertNull(GalaxyStatementParser.toNumber("abc"))
    }

    @Test
    fun `toNumber_空串返回null`() {
        assertNull(GalaxyStatementParser.toNumber(""))
    }

    // ── cleanName：去掉名称里的空格 ──────────────────────────────────────

    @Test
    fun `cleanName_去掉名称中间空格`() {
        assertEquals("中证红利", GalaxyStatementParser.cleanName("中证 红利"))
    }

    @Test
    fun `cleanName_去掉不换行空格U+00A0`() {
        assertEquals("中证红利", GalaxyStatementParser.cleanName("中证\u00A0红利"))
    }

    @Test
    fun `cleanName_首尾trim`() {
        assertEquals("沪深300", GalaxyStatementParser.cleanName(" 沪深300 "))
    }

    // ── normalizeLine：行归一化（全角 + 散开小数点/千分位 + 压空格） ─────────

    @Test
    fun `normalizeLine_全角数字转半角且小数点正确`() {
        assertEquals("1.556", GalaxyStatementParser.normalizeLine("１.５５６"))
    }

    @Test
    fun `normalizeLine_散开的小数点粘回`() {
        assertEquals("1.546", GalaxyStatementParser.normalizeLine("1 . 546"))
    }

    @Test
    fun `normalizeLine_散开的千分位粘回`() {
        assertEquals("1,082", GalaxyStatementParser.normalizeLine("1 , 082"))
    }

    @Test
    fun `normalizeLine_连续空白压成单空格`() {
        assertEquals("卖出 中证红利", GalaxyStatementParser.normalizeLine("卖出  中证红利"))
    }

    @Test
    fun `normalizeLine_不破坏日期后的句点`() {
        // 后顾断言 (?<![-/月]\d) 必须保住「日期. 时间」里的句点，否则这笔连告警都没有地丢失
        assertEquals(
            "09-23. 10:48 700 0.10",
            GalaxyStatementParser.normalizeLine("09-23. 10:48 700 0.10")
        )
    }
}
