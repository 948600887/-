package com.example.stockdiary.network

import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
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

    /** 账户查询接口。BASE_URL 复用 [APIEndpoints.Binance]，避免硬编码重复 */
    private const val ACCOUNT_URL = APIEndpoints.Binance.BASE_URL + "/account"

    /** 批量最新价接口（公开免签）。用于导入时估算市值、按价值过滤零碎 */
    private const val PRICE_URL = APIEndpoints.Binance.BASE_URL + APIEndpoints.Binance.TICKER

    /**
     * 批量取价每批的交易对个数上限。
     *
     * symbols 是 JSON 数组且要整体 URL 编码，一批太多 URL 会超出服务端/中间件
     * 的长度限制。真实账户的去重交易对通常远少于此数，分片主要是防御性的。
     *
     * 防御性调小到 50：一批里混入一个已下架/脏符号会让整批 400（见 [getPrices]
     * 的「对半重试」隔离逻辑），批越小、被一个坏符号拖垮的好符号越少。
     */
    private const val PRICE_BATCH = 50

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
     * 批量取最新价（公开免签）。
     *
     * 用于导入时估算各持仓的人民币市值，好按价值过滤零碎。走公开的
     * `/ticker/price?symbols=[...]`，无需 API Key，也不带任何密钥信息。
     *
     * ## 为什么失败不抛、而是返回空 Map
     *
     * 取价只是「按市值过滤」的增强：取不到价就不过滤、全量导入，
     * 绝不能因为一次公开行情请求挂了就让整个导入失败——余额才是关键数据。
     * 所以内部吞掉异常，尽量返回已取到的部分。
     *
     * ## 整批 400 → 对半重试（隔离坏符号）
     *
     * 币安 `ticker/price?symbols=[...]` 是「**一个符号非法 → 整批 400**」语义。
     * 真实账户 ~1800 个余额里常混有已下架/脏符号，一批里只要有一个就会让整批
     * 80 个全废（见根因诊断）。这里用队列做「整批失败 → 对半重试」：把含坏符号
     * 的批拆小，逐步隔离出单个非法符号（直接丢弃），好符号照常取到价。
     * **正常无坏符号时每批只发一次，零额外请求。**
     *
     * ## 日志口径
     *
     * 只记批次大小，不记 symbols 内容——那等于把用户的持仓构成写进可分享日志。
     *
     * @param pairs 交易对列表（如 BTCUSDT）
     * @return Map<交易对, 最新价>；失败/无数据时返回空 Map（调用方据此降级为不过滤）
     */
    suspend fun getPrices(pairs: List<String>): Map<String, Double> = getPrices(pairs, client)

    /**
     * internal 重载：允许单测注入假 client（自定义 [okhttp3.Interceptor] 模拟币安语义）。
     *
     * 公开 API 语义与 [getPrices] 完全一致——只是把用于发请求的那个 client 提出来，
     * 便于测试时用走假 Interceptor 的 client 替换 [NetworkClient.binanceClient]。
     */
    internal suspend fun getPrices(pairs: List<String>, httpClient: OkHttpClient): Map<String, Double> =
        withContext(Dispatchers.IO) {
            if (pairs.isEmpty()) return@withContext emptyMap()
            val result = mutableMapOf<String, Double>()
            try {
                // 队列驱动「整批 400 → 对半重试」：正常无坏符号时每批只发一次，零额外请求。
                val queue = ArrayDeque<List<String>>().apply {
                    addAll(pairs.distinct().chunked(PRICE_BATCH))
                }
                while (queue.isNotEmpty()) {
                    val batch = queue.removeFirst()
                    val fetched = fetchBatch(batch, httpClient)
                    if (fetched != null) {
                        result.putAll(fetched)
                        continue
                    }
                    // 整批失败：币安语义「一个符号非法 → 整批 400」。对半拆开继续试，
                    // 逐步隔离出坏符号。单条仍失败说明这条本身非法，直接丢弃（不写库、
                    // 不影响其它批）。
                    if (batch.size > 1) {
                        val mid = batch.size / 2
                        // 先压后半、再压前半，保持原批内顺序优先（仅影响排查，不影响结果）
                        queue.addFirst(batch.takeLast(batch.size - mid))
                        queue.addFirst(batch.take(mid))
                    }
                    // batch.size == 1 仍失败：单个非法符号，丢弃（上面 if 不进即此分支）
                }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只记异常类型，不记 message（可能含 URL）；返回已取到的部分（通常为空）
                AppLogger.e("批量取价失败: ${e::class.simpleName}", e, "BinanceAccountAPI")
                result
            }
        }

    /**
     * 对一批交易对发一次 `/ticker/price` 请求。
     *
     * 抽出独立挂起函数，便于 [getPrices] 在「对半重试」里反复调用，也便于测试
     * 用注入的假 client 走真实请求路径。
     *
     * @return 成功（2xx）时返回 交易对→价 的 map；整批失败（非 2xx）或空响应返回
     *         null，交给 [getPrices] 做「对半重试 / 丢弃单条」的隔离处理。
     *         网络异常不在这里吞——上抛给 [getPrices] 的兜底
     *         （CancellationException 原样重抛，其余记日志并返回已取到的部分）。
     */
    private suspend fun fetchBatch(batch: List<String>, httpClient: OkHttpClient): Map<String, Double>? {
        val encoded = buildSymbolsParam(batch)
        val url = "$PRICE_URL?symbols=$encoded"

        // 只记批次大小，不记 symbols（避免把用户持仓构成写进可分享日志，与现有口径一致）
        AppLogger.d("GET /ticker/price (batch=${batch.size})", "BinanceAccountAPI")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val map = mutableMapOf<String, Double>()
            parsePrices(body).forEach { (symbol, price) -> map[symbol] = price }
            return map
        }
    }

    /**
     * 构造 /ticker/price 的 symbols 查询参数值（JSON 数组字符串）。
     *
     * **必须用无空格分隔**：`["BTCUSDT","LTCUSDT"]` 而非 `["BTCUSDT", "LTCUSDT"]`。
     * [getPrices] 里曾经用默认的 `", "` 分隔符，空格经 [URLEncoder] 变成 `+`，
     * 币安的 symbols 解析器不认，整批返回 400 —— 2026-10-17 修。
     */
    internal fun buildSymbolsParam(pairs: List<String>): String {
        // 用 buildString 显式拼，绝不给逗号后面留空格——
        // 一旦有空格，URLEncoder 会编成 '+'，币安 symbols 解析器不认 → 整批 400。
        val json = buildString {
            append('[')
            pairs.forEachIndexed { index, symbol ->
                if (index > 0) append(',')
                append('"').append(symbol).append('"')
            }
            append(']')
        }
        return URLEncoder.encode(json, "UTF-8")
    }

    /**
     * 把币安的错误响应转成带 code+msg 的异常。
     *
     * body 形如 `{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}`。
     * 币安的 msg 是排障的关键（密钥无效 / IP 不在白名单 / 权限不足是三种不同问题），
     * 必须带给用户；解析不出结构化字段时退回 HTTP 状态码。
     *
     * internal：行情门面的加密货币取价也走这套口径（同样要区分"被地区限制"和"代码写错"），
     * 错误提示只此一份实现。
     */
    internal fun binanceError(body: String, httpCode: Int): Exception {
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

    /**
     * 解析批量最新价响应：`[{"symbol":"BTCUSDT","price":"67000.00"},...]`。
     *
     * price 同样是字符串。价格为 0 或解析不了的行跳过——0 价没有估算意义，
     * 混进 map 会让对应资产被当成「估得出价但市值为 0」而误滤掉。
     */
    private fun parsePrices(body: String): Map<String, Double> {
        val arr = JSONArray(body)
        val map = mutableMapOf<String, Double>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val symbol = obj.optString("symbol")
            val price = obj.optString("price").toDoubleOrNull()
            if (symbol.isNotBlank() && price != null && price > 0.0) {
                map[symbol] = price
            }
        }
        return map
    }
}
