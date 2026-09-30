package com.example.stockdiary.network

import com.example.stockdiary.BuildConfig
import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.common.Timeouts
import com.example.stockdiary.utils.AppLogger
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
