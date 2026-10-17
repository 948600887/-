package com.example.stockdiary.utils

import com.example.stockdiary.network.RateSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ExchangeRateManager] 取值 / 缺省回退 / 联网刷新 的回归。
 *
 * 说明：实现已从「静态汇率表」演进为「静态默认表 + 可联网刷新的当前汇率表 +
 * 持久化」。前 8 例（无刷新调用）钉的仍是**静态默认值语义**——未刷新时
 * getRate 必须回到内置默认；后 8 例覆盖刷新链路。
 *
 * 因为管理器是进程内单例、测试类之间共享状态，`@Before`/`@After` 都做
 * [ExchangeRateManager.resetForTesting] 复位：既保证本类用例互不污染，
 * 也保证 mutate 后的汇率不会漏给别的测试类（如 BinanceImportServiceTest
 * 钉的就是静态默认值 7.25）。
 */
class ExchangeRateManagerTest {

    @Before
    fun resetState() {
        ExchangeRateManager.resetForTesting()
    }

    @After
    fun resetStateAfter() {
        ExchangeRateManager.resetForTesting()
    }

    @Test
    fun `getRate 取 USD 与 CNY 基准汇率`() {
        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(1.0, ExchangeRateManager.getRate("CNY"), 1e-9)
    }

    @Test
    fun `getRate 大小写不敏感`() {
        assertEquals(7.25, ExchangeRateManager.getRate("usd"), 1e-9)
        assertEquals(0.93, ExchangeRateManager.getRate("Hkd"), 1e-9)
    }

    @Test
    fun `getRate 取不到币种时回退到 1_0 不崩`() {
        // 未知币种 / 空串都应安全回退到 1.0，而不是抛异常
        assertEquals(1.0, ExchangeRateManager.getRate("XYZ"), 1e-9)
        assertEquals(1.0, ExchangeRateManager.getRate(""), 1e-9)
        assertEquals(1.0, ExchangeRateManager.getRate("   "), 1e-9)
    }

    @Test
    fun `toCny 按汇率折算`() {
        assertEquals(725.0, ExchangeRateManager.toCny(100.0, "USD"), 1e-9)
        assertEquals(100.0, ExchangeRateManager.toCny(100.0, "CNY"), 1e-9)
        // 未知币种回退 1.0 → 原值
        assertEquals(100.0, ExchangeRateManager.toCny(100.0, "ZZZ"), 1e-9)
    }

    @Test
    fun `fromCny 反向折算且对零汇率安全`() {
        assertEquals(100.0, ExchangeRateManager.fromCny(725.0, "USD"), 1e-9)
        assertEquals(100.0, ExchangeRateManager.fromCny(100.0, "CNY"), 1e-9)
        // 汇率 > 0 才除，否则原值返回，避免除零
        assertEquals(100.0, ExchangeRateManager.fromCny(100.0, "ZZZ"), 1e-9)
    }

    @Test
    fun `getDisplayName 与 codeOfDisplayName 互转`() {
        assertEquals("美元", ExchangeRateManager.getDisplayName("USD"))
        assertEquals("人民币", ExchangeRateManager.getDisplayName("CNY"))
        // 未知代码原样返回大写
        assertEquals("XYZ", ExchangeRateManager.getDisplayName("xyz"))

        assertEquals("USD", ExchangeRateManager.codeOfDisplayName("美元"))
        assertEquals("CNY", ExchangeRateManager.codeOfDisplayName("人民币"))
        // 认不出返回 null，让调用方决定怎么办（不默默当成人民币）
        assertNull(ExchangeRateManager.codeOfDisplayName("离岸人民币"))
        assertNull(ExchangeRateManager.codeOfDisplayName(""))
    }

    @Test
    fun `supportedCurrencies 含主要币种`() {
        val cur = ExchangeRateManager.supportedCurrencies()
        assertTrue(cur.contains("CNY"))
        assertTrue(cur.contains("USD"))
        assertTrue(cur.contains("HKD"))
    }

