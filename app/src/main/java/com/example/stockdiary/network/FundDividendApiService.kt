package com.example.stockdiary.network

import com.example.stockdiary.data.dividend.DividendHtmlParser
import com.example.stockdiary.data.dividend.DividendDateValidator
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.common.Timeouts
import com.example.stockdiary.network.NetworkClient
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 场外基金分红API服务
 * 使用天天基金分红配股页面获取基金分红数据
 */
class FundDividendApiService {

    companion object {
        private const val TAG = "FundDividendApi"
        private const val FHSP_URL = "https://fundf10.eastmoney.com/fhsp_{code}.html"
        // 天天基金页面可能给出"每份"或"每10份"两种单位，统一转换为"每10份"
        private const val MAX_DIVIDEND_PER_TEN_SHARES = 100.0

        /**
         * 进程级限流退避窗口（毫秒时间戳）。一旦被天天基金以 514/429 限流，
         * 在此时间戳之前，所有场外基金分红抓取统一短路跳过，不再对同一个被限流的主机
         * 用 OkHttp 或 Jsoup 反复猛打——那只会拉长封禁窗口。
         */
        @Volatile
        internal var rateLimitedUntil: Long = 0L

        /** 限流后强制退避时长（60 秒），覆盖绝大多数天天基金限流窗口。 */
        private const val RATE_LIMIT_BACKOFF_MS = 60_000L

        /**
         * 进程级「主机不可达」退避窗口（毫秒时间戳）。
         *
         * DNS 解析失败（UnknownHostException）是**确定性**故障：同一主机的后续请求必然
         * 同样失败，重试纯属浪费。而本函数失败后还会用 Jsoup 对**同一个 URL** 再抓一遍，
         * 于是网络不通时每只基金要等满两个超时——实测 240 个资产的组合里，场外基金分红
         * 抓取把整轮同步拖到 40~60 秒，结果还全是失败。撞到一次就整段时间短路跳过，
         * 把无效等待从「标的数 × 2 个超时」压到「一次」。
         */
        @Volatile
        internal var hostUnreachableUntil: Long = 0L

        /** 主机不可达后的退避时长（60 秒）：够覆盖一次网络切换恢复，又不至于长时间拒绝重试。 */
        private const val HOST_UNREACHABLE_BACKOFF_MS = 60_000L

        /**
         * 本轮是否撞过天天基金限流（514/429）。
         *
         * 与 [rateLimitedUntil]（进程级退避窗口，撞了就别再打）分开：
         * 这里只是「**本轮**打过且被限」的一个信号，供 Repository 在每轮预取结尾读走、
         * 再由 ViewModel 在刷新收尾时弹一个非阻塞 Snackbar
         * 「今天部分分红可能因限流没抓全」。Repository 每轮开头清零、结尾非消费读走。
         */
        @Volatile
        internal var otcRateLimitHit = false

        // ---- 天天基金 fhsp 整源熔断（通用失败：5xx / 超时 / 反爬空页 / 解析失败）----
        // 与上面两个「确定性单一原因」的短退避（rateLimitedUntil=514/429、hostUnreachableUntil=DNS）
        // 正交：这里处理「整个 fhsp 源在抖/挂」的通用情况——靠连续**通用失败**计数触发，
        // 指数退避 + 抖动。触发后整段时间内所有基金统一跳过「真实抓取」，避免对已经不健康的源
        // 反复猛打、把无效等待从「标的数 × 2 次超时」进一步压到「一次」，并给源喘息恢复的机会。
        // 514/429 与 DNS 由各自窗口处理、不计入本熔断（它们是确定的单一原因，已短退避）。
        private const val FHSP_FAIL_THRESHOLD = 5
        private const val FHSP_BACKOFF_BASE_MS = 30_000L       // 首档退避 30s
        private const val FHSP_BACKOFF_MAX_MS = 10 * 60_000L   // 上限 10min

        /** fhsp 整源熔断相位：CLOSED 放行 / OPEN 冷却短路（无需半开探测，靠真实取价成功自愈）。 */
        internal enum class FhspPhase { CLOSED, OPEN }
        private data class FhspGateState(val phase: FhspPhase, val blockedUntil: Long)

        /** fhsp 整源熔断状态（整包 CAS，防并发越界）。 */
        private val fhspState = AtomicReference(FhspGateState(FhspPhase.CLOSED, 0L))
        /** 连续通用失败计数（CLOSED 下累计；达阈值才熔断，OPEN 下仅作有界记账）。 */
        internal val fhspConsecutiveFailures = AtomicInteger(0)
        /** 已熔断次数（指数退避档位：第 n 次熔断退避 ≈ base×2^(n-1)）。 */
        internal val fhspBackoffHits = AtomicInteger(0)
        /** 诊断读数与供 Repository 读取的公开窗口（与 rateLimitedUntil 同风格）。 */
        @Volatile
        internal var fhspBlockedUntil: Long = 0L

        // 两档并发闸门：健康档 2 并发（与 AssetSyncManager.FUND_MAX_CONCURRENT_SYNC 对齐，不抢总闸）；
        // 降级档串行（Mutex）——刚从熔断恢复或正处于退避时，把对 fhsp 的并发压到 1，给源喘息。
        private val fhspHealthySemaphore = Semaphore(2)
        private val fhspDegradedMutex = Mutex()
        /** 降级标记：熔断触发后置 true（并发档切到串行），真实取价成功自愈后置 false。 */
        @Volatile
        internal var fhspDegraded: Boolean = false

        /** fhsp 整源熔断闸门：OPEN 且冷却未到 → true（调用方据此跳过真实抓取）。 */
        internal fun fhspIsBlocked(now: Long): Boolean {
            val s = fhspState.get()
            return s.phase == FhspPhase.OPEN && now < s.blockedUntil
        }

        /**
         * 记录一次 fhsp 通用失败：连续达 [FHSP_FAIL_THRESHOLD] 即熔断；冷却过期后的「探测失败」
         * 会重新熔断并把退避档位 +1（指数增长，30s×2^(n-1)，封顶 [FHSP_BACKOFF_MAX_MS]），
         * 再叠加最多 +30% 抖动平摊重试风暴。一次真正的成功（[fhspOnSuccess]）会清零档位，
         * 所以「源已恢复」不背惩罚，只有「反复失败」才会把退避越拉越长。
         */
        internal fun fhspOnFailure(now: Long) {
            while (true) {
                val s = fhspState.get()
                when (s.phase) {
                    FhspPhase.OPEN -> {
                        if (now < s.blockedUntil) {
                            // 冷却仍在进行：不顺延、仅把计数记一下（有界），返回
                            fhspConsecutiveFailures.incrementAndGet()
                            return
                        }
                        // 冷却已过期却仍是 OPEN = 冷却结束后的那次抓取（探测）失败：
                        // 重新熔断，退避档位 +1（指数增长），给源更长的喘息。
                        val hits = fhspBackoffHits.incrementAndGet()
                        val shift = (hits - 1).coerceAtMost(31)
                        val exp = (FHSP_BACKOFF_BASE_MS shl shift).coerceAtMost(FHSP_BACKOFF_MAX_MS)
                        val jitter = (Math.random() * exp * 0.3).toLong()  // 最多 +30% 抖动
                        val backoff = exp + jitter
                        val reopened = FhspGateState(FhspPhase.OPEN, now + backoff)
                        if (fhspState.compareAndSet(s, reopened)) {
                            fhspBlockedUntil = reopened.blockedUntil
                            fhspDegraded = true
                            AppLogger.w(
                                "天天基金(fhsp)冷却后探测仍失败，重新熔断 ${backoff / 1000} 秒" +
                                    "（档位 $hits，暂停抓取）",
                                TAG
                            )
                            return
                        }
                        // CAS 失败，重读重试
                    }
                    FhspPhase.CLOSED -> {
                        val consecutive = fhspConsecutiveFailures.incrementAndGet()
                        if (consecutive < FHSP_FAIL_THRESHOLD) return
                        // 达阈值：首次熔断，退避档位从 1 起（指数增长），+ 抖动
                        val hits = fhspBackoffHits.incrementAndGet()
                        val shift = (hits - 1).coerceAtMost(31)
                        val exp = (FHSP_BACKOFF_BASE_MS shl shift).coerceAtMost(FHSP_BACKOFF_MAX_MS)
                        val jitter = (Math.random() * exp * 0.3).toLong()  // 最多 +30% 抖动
                        val backoff = exp + jitter
                        val opened = FhspGateState(FhspPhase.OPEN, now + backoff)
                        if (fhspState.compareAndSet(s, opened)) {
                            fhspBlockedUntil = opened.blockedUntil
                            fhspDegraded = true
                            AppLogger.w(
                                "天天基金(fhsp)连续 $consecutive 次通用失败，整源熔断 ${backoff / 1000} 秒" +
                                    "（暂停抓取，至冷却结束自愈）",
                                TAG
                            )
                            return
                        }
                        // CAS 失败，重读重试
                    }
                }
            }
        }

        /** 记录一次 fhsp 真实成功：清零计数与退避档位，解除熔断与降级，恢复正常两档并发。 */
        internal fun fhspOnSuccess() {
            if (fhspState.get().phase == FhspPhase.OPEN || fhspConsecutiveFailures.get() != 0) {
                AppLogger.i("天天基金(fhsp)恢复，整源熔断解除", TAG)
            }
            fhspConsecutiveFailures.set(0)
            fhspBackoffHits.set(0)
            fhspState.set(FhspGateState(FhspPhase.CLOSED, 0L))
            fhspDegraded = false
        }

        /** 测试专用读数：当前 fhsp 熔断相位 */
        internal fun fhspPhaseForTesting(): FhspPhase = fhspState.get().phase
        /** 测试专用读数：当前是否处于整源熔断 OPEN（冷却中） */
        internal fun fhspIsOpenForTesting(): Boolean = fhspState.get().phase == FhspPhase.OPEN
        /** 测试专用读数：当前连续通用失败计数 */
        internal fun fhspConsecutiveForTesting(): Int = fhspConsecutiveFailures.get()
        /** 测试专用读数：已熔断次数（退避档位） */
        internal fun fhspBackoffHitsForTesting(): Int = fhspBackoffHits.get()

        /** 测试专用复位：清 fhsp 整源熔断状态，防漏给别的测试类（与 resetForTesting 同惯例）。 */
        internal fun fhspResetForTesting() {
            fhspState.set(FhspGateState(FhspPhase.CLOSED, 0L))
            fhspConsecutiveFailures.set(0)
            fhspBackoffHits.set(0)
            fhspBlockedUntil = 0L
            fhspDegraded = false
        }
    }

