package com.example.stockdiary.utils

import android.content.Context
import com.example.stockdiary.common.CacheConfig
import com.example.stockdiary.network.NoOpRateSource
import com.example.stockdiary.network.RateSource
import com.example.stockdiary.network.SinaExchangeRateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 汇率管理器
 *
 * 提供主要货币对人民币的汇率，支持**联网刷新**（新浪外汇行情）。
 *
 * ## 三层结构
 *
 * 1. **静态默认表** [defaultRates]：内置兜底值，同时是脏数据护栏的基准线。
 *    永不改动——刷新失败、离线、进程刚启动时，折算口径退回这里。
 * 2. **当前汇率表** [liveRates]：`@Volatile` 不可变 Map，刷新成功时整体替换。
 *    读路径（[getRate] / [toCny] / [fromCny]）只做一次 map 查找，同步且廉价，
 *    可安全地在 Compose 组合期被扩展属性同步调用。
 * 3. **持久化**（[RateStore]）：刷新成功后连同时间戳落盘，冷启动读回，
 *    离线 / 进程重启后仍用上次的 live 汇率，而不是退回静态默认。
 *
 * ## 刷新（唯一写路径）
 *
 * [refresh] 是挂起函数：走 [RateSource] 拉数 → 护栏校验 → 合并 → 持久化。
 *
 * - TTL（[CacheConfig.EXCHANGE_RATE_TTL_MS]，6 小时）内不重复回源，`force = true` 跳过；
 * - **失败静默降级**：拉不到 / 全被护栏拒绝时保持当前汇率并返回 false，
 *   不抛异常、不弹提示、不阻塞任何链路——汇率刷新是后台增强，不是关键路径；
 * - **脏数据护栏**（[sanitize]）：只接受相对静态默认值 0.2x~5x 区间内、且 > 0
 *   的有限数值，防止接口返回垃圾 / 单位错乱（如「每百日元」报价）把全 App
 *   的钱数带偏。
 *
 * ## 触发点（刻意收拢，只有两处）
 *
 * - `StockDiaryApplication.onCreate`：fire-and-forget 刷一次（TTL 兜底）；
 * - `BinanceImportViewModel.fetch`：算加密资产预估值前刷一次（同样 TTL 兜底）。
 *
 * 汇率表示：1 单位外币 = ? CNY。**没有「按历史日期」的概念**——当前汇率会
 * 被应用到所有时期的折算上（与原先用静态值的语义一致，只是静态值现在会变）。
 */
object ExchangeRateManager {

    /** 静态默认汇率：护栏基准 + 一切降级路径的兜底。永不改动 */
    private val defaultRates = mapOf(
        "CNY" to 1.0,
        "USD" to 7.25,
        "HKD" to 0.93,
        "EUR" to 7.85,
        "JPY" to 0.048,
        "GBP" to 9.15,
        "AUD" to 4.75,
        "CAD" to 5.25,
        "SGD" to 5.35,
        "CHF" to 8.05
    )

    private val displayNames = mapOf(
        "CNY" to "人民币",
        "USD" to "美元",
        "HKD" to "港币",
        "EUR" to "欧元",
        "JPY" to "日元",
        "GBP" to "英镑",
        "AUD" to "澳元",
        "CAD" to "加元",
        "SGD" to "新加坡元",
        "CHF" to "瑞士法郎"
    )

    /**
     * 当前生效汇率。不可变 Map + `@Volatile` 整体替换：
     * UI 线程读、IO 协程写，读者永远看到一致快照，无需加锁。
     */
    @Volatile
    private var liveRates: Map<String, Double> = defaultRates

    /** 上次成功刷新（或读回）的时间戳；0 表示还没刷过 */
    @Volatile
    private var lastRefreshTimeMs: Long = 0L

    /**
     * 已经告警过的未知币种集合：每个未知币种只警告一次，避免 Compose 重组合期
     * 反复调 [getRate] 把日志刷爆。同进程内去重，不落盘——重启后重新告警即可。
     */
    private val warnedUnknownCurrencies = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 刷新互斥：防止启动触发与导入触发并发回源（TTL 判定在锁内进行） */
    private val refreshMutex = Mutex()

    /** 数据源。默认走新浪外汇；单测注入假源，完全离线可跑 */
    internal var rateSource: RateSource = SinaExchangeRateSource()

    /** 持久化层。默认进程内空存储，[init] 之后换成 SharedPreferences */
    internal var rateStore: RateStore = InMemoryRateStore()

    /**
     * 应用启动时调用：挂上 SharedPreferences 持久化，并读回上次的 live 汇率。
     * 幂等，重复调用无副作用。
     */
    fun init(context: Context) {
        rateStore = SharedPrefsRateStore(context)
        loadFromStore()
    }

    /**
     * 从持久化层读回汇率与时间戳（冷启动路径）。
     *
     * 读回的数据**同样要过护栏**：prefs 可能被写坏或来自旧版本格式，
     * 不能直接信。全部不可信时保持静态默认不动。
     */
    internal fun loadFromStore() {
        val saved = rateStore.load() ?: return
        val accepted = sanitize(saved.rates)
        if (accepted.isEmpty()) return
        liveRates = defaultRates + accepted
        lastRefreshTimeMs = saved.timestampMs
    }

