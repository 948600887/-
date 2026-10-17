package com.example.stockdiary.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TencentStockAPI.parseTencentData] 的回归（[parseTencentData] 已提为 internal 以可单测）。
 *
 * 只测真实代码：把 qt.gtimg.cn 真实形态的响应串喂进去，断言解析出的 [SinaStockData]。
 * 以下契约均按当前实现锁定（真实行为，非臆想）：
 *  - 一次只解析第一条 `v_xxx="..."` 匹配（[Regex.find] 而非 findAll）：批量响应也只返回第一只；
 *  - code 取自响应里的 v_ 前缀，与入参 stockCode 无关（该入参被 @Suppress UNUSED_PARAMETER）；
 *  - 涨跌/涨跌幅由「现价 - 昨收」现算，不读腾讯自带的 31/32 字段；
 *  - date 与 time 取同一个字段（下标 30）；
 *  - 现价=0 且昨收>0：退回昨收当有效价、不算停牌（涨跌未记 0，与 SinaStockAPI 不同，现状锁定）；
 *  - 现价与昨收都为 0：suspended[0]=true 并返回 null；
 *  - 字段 <11 个直接返回 null；11~34 个会因 33/34 下标越界被内层 catch 兜住转 null；都不崩。
 */
class TencentStockAPIParseTest {

    /** 构造一条腾讯行情响应：`v_代码="字段~字段~..."` */
    private fun quote(code: String, fields: List<String>): String =
        "v_$code=\"${fields.joinToString("~")}\""

    /**
     * 生成一段腾讯字段串：默认 49 个空段（对齐真实响应的字段数），
     * 用 [overrides] 按下标覆写。空段即「字段缺失/为空」，用来验证各处兜底。
     */
    private fun fieldsOf(
        size: Int = 49,
        overrides: Map<Int, String> = emptyMap()
    ): List<String> = (0 until size).map { overrides[it] ?: "" }

    @Test
    fun `正常响应解析_全字段按腾讯口径映射`() {
        val fields = listOf(
            "1",               // 0  未知（市场标识）
            "浦发银行",         // 1  名称
            "600000",          // 2  代码（注意：结果的 code 取自 v_ 前缀，不是这个字段）
            "9.99",            // 3  现价
            "10.00",           // 4  昨收
            "9.98",            // 5  今开
            "123456",          // 6  成交量(手)
            "0",               // 7  外盘
            "0",               // 8  内盘
            "9.98", "100",     // 9-10  买一价/买一量
            "9.97", "200",     // 11-12
            "9.96", "300",     // 13-14
            "9.95", "400",     // 15-16
            "9.94", "500",     // 17-18
            "10.01", "100",    // 19-20 卖一价/卖一量
            "10.02", "200",    // 21-22
            "10.03", "300",    // 23-24
            "10.04", "400",    // 25-26
            "10.05", "500",    // 27-28
            "0",               // 29 最近逐笔成交
            "20240105103000",  // 30 时间（date 与 time 都取这一字段）
            "-0.01",           // 31 涨跌（解析器不读，自行现算）
            "-0.10",           // 32 涨跌%（解析器不读，自行现算）
            "10.05",           // 33 最高价
            "9.90",            // 34 最低价
            "9.99/123/122.1",  // 35 价格/成交量(手)/成交额
            "123456",          // 36 成交量(手)
            "12210.00",        // 37 成交额(万)（amount 取这里，不取下标 8）
            "0.05",            // 38 换手率
            "5.20",            // 39 市盈率
            "",                // 40 未知
            "",                // 41
            "",                // 42
            "2.31",            // 43 振幅
            "2900.00",         // 44 流通市值(万)
            "3500.00",         // 45 总市值(万)
            "1.05",            // 46 市净率
            "10.50",           // 47 涨停价
            "9.50"             // 48 跌停价
        )
        val text = quote("sh600000", fields)

        // stockCode 入参不参与解析，故意传个不一样的值把「code 取自响应」钉住
        val suspended = booleanArrayOf(false)
        val d = TencentStockAPI.parseTencentData(text, "600000", suspended)

        assertNotNull(d)
        assertFalse(suspended[0])
        assertEquals("sh600000", d!!.code)
        assertEquals("浦发银行", d.name)
        assertEquals(9.99, d.price, 1e-9)          // 现价
        assertEquals(10.00, d.prevClose, 1e-9)     // 昨收
        assertEquals(9.98, d.open, 1e-9)           // 今开
        assertEquals(10.05, d.high, 1e-9)          // 最高（下标 33）
        assertEquals(9.90, d.low, 1e-9)            // 最低（下标 34）
        assertEquals(123456L, d.volume)            // 成交量(手)（下标 6）
        assertEquals(12210.0, d.amount, 1e-6)      // 成交额(万)（下标 37，非下标 8）
        assertEquals("20240105103000", d.date)     // date 与 time 同源（下标 30）
        assertEquals("20240105103000", d.time)
        assertEquals(5.20, d.peRatio, 1e-9)        // 市盈率（下标 39）
        assertEquals(3500.0, d.marketCap, 1e-6)    // 总市值(万)（下标 45）
        assertEquals(2900.0, d.circulatingCap, 1e-6) // 流通市值(万)（下标 44）
        assertEquals(0.05, d.turnoverRate, 1e-9)   // 换手率（下标 38）
        assertEquals(1.05, d.pbRatio, 1e-9)        // 市净率（下标 46）
        assertEquals(2.31, d.amplitude, 1e-9)      // 振幅（下标 43）
        // 涨跌由现价与昨收现算：(9.99 - 10.00) / 10.00 * 100
        assertEquals(-0.01, d.change, 1e-9)
        assertEquals(-0.1, d.changePercent, 1e-9)
    }

