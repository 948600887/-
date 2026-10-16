package com.example.stockdiary.network

import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 币安现货余额的一条记录。
 *
 * 账户接口（`/api/v3/account`）按基础资产给余额：BTC、ETH、USDT……
 * [free] 是可用、[locked] 是挂单冻结，两者相加才是真实持仓。
 */
data class BinanceBalance(
    val asset: String,
    val free: Double,
    val locked: Double
) {
    /** 可用 + 冻结 = 该资产的实际持有量 */
    val total: Double
        get() = free + locked
}

/**
 * 币安**签名账户接口**客户端（只读）。
 *
 * 与 [BinanceAPI] 的分工：那个是公开行情（ticker/klines，无需密钥）；
 * 这个走需要 API Key 签名的账户查询，用来拉用户的现货持仓。
 * 两者共用同一套 OkHttp（[NetworkClient.binanceClient]）与 BASE_URL。
 *
 * ## 为什么单独一个文件而不是并进 [BinanceAPI]
 *
 * 签名链路（HMAC、密钥头、错误码解析）和公开行情几乎没有共用逻辑，
 * 混在一起会让「改签名」和「改行情」互相牵连。分开后签名这块可以独立单测
 * （见 [sign]），也不必担心给公开接口误加密钥头。
 *
 * ## 安全约定（务必遵守）
 *
 * - **只读**：只调用 `/account` 查询，不做任何下单/撤单/转账。
 * - API Key / Secret **绝不进日志、绝不进异常 message**：URL 里带 signature
 *   （由 secret 派生），请求头带 X-MBX-APIKEY，两者都不允许出现在
 *   AppLogger 或 Result.failure 的文案里。日志只记「GET /account (signed)」。
 * - 币安返回的业务错误（如 -2015 密钥无效）可以带出来给用户看——那是排障必需，
 *   且币安的 msg 本身不含密钥；但绝不允许把本地 secret 拼进去。
 */
object BinanceAccountAPI {

    /** 账户查询接口。BASE_URL 与 [BinanceAPI] 保持一致 */
    private const val ACCOUNT_URL = "https://api.binance.com/api/v3/account"

    /**
     * recvWindow：请求有效期窗口（毫秒）。
     *
     * 币安默认 5000，但对手机来说偏紧——弱网/系统休眠后 timestamp 容易超出窗口
     * 报 -1021。放到 60000 留足余量，只读接口放宽这个没有安全代价。
     */
    private const val RECV_WINDOW = 60000

    private val client = NetworkClient.binanceClient

