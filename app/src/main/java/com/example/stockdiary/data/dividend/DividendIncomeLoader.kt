package com.example.stockdiary.data.dividend

import com.example.stockdiary.data.repository.DividendRepository
import com.example.stockdiary.data.sync.withHeartbeat
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

class DividendIncomeLoader(
    private val dividendRepository: DividendRepository,
    /**
     * 「预计收入」计算结果的持久层。默认 null：纯 JVM 单测没有 Android Context，
     * 传 null 时整条缓存逻辑自动跳过（行为退化为改造前，保证既有单测零改动、零网络）。
     * 生产环境由 [com.example.stockdiary.di.AppContainer] 注入真实实例。
     */
    private val incomeCache: DividendIncomeCache? = null
) {
    companion object {
        /** 与 AssetSyncManager.MAX_CONCURRENT_SYNC 同数量级：持仓上百支时不要一次打出上百个并发请求 */
        private const val MAX_CONCURRENT_FETCH = 8

        /** 计算当天日期的字符串格式（yyyy-MM-dd），与 [DividendIncomeCache.CachedIncome.date] 同口径 */
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
    }

    data class IncomeResult(
        val dividendsBySymbol: Map<String, Double>,
        /** 原始分红记录，供「收息进度 / 月度现金流 / 下一笔倒计时」使用 */
        val recordsBySymbol: Map<String, List<DividendRecord>> = emptyMap(),
        val totalAnnualIncome: Double,
        val hasDividendData: Boolean
    )

    /**
     * @param forceRefresh 默认 false。为 true 时会先清掉该标的的非手动分红记录再全量重抓，
     *        属于「数据可疑时手动修复」的动作，不应该成为默认行为——否则新加一个调用点忘了传参
     *        就会静默清库。
     * @param cacheOnly 冷启动用。为 true 时**只读缓存，TTL 过期也照用**（上限 30 天），
     *        一个请求都不发；只有当某个标的连过期数据都没有时才让它走正常抓取流程。
     *        打开 App 不该为了刷新「预计收入」去敲几十个接口 —— 那是用户点「刷新」的事。
     */
    suspend fun loadIncome(
        assets: List<Asset>,
        forceRefresh: Boolean = false,
        tag: String = "DividendIncomeLoader",
        cacheOnly: Boolean = false
    ): IncomeResult = withContext(Dispatchers.IO) {
        val stockAssets = assets.filter { it.type == AssetType.STOCK || it.type == AssetType.FUND }

        // ===== 同日短路：零网络复用上次算好的结果，杀掉同日抖动 =====
        // 抖动根因：同一天内 TTM 滑窗不变，但不同次抓取拿到的 records 集不同 → TTM 求和变。
        // 这里在发起任何抓取前，先零网络把各标的缓存记录取出来，连同持仓一起算 dataHash；
        // 若上次落盘的缓存「哈希一致 + 当天 + 非强制刷新」就直接复用，不进 prefetch、不算 TTM。
        // 哈希覆盖两路底层输入：分红记录（records 变了才重算）+ 持仓（当天加仓/调仓才重算）。
        // 仅当 records 真变、持仓真变或跨日（date != 今天）才重算 ——
        // 即「保留跨日 TTM 漂移 + 仅底层输入变化才重算」。
        if (!forceRefresh) {
            // 一次性批量取所有标的的缓存记录（内部一次 IN 查询 + 一次 SP getAll），
            // 取代逐标的的 2N 次 IO —— 240 个标的冷启动实测能省 3~4 秒。
            // 语义与逐标的 getCachedAllowingStale 逐分支一致，见该方法的 KDoc。
            val cachedBatch = dividendRepository.getCachedAllowingStaleBatch(
                stockAssets.mapNotNull { it.symbol }
            )
            val cachedRecords = LinkedHashMap<String, List<DividendRecord>>()
            var allHit = true
            for (asset in stockAssets) {
                val symbol = asset.symbol ?: continue
                // 注意 key 用**原始 symbol**（与改造前一致，调用方按 asset.symbol 取）；
                // 批量查的是规范化后的键，所以这里要 normalize 一次再查
                val data = cachedBatch[StockCodeManager.normalizeSymbol(symbol)]
                if (data == null) {
                    allHit = false
                    break
                }
                cachedRecords[symbol] = data
            }

            if (allHit && cachedRecords.isNotEmpty()) {
                val currentHash = computeDataHash(cachedRecords, assets)
                val today = LocalDate.now().format(DATE_FORMATTER)
                val cached = incomeCache?.loadCached(scopeKey(assets))
                // 同日 + 底层数据未变 → 直接复用缓存的 IncomeResult，彻底消掉同日抖动
                if (cached != null && cached.dataHash == currentHash && cached.date == today) {
                    AppLogger.d("同日且底层分红数据未变，复用缓存的预计收入（不重算）", tag)
                    return@withContext IncomeResult(
                        dividendsBySymbol = cached.dividendsBySymbol,
                        // 用刚读到的缓存 records 回填，保证收息进度/日历拿到的仍是当前记录
                        recordsBySymbol = cachedRecords,
                        totalAnnualIncome = cached.totalAnnualIncome,
                        hasDividendData = cached.hasDividendData
                    )
                }
                // 全命中但未复用（缓存为空 / 跨日 / 底层数据变了）：
                // cacheOnly 零请求重算并落盘；正常路径继续往下走抓取。
                if (cacheOnly) {
                    return@withContext buildAndSave(assets, cachedRecords, currentHash, today, tag)
                }
            }
            // allHit=false（有标的连过期数据都没有）时，cacheOnly 原有语义是「整批走抓取」，
            // 这里自然落到下面的正常路径，不会重复读一遍缓存。
        }

        val dividendsBySymbol = ConcurrentHashMap<String, Double>()
        val recordsBySymbol = ConcurrentHashMap<String, List<DividendRecord>>()
        val hasDividendFlag = AtomicBoolean(false)
        val doneCount = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT_FETCH)

        // 先把这一批一次性抓完（东财那条路合并成少量请求），再逐个读缓存。
        // 顺序不能反：先 prefetch 再逐个 getDividendsBySymbol，后者才会命中预取结果；
        // 反过来就是每个代码各发一次请求，批量白发。
        // 结果按次传回给下面的逐条循环：并发的另一个抓取周期有自己的一份，
        // 不会互相清空 —— 曾经的共享字段就是这么把批量结果弄丢、退回逐条级联的
        val batchResults = dividendRepository.prefetchDividendsBatch(
            symbols = stockAssets.mapNotNull { it.symbol },
            forceRefresh = forceRefresh
        )

        // 心跳：刷新后的这一步（「同步完成后再算一次收入」）同样可能跑几十秒，
        // 从前中间完全静默，卡住了只能干等。跑到 IO 线程之后更要在日志里留痕
        withHeartbeat(
            tag = tag,
            phase = "分红收入计算",
            status = { "已算完 ${doneCount.get()}/${stockAssets.size} 个标的" }
        ) {
            coroutineScope {
                stockAssets.map { asset ->
                    async {
                        val symbol = asset.symbol ?: return@async
                        val dividends = try {
                            semaphore.withPermit {
                                dividendRepository.getDividendsBySymbol(symbol, forceRefresh, batchResults)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("获取${symbol}分红失败: ${e.message}", e, tag)
                            emptyList()
                        }
                        val ttmDividend = DividendYieldCalculator.getTTMDividend(dividends)
                        dividendsBySymbol[symbol] = ttmDividend
                        recordsBySymbol[symbol] = dividends
                        if (ttmDividend > 0) hasDividendFlag.set(true)
                        doneCount.incrementAndGet()
                    }
                }.forEach { it.await() }
            }
        }

        val totalAnnualIncome = DividendProgressCalculator.naturalYearAnnualIncome(assets, recordsBySymbol, LocalDate.now())

        val result = IncomeResult(
            dividendsBySymbol = dividendsBySymbol,
            recordsBySymbol = recordsBySymbol,
            totalAnnualIncome = totalAnnualIncome,
            hasDividendData = hasDividendFlag.get()
        )

        // 落盘：仅当注入了缓存层。date=今天、dataHash=本次 records + 持仓的哈希，
        // 下次同日且底层输入（分红记录/持仓）不变时即可短路复用，杀掉同日抖动
        incomeCache?.save(
            result,
            computeDataHash(recordsBySymbol, assets),
            LocalDate.now().format(DATE_FORMATTER),
            scopeKey(assets)
        )

        result
    }

    /**
     * 从「symbol → 分红记录」直接算出结果并落盘，不走抓取。
     *
     * 抽出来是为了让冷启动的零请求路径和正常抓取路径**共用同一套汇总口径** ——
     * 两条路各算一遍 TTM 和年度收入，迟早会给出不一样的「预计收入」。
     */
    private fun buildAndSave(
        assets: List<Asset>,
        recordsBySymbol: Map<String, List<DividendRecord>>,
        dataHash: String,
        date: String,
        tag: String
    ): IncomeResult {
        val result = buildResult(assets, recordsBySymbol)
        incomeCache?.save(result, dataHash, date, scopeKey(assets))
        AppLogger.d("冷启动零请求重算预计收入并落盘（date=$date）", tag)
        return result
    }

    /**
     * 从「symbol → 分红记录」直接算出结果，不走抓取。
     *
     * 抽出来是为了让冷启动的零请求路径和正常抓取路径**共用同一套汇总口径** ——
     * 两条路各算一遍 TTM 和年度收入，迟早会给出不一样的「预计收入」。
     */
    private fun buildResult(
        assets: List<Asset>,
        recordsBySymbol: Map<String, List<DividendRecord>>
    ): IncomeResult {
        val dividendsBySymbol = recordsBySymbol.mapValues { (_, records) ->
            DividendYieldCalculator.getTTMDividend(records)
        }
        return IncomeResult(
            dividendsBySymbol = dividendsBySymbol,
            recordsBySymbol = recordsBySymbol,
            totalAnnualIncome = DividendProgressCalculator.naturalYearAnnualIncome(assets, recordsBySymbol, LocalDate.now()),
            hasDividendData = dividendsBySymbol.values.any { it > 0 }
        )
    }

    /**
     * 由「分红记录 + 持仓」算出**稳定哈希**，用于判断「预计收入的底层输入有没有变」。
     *
     * 预计收入 = Σ(持仓 × 每只标的 TTM 分红)，底层输入有两路，任一路变都要重算：
     *   1. 分红记录（symbol → 除息日/每股分红列表）——变了说明抓到新数据；
     *   2. 持仓（[DividendIncomeCalculator.calculateAnnualIncome] 的真实计算输入）——
     *      变了说明用户当天加仓/调仓，预计收入必须跟着更新。
     *
     * 持仓口径**严格对齐 calculateAnnualIncome 实际读到的字段**（已核对源码）：
     *   - 存款：年化利息 = totalValueCny × interestRate/100，而存款 totalValue = balance
     *     （存款不走 price），故输入为 balance + currency + interestRate；
     *   - 股票/基金：年化 = balance × toCny(每十股分红 或 每万份收益, currency) / 10（或 ×365），
     *     输入为 balance + currency + millionCopiesIncome（每股分红由分红记录那路覆盖）；
     *   - type 决定走哪条分支；symbol 决定从 dividendsBySymbol 取哪只的分红。
     *   **不含 price**：price 不参与 calculateAnnualIncome。把它算进哈希会让每次行情同步都
     *   触发重算，白白重开「重新抓取拿到不同 records」的抖动窗口（本方案正是要关掉它），
     *   且对预计收入毫无影响。
     *
     * 拼接前排序保证确定性，输出仍是稳定字符串。
     */
    /**
     * 「口径标识」：本次计算**覆盖哪些标的**的指纹，用于给持久化缓存分片。
     *
     * 与 [computeDataHash] 分工不同：后者是「底层数据有没有变」（变了就该重算），
     * 这里是「这次算的是哪一批标的」（不同批的结果本来就不一样，不能互相覆盖）。
     *
     * 不分片的后果（已实测）：三页传入的资产集合口径互不相同（首页=全量非负债、
     * 持仓页=按显示开关与隐藏小额过滤后的子集、日历页=只含股票/基金/存款），
     * dataHash 自然也不同，却共用同一把缓存键 —— 后算的覆盖先算的，先算的那页
     * 下次进来必然哈希不匹配而重算。冷启动表现为同一份分红被连算 2~3 遍。
     *
     * 只取 symbol 集合（去重 + 排序保证确定性）：预计收入的计算范围由「有哪些标的」
     * 决定，持仓数量的变化由 [computeDataHash] 负责判重，这里不重复计入。
     */
    private fun scopeKey(assets: List<Asset>): String =
        assets.mapNotNull { it.symbol }.distinct().sorted()
            .joinToString(",").hashCode().toString(36)

    private fun computeDataHash(
        recordsBySymbol: Map<String, List<DividendRecord>>,
        assets: List<Asset>
    ): String {
        val sb = StringBuilder()

        // 1) 分红记录部分：按 symbol 排序，每只再按（除息日, 每股分红）排序，与抓取/序列化顺序无关
        recordsBySymbol.keys.sorted().forEach { symbol ->
            sb.append(symbol).append('|')
            recordsBySymbol[symbol]
                ?.sortedWith(compareBy({ it.exDividendDate }, { it.dividendPerShare }))
                ?.forEach { record ->
                    sb.append(record.exDividendDate).append('=').append(record.dividendPerShare).append(';')
                }
            sb.append('\n')
        }

        // 2) 持仓指纹部分：calculateAnnualIncome 的真实计算输入（见上方口径说明）
        assets
            .sortedWith(compareBy({ it.symbol ?: "" }, { it.type.name }, { it.balance }))
            .forEach { asset ->
                sb.append("A:").append(asset.symbol ?: "-")
                    .append("|t=").append(asset.type.name)
                    .append("|bal=").append(asset.balance)
                    .append("|cur=").append(asset.currency)
                    .append("|mci=").append(asset.millionCopiesIncome ?: -1.0)
                    .append("|ir=").append(asset.interestRate ?: -1.0)
                    .append('\n')
            }

        // Java String.hashCode 是字符的纯函数、跨 JVM 运行稳定，足够做「内容是否相同」判等
        return sb.toString().hashCode().toString()
    }
}