    @Test
    fun `涨跌与涨跌幅由现价昨收现算_不读腾讯自带字段`() {
        // 腾讯自带的 31=涨跌、32=涨跌% 塞明显错误的值为「999」，
        // 若解析器读它们，下面两个断言就会挂——以此锁定「现算」契约。
        val text = quote(
            "sh600000",
            fieldsOf(
                overrides = mapOf(
                    1 to "浦发银行",
                    3 to "9.99",   // 现价
                    4 to "10.00",  // 昨收
                    31 to "999",   // 腾讯自带涨跌（应被忽略）
                    32 to "888"    // 腾讯自带涨跌%（应被忽略）
                )
            )
        )

        val d = TencentStockAPI.parseTencentData(text, "sh600000")

        assertNotNull(d)
        assertEquals(-0.01, d!!.change, 1e-9)
        assertEquals(-0.1, d.changePercent, 1e-9)
    }

    @Test
    fun `批量响应只解析第一只_接口契约是单只`() {
        // 腾讯支持一次查多只，但 parseTencentData 用 find 只取第一条匹配。
        // 调用方（StockDataManager 容错链）要批量就自己分多次请求。
        val a = quote(
            "sh600000",
            fieldsOf(overrides = mapOf(1 to "浦发银行", 3 to "9.99", 4 to "10.00"))
        )
        val b = quote(
            "sh600519",
            fieldsOf(overrides = mapOf(1 to "贵州茅台", 3 to "1700.00", 4 to "1680.00"))
        )
        val text = "$a;$b"

        val d = TencentStockAPI.parseTencentData(text, "sh600000")

        assertNotNull(d)
        assertEquals("sh600000", d!!.code)
        assertEquals("浦发银行", d.name)
    }

    @Test
    fun `现价为 0 昨收大于 0_退回昨收且不算停牌`() {
        // 停牌/集合竞价前：腾讯返回的现价是 0，但昨收正常。
        // 有效价退到昨收（不能把 0 当价格塞进缓存），suspended 不置位。
        // 注意：与 SinaStockAPI 不同，腾讯解析器此时没有把 change/changePercent 记 0，
        // 这里按现状锁定其真实值；如需对齐 Sina 的行为应另行修 parseTencentData，本次只加测试。
        val text = quote(
            "sh600000",
            fieldsOf(overrides = mapOf(1 to "浦发银行", 3 to "0.00", 4 to "10.00"))
        )

        val suspended = booleanArrayOf(false)
        val d = TencentStockAPI.parseTencentData(text, "sh600000", suspended)

        assertNotNull(d)
        assertFalse(suspended[0])
        assertEquals(10.0, d!!.price, 1e-9)        // 有效价 = 昨收
        assertEquals(10.0, d.prevClose, 1e-9)
        assertEquals(-10.0, d.change, 1e-9)        // 现状：0 - 10，未记 0
        assertEquals(-100.0, d.changePercent, 1e-9)
    }

