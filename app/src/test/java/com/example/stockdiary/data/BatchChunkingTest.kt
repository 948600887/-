package com.example.stockdiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量预取分块规则的回归。
 *
 * ## 为什么这个纯函数值得单独钉死
 *
 * 刷新按钮（`forceRefresh=true`）从前完全绕开批量预取，每个标的各打一遍完整级联，
 * 59 个标的打出 250~400 个请求 → OkHttp 队列排长龙 + 多协程并发 Jsoup 解析 →
 * 系统回收进程 → 用户看到「按了刷新就闪退」。修法就是让这条路径也走批量请求。
 *
 * 而批量请求有一个隐蔽的失败模式：所有代码拼进一个
 * `filter=(SECURITY_CODE in ("a","b",…))`，100+ 个代码的 URL 有好几 KB，
 * 服务端可能返回 414、OkHttp/代理也可能直接拒。**批量失败意味着整批预取悄悄退化成
 * 逐条级联** —— 请求风暴照旧、闪退照旧，而且日志里只有一行 warning，极难发现。
 *
 * 所以切块必须在发请求之前完成，且规则不能错：丢一个代码 = 那个标的退化成级联；
 * 切出超长块 = 整块被拒。这两个方向都只能用单测兜住。
 */
class BatchChunkingTest {

    /** 9 字符代码（如 "SH.510050"）的最坏编码开销：(9+3)*3 = 36 */
    private val longCode = "SH.510050"

    private fun codes(count: Int, code: String = longCode) = List(count) { code }

    @Test
    fun `空列表返回空分块`() {
        assertTrue(chunkSecurityCodes(emptyList()).isEmpty())
    }

    @Test
    fun `默认按数量上限切块_不丢不重且保持顺序`() {
        // 默认 MAX_SECURITY_CODES_PER_REQUEST = 50
        val input = (1..120).map { "SH.000%03d".format(it) }

        val chunks = chunkSecurityCodes(input)

        assertEquals(listOf(50, 50, 20), chunks.map { it.size })
        // 不丢不重：拼回去必须与原列表完全一致（顺序也一致）
        assertEquals(input, chunks.flatten())
    }

    @Test
    fun `单个代码就超预算时独占一块_不能把它切丢`() {
        // 预算只有 100，这个代码光自身编码开销就远超预算。
        // 独占一块意味着会发一个可能被拒的请求 —— 但那是「单个标的走级联」，
        // 比「批量整体失败、全部标的走级联」好得多，也绝不能让代码凭空消失
        val huge = "X".repeat(2000)

        val chunks = chunkSecurityCodes(
            codes = listOf(huge),
            maxUrlLength = 100,
            fixedOverhead = 100
        )

        assertEquals(1, chunks.size)
        assertEquals(listOf(huge), chunks.first())
    }

    @Test
    fun `长度预算先于数量上限触发`() {
        // 数量上限放到 100（不触发），长度预算 1000 / 固定基数 100：
        // 每个 9 字符代码开销 36，1000-100=900 的余量只放得下 25 个
        val input = codes(60)

        val chunks = chunkSecurityCodes(
            codes = input,
            maxCodesPerChunk = 100,
            maxUrlLength = 1000,
            fixedOverhead = 100
        )

        assertEquals(listOf(25, 25, 10), chunks.map { it.size })
        assertEquals(input, chunks.flatten())
        // 每一块的实际预算都不能超（按同一个公式回算）
        chunks.forEach { chunk ->
            val used = 100 + chunk.sumOf { (it.length + 3) * 3 }
            assertTrue("块超出长度预算: $used", used <= 1000)
        }
    }

    @Test
    fun `短代码按数量上限切_每块都不超限`() {
        val input = codes(200, code = "1")

        val chunks = chunkSecurityCodes(input, maxCodesPerChunk = 50)

        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it.size <= 50 })
        assertEquals(input, chunks.flatten())
    }

    @Test
    fun `默认预算下百个代码也只会撞数量上限_URL不会接近极限`() {
        // 回归文档里的估算：URLEncoder 会把引号/括号/逗号转义成 %XX，
        // 最坏 3 倍放大。默认 4000 预算 + 600 固定基数，
        // 50 个 9 字符代码 = 600 + 50*36 = 2400，离 8KB 的 URL 极限还远
        val chunks = chunkSecurityCodes(codes(200))

        assertTrue(chunks.all { it.size == 50 })
        chunks.forEach { chunk ->
            val worstCase = BATCH_URL_FIXED_OVERHEAD + chunk.sumOf { (it.length + 3) * 3 }
            assertTrue("最坏情况 URL 长度 $worstCase 超过预算", worstCase <= MAX_BATCH_URL_LENGTH)
        }
    }

    @Test
    fun `默认常量取值符合设计`() {
        // 每块 50 个代码：既能把百个代码压到 2~4 个请求，
        // 又不至于让单个 URL 长到被服务端/代理拒绝
        assertEquals(50, MAX_SECURITY_CODES_PER_REQUEST)
        // 3 个块并发：块数本来就少，别把东财当成压测对象
        assertEquals(3, MAX_CONCURRENT_BATCH_CHUNKS)
    }
}
