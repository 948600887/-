package com.example.stockdiary.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.example.stockdiary.network.EastmoneySkillApi
import com.example.stockdiary.network.FundNavParser
import com.example.stockdiary.network.NetworkClient
import com.example.stockdiary.network.TencentStockAPI
import kotlinx.coroutines.CancellationException

data class DataSourceHealth(
    val name: String,
    val url: String,
    val isAvailable: Boolean,
    val latency: Long,
    val lastCheck: Long,
    val errorMessage: String? = null,
    /**
     * 该源服务的资产域：股票 / 基金 / 加密。
     *
     * 摘要按域分组统计，而不是把全部源混成一个「x/y 个源可用」：
     * 币安在部分地区不可用（或用户根本不持币）时，混算会把「股票源全好」
     * 也说成整体降级，属于误报。
     */
    val category: String = "股票"
)

object DataSourceHealthChecker {

    /** 健康检查用的样本基金码：一只长期在市的普通股票型基金。 */
    private const val HEALTH_CHECK_FUND_CODE = "110022"

    private val client = NetworkClient.defaultClient
    
    /**
     * 并行体检**完整取价链路**：妙想Skill（配了 Key 才参与）→ 新浪 → 腾讯，外加持仓用到的
     * 天天基金（基金净值）与 Binance（加密货币行情）。
     *
     * 原先只探「新浪 / 天天基金 / Binance」：腾讯是真实存在的二级降级源却从不体检，
     * 妙想（配置后是首选源）更是完全缺席；而三家串行探测，任一源超时都要把整体拖到十几秒。
     */
    suspend fun checkAllHealth(): List<DataSourceHealth> = coroutineScope {
        val skill = if (EastmoneySkillApi.isApiKeyConfigured()) {
            async(Dispatchers.IO) { checkSkillAPI() }
        } else {
            null
        }
        val sina = async(Dispatchers.IO) { checkSinaStock() }
        val tencent = async(Dispatchers.IO) { checkTencentStock() }
        val fund = async(Dispatchers.IO) { checkFundAPI() }
        val binance = async(Dispatchers.IO) { checkBinanceAPI() }

        buildList {
            if (skill != null) add(skill.await())
            add(sina.await())
            add(tencent.await())
            add(fund.await())
            add(binance.await())
        }
    }

    /**
     * 妙想 Skill 体检：仅在配置了 API Key 时参与（没 Key 时它本来就不在链路里）。
     *
     * 没有轻量 ping 接口，只能用一次真实的股票查询探活；设置页手动触发一次，
     * 用量可以接受。返回失败时区分不了 Key 无效/额度用尽/限流，文案里一并提示。
     */
    suspend fun checkSkillAPI(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val result = EastmoneySkillApi.queryStock("600519 最新价")
            DataSourceHealth(
                name = "妙想Skill",
                url = "mkapi2.dgcfs.com",
                isAvailable = result.isSuccess,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = if (result.isSuccess) null else {
                    result.exceptionOrNull()?.message ?: "接口返回错误（Key 无效 / 额度用尽 / 限流）"
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "妙想Skill",
                url = "mkapi2.dgcfs.com",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = e.message
            )
        }
    }

    /**
     * 腾讯行情体检：股票取价链路的二级降级源，原来完全没纳入体检。
     */
    suspend fun checkTencentStock(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val result = TencentStockAPI.getStockData("600519")
            val data = result.getOrNull()
            val healthy = result.isSuccess && data?.name?.isNotBlank() == true
            DataSourceHealth(
                name = "腾讯财经",
                url = "qt.gtimg.cn",
                isAvailable = healthy,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = if (healthy) null else {
                    result.exceptionOrNull()?.message ?: "返回空行情（解析失败或字段缺失）"
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "腾讯财经",
                url = "qt.gtimg.cn",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = e.message
            )
        }
    }
    
