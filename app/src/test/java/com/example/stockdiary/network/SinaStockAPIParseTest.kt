package com.example.stockdiary.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SinaStockAPI.parseRealtimeData] 的回归（[parseRealtimeData] 已提为 internal 以可单测）。
 *
 * 只测真实代码：把 hq.sinajs.cn 真实形态的响应串喂进去，断言解析出的 [SinaStockData]。
 * 重点三条易错分支：
 *  - 多字段正常解析（名称/开/昨收/现价/高/低/量/额/日期/时间 + 涨跌幅）
 *  - 非法 / 截断响应不崩（字段不足 32、无 hq_str_ 模式、空串都安全返回空 Map）
 *  - 昨收(prevClose)=0 时涨跌幅走 0.0 分支；现价=0 且昨收>0 时判为停牌(进 suspended 集合，不进结果)
 */
class SinaStockAPIParseTest {

    /** 构造一行标准 hq.sinajs 响应：索引 30=日期、31=时间，其余字段按新浪顺序 */
    private fun line(code: String, fields: List<String>): String =
        "var hq_str_$code=\"${fields.joinToString(",")}\";"

    @Test
    fun `正常多字段解析 sh600519`() {
        val fields = listOf(
            "贵州茅台",      // 0  名称
            "1700.00",      // 1  今开
            "1680.00",      // 2  昨收(prevClose)
            "1690.00",      // 3  现价
            "1710.00",      // 4  最高
            "1670.00",      // 5  最低
            "1685.00",      // 6  竞买
            "1686.00",      // 7  竞卖
            "123456",       // 8  成交量
            "209876543.00", // 9  成交额
            "100", "1685.00", "200", "1686.00", "300", "1684.00",
            "400", "1683.00", "500", "1682.00", "600", "1681.00",
            "700", "1680.00", "800", "1679.00", "900", "1678.00",
            "0",            // 28
            "0",            // 29
            "2024-01-05",   // 30 日期
            "09:30:00",     // 31 时间
            "0.00", "0.00"  // 32 33 多余字段
        )
        val text = line("sh600519", fields)

        val result = SinaStockAPI.parseRealtimeData(text, emptyList())

        assertEquals(1, result.size)
        val d = result["sh600519"]!!
        assertEquals("贵州茅台", d.name)
        assertEquals("sh600519", d.code)
        assertEquals(1700.0, d.open, 1e-9)
        assertEquals(1680.0, d.prevClose, 1e-9)   // 昨收
        assertEquals(1690.0, d.price, 1e-9)        // 现价=有效价
        assertEquals(1710.0, d.high, 1e-9)
        assertEquals(1670.0, d.low, 1e-9)
        assertEquals(123456L, d.volume)
        assertEquals(209876543.0, d.amount, 1e-6)
        assertEquals("2024-01-05", d.date)
        assertEquals("09:30:00", d.time)
        // 涨跌幅：(1690 - 1680) / 1680 * 100
        assertEquals(10.0, d.change, 1e-9)
        assertEquals(10.0 / 1680.0 * 100.0, d.changePercent, 1e-9)
    }

    @Test
    fun `多只股票一次解析`() {
        val a = line("sh600519", List(32) { i -> if (i == 0) "茅台" else if (i == 2) "100" else if (i == 3) "110" else "0" })
        val b = line("sz000858", List(32) { i -> if (i == 0) "五粮液" else if (i == 2) "200" else if (i == 3) "210" else "0" })
        val text = "$a\n$b"

        val result = SinaStockAPI.parseRealtimeData(text, emptyList())

        assertEquals(2, result.size)
        assertEquals("茅台", result["sh600519"]!!.name)
        assertEquals("五粮液", result["sz000858"]!!.name)
    }

    @Test
    fun `昨收为 0 时涨跌幅走 0_0 分支`() {
        val fields = List(32) { i ->
            when (i) {
                0 -> "X"
                2 -> "0"      // 昨收 = 0
                3 -> "5"      // 现价 > 0
                else -> "0"
            }
        }
        val text = line("sh000001", fields)

        val result = SinaStockAPI.parseRealtimeData(text, emptyList())
        val d = result["sh000001"]!!

        assertEquals(5.0, d.price, 1e-9)          // 现价有效 → 用现价
        assertEquals(0.0, d.prevClose, 1e-9)      // 昨收就是 0
        assertEquals(0.0, d.change, 1e-9)          // change 走 0.0 分支
        assertEquals(0.0, d.changePercent, 1e-9)    // changePercent 走 0.0 分支
    }

    @Test
    fun `现价 0 昨收大于 0 时退回昨收_涨跌记 0_仍算有效报价`() {
        // 停牌/集合竞价前：新浪返回的「现价」是 0，但昨收正常。
        // 此时有效价退回昨收（券商 App 通行做法），涨跌记 0 —— 不能把 0 当价格塞进缓存。
        val fields = List(32) { i ->
            when (i) {
                0 -> "Y"
                2 -> "10"     // 昨收 > 0
                3 -> "0"      // 现价 = 0
                else -> "0"
            }
        }

        val result = SinaStockAPI.parseRealtimeData(line("sh600000", fields), emptyList())
        val d = result["sh600000"]!!

        assertEquals(10.0, d.price, 1e-9)         // 有效价 = 昨收
        assertEquals(10.0, d.prevClose, 1e-9)
        assertEquals(0.0, d.change, 1e-9)          // 涨跌记 0
        assertEquals(0.0, d.changePercent, 1e-9)
    }

    @Test
    fun `现价与昨收都为 0 判为停牌_进 suspended_不进结果`() {
        // 现价和昨收都是 0：该标的当前根本没有报价 → 记「停牌」而不是普通失败
        val fields = List(32) { i -> if (i == 0) "Z" else "0" }
        val suspended = mutableSetOf<String>()

        val result = SinaStockAPI.parseRealtimeData(line("sh600001", fields), emptyList(), suspended)

        assertTrue("无报价标的不应出现在报价结果里", result.isEmpty())
        assertTrue("无报价标的应进入 suspended 集合", suspended.contains("sh600001"))
    }

    @Test
    fun `字段不足 32 被跳过不崩`() {
        // 只有 3 个字段，远少于 32，应安全跳过（不抛、不进结果）
        val text = "var hq_str_sh600519=\"茅台,1,2\";"
        val result = SinaStockAPI.parseRealtimeData(text, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `无 hq_str_ 模式的脏数据被跳过不崩`() {
        val text = "这是一段跟行情无关的普通文本，没有 var hq_str_ 结构"
        val result = SinaStockAPI.parseRealtimeData(text, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `空响应串返回空 Map 不崩`() {
        assertTrue("空响应应返回空 Map 而非崩溃", SinaStockAPI.parseRealtimeData("", emptyList()).isEmpty())
    }
}
