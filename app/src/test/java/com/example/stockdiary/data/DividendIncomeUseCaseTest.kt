package com.example.stockdiary.data

import com.example.stockdiary.data.dividend.DividendIncomeLoader
import com.example.stockdiary.data.dividend.DividendIncomeUseCase
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 读取 Kotlin `private var` 的底层字段（[DividendIncomeUseCase] 的在飞 / 上次成功等内部状态）。
 * 单测不依赖 Android Context，JVM 直接跑，字段名未被混淆，可经反射拿到，用于断言单飞收尾的
 * 内部不变量（inFlight / inFlightKey 是否清理、lastSuccess 是否被污染）。
 */
@Suppress("UNCHECKED_CAST")
private fun <T> readPrivate(instance: Any, fieldName: String): T {
    val field = instance.javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    return field.get(instance) as T
}

class DividendIncomeUseCaseTest {

    private fun asset(symbol: String) = Asset(
        name = symbol,
        type = AssetType.STOCK,
        symbol = symbol,
        account = "默认",
        balance = 100.0
    )

    private fun assetWith(
        symbol: String,
        interestRate: Double? = null,
        millionCopiesIncome: Double? = null,
        type: AssetType = AssetType.STOCK
    ) = Asset(
        name = symbol,
        type = type,
        symbol = symbol,
        account = "默认",
        balance = 100.0,
        interestRate = interestRate,
        millionCopiesIncome = millionCopiesIncome
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
    fun `并发同key绝不会重复抓取_即使阶段1复用检查都返回null`() = runBlocking {
        // 回归单飞并发缝隙：旧实现「读 existing(锁外) → async → 登记(锁内)」非原子，
        // 两个并发同 key 调用可能都在阶段1看到 null，于是各自 async 造成重复抓取。
        // 修复后阶段2 在锁内二次校验，无论调度如何交错，同一 key 只真正抓取一次。
        val counter = AtomicInteger(0)
        val useCase = DividendIncomeUseCase(countingLoader(counter))
        val assets = listOf(asset("600900"), asset("000001"))

        // 拉高并发数，放大「阶段1 都返回 null 再各自登记」的竞态窗口
        val results = (1..20).map {
            async { useCase.load(assets, forceRefresh = false, tag = "test") }
        }.map { it.await() }

        assertEquals("并发同 key 必须只真正抓取一次（单飞缝隙已堵）", 1, counter.get())
        results.forEach { assertEquals(2.0, it.totalAnnualIncome, 0.0) }
    }

    @Test
    fun `利率不同的同标的并发各自抓取_不共享单飞`() = runBlocking {
        // 回归坑②：requestKey 必须含 interestRate / millionCopiesIncome，否则存款/货基利率变动、
        // 且恰在并发瞬间时，单飞会复用「利率未更新」前的过期年度收入。
        // 这里两只同 symbol/balance/currency、仅 interestRate 不同的资产应被视为不同 key，
        // 各自触发一次抓取（counter==2），而非共享同一次。
        val counter = AtomicInteger(0)
        val useCase = DividendIncomeUseCase(countingLoader(counter))
        val lowRate = listOf(assetWith("600900", interestRate = 1.0))
        val highRate = listOf(assetWith("600900", interestRate = 2.0))

        val results = listOf(
            async { useCase.load(lowRate, forceRefresh = false, tag = "test") },
            async { useCase.load(highRate, forceRefresh = false, tag = "test") }
        ).map { it.await() }

        assertEquals("利率不同的同标的必须各自抓取（口径已对齐 computeDataHash）", 2, counter.get())
        results.forEach { assertEquals(1.0, it.totalAnnualIncome, 0.0) }
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
    fun `cacheOnly退出签名后_刷新不复用冷启动旧值但冷启动可复用刷新新值`() = runBlocking {
        // R4：requestKey 去掉 cacheOnly 维度后，必须用 freshness 守卫保证
        // 「刷新(cacheOnly=false) 绝不复用冷启动(cacheOnly=true) 的旧结果」，
        // 否则刷新会拿到进场零请求算出的旧收入，正是用户报告的抖动来源之一。
        var call = 0
        val useCase = DividendIncomeUseCase { _, _, _, _ ->
            call += 1
            DividendIncomeLoader.IncomeResult(
                dividendsBySymbol = emptyMap(),
                recordsBySymbol = emptyMap(),
                totalAnnualIncome = call.toDouble(),
                hasDividendData = true
            )
        }
        val assets = listOf(asset("600900"))

        val cold = useCase.load(assets, forceRefresh = false, cacheOnly = true)
        assertEquals(1.0, cold.totalAnnualIncome, 0.0)

        // 紧接着刷新：必须重新算，不能复用冷启动的 1.0
        val refreshed = useCase.load(assets, forceRefresh = false, cacheOnly = false)
        assertEquals(2.0, refreshed.totalAnnualIncome, 0.0)

        // 刷新后短时间内冷启动：可以复用刚算出的新值 2.0（短 TTL 结果复用）
        val coldAgain = useCase.load(assets, forceRefresh = false, cacheOnly = true)
        assertEquals(2.0, coldAgain.totalAnnualIncome, 0.0)
        assertEquals("刷新新增一次抓取，随后冷启动复用其结果", 2, call)
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

    @Test
    fun `并发单飞结束后inFlight与inFlightKey清理干净且lastSuccess只由发起方写入`() = runBlocking {
        // 回归单飞收尾：多个并发调用共享同一次抓取后，无论调度如何交错，阶段2 发起方的 finally
        // （=== 身份比较）必须把 inFlight / inFlightKey 清掉；复用方不得写入 lastSuccess，
        // 否则会覆盖发起方算出的正确结果（「lastSuccess 被污染」）。
        val counter = AtomicInteger(0)
        val useCase = DividendIncomeUseCase(countingLoader(counter))
        val assets = listOf(asset("600900"), asset("000001"))

        val results = (1..10).map {
            async { useCase.load(assets, forceRefresh = false, tag = "t") }
        }.map { it.await() }

        assertEquals("并发只真正抓取一次", 1, counter.get())
        results.forEach { assertEquals(2.0, it.totalAnnualIncome, 0.0) }
        // 收尾清理：在飞引用必须清空，否则后续调用无法重新走缓存 / 抓取
        assertNull(readPrivate<Any?>(useCase, "inFlight"))
        assertNull(readPrivate<String?>(useCase, "inFlightKey"))
        // lastSuccess 只由发起方写入一次，且等于唯一正确结果（复用方未污染）
        val last = readPrivate<DividendIncomeLoader.IncomeResult?>(useCase, "lastSuccess")
        assertNotNull(last)
        assertEquals(2.0, last!!.totalAnnualIncome, 0.0)
    }

    @Test
    fun `发起方await被取消后单飞状态清理且后续调用只触发一次新抓取`() = runBlocking {
        // 进程级 scope 上的在飞任务在调用方取消自己 await 时不会被取消；finally 的 === 清理仍须把
        // inFlight / inFlightKey 清掉，否则会卡死后续刷新；且这次失败 / 取消的调用绝不能污染 lastSuccess。
        val counter = AtomicInteger(0)
        val useCase = DividendIncomeUseCase { _, _, _, _ ->
            counter.incrementAndGet()
            delay(500) // 持续挂起，给调用方留出取消窗口
            DividendIncomeLoader.IncomeResult(emptyMap(), emptyMap(), 5.0, true)
        }
        val assets = listOf(asset("600900"))

        // 发起一次，但立刻取消这次 await（不取消底层在飞任务）
        val job = launch { useCase.load(assets, forceRefresh = false, tag = "t") }
        while (counter.get() == 0) delay(5) // 等 loader 真正登记
        job.cancel()
        delay(50) // 等 finally 跑完

        // 取消后 inFlight / inFlightKey 必须被清理（=== 身份比较命中），不残留卡死后续刷新
        assertNull(readPrivate<Any?>(useCase, "inFlight"))
        assertNull(readPrivate<String?>(useCase, "inFlightKey"))
        // 这次失败 / 取消的调用不应污染 lastSuccess
        assertNull(readPrivate<DividendIncomeLoader.IncomeResult?>(useCase, "lastSuccess"))

        // 后续一次全新调用：取消清理后必须只触发一次新抓取（不重复、不遗漏），且拿到正确值
        val result = useCase.load(assets, forceRefresh = false, tag = "t")
        assertEquals("取消清理后后续调用只多触发一次抓取（无残留重复）", 2, counter.get())
        assertEquals(5.0, result.totalAnnualIncome, 0.0)
        assertNotNull(readPrivate<DividendIncomeLoader.IncomeResult?>(useCase, "lastSuccess"))
    }
}
