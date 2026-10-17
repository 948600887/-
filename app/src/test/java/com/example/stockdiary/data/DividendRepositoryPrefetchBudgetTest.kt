package com.example.stockdiary.data

import com.example.stockdiary.data.repository.DividendRepository
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DividendRepository.preFetchDividends] 时间预算的数据完整性回归。
 *
 * ## 为什么值得单测
 *
 * 手动刷新的分红预取阶段现在带 30 秒子预算（见 [SyncBudget.prefetchPhaseBudget]），
 * 超时后**跳过**还没抓的代码。跳过本身不是问题，问题是「跳过」必须留不下任何痕迹：
 *
 * - 不写内存 `cache`；
 * - 不写 `fetchState` 的「上次抓取时间」。
 *
 * 这两笔一旦被写，「抓过，且确实没有分红」的结论会把空结果压 7 天
 * （[shouldReuseEmptyResult] + NEGATIVE_RESULT_TTL），那才是真的丢数据——
 * 界面上分红永远是空的，而且没有任何报错。SyncBudget 的注释声称
 * 「跳过的分红标的不会写缓存，之后按需抓取会补上，不存在数据丢失」，
 * 这个承诺必须有用例兜底，不能只活在注释里。
 *
 * 测法：对**真实**的 [DividendRepository] 实例（单例、mock Context 构造，
 * Room/SharedPreferences 都是 lazy，不碰）做 spy，把 [getDividendsBySymbol]
 * 整个桩掉——它是本流程里**唯一**写 cache / markFetched 的入口
 * （`markFetched` 全应用只有它一个调用点）。于是「跳过 ⇔ 它没被调用」
 * 就是数据完整性命题的直接观测。
 *
 * 符号全部选**场外基金**（`fund_` + 01xxxx OTC 代码）：批量预取对 OTC
 * 直接返回空，不走东财；配合 forceRefresh=true 跳过 tryGetFromCache，
 * 整条路不碰网络与 Room。
 */
class DividendRepositoryPrefetchBudgetTest {

    private val context = mockk<Context>(relaxed = true)

    /** 真实实例的 spy：抓取被桩掉，但预算/跳过逻辑走的是真代码 */
    private val repository = spyk(DividendRepository.getInstance(context))

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val symbols = listOf("fund_010001", "fund_010002", "fund_010003")

    @Test
    fun `预算充足时每个待查代码都走到逐条抓取`() = runBlocking {
        coEvery { repository.getDividendsBySymbol(any(), any(), any()) } returns emptyList()

        repository.preFetchDividends(symbols, forceRefresh = true, timeBudgetMs = 600_000L)

        symbols.forEach { symbol ->
            coVerify(exactly = 1) { repository.getDividendsBySymbol(symbol, true, emptyMap()) }
        }
        // 抓取是桩，什么都不该写进缓存
        assertTrue(repository.getCacheStats()["size"] == 0)
    }

    @Test
    fun `超出预算时未开始的代码被跳过_不抓取_不写缓存`() = runBlocking {
        // 预算只有 1ms：map 循环逐个创建 async 并分发到 IO，2000 个协程的
        // 创建+分发远不止 1ms，截止时刻之后创建的协程全部命中断言分支。
        // 不 mock 时钟——mockkStatic(System::class) 会让 MockK 的静态拦截器
        // 自我递归，直接 StackOverflow 把测试 JVM 炸掉（实测过，别试）。
        // 这里靠的机理与真实刷新完全一致：预算判据写的本就是「还没开始的」代码。
        // 2000 个代码全是 01xxxx 场外基金：批量预取对 OTC 直接返回空，
        // 不走东财，也不碰 Room。
        val manySymbols = (1..2000).map { "fund_01%04d".format(it) }
        val fetchedCount = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { repository.getDividendsBySymbol(any(), any(), any()) } coAnswers {
            fetchedCount.incrementAndGet()
            emptyList()
        }

        repository.preFetchDividends(manySymbols, forceRefresh = true, timeBudgetMs = 1L)

        // 最后一个代码一定创建于截止时刻之后：必须被跳过。
        // getDividendsBySymbol 是本流程唯一写 cache / markFetched 的入口
        // （markFetched 全应用只有它一个调用点），它对该代码零调用
        // ⇒ 这个代码不会留下「抓过且没有分红」的结论，按需抓取还会补上
        coVerify(exactly = 0) { repository.getDividendsBySymbol("fund_012000", true, emptyMap()) }
        // 抓取总数必须远小于代码总数：预算真的咬到了，不是形同虚设。
        // （机器再快，2000 个协程的创建+分发也远不止 1ms；走桩计数而不是
        // MockK 的 atMost——后者在零调用时也会报失败）
        assertTrue(
            "预算 1ms 只应放行极少数代码，实际抓取 ${fetchedCount.get()} 个",
            fetchedCount.get() < 500
        )
        // 走完这一轮，内存缓存里零写入
        assertTrue(repository.getCacheStats()["size"] == 0)
    }
}