    fun getRate(currency: String): Double {
        val code = currency.uppercase()
        return liveRates[code] ?: run {
            // 未知币种：保留 1.0 兜底（等同人民币，比返回 0 把金额清零安全），但告警一次，
            // 否则「离岸人民币」「泰铢」之类会被无声无息地按人民币汇总，错得毫无痕迹。
            if (warnedUnknownCurrencies.add(code)) {
                AppLogger.w(
                    "未知币种 '$code'，汇率按 1.0（等同人民币）兜底；请在 defaultRates 补汇率或核对币种代码",
                    "ExchangeRateManager"
                )
            }
            1.0
        }
    }

    fun toCny(amount: Double, currency: String): Double = amount * getRate(currency)

    fun fromCny(amountCny: Double, currency: String): Double {
        val rate = getRate(currency.uppercase())
        return if (rate > 0) amountCny / rate else amountCny
    }

    fun getDisplayName(currency: String): String = displayNames[currency.uppercase()] ?: currency.uppercase()

    /**
     * 中文币种名 → 货币代码。
     *
     * 基金E账户导出的「结算币种」列给的是中文名（「人民币」「美元」…），
     * 而库里存的是代码。认不出的返回 null，由调用方决定怎么办——
     * 这里**不**默认返回 CNY：把「离岸人民币」之类静默当成人民币，
     * 会让外币净值按人民币数值直接汇总，错得无声无息。
     */
    fun codeOfDisplayName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return displayNames.entries.firstOrNull { it.value == trimmed }?.key
    }

    fun supportedCurrencies(): List<String> = liveRates.keys.toList()

    /** 上次成功刷新时间；0 = 尚未刷新（用的是静态默认值）。供设置页展示 */
    fun getLastRefreshTimeMillis(): Long = lastRefreshTimeMs

    /**
     * 联网刷新汇率。
     *
     * @param force true 跳过 TTL 强制回源
     * @return true = 汇率已更新（或 TTL 内无需更新）；false = 刷新失败，
     *   当前汇率保持不变。失败不抛异常，最多记一条日志。
     */
    suspend fun refresh(force: Boolean = false): Boolean = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshTimeMs < CacheConfig.EXCHANGE_RATE_TTL_MS) {
            // TTL 内：汇率仍算新鲜，不再回源
            return true
        }

        val fetched = try {
            rateSource.fetch()
        } catch (e: CancellationException) {
            // 协程取消原样抛出，不当成刷新失败吞掉（项目铁律）
            throw e
        } catch (e: Exception) {
            AppLogger.w("汇率刷新失败，沿用当前汇率: ${e.message}", "ExchangeRateManager")
            null
        } ?: return false

        val accepted = sanitize(fetched)
        if (accepted.isEmpty()) {
            AppLogger.w("汇率源返回的数据全部不可信，沿用当前汇率", "ExchangeRateManager")
            return false
        }

        // 只覆盖响应里有的币种，其余保留现值（静态默认或上次的 live 值）
        liveRates = liveRates + accepted
        lastRefreshTimeMs = now
        rateStore.save(liveRates, now)
        AppLogger.i(
            "汇率已刷新: ${accepted.entries.joinToString { "${it.key}=${it.value}" }}",
            "ExchangeRateManager"
        )
        true
    }

    /**
     * 脏数据护栏：过滤出可信的汇率条目。
     *
     * 规则（同时满足才接受）：
     * - 币种必须在静态默认表里——不认识的币种一律不要；
     * - 数值有限且 > 0——拒绝 0 / 负数 / NaN / Inf；
     * - 落在静态默认值的 0.2x~5x 区间——低于疑似过时或缩水，高于疑似
     *   单位错乱（例如接口按「每百日元」报价：4.8 vs 默认 0.048，100 倍）。
     */
    private fun sanitize(fetched: Map<String, Double>): Map<String, Double> {
        val accepted = HashMap<String, Double>()
        for ((rawCode, value) in fetched) {
            val code = rawCode.trim().uppercase()
            val base = defaultRates[code] ?: continue
            if (!value.isFinite() || value <= 0.0) continue
            if (value < base * MIN_RATE_FACTOR) continue
            if (value > base * MAX_RATE_FACTOR) continue
            accepted[code] = value
        }
        return accepted
    }

    /**
     * 测试专用复位：汇率回到静态默认、时间戳清零、持久化层换新、
     * 数据源换空实现（保证单测绝不误触真实网络，需要造数据的用例自行注入假源）。
     *
     * `ExchangeRateManager` 是进程内单例，测试类之间共享状态——本类测试的
     * `@Before`/`@After` 都调它，避免 mutate 后的汇率漏给其它测试类
     * （如 BinanceImportServiceTest 钉的就是静态默认值 7.25）。
     */
    internal fun resetForTesting(store: RateStore = InMemoryRateStore()) {
        liveRates = defaultRates
        lastRefreshTimeMs = 0L
        rateStore = store
        rateSource = NoOpRateSource()
        warnedUnknownCurrencies.clear()
    }

    private const val MIN_RATE_FACTOR = 0.2
    private const val MAX_RATE_FACTOR = 5.0
}
