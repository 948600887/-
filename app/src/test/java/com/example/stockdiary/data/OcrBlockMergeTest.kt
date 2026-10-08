package com.example.stockdiary.data

import com.example.stockdiary.database.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长截图分块识别后的行拼接。
 *
 * 对账单经常被做成长图（1080×5888 这种），整张交给 ML Kit 会被降采样到认不出字，
 * 因此按高度分块识别再拼回来。块之间留了重叠，拼接时要把重复的部分去掉，
 * 否则边界处的行会被记两遍——表现为「笔数凭空变多」。
 */
class OcrBlockMergeTest {

    @Test
    fun `没有重叠时按顺序拼接`() {
        val merged = OcrStatementService.mergeBlockLines(
            listOf(
                listOf("证券买入-甲 1.0 -100.0", "买 09-23 10:00 100 0.10"),
                listOf("证券买入-乙 2.0 -200.0", "买 09-23 11:00 200 0.20")
            )
        )
        assertEquals(4, merged.size)
        assertEquals("证券买入-甲 1.0 -100.0", merged[0])
        assertEquals("证券买入-乙 2.0 -200.0", merged[2])
    }

    @Test
    fun `末尾一行被下一块重复时只保留一份`() {
        val tail = "买 09-23 10:00 100 0.10"
        val merged = OcrStatementService.mergeBlockLines(
            listOf(
                listOf("证券买入-甲 1.0 -100.0", tail),
                listOf(tail, "证券买入-乙 2.0 -200.0", "买 09-23 11:00 200 0.20")
            )
        )
        assertEquals(4, merged.size)
        assertEquals("证券买入-乙 2.0 -200.0", merged[2])
    }

    @Test
    fun `末尾两行被下一块重复时都能去掉`() {
        val a = "证券买入-甲 1.0 -100.0"
        val b = "买 09-23 10:00 100 0.10"
        val merged = OcrStatementService.mergeBlockLines(
            listOf(
                listOf(a, b),
                listOf(a, b, "证券买入-乙 2.0 -200.0")
            )
        )
        assertEquals(3, merged.size)
        assertEquals(a, merged[0])
        assertEquals(b, merged[1])
        assertEquals("证券买入-乙 2.0 -200.0", merged[2])
    }

    @Test
    fun `多块依次拼接不产生重复行`() {
        val blocks = listOf(
            listOf("行1", "行2", "行3"),
            listOf("行3", "行4", "行5"),
            listOf("行5", "行6")
        )
        val merged = OcrStatementService.mergeBlockLines(blocks)
        assertEquals(listOf("行1", "行2", "行3", "行4", "行5", "行6"), merged)
    }

    @Test
    fun `真实长截图的分块拼接不会让笔数变多`() {
        // 模拟 1080×5888 长截图按 1600 分块（块间重叠 120px），边界处一行出现两次
        val line1 = "卖出-中证红利 1.556 1,089.20"
        val line1d = "卖 09-22 13:00 700 --"
        val line2 = "买入-科创50 1.770 531.00"
        val line2d = "买 09-22 13:00 300 --"
        val line3 = "证券买入-中证红利 1.546 -1,082.30"
        val line3d = "买 09-22 09:34 700 0.10"

        val merged = OcrStatementService.mergeBlockLines(
            listOf(
                listOf(line1, line1d, line2, line2d),
                listOf(line2, line2d, line3, line3d)
            )
        )
        assertEquals(6, merged.size)

        val result = GalaxyStatementParser.parse(merged, 2026)
        assertEquals(3, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals("科创50", result[1].name)
        assertEquals(TransactionType.BUY.value, result[2].type)
    }
}
