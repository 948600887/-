package com.example.stockdiary.data.dividend

import com.example.stockdiary.database.Asset
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 分红收入计算的统一入口。
 *
 * 之前 Dashboard / Portfolio / Calendar 三个 ViewModel 各自 `new DividendIncomeLoader` 并直接调
 * [DividendIncomeLoader.loadIncome]，同一份分红数据被重复抓取、重复计算：冷启动同时打开多屏
 * 就是多倍请求，且每个 VM 还要各自再跑一遍 TTM / 进度计算。
 *
 * 这里把抓取收口到单一入口，并用进程内 in-flight 单飞（[Mutex] + [Deferred]）：同一时刻多个调用者
 * 只真正发起一次抓取，其余直接复用进行中的结果。配合 [DividendRepository] 的 24h 缓存，
 * 重复请求基本归零。用例本身经 AppContainer 暴露为单例，所以单飞是跨 VM 的。
 *
 * @param loader 真正的抓取动作（生产环境是 [DividendIncomeLoader.loadIncome]）。用挂起 lambda 注入，
 *        方便单测在不依赖 Android Context 的情况下替换成计数桩。
 *        最后一个参数 cacheOnly 表示「只读缓存、TTL 过期也照用」，冷启动路径用它做到零请求。
 */
class DividendIncomeUseCase(
    private val loader: suspend (
        assets: List<Asset>,
        forceRefresh: Boolean,
        tag: String,
        cacheOnly: Boolean
    ) -> DividendIncomeLoader.IncomeResult
) {
    // 进行中的抓取挂在这个独立作用域上，而不是某个调用者的作用域：否则调用者一返回，
    // 它的 coroutineScope 会把进行中的 deferred 取消掉，复用方就再也 await 不到结果了。
    //
    // 必须带 CoroutineExceptionHandler：这个 scope 是 SupervisorJob 且**没有父作用域**，
    // 一旦 loader 抛出而调用方已经离开（deferred 再也没有人 await，典型的就是
    // 「刷新跑到一半用户切走」），异常就无处可去 —— 会一路抛到线程的默认处理器把进程杀掉，
    // 表现就是「刷新跑一段时间后闪退」，而且堆栈里还看不到业务代码。
    // 装上 handler 后：调用方 await 到的异常照旧交给调用方（不会被这里吞掉），
    // 只有真的没人接的那次才落到这里记录一行日志。
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                AppLogger.e("分红抓取协程异常（无人 await）: ${throwable.message}", throwable, "DividendIncome")
            }
    )
    private val mutex = Mutex()
    private var inFlight: Deferred<DividendIncomeLoader.IncomeResult>? = null
    /** 与 [inFlight] 配对：记录那次在飞抓取的「请求签名」，用来判断别人能不能复用它 */
    private var inFlightKey: String? = null

    suspend fun load(
        assets: List<Asset>,
        forceRefresh: Boolean = false,
        tag: String = "DividendIncomeUseCase",
        cacheOnly: Boolean = false
    ): DividendIncomeLoader.IncomeResult {
        val key = requestKey(assets, cacheOnly)
        // 非强制刷新时，先看有没有进行中**且签名一致**的抓取可复用（锁外读，不在持锁时 await）
        val existing = mutex.withLock {
            if (!forceRefresh) inFlight?.takeIf { it.isActive && inFlightKey == key } else null
        }
        if (existing != null) return existing.await()

        // 没在飞的（或签名不同、不能复用）就新起一个抓取，并登记为进行中
        val deferred = scope.async { loader(assets, forceRefresh, tag, cacheOnly) }
        mutex.withLock {
            inFlight = deferred
            inFlightKey = key
        }
        return try {
            deferred.await()
        } finally {
            // 抓取结束后清掉引用，下次非强制调用可重新走缓存路径
            mutex.withLock {
                if (inFlight === deferred) {
                    inFlight = null
                    inFlightKey = null
                }
            }
        }
    }

    /**
     * 请求签名：只有「资产范围」和「cacheOnly 语义」都一致，别的调用者才能复用这次在飞的抓取。
     *
     * 不比对签名的后果（真实 bug）：三个页面传进来的 assets 范围**互不相同**——首页是全部非负债
     * 资产、持仓页是按用户勾选的可见类型 + 隐藏小额币种过滤后的子集、日历页只含
     * STOCK/FUND/BANK_DEPOSIT。若只看「有没有在飞」就复用，先起飞的那次会决定另外两个的结果：
     * 首页头条「预计年度收入」漏掉被持仓页过滤掉的资产（如银行存款利息），而且这个错值还会被
     * Dashboard 持久化进 annualDividendCache，下次进场先摆一个错数。
     * cacheOnly 同理：冷启动时 cacheOnly=true 的在飞请求会被手动刷新（cacheOnly=false）复用，
     * 于是刷新完分红数字纹丝不动——与 Dashboard 那句「这条不能 cacheOnly」的注释直接矛盾。
     *
     * 只取影响结果的字段：symbol（取哪些分红）、balance（利息按余额算）、currency（外币折算）。
     * 刻意不含 currentPrice/lastSyncTime：分红与利息跟实时价无关，价格抖动不该让签名失效
     * （与 CalendarViewModel.calendarSignature 同一口径）。排序后再拼，避免调用方传入顺序不同
     * 导致本可复用的两次抓取被判成不一样。
     */
    private fun requestKey(assets: List<Asset>, cacheOnly: Boolean): String =
        assets.map { "${it.symbol}:${it.balance}:${it.currency}" }
            .sorted()
            .joinToString(separator = ",", prefix = "$cacheOnly|")
}
