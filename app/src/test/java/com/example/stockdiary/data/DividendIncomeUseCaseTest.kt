package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
     *
     * 第四个参数 cacheOnly 只透传给 loader，计数桩不需要区分 ——
     * 「冷启动到底有没有发请求」由 [DividendIncomeLoader] 自己决定并单测。
     */
    private fun countingLoader(counter: AtomicInteger): suspend (List<Asset>, Boolean, String, Boolean) -> DividendIncomeLoader.IncomeResult =
        { assets, _force, _tag, _cacheOnly ->
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

    @Test
    fun `底层抓取抛异常时调用方仍能拿到，不被兜底handler吞掉`() = runBlocking {
        // 进程级 scope 上装了 CoroutineExceptionHandler（防止无人 await 的异常杀进程），
        // 但它**绝不能**把 await 这条路径的异常也吞了 —— 否则调用方的 try-catch
        // 全部失效，界面会一直显示旧值却不知道抓取其实失败了。
        val boom = RuntimeException("抓取炸了")
        val useCase = DividendIncomeUseCase { _, _, _, _ -> throw boom }
        val assets = listOf(asset("600900"))

        val thrown = runCatching {
            useCase.load(assets, forceRefresh = false, tag = "test")
        }.exceptionOrNull()

        // 注意：不能断言「就是 boom 那个对象」。协程跨线程传递异常时会复制一份实例
        // （Throwable.equals 是对象身份比较，两个 message 相同的实例并不相等），
        // 所以这里比的是类型 + 消息。
        assertTrue("异常必须照常抛给调用方，不能被兜底 handler 吞掉", thrown is RuntimeException)
        assertEquals("抓取炸了", thrown?.message)
    }

    @Test
    fun `单飞登记在被兜底后仍能清理，后续调用不受污染`() = runBlocking {
        // 上一次抓取失败并已抛给调用方后，inFlight 必须清掉：
        // 否则后续调用会一直 await 到那个已经失败的 deferred，永远拿到同一个异常。
        var attempt = 0
        val useCase = DividendIncomeUseCase { assets, _, _, _ ->
            attempt += 1
            if (attempt == 1) throw RuntimeException("第一次失败")
            DividendIncomeLoader.IncomeResult(
                dividendsBySymbol = assets.associate { it.symbol!! to 1.0 },
                recordsBySymbol = emptyMap(),
                totalAnnualIncome = 7.0,
                hasDividendData = true
            )
        }
        val assets = listOf(asset("600900"))

        runCatching { useCase.load(assets, forceRefresh = false, tag = "test") }

        val recovered = useCase.load(assets, forceRefresh = false, tag = "test")
        assertEquals("失败后应重新抓取，而不是复用失败的 deferred", 7.0, recovered.totalAnnualIncome, 0.0)
    }
}
