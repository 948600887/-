package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DividendIncomeUseCaseTest {

    private fun asset(symbol: String) = Asset(
        name = symbol,
        type = AssetType.STOCK,
        symbol = symbol,
        account = "默认",
        balance = 100.0
    )

    /**
     * 用计数 lambda 模拟一次真实抓取（带延迟让并发重叠），验证单飞：
     * 同一时刻多个调用者应当只真正触发一次底层抓取。
     */
    private fun countingLoader(counter: AtomicInteger): suspend (List<Asset>, Boolean, String) -> DividendIncomeLoader.IncomeResult =
        { assets, _force, _tag ->
            counter.incrementAndGet()
            kotlinx.coroutines.delay(50) // 模拟网络耗时
            DividendIncomeLoader.IncomeResult(
                dividendsBySymbol = assets.associate { it.symbol!! to 1.0 },
                recordsBySymbol = emptyMap(),
                totalAnnualIncome = assets.size.toDouble(),
                hasDividendData = true
            )
        }

    @Test
    fun `并发调用只触发一次底层抓取`() = runBlocking {
        val counter = AtomicInteger(0)
        val useCase = DividendIncomeUseCase(countingLoader(counter))
        val assets = listOf(asset("600900"), asset("000001"))

        // 同时发起 5 个调用，模拟冷启动三个页面一起拉分红
        val results = (1..5).map {
            async { useCase.load(assets, forceRefresh = false, tag = "test") }
        }.map { it.await() }

        assertEquals("并发调用应只真正抓取一次", 1, counter.get())
        results.forEach { assertEquals(2.0, it.totalAnnualIncome, 0.0) }
    }

    @Test
    fun `并发时强制刷新会绕过单飞重新抓取`() = runBlocking {
        val counter = AtomicInteger(0)
        val useCase = DividendIncomeUseCase(countingLoader(counter))
        val assets = listOf(asset("600900"))

        // 一个非强制 + 一个强制同时发起：强制刷新不应复用进行中的抓取
        val results = listOf(
            async { useCase.load(assets, forceRefresh = false, tag = "test") },
            async { useCase.load(assets, forceRefresh = true, tag = "test") }
        ).map { it.await() }

        assertEquals("强制刷新应额外触发一次抓取", 2, counter.get())
        results.forEach { assertEquals(1.0, it.totalAnnualIncome, 0.0) }
    }

    @Test
    fun `串行调用返回一致结果`() = runBlocking {
        val counter = AtomicInteger(0)
        val useCase = DividendIncomeUseCase(countingLoader(counter))
        val assets = listOf(asset("600900"))

        // 串行两次：单飞只去重并发，串行由底层 24h 缓存吸收，这里只验证结果一致、不抛异常
        val first = useCase.load(assets, forceRefresh = false)
        val second = useCase.load(assets, forceRefresh = false)
        assertEquals(1.0, first.totalAnnualIncome, 0.0)
        assertEquals(1.0, second.totalAnnualIncome, 0.0)
    }
}
