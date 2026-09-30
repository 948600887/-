package com.example.stockdiary.data

import com.example.stockdiary.database.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 银河证券对账单截图解析器单元测试。
 * 测试数据来自真实截图，模拟 OCR 输出（元素间用空格拼接）。
 */
class GalaxyStatementParserTest {

    /**
     * 完整对账单截图内容（模拟 OCR 识别后的行文本）。
     * 包含：国债逆回购(应跳过)、证券买入、证券卖出。
     */
    private val sampleLines = listOf(
        "2026-09  -1,435.50  -0.83%  上证 -1.25%",
        "通用回购逆回购-GC001  1.345  -83,000.83",
        "其 09-23 14:51  830  0.83",
        "证券买入-中证红利  1.546  -1,082.30",
        "买 09-23 10:48  700  0.10",
        "证券买入-综指ETF  1.011  -505.60",
        "买 09-23 10:01  500  0.10",
        "证券买入-中证红利  1.551  -1,085.80",
        "买 09-23 09:33  700  0.10",
        "通用回购逆回购购回-GC001  1.450  90,003.58",
        "其 09-23  900  0.00",
        "通用回购逆回购-GC001  1.450  -90,000.90",
        "其 09-22 14:51  900  0.90",
        "证券卖出-中证红利  1.556  1,089.10",
        "卖 09-22 13:00  700  0.10",
        "证券买入-科创50  1.770  -531.10",
        "买 09-22 13:00  300  0.10",
        "证券卖出-中证红利  1.551  1,085.60",
        "卖 09-22 09:41  700  0.10",
        "证券买入-中证红利  1.546  -1,082.30",
        "买 09-22 09:34  700  0.10",
        "证券卖出-科创50  1.793  537.80",
        "卖 09-22 09:30  300  0.10",
        "证券买入-中证红利  1.551  -1,085.80",
        "买 09-22 09:30  700  0.10",
        "证券卖出-科创50  1.790  536.90",
        "卖 09-22 09:30  300  0.10",
        "证券卖出-综指ETF  1.016  507.90",
        "卖 09-22 09:25  500  0.10",
        "通用回购逆回购购回-GC001  1.480  89,003.61",
        "其 09-22  890  0.00",
        "通用回购逆回购-GC001  1.480  -89,000.89",
        "其 09-21 14:51  890  0.89",
        "证券卖出-中证红利  1.556  1,089.10",
        "卖 09-21 14:19  700  0.10",
        "证券卖出-综指ETF  1.011  505.40",
        "卖 09-21 13:43  500  0.10",
        "证券卖出-中证红利  1.551  1,085.60",
        "卖 09-21 13:07  700  0.10",
        "证券卖出-中证红利  1.546  1,082.10",
        "卖 09-21 10:31  700  0.10",
        "证券买入-科创50  1.746  -523.90",
        "买 09-21 09:57  300  0.10",
        "证券卖出-中证红利  1.541  1,078.60",
        "卖 09-21 09:56  700  0.10",
        "证券卖出-科创50  1.761  528.20",
        "卖 09-21 09:30  300  0.10",
        "证券买入-中证红利  1.536  -1,075.30",
        "买 09-21 09:30  700  0.10"
    )

    @Test
    fun `完整对账单解析出正确数量的交易`() {
        val result = GalaxyStatementParser.parse(sampleLines, 2026)
        // 19 笔证券买卖（国债逆回购被跳过）
        assertEquals(19, result.size)
    }

    @Test
    fun `第一笔买入解析正确`() {
        val result = GalaxyStatementParser.parse(sampleLines, 2026)
        val tx = result[0]

        assertEquals("2026-09-23", tx.date)
        assertEquals("中证红利", tx.name)
        assertEquals(TransactionType.BUY.value, tx.type)
        assertEquals(700.0, tx.quantity, 0.001)
        assertEquals(1.546, tx.price, 0.001)
        assertEquals(0.10, tx.fee, 0.001)
        assertEquals("银河证券", tx.account)
    }

    @Test
    fun `卖出交易解析正确`() {
        val result = GalaxyStatementParser.parse(sampleLines, 2026)
        // 第 4 笔是卖出中证红利
        val sellTx = result[3]

        assertEquals("2026-09-22", sellTx.date)
        assertEquals("中证红利", sellTx.name)
        assertEquals(TransactionType.SELL.value, sellTx.type)
        assertEquals(700.0, sellTx.quantity, 0.001)
        assertEquals(1.556, sellTx.price, 0.001)
    }

