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
    companion object {
        /**
         * 最后一次成功结果的短 TTL（R4）。冷启动零请求重算只需几十毫秒，
         * 三 VM 的调用常错开几十毫秒就各起一个 loader；保留一份短 TTL 结果
         * 可把这段「串行重复计算」吸收掉，又不会让刷新拿到几分钟前的旧值。
         */
        private const val LAST_SUCCESS_TTL_MS = 1_500L
    }

    private val mutex = Mutex()
    private var inFlight: Deferred<DividendIncomeLoader.IncomeResult>? = null
    /** 与 [inFlight] 配对：记录那次在飞抓取的「请求签名」，用来判断别人能不能复用它 */
    private var inFlightKey: String? = null

    /**
     * 在飞任务是不是 cacheOnly（R4 freshness）。[requestKey] 不再区分 cacheOnly 后，
     * 必须靠这个标记保证「刷新(cacheOnly=false) 绝不复用冷启动(cacheOnly=true) 的旧值」；
     * 反过来冷启动复用刷新刚算出的新值是安全的。
     */
    private var inFlightCacheOnly: Boolean = true

    /** 最后一次成功结果（R4 短 TTL 复用），仅由发起方写入。 */
    private var lastSuccess: DividendIncomeLoader.IncomeResult? = null
    private var lastSuccessKey: String? = null
    private var lastSuccessAt: Long = 0L
    private var lastSuccessCacheOnly: Boolean = true

    suspend fun load(
        assets: List<Asset>,
        forceRefresh: Boolean = false,
        tag: String = "DividendIncomeUseCase",
        cacheOnly: Boolean = false
    ): DividendIncomeLoader.IncomeResult {
        // R4：cacheOnly 不再进入 requestKey（进场与刷新可对同一批资产共享单飞），
        // 改由 inFlightCacheOnly / lastSuccessCacheOnly 做 freshness 守卫。
        // R5：签名含按日汇率版本与年份，防止汇率刷新后复用旧收入、跨年复用平/闰年旧值。
        val key = requestKey(assets)
        val now = System.currentTimeMillis()

        // R4：最后一次成功结果短 TTL 复用。freshness 守卫保证「刷新不复用冷启动旧值」。
        val recent = mutex.withLock {
            val hit = lastSuccess
            if (!forceRefresh && hit != null && lastSuccessKey == key &&
                now - lastSuccessAt < LAST_SUCCESS_TTL_MS &&
                !(cacheOnly == false && lastSuccessCacheOnly)
            ) hit else null
        }
        if (recent != null) return recent

        // 阶段1（锁外快速路径）：先看有没有进行中**且签名一致**的抓取可复用。
        // 复用方直接等别人的结果，但**不**清理 inFlight（清理由原发起方负责），避免误清在飞任务。
        val reusable = mutex.withLock {
            if (!forceRefresh) inFlight?.takeIf {
                it.isActive && inFlightKey == key && !(cacheOnly == false && inFlightCacheOnly)
            } else null
        }
        if (reusable != null) return reusable.await()

        // 阶段2（持锁登记）：新起抓取，且登记必须在锁内完成。
        // 关键修复（单飞并发缝隙）：阶段1 读到的 reusable 可能为 null（两并发都还没登记），
        // 若不二次校验就各自 async，会出现「同一 key 两次真实抓取」。
        // 这里在锁内再查一次 —— 若此刻已有人发起了同 key 任务，就复用它而非重复发起。
        var isInitiator = false
        val deferred = mutex.withLock {
            val race = if (!forceRefresh) inFlight?.takeIf {
                it.isActive && inFlightKey == key && !(cacheOnly == false && inFlightCacheOnly)
            } else null
            race ?: scope.async { loader(assets, forceRefresh, tag, cacheOnly) }.also {
                inFlight = it
                inFlightKey = key
                inFlightCacheOnly = cacheOnly
                isInitiator = true
            }
        }
        return try {
            val result = deferred.await()
            // 只有发起方记录成功结果（复用方不重复写）；失败时 await 抛异常，不会记录。
            if (isInitiator) {
                mutex.withLock {
                    lastSuccess = result
                    lastSuccessKey = key
                    lastSuccessAt = System.currentTimeMillis()
                    lastSuccessCacheOnly = cacheOnly
                }
            }
            result
        } finally {
            // 抓取结束后清掉引用，下次非强制调用可重新走缓存路径。
            // 用 === 身份比较：只有「当前 inFlight 仍指向本次 deferred」时才清，
            // 并发复用方（拿到的是同一个 deferred）不会重复清，也不误清之后的新任务。
            mutex.withLock {
                if (inFlight === deferred) {
                    inFlight = null
                    inFlightKey = null
                }
            }
        }
    }

    /**
     * 请求签名：资产范围 + 按日汇率版本 + 年份一致，别的调用者才能复用这次在飞的抓取。
     *
     * R4：cacheOnly 不再参与签名（旧实现让进场 cacheOnly=true 与刷新 phase-2 各起一个
     * loader，是刷新期重复计算的来源之一）；改由 [inFlightCacheOnly] /
     * [lastSuccessCacheOnly] 做 freshness 守卫，保证刷新不会复用冷启动的旧结果。
     *
     * 不比对签名的后果（真实 bug）：三个页面传进来的 assets 范围**互不相同**——首页是全部非负债
     * 资产、持仓页是按用户勾选的可见类型 + 隐藏小额币种过滤后的子集、日历页只含
     * STOCK/FUND/BANK_DEPOSIT。若只看「有没有在飞」就复用，先起飞的那次会决定另外两个的结果：
     * 首页头条「预计年度收入」漏掉被持仓页过滤掉的资产（如银行存款利息），而且这个错值还会被
     * Dashboard 持久化进 annualDividendCache，下次进场先摆一个错数。
     * cacheOnly 同理：冷启动时 cacheOnly=true 的在飞请求会被手动刷新（cacheOnly=false）复用，
     * 于是刷新完分红数字纹丝不动——与 Dashboard 那句「这条不能 cacheOnly」的注释直接矛盾。
     *
     * 只取影响结果的字段，且必须与 [DividendIncomeLoader.computeDataHash] /
     * CalendarViewModel.calendarSignature 同一口径：symbol（取哪些分红）、type（存款/股票分支）、
     * balance（利息按余额算）、currency（外币折算）、interestRate（存款利率）、millionCopiesIncome（货基万份收益）。
     * 刻意不含 currentPrice/lastSyncTime：分红与利息跟实时价无关，价格抖动不该让签名失效。
     * 旧实现漏掉 type/interestRate/millionCopiesIncome，会让存款/货基的利率或万份收益变动、
     * 且恰在并发瞬间时复用到过期年度收入（坑②）。interestRate / millionCopiesIncome 可空，
     * 缺失用 -1.0 占位，与 computeDataHash 一致。排序后再拼，避免调用方传入顺序不同
     * 导致本可复用的两次抓取被判成不一样。
     */
    private fun requestKey(assets: List<Asset>): String =
        assets.map { a ->
            "${a.symbol}:${a.type}:${a.balance}:${a.currency}:" +
                "${a.interestRate ?: -1.0}:${a.millionCopiesIncome ?: -1.0}"
        }.sorted().joinToString(
            separator = ",",
            // R5：year 防跨年首帧复用到平/闰年切换的旧值；exchangeRateVersion 按日，
            // 避免一天十次汇率刷新把缓存打散。刻意不含 price / lastSyncTime。
            prefix = "${java.time.LocalDate.now().year}|${exchangeRateVersion()}|"
        )
}