    suspend fun checkSinaStock(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://hq.sinajs.cn/list=sh600519")
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://finance.sina.com.cn")
                .build()

            // 必须 use{}：ResponseBody 不 close 的话连接不会归还连接池，
            // 每次打开设置页都会泄漏 3 条，直到 keepAlive 60s 超时才释放
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                val bodyText = if (response.isSuccessful) response.body?.string().orEmpty() else ""

                // 不能只看 HTTP 200：新浪在缺 Referer、或请求被 query 参数污染时仍回 200 + 空体，
                // 会让「新浪=正常」成为假健康（见 SinaStockAPI.buildRealtimeUrl 注释：
                // 曾因 &rn= 时间戳污染代码，所有 A 股返回 41 字节空响应却仍判 200）。
                // 必须确认响应体里确有 sh600519 的真实报价（名称非空）才判为可用。
                val healthy = response.isSuccessful && runCatching {
                    val m = Regex("""hq_str_sh600519="([^"]*)"""").find(bodyText)
                    m?.groupValues?.get(1)?.split(",")?.firstOrNull()?.isNotBlank() == true
                }.getOrDefault(false)

                DataSourceHealth(
                    name = "新浪财经",
                    url = "hq.sinajs.cn",
                    isAvailable = healthy,
                    latency = latency,
                    lastCheck = System.currentTimeMillis(),
                    errorMessage = when {
                        !response.isSuccessful -> "HTTP ${response.code}"
                        !healthy -> "返回空行情（Referer 缺失或请求被污染，实际取不到价）"
                        else -> null
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "新浪财经",
                url = "hq.sinajs.cn",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 天天基金健康检查。
     *
     * 探测地址就是 FundAPI 实际用的**轻量净值源**（~2.5KB）。原来用的是
     * `pingzhongdata/110022.js`：那是基金的**完整历史净值**，单次响应约 570KB，
     * 而健康检查只想知道「这个源通不通」—— 拿大文件探测既慢又白白吃掉流量，
     * 还可能在打开设置页的一次体检里再添一份内存压力。
     *
     * 判定也比原来严一点：HTTP 200 之外还要求报文能解析出净值（接口业务报错时
     * 同样返回 200，光看状态码会把「源坏了但连得上」报成健康）。
     */
    suspend fun checkFundAPI(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(
                    "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNBasicInformation" +
                        "?FCODE=$HEALTH_CHECK_FUND_CODE&deviceid=Wap&plat=Wap" +
                        "&product=EFund&version=2.0.0"
                )
                // 与 FundAPI 一致：不带 UA 时该接口回 ErrCode 61136
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                val body = if (response.isSuccessful) response.body?.string().orEmpty() else ""
                val parsed = if (response.isSuccessful) {
                    FundNavParser.parseBasicInfo(body, HEALTH_CHECK_FUND_CODE)
                } else {
                    null
                }
                val available = response.isSuccessful && parsed != null

                DataSourceHealth(
                    name = "天天基金",
                    url = "fundmobapi.eastmoney.com",
                    isAvailable = available,
                    latency = latency,
                    lastCheck = System.currentTimeMillis(),
                    category = "基金",
                    errorMessage = when {
                        !response.isSuccessful -> "HTTP ${response.code}"
                        parsed == null -> "轻量净值接口返回异常（业务码非 0 或数据缺失）"
                        else -> null
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "天天基金",
                url = "fundmobapi.eastmoney.com",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                category = "基金",
                errorMessage = e.message
            )
        }
    }

    suspend fun checkBinanceAPI(): DataSourceHealth = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // 公开行情走 data-api.binance.vision（不做地区封锁）；api.binance.com 在部分地区回 451，
            // 用它做健康检查会误报「币安不可用」——其实价格是从镜像拿的。健康检查必须对准真实取价主机。
            val request = Request.Builder()
                .url("https://data-api.binance.vision/api/v3/ping")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime

                DataSourceHealth(
                    name = "Binance",
                    url = "data-api.binance.vision",
                    isAvailable = response.isSuccessful,
                    latency = latency,
                    lastCheck = System.currentTimeMillis(),
                    category = "加密",
                    errorMessage = if (!response.isSuccessful) "HTTP ${response.code}" else null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DataSourceHealth(
                name = "Binance",
                url = "data-api.binance.vision",
                isAvailable = false,
                latency = System.currentTimeMillis() - startTime,
                lastCheck = System.currentTimeMillis(),
                category = "加密",
                errorMessage = e.message
            )
        }
    }
}
