package com.example.stockdiary.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.example.stockdiary.network.FundNavParser
import com.example.stockdiary.network.NetworkClient
import kotlinx.coroutines.CancellationException

data class DataSourceHealth(
    val name: String,
    val url: String,
    val isAvailable: Boolean,
    val latency: Long,
    val lastCheck: Long,
    val errorMessage: String? = null
)

object DataSourceHealthChecker {

    /** 健康检查用的样本基金码：一只长期在市的普通股票型基金。 */
    private const val HEALTH_CHECK_FUND_CODE = "110022"

    private val client = NetworkClient.defaultClient
    
    suspend fun checkAllHealth(): List<DataSourceHealth> = withContext(Dispatchers.IO) {
        listOf(
            checkSinaStock(),
            checkFundAPI(),
            checkBinanceAPI()
        )
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

                DataSourceHealth(
                    name = "新浪财经",
                    url = "hq.sinajs.cn",
                    isAvailable = response.isSuccessful,
                    latency = latency,
                    lastCheck = System.currentTimeMillis(),
                    errorMessage = if (!response.isSuccessful) "HTTP ${response.code}" else null
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
                errorMessage = e.message
            )
        }
    }
}