    @Test
    fun `所有交易都标记需手动填代码`() {
        val result = GalaxyStatementParser.parse(sampleLines, 2026)
        result.forEach { tx ->
            assertTrue(
                "每笔都应标记需填代码: row=${tx.rowNumber}",
                tx.errors.contains("需手动填写证券代码")
            )
            assertTrue(tx.symbol.isEmpty())
        }
    }

    @Test
    fun `国债逆回购行被跳过`() {
        val result = GalaxyStatementParser.parse(sampleLines, 2026)
        // 不应该出现 GC001
        result.forEach { tx ->
            assertFalse("不应包含逆回购: ${tx.name}", tx.name.contains("GC001"))
        }
    }

    @Test
    fun `买卖数量统计正确`() {
        val result = GalaxyStatementParser.parse(sampleLines, 2026)
        val buys = result.count { it.type == TransactionType.BUY.value }
        val sells = result.count { it.type == TransactionType.SELL.value }

        assertEquals(8, buys)   // 中证红利×5 + 综指ETF×1 + 科创50×2
        assertEquals(11, sells) // 中证红利×6 + 综指ETF×2 + 科创50×3
    }

    @Test
    fun `OCR 在减号前后加空格也能匹配`() {
        val lines = listOf(
            "证券买入 - 中证红利  1.546  -1,082.30",
            "买 09-23 10:48  700  0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
    }

    @Test
    fun `日期格式兼容斜杠和中文`() {
        // 09/23 格式
        val lines1 = listOf(
            "证券买入-测试A  10.0  -1000.0",
            "买 09/23 10:48  100  1.0"
        )
        assertEquals("2026-09-23", GalaxyStatementParser.parse(lines1, 2026)[0].date)

        // 09月23日 格式
        val lines2 = listOf(
            "证券买入-测试B  10.0  -1000.0",
            "买 09月23日 10:48  100  1.0"
        )
        assertEquals("2026-09-23", GalaxyStatementParser.parse(lines2, 2026)[0].date)
    }

    @Test
    fun `无匹配内容返回空列表`() {
        val lines = listOf(
            "本月  近三月  近半年",
            "2026-09  -1,435.50  -0.83%"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `价格或数量为0时标记异常`() {
        val lines = listOf(
            "证券买入-异常  0.0  -1000.0",
            "买 09-23 10:48  0  1.0"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        val errors = result[0].errors
        assertTrue(errors.any { it.contains("价格识别异常") })
        assertTrue(errors.any { it.contains("数量识别异常") })
    }

    // ---------- 以下为 OCR 噪声容错用例 ----------

    @Test
    fun `全角数字与全角符号也能解析`() {
        val lines = listOf(
            "证券买入－中证红利　１．５４６　－１，０８２．３０",
            "买　０９－２３　１０：４８　７００　０．１０"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("2026-09-23", result[0].date)
        assertEquals(1.546, result[0].price, 0.001)
        assertEquals(700.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
    }

    @Test
    fun `小数点两侧被插入空格也能解析`() {
        val lines = listOf(
            "证券买入-综指ETF  1 . 011  -505 . 60",
            "买 09-23 10:01  500  0 . 10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals(1.011, result[0].price, 0.001)
        assertEquals(500.0, result[0].quantity, 0.001)
        assertEquals(0.10, result[0].fee, 0.001)
    }

    @Test
    fun `名称中被插入空格会被清理`() {
        val lines = listOf(
            "证券买入-中证 红利  1.546  -1,082.30",
            "买 09-23 10:48  700  0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
    }

    @Test
    fun `次行前夹了一行噪声仍能配对`() {
        val lines = listOf(
            "证券买入-中证红利  1.546  -1,082.30",
            "成交明细",
            "买 09-23 10:48  700  0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("2026-09-23", result[0].date)
        assertEquals(700.0, result[0].quantity, 0.001)
    }

    @Test
    fun `不会去抢下一笔交易的次行`() {
        // 第一笔缺次行、第二笔完整。错误实现会把第二笔的次行配给第一笔，
        // 造成数量与费用张冠李戴，因此这里必须只解析出 1 笔，且是第二笔的数据。
        val lines = listOf(
            "证券买入-缺次行  1.000  -100.0",
            "证券买入-完整  2.000  -400.20",
            "买 09-23 10:00  200  0.20"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("完整", result[0].name)
        assertEquals(200.0, result[0].quantity, 0.001)
        assertEquals(0.20, result[0].fee, 0.001)
    }

    // ---------- 以下为真实截图形态 ----------

    /**
     * 真实截图是三列表格（操作 / 价格数量 / 金额费用），ML Kit 会按列切块。
     * 识别结果重建为视觉行之后，就是这个形态——价格、金额与名称在同一行。
     * 这里的数字取自 2026-09-24 的真实对账单，并已用「价格×数量≈|金额|」验证过对齐关系。
     */
    @Test
    fun `真实列块形态（价格与金额在独立列）也能解析`() {
        val lines = listOf(
            "通用回购逆回购-GC001 1.445 -80,000.80",
            "其09-24 14:51 800 0.80",
            "证券买入科创50 1.721 -516.40",
            "09-24 14:02 300 0.10",
            "证券买入-中证红利 1.546 -1,082.30",
            "09-24 11:09 700 0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)

        // 逆回购不是证券买卖，应被跳过
        assertEquals(2, result.size)

        assertEquals("科创50", result[0].name)
        assertEquals("2026-09-24", result[0].date)
        assertEquals(1.721, result[0].price, 0.001)
        assertEquals(300.0, result[0].quantity, 0.001)
        assertEquals(TransactionType.BUY.value, result[0].type)

        assertEquals("中证红利", result[1].name)
        assertEquals(700.0, result[1].quantity, 0.001)
    }

    @Test
    fun `详情行没有方向前缀也能解析`() {
        // 真实截图里第 2 行可能只有日期，没有"买/卖"前缀
        val lines = listOf(
            "证券买入-中证红利 1.546 -1,082.30",
            "09-24 11:09 700 0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals(TransactionType.BUY.value, result[0].type)
        assertEquals("2026-09-24", result[0].date)
    }

    @Test
    fun `名称不带连字符也能解析`() {
        // 真实数据里既有「证券买入-中证红利」也有「证券买入科创50」
        val lines = listOf(
            "证券买入综指ETF 1.006 -503.10",
            "09-24 10:28 500 0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("综指ETF", result[0].name)
        assertEquals(1.006, result[0].price, 0.001)
    }

    @Test
    fun `名称独占一行、价格在下一行时也能解析`() {
        // 列块没合并成功时的兜底：价格独占一行且紧跟在名称行之后
        val lines = listOf(
            "证券买入科创50",
            "1.721",
            "-516.40",
            "09-24 14:02 300 0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("科创50", result[0].name)
        assertEquals(1.721, result[0].price, 0.001)
        assertEquals(300.0, result[0].quantity, 0.001)
    }

    @Test
    fun `金额与价格数量对不上时标记异常`() {
        // 列错位是最危险的失败模式——数字看着都正常，但配错了行。
        // 用「价格×数量 ≈ |金额|」交叉校验把这种情况暴露出来。
        val lines = listOf(
            "证券买入-中证红利 1.546 -9,999.99",
            "09-24 11:09 700 0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertTrue(result[0].errors.any { it.contains("列错位") })
    }

    @Test
    fun `最低佣金的小额交易不被交叉校验误拦`() {
        // 100 股 × 1.0 元 = 100 元，最低佣金 5 元 + 杂费后金额 105.50。
        // 相对偏差高达 5.5%（远超 2% 相对容差），但差额的绝对值只有 5.5 元，
        // 属于最低佣金档的真实交易，绝对容差（6 元）必须放行。
        val lines = listOf(
            "证券买入-某股票 1.000 -105.50",
            "09-24 11:09 100 5.50"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertTrue(result[0].errors.none { it.contains("列错位") })
    }

    @Test
    fun `逆回购行被跳过且不影响相邻交易`() {
        val lines = listOf(
            "通用回购逆回购-GC001  1.345  -83,000.83",
            "其 09-23 14:51  830  0.83",
            "证券买入-中证红利  1.546  -1,082.30",
            "买 09-23 10:48  700  0.10"
        )
        val result = GalaxyStatementParser.parse(lines, 2026)
        assertEquals(1, result.size)
        assertEquals("中证红利", result[0].name)
        assertEquals(1.546, result[0].price, 0.001)
    }
}
