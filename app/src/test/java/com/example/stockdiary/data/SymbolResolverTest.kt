package com.example.stockdiary.data

import com.example.stockdiary.data.importer.SymbolResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 证券「名称 → 代码」解析单元测试。
 *
 * 这里是「 screenshot 导入要不要用户手填代码」的全部决策逻辑，核心约束是：
 * **宁可让用户手填，也不能自动填错**。填错会把 A 标的的流水记到 B 标的下，
 * 成本、持仓、盈亏全部错乱，而且是静默的。
 */
class SymbolResolverTest {

    private fun entry(name: String, symbol: String) = SymbolResolver.NameSymbol(name, symbol)

    // ---------- 优先级 ----------

    @Test
    fun `记忆库优先级最高，覆盖其它来源`() {
        val index = SymbolResolver.buildIndex(
            alias = mapOf("中证红利" to "sz515080"),
            holding = listOf(entry("中证红利", "sh515080")),
            basic = listOf(entry("中证红利", "sh000000"))
        )
        val hit = SymbolResolver.resolveExact("中证红利", index)
        assertNotNull(hit)
        assertEquals("sz515080", hit!!.symbol)
        assertEquals(SymbolResolver.Source.ALIAS, hit.source)
    }

    @Test
    fun `无记忆时按优先级取 持仓 历史流水 证券库 内置库`() {
        val index = SymbolResolver.buildIndex(
            holding = listOf(entry("贵州茅台", "sh600519")),
            history = listOf(entry("比亚迪", "sz002594")),
            basic = listOf(entry("五粮液", "sz000858")),
            preset = listOf(entry("浦发银行", "sh600000"))
        )
        assertEquals("sh600519", SymbolResolver.resolveExact("贵州茅台", index)?.symbol)
        assertEquals(
            SymbolResolver.Source.HISTORY,
            SymbolResolver.resolveExact("比亚迪", index)?.source
        )
        assertEquals(SymbolResolver.Source.BASIC, SymbolResolver.resolveExact("五粮液", index)?.source)
        assertEquals(SymbolResolver.Source.PRESET, SymbolResolver.resolveExact("浦发银行", index)?.source)
    }

    // ---------- 安全边界：不许自动填错 ----------

    @Test
    fun `同名多代码不自动回填，只进候选`() {
        // 沪深300ETF 有沪市 sh510300 与深市 sz159919 两只，谁都不能替用户决定
        val index = SymbolResolver.buildIndex(
            preset = listOf(
                entry("沪深300ETF", "sh510300"),
                entry("沪深300ETF", "sz159919")
            )
        )
        assertNull("同名多代码时不得自动回填", SymbolResolver.resolveExact("沪深300ETF", index))
        val candidates = SymbolResolver.suggest("沪深300ETF", index)
        assertTrue(candidates.any { it.symbol == "sh510300" })
        assertTrue(candidates.any { it.symbol == "sz159919" })
    }

    @Test
    fun `用户选过的代码优先于歧义判定`() {
        val index = SymbolResolver.buildIndex(
            alias = mapOf("沪深300ETF" to "sh510300"),
            preset = listOf(
                entry("沪深300ETF", "sh510300"),
                entry("沪深300ETF", "sz159919")
            )
        )
        val hit = SymbolResolver.resolveExact("沪深300ETF", index)
        assertNotNull(hit)
        assertEquals("sh510300", hit!!.symbol)
        assertEquals(SymbolResolver.Source.ALIAS, hit.source)
    }

    @Test
    fun `多数表决后仍并列才判歧义`() {
        // 历史流水里混入一条 OCR 噪声，但正解出现 2 次 —— 应当仍能自动回填正解
        val index = SymbolResolver.buildIndex(
            history = listOf(
                entry("中证红利", "sh515080"),
                entry("中证红利", "sh515080"),
                entry("中证红利", "sz000000")
            )
        )
        assertEquals("sh515080", SymbolResolver.resolveExact("中证红利", index)?.symbol)
    }

    @Test
    fun `完全没见过的名称不回填也不报错`() {
        val index = SymbolResolver.buildIndex(preset = listOf(entry("浦发银行", "sh600000")))
        assertNull(SymbolResolver.resolveExact("某某精选混合", index))
        assertNull(SymbolResolver.resolveExact("某某精选混合", SymbolResolver.Index()))
    }

    @Test
    fun `空名称与空代码不参与解析`() {
        val index = SymbolResolver.buildIndex(
            alias = mapOf("" to "sh600000"),
            holding = listOf(entry("贵州茅台", "")),
            preset = listOf(entry("浦发银行", "sh600000"))
        )
        assertNull(SymbolResolver.resolveExact("", index))
        assertNull(SymbolResolver.resolveExact("贵州茅台", index))
    }

    // ---------- 名称归一化 ----------

    @Test
    fun `名称中的空格 OCR 噪声不影响匹配`() {
        val index = SymbolResolver.buildIndex(holding = listOf(entry("中证红利", "sh515080")))
        assertEquals("sh515080", SymbolResolver.resolveExact("中证 红利", index)?.symbol)
    }

    @Test
    fun `全角字符与大小写不影响匹配`() {
        val index = SymbolResolver.buildIndex(holding = listOf(entry("红利ETF", "sh510880")))
        assertEquals("sh510880", SymbolResolver.resolveExact("红利ＥＴＦ", index)?.symbol)
        assertEquals("sh510880", SymbolResolver.resolveExact("红利etf", index)?.symbol)
    }

    @Test
    fun `ST 标记不会被当作同名`() {
        val index = SymbolResolver.buildIndex(basic = listOf(entry("海航控股", "sh600221")))
        // key() 会剥掉 '*'，但 "ST海航" 与 "海航控股" 本来就不是同一个名字，不能误匹配
        assertEquals("st海航", SymbolResolver.key("*ST海航"))
        assertNull(SymbolResolver.resolveExact("ST海航", index))
        assertEquals("sh600221", SymbolResolver.resolveExact("海航控股", index)?.symbol)
    }

    // ---------- 候选建议 ----------

    @Test
    fun `建议列表把精确命中排在最前`() {
        val index = SymbolResolver.buildIndex(
            holding = listOf(
                entry("中证红利ETF", "sh515080"),
                entry("中证白酒", "sz161725")
            )
        )
        val candidates = SymbolResolver.suggest("中证红利", index, limit = 4)
        assertTrue(candidates.isNotEmpty())
        assertEquals("sh515080", candidates.first().symbol)
    }

    @Test
    fun `建议按符号去重且不超过上限`() {
        val index = SymbolResolver.buildIndex(
            holding = listOf(entry("红利ETF", "sh510880")),
            basic = listOf(entry("红利ETF", "sh510880")),
            preset = listOf(entry("红利低波ETF", "sh512890"))
        )
        val candidates = SymbolResolver.suggest("红利", index, limit = 2)
        assertTrue(candidates.size <= 2)
        assertEquals(candidates.size, candidates.map { it.symbol }.toSet().size)
    }
}