    @Test
    fun `repeatCallStable 同一币种多次取值一致（等价未过期缓存）`() {
        // 未刷新时实现是静态默认表；刷新后 TTL 内也不回源。
        // 这钉死了「未过期命中」的语义：不会因调用次数多而漂移或触发回源。
        repeat(5) {
            assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
            assertEquals(1.0, ExchangeRateManager.getRate("CNY"), 1e-9)
            assertEquals(1.0, ExchangeRateManager.getRate("UNKNOWN"), 1e-9)
        }
    }

    // ==================== 以下为联网刷新新增用例 ====================

    @Test
    fun `刷新成功后 getRate 返回新值且未响应的币种保留默认`() = runTest {
        val source = FakeRateSource(result = mapOf("usd" to 6.9, "EUR" to 7.6))
        ExchangeRateManager.rateSource = source

        assertTrue(ExchangeRateManager.refresh())

        // 新值生效（源里故意给小写代码，管理器应大小写不敏感地归一）
        assertEquals(6.9, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(7.6, ExchangeRateManager.getRate("eur"), 1e-9)
        // 响应里没有的币种保留静态默认，不被清空
        assertEquals(0.048, ExchangeRateManager.getRate("JPY"), 1e-9)
        assertEquals(1.0, ExchangeRateManager.getRate("CNY"), 1e-9)
        assertTrue(ExchangeRateManager.getLastRefreshTimeMillis() > 0)
        assertEquals(1, source.callCount)
    }

    @Test
    fun `source 返回 null 时刷新失败且汇率不变`() = runTest {
        ExchangeRateManager.rateSource = FakeRateSource(result = null)

        assertFalse(ExchangeRateManager.refresh())

        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(0L, ExchangeRateManager.getLastRefreshTimeMillis())
    }

    @Test
    fun `source 抛异常时刷新失败且汇率不变`() = runTest {
        ExchangeRateManager.rateSource =
            FakeRateSource(error = java.io.IOException("network down"))

        // 失败必须静默：不抛给调用方，返回 false
        assertFalse(ExchangeRateManager.refresh())

        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(0L, ExchangeRateManager.getLastRefreshTimeMillis())
    }

    @Test
    fun `脏数据护栏 拒绝 0 负数 NaN 超区间与未知币种`() = runTest {
        ExchangeRateManager.rateSource = FakeRateSource(
            result = mapOf(
                "USD" to 0.0,            // 0 → 拒绝
                "EUR" to -7.8,           // 负数 → 拒绝
                "JPY" to 4.8,            // 约 100 倍（每百日元报价）→ 拒绝
                "GBP" to 100.0,          // 超出 5 倍上限 → 拒绝
                "AUD" to 0.1,            // 低于 0.2 倍下限 → 拒绝
                "SGD" to Double.NaN,     // NaN → 拒绝
                "XYZ" to 3.0,            // 未知币种 → 拒绝
                "HKD" to 0.88            // 合理值 → 接受
            )
        )

        assertTrue(ExchangeRateManager.refresh())

        assertEquals(0.88, ExchangeRateManager.getRate("HKD"), 1e-9)
        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(7.85, ExchangeRateManager.getRate("EUR"), 1e-9)
        assertEquals(0.048, ExchangeRateManager.getRate("JPY"), 1e-9)
        assertEquals(9.15, ExchangeRateManager.getRate("GBP"), 1e-9)
        assertEquals(4.75, ExchangeRateManager.getRate("AUD"), 1e-9)
        assertEquals(5.35, ExchangeRateManager.getRate("SGD"), 1e-9)
        assertFalse(ExchangeRateManager.supportedCurrencies().contains("XYZ"))
    }

    @Test
    fun `响应全不可信时刷新失败且不动现有汇率`() = runTest {
        ExchangeRateManager.rateSource =
            FakeRateSource(result = mapOf("USD" to 0.0, "EUR" to -1.0))

        assertFalse(ExchangeRateManager.refresh())

        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(7.85, ExchangeRateManager.getRate("EUR"), 1e-9)
        assertEquals(0L, ExchangeRateManager.getLastRefreshTimeMillis())
    }

    @Test
    fun `TTL 内二次 refresh 不再调 source_force 为 true 强制回源`() = runTest {
        val source = FakeRateSource(result = mapOf("USD" to 6.9))
        ExchangeRateManager.rateSource = source

        assertTrue(ExchangeRateManager.refresh())
        // TTL 内：命中缓存，不再调 source
        assertTrue(ExchangeRateManager.refresh())
        assertEquals(1, source.callCount)
        // force = true：跳过 TTL，强制回源
        assertTrue(ExchangeRateManager.refresh(force = true))
        assertEquals(2, source.callCount)
        assertEquals(6.9, ExchangeRateManager.getRate("USD"), 1e-9)
    }

    @Test
    fun `并发 refresh 只回源一次`() = runTest {
        val source = FakeRateSource(result = mapOf("USD" to 6.9))
        ExchangeRateManager.rateSource = source

        // 启动触发与导入触发可能撞车：互斥锁保证只有一个真正回源，
        // 另一个在锁内做 TTL 判定直接命中
        val results = listOf(
            async { ExchangeRateManager.refresh() },
            async { ExchangeRateManager.refresh() }
        ).awaitAll()

        assertEquals(1, source.callCount)
        assertTrue(results.all { it })
        assertEquals(6.9, ExchangeRateManager.getRate("USD"), 1e-9)
    }

    @Test
    fun `持久化_模拟进程重启后仍读回上次的 live 汇率`() = runTest {
        val store = InMemoryRateStore()
        ExchangeRateManager.rateStore = store
        ExchangeRateManager.rateSource = FakeRateSource(result = mapOf("USD" to 6.9, "HKD" to 0.88))
        assertTrue(ExchangeRateManager.refresh())

        // 模拟进程重启：内存态全丢（汇率回默认、时间戳清零），store 保留
        ExchangeRateManager.resetForTesting(store = store)
        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        // 冷启动读回（等价 Application.onCreate 里 init 的读回步骤）
        ExchangeRateManager.loadFromStore()
        assertEquals(6.9, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(0.88, ExchangeRateManager.getRate("HKD"), 1e-9)
        assertTrue(ExchangeRateManager.getLastRefreshTimeMillis() > 0)
    }

    @Test
    fun `读回的时间戳参与 TTL 判定_重启后不会立刻回源`() = runTest {
        val store = InMemoryRateStore()
        ExchangeRateManager.rateStore = store
        ExchangeRateManager.rateSource = FakeRateSource(result = mapOf("USD" to 6.9))
        assertTrue(ExchangeRateManager.refresh())

        // 重启 + 读回后，时间戳仍在 TTL 内 → 不应再回源
        ExchangeRateManager.resetForTesting(store = store)
        ExchangeRateManager.loadFromStore()
        val source = FakeRateSource(result = mapOf("USD" to 7.0))
        ExchangeRateManager.rateSource = source

        assertTrue(ExchangeRateManager.refresh())
        assertEquals(0, source.callCount)
        assertEquals(6.9, ExchangeRateManager.getRate("USD"), 1e-9)
    }

    @Test
    fun `读回持久化数据时同样过护栏_损坏数据被拒绝`() {
        // prefs 可能被写坏：999 远超 5 倍上限，必须拒绝而不是信
        val store = InMemoryRateStore()
        store.save(mapOf("USD" to 999.0, "HKD" to 0.88), timestampMs = 123L)
        ExchangeRateManager.resetForTesting(store = store)

        ExchangeRateManager.loadFromStore()

        assertEquals(7.25, ExchangeRateManager.getRate("USD"), 1e-9)
        assertEquals(0.88, ExchangeRateManager.getRate("HKD"), 1e-9)
        assertEquals(123L, ExchangeRateManager.getLastRefreshTimeMillis())
    }
}

/**
 * 可控假数据源：记录调用次数，按脚本返回数据或抛异常。
 * 全程不碰网络，单测离线可跑。
 */
private class FakeRateSource(
    private val result: Map<String, Double>? = null,
    private val error: Throwable? = null
) : RateSource {

    var callCount = 0
        private set

    override suspend fun fetch(): Map<String, Double>? {
        callCount++
        error?.let { throw it }
        return result
    }
}
