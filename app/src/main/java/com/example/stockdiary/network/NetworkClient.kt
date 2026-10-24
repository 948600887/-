package com.example.stockdiary.network

import com.example.stockdiary.BuildConfig
import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.common.Timeouts
import com.example.stockdiary.utils.AppLogger
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * 共享网络客户端池。
 * 
 * 设计原则：
 * - 每个业务域有独立客户端，共享连接池和超时配置
 * - BaseUrlInterceptor 仅用于需要补全相对路径的 API
 * - 使用完整 URL 的 API 不加 BaseUrlInterceptor，避免拦截器链干扰
 * 
 * 客户端用途：
 * - defaultClient: 通用请求，无 BaseUrlInterceptor
 * - sinaClient:    新浪股票 API，使用完整 URL (hq.sinajs.cn)，无 BaseUrlInterceptor
 * - tencentClient: 腾讯股票 API，使用完整 URL (qt.gtimg.cn)，无 BaseUrlInterceptor
 * - fundClient:    天天基金/东方财富 API，使用 BaseUrlInterceptor 补全相对路径
 * - binanceClient: 币安 API，使用 BaseUrlInterceptor 补全相对路径
 * 
 * 注意：新增客户端时，除非 API 使用相对路径，否则不要加 BaseUrlInterceptor。
 */
object NetworkClient {

    private val isDebug: Boolean = BuildConfig.DEBUG

    /**
     * 所有业务客户端共享同一个底层 OkHttpClient 的 ConnectionPool 与 Dispatcher。
     * 业务客户端通过 [OkHttpClient.newBuilder] 派生，仅追加各自所需的拦截器。
     */
    private val baseClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(Timeouts.CONNECT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(Timeouts.READ_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(Timeouts.WRITE_MS.toLong(), TimeUnit.MILLISECONDS)
            // callTimeout 全局覆盖（所有业务客户端经 newBuilder 派生，一并继承）：
            // connect/read 只约束「单连接上的单次 IO」，而 OkHttp 默认
            // retryOnConnectionFailure(true)——主机解析出一串 IPv6/IPv4 地址时会逐个重试、
            // 每个地址各自重新计时。实测一个基金净值请求挂了 54 分钟（app 退后台被系统冻结，
            // 醒来才恢复），把 AssetSyncManager 的并发信号量整个堵死；币安主机不可达时单个
            // call 也曾挂到 3478 秒。callTimeout 覆盖从 enqueue 到响应读完的**整段**时间
            // （含重试与排队），是唯一能兜住「主机压根不可达 / 进程被冻结」的开关。
            .callTimeout(Timeouts.CALL_MS.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 15,
                    keepAliveDuration = 60,
                    timeUnit = TimeUnit.SECONDS
                )
            )
            .dispatcher(
                Dispatcher().apply {
                    // 默认 maxRequestsPerHost=5 是这次「刷新卡死」的二号根因：
                    // 一次手动刷新 60 个持仓的价格请求集中在 qt.gtimg.cn /
                    // hq.sinajs.cn 少数几个主机上，超出配额的请求排在 Dispatcher
                    // 里**没有总超时**（connect/read 超时都从 call 真正开始执行
                    // 才起算），于是配了 15 秒读超时的请求排队一分多钟不返回
                    // 也不报错——诊断日志里那个 112 秒没下文的腾讯请求就是这么来的。
                    //
                    // 调到 16：真实并发仍由各业务层的 Semaphore（价格同步 8 +
                    // 分红级联 8 + 批量分块 3）兜底，不会变成请求风暴；16 也正好
                    // 覆盖「后台 Worker 与手动刷新不幸撞车」时的同主机峰值。
                    // maxRequests 显式写出来，别让人以后以为是默认值乱改
                    maxRequests = 64
                    maxRequestsPerHost = 16
                }
            )

        if (isDebug) {
            builder.addInterceptor(networkLogInterceptor)
        }

        builder.build()
    }

    /**
     * 一行式网络日志（取代 okhttp logging-interceptor 的 BASIC 级别）。
     *
     * 为什么换掉：BASIC 每个请求打两行（`--> GET url` 与 `<-- 200 OK url (70ms, 2455-byte body)`），
     * 真机 5000 行诊断日志里 OkHttp 占 3091 行（62%），真正有用的信号全埋在噪音里，
     * 每次排障都要先和它们搏斗。这里压缩成**一行响应日志**，行数直接减半。
     *
     * 同时把**非 2xx 提升到 WARN**：失败本该一眼看到，而不是和 200 成功混在同一级别里
     * 让人数。异常路径也各记一行（只记类型，不记 message——OkHttp 的 message 含设备 IP/端口）。
     *
     * 脱敏口径与原实现一致：BASIC 的 message 是整行（含 query），币安账户接口的
     * signature 就在 query 里，不剥就是明文落盘，所以每条都过 [AppLogger.sanitizeForLog]。
     *
     * 注意：必须原样返回 response / 重新抛异常，拦截器不能吞结果。
     */
    private val networkLogInterceptor = Interceptor { chain ->
        val request = chain.request()
        val startedAt = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            val ms = System.currentTimeMillis() - startedAt
            val bytes = runCatching { response.body?.contentLength() }.getOrNull() ?: -1L
            val line = "<-- ${response.code} ${request.method} ${request.url} (${ms}ms, ${bytes}B)"
            val safe = AppLogger.sanitizeForLog(line)
            if (response.isSuccessful) AppLogger.d(safe, "OkHttp") else AppLogger.w(safe, "OkHttp")
            response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(
                AppLogger.sanitizeForLog(
                    "!-- ${request.method} ${request.url} 失败: ${e::class.simpleName}"
                ),
                "OkHttp"
            )
            throw e
        }
    }

    val defaultClient: OkHttpClient by lazy {
        baseClient.newBuilder().build()
    }

    val sinaClient: OkHttpClient by lazy {
        baseClient.newBuilder().build()
    }

    val tencentClient: OkHttpClient by lazy {
        baseClient.newBuilder().build()
    }

    val fundClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor(BaseUrlInterceptor(APIEndpoints.Eastmoney.BASE_URL))
            .build()
    }

    /**
     * 币安客户端：与其它业务客户端一样经 [baseClient] 派生，仅追加 BaseUrlInterceptor。
     *
     * [okhttp3.OkHttpClient.Builder.callTimeout] 现已全局覆盖（见 [baseClient]），币安不再特殊。
     * 保留这段 rationale：connect/read 超时只约束单连接单次 IO，而 OkHttp 默认
     * `retryOnConnectionFailure(true)`，api.binance.com 解析出一串 IPv6/IPv4 地址时会逐个重试、
     * 每个地址各自重新计时——实测主机不可达时单个 call 挂了 3478 秒，把 AssetSyncManager 的并发
     * 信号量堵死。callTimeout 覆盖整段 call（含重试与排队），是唯一能兜住这种情况的开关，
     * 因此必须对**所有**客户端生效，而非只币安。
     */
    val binanceClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor(BaseUrlInterceptor(APIEndpoints.Binance.BASE_URL))
            .build()
    }
    
    private class BaseUrlInterceptor(private val baseUrl: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()
            
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return chain.proceed(originalRequest)
            }
            
            val newUrl = if (url.startsWith("/")) {
                "$baseUrl$url"
            } else {
                "$baseUrl/$url"
            }
            
            val newRequest = originalRequest.newBuilder()
                .url(newUrl)
                .build()
            
            return chain.proceed(newRequest)
        }
    }
}
