package com.example.stockdiary.network

import com.example.stockdiary.BuildConfig
import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.common.Timeouts
import com.example.stockdiary.utils.AppLogger
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

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
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                AppLogger.d(message, "OkHttp")
            }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(loggingInterceptor)
        }

        builder.build()
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