    @Test
    fun `现价与昨收都为 0_判停牌并返回 null`() {
        // 现价和昨收都是 0：该标的当前根本没有报价 → 记「停牌」而不是普通失败
        val text = quote(
            "sh600000",
            fieldsOf(overrides = mapOf(1 to "浦发银行", 3 to "0.00", 4 to "0.00"))
        )

        val suspended = booleanArrayOf(false)
        val d = TencentStockAPI.parseTencentData(text, "sh600000", suspended)

        assertNull(d)
        assertTrue("无报价标的应置位 suspended 标志", suspended[0])
    }

    @Test
    fun `字段数量不足 11_直接返回 null 不崩`() {
        // 只有 5 个字段，够不到 11 的下限，应安全返回 null（不抛、不置 suspended）
        val text = "v_sh600000=\"1~浦发银行~600000~9.99~10.00\""

        val suspended = booleanArrayOf(false)
        val d = TencentStockAPI.parseTencentData(text, "sh600000", suspended)

        assertNull(d)
        assertFalse(suspended[0])
    }

    @Test
    fun `字段数在 11 与 34 之间_越界被内层 catch 兜住返回 null`() {
        // 20 个字段：过了 11 的门槛，但够不着 33/34 下标 ——
        // IndexOutOfBounds 被 parseTencentData 外层 catch 兜住转 null，不会崩。
        val text = quote("sh600000", List(20) { "1" })

        val suspended = booleanArrayOf(false)
        val d = TencentStockAPI.parseTencentData(text, "sh600000", suspended)

        assertNull(d)
        assertFalse(suspended[0])
    }

    @Test
    fun `无 v_ 模式的脏数据_返回 null 不崩`() {
        val text = "这是一段跟行情无关的普通文本，没有 v_ 结构"

        val d = TencentStockAPI.parseTencentData(text, "sh600000")

        assertNull(d)
    }

    @Test
    fun `空响应串_返回 null 不崩`() {
        assertNull("空响应应返回 null 而非崩溃", TencentStockAPI.parseTencentData("", "sh600000"))
    }

    @Test
    fun `关键字段为空串或非数字_兜底 0 并按回落链取值`() {
        // 现价有效（12.34）但一批关键字段缺失/非数字：
        // 昨收=""→0（涨跌幅随之走 0.0 分支）；最高/最低/成交额按 ?: 回落链取兜底源；
        // 成交量字段是 "9.80"（非 Long）→ 0；市盈率="-" → 0。
        val text = quote(
            "sh600000",
            fieldsOf(
                overrides = mapOf(
                    1 to "兜底股票",
                    3 to "12.34",            // 现价有效
                    4 to "",                  // 昨收缺失 → 0.0
                    5 to "12.00",            // 今开，兼最高价的回落源
                    6 to "9.80",             // 兼最低价的回落源（本身非 Long → 成交量兜底 0）
                    8 to "3.50",             // 兼成交额的回落源
                    30 to "20240105093000",  // 时间
                    33 to "",                 // 最高价缺失 → 回落下标 5
                    34 to "",                 // 最低价缺失 → 回落下标 6
                    37 to "",                 // 成交额缺失 → 回落下标 8
                    39 to "-",                // 市盈率非数字 → 0.0
                    45 to ""                  // 总市值缺失 → 0.0
                )
            )
        )

        val suspended = booleanArrayOf(false)
        val d = TencentStockAPI.parseTencentData(text, "sh600000", suspended)

        assertNotNull(d)
        assertFalse(suspended[0])
        assertEquals("兜底股票", d!!.name)
        assertEquals(12.34, d.price, 1e-9)
        assertEquals(0.0, d.prevClose, 1e-9)
        assertEquals(12.34, d.change, 1e-9)          // 12.34 - 0
        assertEquals(0.0, d.changePercent, 1e-9)     // 昨收为 0 → 0.0 分支
        assertEquals(12.0, d.high, 1e-9)             // 回落下标 5
        assertEquals(9.80, d.low, 1e-9)              // 回落下标 6
        assertEquals(0L, d.volume)                   // "9.80" 不是 Long → 0
        assertEquals(3.50, d.amount, 1e-9)           // 回落下标 8
        assertEquals(0.0, d.peRatio, 1e-9)           // "-" → 0.0
        assertEquals(0.0, d.marketCap, 1e-9)
        assertEquals("20240105093000", d.date)
        assertEquals("20240105093000", d.time)
    }
}
