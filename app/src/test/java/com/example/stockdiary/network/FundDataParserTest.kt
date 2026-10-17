package com.example.stockdiary.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FundDataParser.parseNawData 的回归测试。
 *
 * 重点钉死「货币基金（货币基金）不再被误丢弃」这一修复：
 * 货币基金没有 Data_netWorthTrend，只有 Data_millionCopiesIncome，
 * 其单位净值恒为 1.0000。修复前 parseNawData 找不到走势数组会返回
 * NawData()（naw=0.0），随后被 FundAPI 的 `naw <= 0.0` 判定为无效而丢弃。
 */
class FundDataParserTest {

    @Test
    fun 货币基金无Data_netWorthTrend_返回单位净值1_0() {
        val fake = """var Data_millionCopiesIncome = [[1700000000000,1.0113]];"""
        val result = FundDataParser.parseNawData(fake)
        assertEquals(1.0, result.naw, 0.0001)
        // 货币基金同时解析出每万份收益（最后一个数据点的第二个值）
        assertEquals(1.0113, result.millionCopiesIncome!!, 0.0001)
    }

    @Test
    fun 普通基金_每万份收益为null() {
        val fake = """var Data_netWorthTrend = [{"x":1700000000000,"y":2.345,"equityReturn":1.2}];"""
        assertNull(FundDataParser.parseNawData(fake).millionCopiesIncome)
    }

    @Test
    fun 普通基金_仍按Data_netWorthTrend取最后一条() {
        val fake = """var Data_netWorthTrend = [{"x":1700000000000,"y":2.345,"equityReturn":1.2}];"""
        assertEquals(2.345, FundDataParser.parseNawData(fake).naw, 0.0001)
    }

    @Test
    fun 既无走势也无万份收益_返回0_0() {
        val fake = """var fS_name = "测试";"""
        assertEquals(0.0, FundDataParser.parseNawData(fake).naw, 0.0001)
    }
}