    // 复用统一客户端：通过 defaultClient.newBuilder() 派生，共享底层 ConnectionPool/Dispatcher
    // （连接池复用，避免每 API 类各建一个连接池的浪费）；defaultClient 不带 BaseUrlInterceptor，
    // 不会干扰天天基金 fhsp 页面的完整 https URL 请求。仅保留基金页面所需的长超时。
    // 超时统一走全局标准（common/APIConfig.kt 的 Timeouts），与 NetworkClient / EastmoneySkillApi 保持一致，
    // 避免各客户端各自硬编码、出现与全局策略不一致的魔数。
    internal var client: OkHttpClient = NetworkClient.defaultClient.newBuilder()
        .connectTimeout(Timeouts.CONNECT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(Timeouts.READ_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    /**
     * 获取场外基金分红历史
     * @param fundCode 纯数字基金代码，如 "110011"
     *
     * 编排：先做三道「顶部短路」——514/429 限流退避、DNS 主机不可达退避、整源熔断（fhsp）
     * 三者任一命中即直接失败、绝不发起网络请求（详见各自说明）；通过后才进入两档并发闸门，
     * 把真实抓取（[fetchFundDividendInner]）的并发控在「健康 2 / 降级串行」两档，最后按抓取
     * 成败精确记账整源熔断（成功→自愈，通用失败→累计触发）。
     */
    suspend fun getFundDividendHistory(fundCode: String): Result<List<DividendRecord>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // 进程级限流退避：一旦被天天基金 514/429 限流，整段时间内所有基金统一跳过，
        // 不再对同一个被限流的主机用 OkHttp 或 Jsoup 反复猛打（只会拉长封禁窗口）。
        if (now < rateLimitedUntil) {
            AppLogger.w("天天基金限流退避中，跳过 $fundCode 本次抓取", TAG)
            return@withContext Result.failure(Exception("天天基金限流退避中，跳过"))
        }
        // 主机不可达退避：DNS 都解析不了时，后续每只基金都会走同样的失败路径，
        // 不短路就是「标的数 × 2 个超时」的纯等待
        if (now < hostUnreachableUntil) {
            AppLogger.w("天天基金主机不可达退避中，跳过 $fundCode 本次抓取", TAG)
            return@withContext Result.failure(Exception("天天基金主机不可达退避中，跳过"))
        }
        // 整源熔断（fhsp 通用失败累计触发，指数退避）：整个 fhsp 源在抖/挂时统一短路，
        // 不再对已经不健康的源反复猛打。自愈由真实取价成功驱动（见 fetchFundDividendInner）。
        if (fhspIsBlocked(now)) {
            AppLogger.w("天天基金(fhsp)整源熔断冷却中，跳过 $fundCode 本次抓取", TAG)
            return@withContext Result.failure(Exception("天天基金整源熔断冷却中，跳过"))
        }

        // 两档并发闸门：降级（刚从熔断恢复/退避中）串行（Mutex），健康档 2 并发（Semaphore）。
        // 取快照决定用哪档——中途即便标记翻转也不影响本次已选定的闸门，避免半途切换死锁。
        val useDegraded = fhspDegraded
        if (useDegraded) fhspDegradedMutex.lock() else fhspHealthySemaphore.acquire()
        try {
            return@withContext fetchFundDividendInner(fundCode)
        } finally {
            if (useDegraded) fhspDegradedMutex.unlock() else fhspHealthySemaphore.release()
        }
    }

    /**
     * 真实抓取逻辑（OkHttp + Jsoup 兜底），不含顶部短路与并发闸门（由 [getFundDividendHistory] 负责）。
     *
     * 整源熔断记账在此按返回路径精确落位：
     * - 成功页（isRealDividendPage）或有数据的 Jsoup 结果 → [fhspOnSuccess] 自愈；
     * - 特定短退避（514/429、DNS）由各自窗口处理、不计入本熔断（直接失败、不调 [fhspOnFailure]）；
     * - 其余通用失败（5xx 落到反爬页、超时、解析全空等）→ 累计触发整源熔断。
     */
    private suspend fun fetchFundDividendInner(fundCode: String): Result<List<DividendRecord>> {
        val url = FHSP_URL.replace("{code}", fundCode)

        // 第一次尝试：使用 OkHttp
        try {
            AppLogger.apiUrl("GET", url, TAG)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "https://fundf10.eastmoney.com/")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                AppLogger.d("天天基金响应: code=${response.code}, isSuccessful=${response.isSuccessful}, contentType=${response.header("Content-Type")}", TAG)
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    AppLogger.d("天天基金HTML长度: ${html.length}, 是否包含分红表格: ${html.contains("每10份分红") || html.contains("每份分红")}", TAG)
                    val records = parseDividendHtml(html, fundCode)
                    AppLogger.d("OkHttp 解析到 ${records.size} 条基金分红记录", TAG)

                    // **请求成功就是拿到答案了，哪怕解析出 0 条。**
                    //
                    // 以前这里写成「解析到非空才返回」，于是「这只基金确实没有分红」
                    // 被当成失败，掉到下面用 Jsoup 把**同一个 URL** 再抓一遍。
                    // 实测：一次全量抓取里 525 次抓回 0 条、只有 77 次有数据（87% 是空的），
                    // 而这些空页面上明写着「暂无分红信息!」—— 第二遍必然也是 0。
                    // 等于每个空标的付双倍流量、多解析一遍 40KB 的 DOM，
                    // 而且 Jsoup 那条路遇到非 2xx 会抛 HttpStatusException，
                    // 诊断报告里那 106 次 HTTP 514（反爬限流）就是这么撞出来的。
                    //
                    // 唯一还值得换 Jsoup 重试的情况是**拿到的根本不是分红页**
                    // （反爬页 / 错误页），判据见 [isRealDividendPage]。
                    if (isRealDividendPage(html)) {
                        fhspOnSuccess()
                        return Result.success(records)
                    }
                    AppLogger.w("天天基金返回的页面不像分红页（可能是反爬页），换 Jsoup 重试", TAG)
                } else {
                    AppLogger.w("OkHttp HTTP 请求失败: ${response.code}", TAG)
                    // 限流（514/429）：设置退避窗口并直接失败，绝不再用 Jsoup 对同一个被限流
                    // 主机补刀——那只会让限流窗口更久。其它非 2xx 仍保留降级到 Jsoup 的机会。
                    // 注意：514/429 由 rateLimitedUntil 单独处理，**不计入整源熔断**（见 fhspOnFailure）。
                    if (response.code == 514 || response.code == 429) {
                        rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                        otcRateLimitHit = true
                        AppLogger.w("天天基金限流（${response.code}），本周期退避，不再用 Jsoup 兜底猛打", TAG)
                        return Result.failure(Exception("天天基金限流（${response.code}），本周期退避"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("OkHttp 获取天天基金分红失败: ${e.message}", TAG)
            // DNS 解析失败是确定性故障：下面 Jsoup 打的是同一个主机、必然同样失败，
            // 再补一遍等于让每只基金都白等第二个超时。设退避窗口并直接失败。
            // DNS 由 hostUnreachableUntil 单独处理，**不计入整源熔断**。
            if (isHostUnreachable(e)) {
                hostUnreachableUntil = System.currentTimeMillis() + HOST_UNREACHABLE_BACKOFF_MS
                AppLogger.w("天天基金主机不可达（DNS 解析失败），本轮退避 60s，不再用 Jsoup 兜底", TAG)
                return Result.failure(e)
            }
        }

        // 第二次尝试：使用 Jsoup 直接连接作为备用
        try {
            AppLogger.d("尝试使用 Jsoup 备用获取天天基金分红: $url", TAG)
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .referrer("https://fundf10.eastmoney.com/")
                .timeout(Timeouts.READ_MS)
                .get()
            val html = doc.html()
            AppLogger.d("Jsoup HTML长度: ${html.length}, 是否包含分红表格: ${html.contains("每10份分红") || html.contains("每份分红")}", TAG)
            val records = parseDividendHtml(html, fundCode)
            AppLogger.d("Jsoup 解析到 ${records.size} 条基金分红记录", TAG)
            if (records.isNotEmpty()) {
                fhspOnSuccess()
                return Result.success(records)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            // 限流（514/429）：同样设置退避窗口并直接失败，不再继续走最终的兜底失败逻辑
            // 或重复对限流主机发请求。其它 HttpStatusException（如 404/503）仍按普通异常处理。
            // 514/429 由 rateLimitedUntil 单独处理，**不计入整源熔断**。
            if (e.statusCode == 514 || e.statusCode == 429) {
                rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                otcRateLimitHit = true
                AppLogger.w("天天基金限流（${e.statusCode}），本周期退避，不再用 Jsoup 兜底猛打", TAG)
                return Result.failure(Exception("天天基金限流（${e.statusCode}），本周期退避"))
            }
            AppLogger.e("Jsoup 获取天天基金分红失败: ${e.message}", e, TAG)
        } catch (e: Exception) {
            AppLogger.e("Jsoup 获取天天基金分红失败: ${e.message}", e, TAG)
        }

        // 落到这里 = 通用失败（5xx 落到反爬页 / 超时 / 解析全空等）：累计触发整源熔断
        // （非 514/429、非 DNS——那两类由各自窗口短退避）。
        fhspOnFailure(System.currentTimeMillis())
        return Result.failure(Exception("无法获取天天基金分红数据"))
    }

    /**
     * 解析天天基金分红配股HTML页面
     * 表头示例：年份 | 权益登记日 | 除息日 | 每10份分红 | 分红发放日
     */
    internal fun parseDividendHtml(html: String, @Suppress("UNUSED_PARAMETER") fundCode: String): List<DividendRecord> {
        val records = mutableListOf<DividendRecord>()

        try {
            val doc = Jsoup.parse(html)
            val tables = doc.select("table")

            for (table in tables) {
                val tableText = table.text()
                if (!tableText.contains("权益登记日") &&
                    !tableText.contains("除息日") &&
                    !tableText.contains("分红")) {
                    continue
                }

                val headerIndices = parseFundDividendHeaders(table)
                if (headerIndices.exDateIndex < 0 || headerIndices.dividendIndex < 0) {
                    AppLogger.d("未识别到分红表头，尝试下一表格", TAG)
                    continue
                }

                val rows = table.select("tr")
                for (i in 1 until rows.size) {
                    val row = rows[i]
                    val cells = row.select("td")
                    if (cells.size <= maxOf(headerIndices.exDateIndex, headerIndices.dividendIndex)) continue

                    try {
                        val rawExDate = cells.getOrNull(headerIndices.exDateIndex)?.text()?.trim() ?: ""
                        val regDate = cells.getOrNull(headerIndices.regDateIndex)?.text()?.trim() ?: ""
                        val dividendText = cells.getOrNull(headerIndices.dividendIndex)?.text()?.trim() ?: ""

                        // 除息日过语义校验：除了原来的形态正则，再加真实日历校验，
                        // 挡掉 2026-13-01 / 2026-02-30 这类脏日期——它曾让消费端
                        // month=13 炸 IndexOutOfBounds（见 DividendDateValidator 注释）。
                        // 归一后恒为 10 字符 yyyy-MM-dd，下游可直接当合法日期用。
                        val exDate = DividendDateValidator.normalizeOrNull(rawExDate) ?: continue

                        val dividendPerShare = parseFundDividendAmount(dividendText)
                        if (dividendPerShare > 0 && dividendPerShare <= MAX_DIVIDEND_PER_TEN_SHARES) {
                            val fiscalYear = exDate.substring(0, 4)
                            records.add(
                                DividendRecord(
                                    exDividendDate = exDate,
                                    dividendPerShare = dividendPerShare.toString(),
                                    announceDate = regDate,
                                    fiscalYear = fiscalYear,
                                    // 钱真正到账的日子，比除息日通常晚 1~4 周
                                    paymentDate = parsePayDate(cells, headerIndices.payDateIndex)
                                )
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("解析基金分红行失败: ${e.message}", TAG)
                        continue
                    }
                }

                if (records.isNotEmpty()) break
            }

            // 兜底：按行查找包含"每10份派"或"每份派"的分红数据
            if (records.isEmpty()) {
                val allRows = doc.select("tr")
                for (row in allRows) {
                    val cells = row.select("td")
                    if (cells.size < 3) continue

                    var date = ""
                    var dividendText = ""

                    for (cell in cells) {
                        val text = cell.text().trim()
                        // 兜底路径与主路径用同一把尺子：形态 + 真实日历双重校验，
                        // 脏日期（2026-13-01 等）连备选资格都不该有
                        val normalized = DividendDateValidator.normalizeOrNull(text)
                        if (normalized != null) {
                            date = normalized
                        }
                        if (text.contains("每10份派") || text.contains("每份派")) {
                            dividendText = text
                        }
                    }

                    if (date.isNotEmpty() && dividendText.isNotEmpty()) {
                        val dividendPerShare = parseFundDividendAmount(dividendText)
                        if (dividendPerShare > 0 && dividendPerShare <= MAX_DIVIDEND_PER_TEN_SHARES) {
                            val fiscalYear = if (date.length >= 4) date.substring(0, 4) else ""
                            records.add(
                                DividendRecord(
                                    exDividendDate = date,
                                    dividendPerShare = dividendPerShare.toString(),
                                    announceDate = "",
                                    fiscalYear = fiscalYear
                                )
                            )
                        }
                    }
                }
            }

            records.sortByDescending { it.exDividendDate }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("HTML解析异常: ${e.message}", e, TAG)
        }

        return records
    }

    private data class FundDividendHeaderIndices(
        val exDateIndex: Int,
        val regDateIndex: Int,
        val dividendIndex: Int,
        val payDateIndex: Int
    )

    private fun parseFundDividendHeaders(table: org.jsoup.nodes.Element): FundDividendHeaderIndices {
        // 顺序敏感：payDate 必须先于 dividend，否则「分红发放日」会被泛化的「分红」抢走该列。
        // 与旧 when 分支顺序（除息日→权益登记日→分红发放日→分红）保持一致。
        val idx = DividendHtmlParser.resolveHeaderIndices(
            table,
            listOf(
                DividendHtmlParser.ColumnSpec("exDate", listOf("除息日"), 2),
                DividendHtmlParser.ColumnSpec("regDate", listOf("权益登记日"), 1),
                DividendHtmlParser.ColumnSpec("payDate", listOf("分红发放日", "派息日"), 4),
                DividendHtmlParser.ColumnSpec(
                    "dividend",
                    listOf("每10份分红", "每份分红", "每10份派", "每份派", "分红"),
                    3
                )
            )
        )
        val exDateIndex = idx["exDate"] ?: -1
        val regDateIndex = idx["regDate"] ?: -1
        val dividendIndex = idx["dividend"] ?: -1
        val payDateIndex = idx["payDate"] ?: -1

        if (exDateIndex < 0) AppLogger.d("未识别除息日列，使用默认索引 2", TAG)
        if (dividendIndex < 0) AppLogger.d("未识别分红金额列，使用默认索引 3", TAG)
        if (payDateIndex < 0) AppLogger.d("未识别分红发放日列，使用默认索引 4", TAG)

        return FundDividendHeaderIndices(exDateIndex, regDateIndex, dividendIndex, payDateIndex)
    }

    /** 取「分红发放日」单元格文本，格式不对就当没有 */
    private fun parsePayDate(cells: org.jsoup.select.Elements, index: Int): String? {
        if (index < 0) return null
        val text = cells.getOrNull(index)?.text()?.trim().orEmpty()
        return if (text.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) text else null
    }

    /**
     * 解析天天基金分红金额文本，统一转换为"每10份"单位
     * 支持："每10份派现金0.2000元" -> 0.2，"每份派现金0.0200元" -> 0.2
     * 注意：不能简单提取所有数字，否则会误把"每10份"中的"10"也当作金额一部分。
     */
    internal fun parseFundDividendAmount(text: String): Double {
        if (text.isBlank()) return 0.0

        // 标准格式：每10份派现金0.2000元 / 每份派现金0.0200元
        // 提取"派现金"或"派"后面的数字作为金额
        val match = Regex("派(?:现金)?([0-9]+(?:\\.[0-9]+)?)").find(text)
        val numericValue = if (match != null) {
            match.groupValues[1].toDoubleOrNull() ?: 0.0
        } else {
            // 兜底：提取所有数字（此时已无法确定单位，按默认每10份处理）
            AppLogger.w("无法按标准格式解析分红金额: $text，尝试兜底提取", TAG)
            text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
        }

        if (numericValue <= 0) return 0.0

        return when {
            text.contains("每10份") -> numericValue
            text.contains("每份") -> numericValue * 10.0
            else -> {
                AppLogger.w("无法识别的分红单位: $text，默认按每10份处理", TAG)
                numericValue
            }
        }
    }
}

/**
 * 判断这页是不是**真的分红页**（而不是反爬页 / 错误页）。
 *
 * 判据是「有没有分红表的表头」，**不是**「有没有数据行」：
 * 一只从没分过红的基金，页面上照样是
 * 「年份 | 权益登记日 | 除息日 | 每10份分红 | 分红发放日」表头 + 一行「暂无分红信息!」
 * —— **表头在、数据不在**。表头在就说明拿对了页面，此时 0 条是真实答案，
 * 不该再抓一遍（以前就因为这里判错，87% 的空标的都被抓了第二遍，
 * 还把接口往 HTTP 514 限流上撞）；表头不在才说明拿到了反爬页，值得换条路重试。
 *
 * 两个关键词都收：极端情况下页面可能只渲染出表头、或只渲染出空提示。
 * 放在文件级而不是类里，是为了能直接 JVM 单测（不用构造带 OkHttp 客户端的实例）。
 */
internal fun isRealDividendPage(html: String): Boolean =
    html.contains("权益登记日") || html.contains("暂无分红信息")

/**
 * 判断异常是不是「主机不可达」（DNS 解析失败）。
 *
 * 只看 UnknownHostException：它是**确定性**故障（同一个域名解析不了，重试也解析不了），
 * 值得整轮退避。超时/连接重置这类**偶发**故障不在此列——它们下一只可能就成了，
 * 整轮短路会白白牺牲正常的标的，所以刻意不收。
 *
 * OkHttp 会把真实原因包在 IOException 的 cause 链里，故要沿 cause 找一层。
 * 与 isRealDividendPage 一样放文件级，便于 JVM 单测。
 */
internal fun isHostUnreachable(e: Throwable): Boolean {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth < 5) {
        if (cur is java.net.UnknownHostException) return true
        cur = cur.cause
        depth++
    }
    return false
}