    /**
     * HMAC-SHA256 签名，输出小写 hex。
     *
     * ## 为什么抽成纯函数
     *
     * 签名是这套链路里唯一「错一位全盘皆错」又**不依赖网络**的逻辑，
     * 抽成 [sign] 就能在 JVM 单测里用币安官方文档的已知向量直接验，
     * 不必连网、也不必伪造 OkHttp。整条 getAccount 依赖网络，不强行单测。
     *
     * @param secret API Secret
     * @param query  待签名的 query 串（如 `timestamp=...&recvWindow=...`，**不含** signature）
     * @return 64 位小写 hex 字符串
     */
    internal fun sign(secret: String, query: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        // 密钥与数据都按 UTF-8 编字节：币安文档即此口径，换编码会得到不同签名
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(query.toByteArray(Charsets.UTF_8))
        // 256 bit / 4 bit-per-hex = 64 个 hex 字符
        return raw.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * 拉取现货账户余额（只读）。
     *
     * @param apiKey    币安 API Key
     * @param apiSecret 币安 API Secret
     * @return 成功时返回**未过滤**的全部余额（含 dust，过滤交给导入服务）；
     *         失败时 [Result.failure] 的 message 带币安 code+msg，便于用户排障。
     */
    suspend fun getAccount(apiKey: String, apiSecret: String): Result<List<BinanceBalance>> =
        withContext(Dispatchers.IO) {
            try {
                // timestamp 用本地时钟：币安按它判断请求是否在 recvWindow 内。
                // 手机时钟不准会报 -1021，那是用户侧问题，这里如实把错误带出去。
                val timestamp = System.currentTimeMillis()
                val query = "timestamp=$timestamp&recvWindow=$RECV_WINDOW"
                val signature = sign(apiSecret, query)
                val url = "$ACCOUNT_URL?$query&signature=$signature"

                // 日志只记端点路径：url 里的 signature 由 secret 派生，属于机密，
                // 一旦进 AppLogger 落盘就等于把密钥写进了可分享的日志文件
                AppLogger.d("GET /account (signed, recvWindow=$RECV_WINDOW)", "BinanceAccountAPI")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-MBX-APIKEY", apiKey)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()
                response.use { resp ->
                    val body = resp.body?.string() ?: return@withContext Result.failure(
                        Exception("币安返回了空响应")
                    )

                    if (!resp.isSuccessful) {
                        // 4xx 时币安会在 body 里给 {"code":-2015,"msg":"..."}，
                        // 解析出来转成失败，别吞成"未知错误"——用户分不清是密钥错还是网络错
                        return@withContext Result.failure(binanceError(body, resp.code))
                    }

                    return@withContext Result.success(parseBalances(body))
                }
            } catch (e: CancellationException) {
                // 协程取消必须原样抛回，不能被 catch(Exception) 吞成失败结果
                throw e
            } catch (e: Exception) {
                // 连接类失败（连不上 / 超时 / 域名解析不了）换成一句人话。
                // OkHttp 的原始 message 形如 "failed to connect to api.binance.com/2001:… (port 443)
                // from /2409:… (port 48944) after 10000ms"——既看不懂，又把设备 IP/端口
                // 抖给用户。币安国际站在部分网络/地区连不上是常态，这句提示更对症。
                // 日志也只记异常类型，不记 e.message（同样含 IP），避免地址写进可分享的日志。
                val userFacing = if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                    Exception("连不上币安服务器，可能被网络或地区限制，请检查网络或代理后重试")
                } else {
                    e
                }
                AppLogger.e("拉取币安账户失败: ${e::class.simpleName}", userFacing, "BinanceAccountAPI")
                Result.failure(userFacing)
            }
        }

    /**
     * 把币安的错误响应转成带 code+msg 的异常。
     *
     * body 形如 `{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}`。
     * 币安的 msg 是排障的关键（密钥无效 / IP 不在白名单 / 权限不足是三种不同问题），
     * 必须带给用户；解析不出结构化字段时退回 HTTP 状态码。
     */
    private fun binanceError(body: String, httpCode: Int): Exception {
        // 地区限制：币安按条款 "b. Eligibility" 挡部分地区/IP，常返回 451 或
        // {"code":0,"msg":"Service unavailable from a restricted location..."}。
        // 只给英文原文用户不知道该干嘛，补一句可操作的中文——换允许地区的代理节点即可。
        val geoHint = "（当前代理/VPN 节点所在地区被币安限制，更换到允许地区的节点后重试）"
        return try {
            val obj = JSONObject(body)
            val code = obj.optInt("code", httpCode)
            val msg = obj.optString("msg").ifBlank { "HTTP $httpCode" }
            val isGeo = httpCode == 451 ||
                msg.contains("restricted location", true) ||
                msg.contains("Eligibility", true) ||
                msg.contains("Service unavailable", true)
            Exception("币安拒绝请求（$code）：$msg" + if (isGeo) geoHint else "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 非 JSON body（451 有时返回纯文本/HTML）：按状态码兜底，451 同样给地区提示
            Exception("币安请求失败（HTTP $httpCode）" + if (httpCode == 451) geoHint else "")
        }
    }

    /**
     * 解析 `balances` 数组。
     *
     * free/locked 在币安响应里是**字符串**（如 "0.00000000"），不是数字，
     * 按字符串取出再 toDoubleOrNull，解析不到记 0（不影响其它行）。
     */
    private fun parseBalances(body: String): List<BinanceBalance> {
        val arr = JSONObject(body).getJSONArray("balances")
        val result = mutableListOf<BinanceBalance>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                BinanceBalance(
                    asset = obj.getString("asset"),
                    free = obj.optString("free").toDoubleOrNull() ?: 0.0,
                    locked = obj.optString("locked").toDoubleOrNull() ?: 0.0
                )
            )
        }
        return result
    }
}
