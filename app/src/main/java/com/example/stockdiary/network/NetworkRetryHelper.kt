package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException

object NetworkRetryHelper {
    
    private const val TAG = "NetworkRetry"
    
    suspend fun <T> executeWithRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 5000,
        onRetry: (attempt: Int, exception: Exception) -> Unit = { attempt, _ ->
            AppLogger.w("重试 $attempt/$maxRetries", TAG)
        },
        block: suspend () -> Result<T>
    ): Result<T> {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                val result = block()
                if (result.isSuccess) {
                    return result
                }
                
                lastException = (result.exceptionOrNull() as? Exception) ?: IOException("未知错误")
                
                if (attempt < maxRetries && shouldRetry(lastException)) {
                    onRetry(attempt, lastException)
                    delay(currentDelay)
                    currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
                } else {
                    return Result.failure(lastException)
                }
            } catch (e: Exception) {
                lastException = e
                
                if (attempt < maxRetries && shouldRetry(e)) {
                    onRetry(attempt, e)
                    delay(currentDelay)
                    currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
                } else {
                    return Result.failure(e)
                }
            }
        }
        
        return Result.failure(lastException ?: IOException("达到最大重试次数"))
    }
    
    private fun shouldRetry(exception: Exception): Boolean {
        return when (exception) {
            is SocketTimeoutException -> {
                AppLogger.w("网络超时，将重试", TAG)
                true
            }
            is UnknownHostException -> {
                AppLogger.w("无法解析域名，将重试", TAG)
                true
            }
            is IOException -> {
                AppLogger.w("IO异常: ${exception.message}，将重试", TAG)
                true
            }
            else -> false
        }
    }
    
    suspend fun <T> executeWithFallback(
        primaryBlock: suspend () -> Result<T>,
        fallbackBlock: (suspend () -> T)? = null
    ): Result<T> {
        return try {
            primaryBlock()
        } catch (e: Exception) {
            AppLogger.e("主请求失败: ${e.message}", e, TAG)
            
            if (fallbackBlock != null) {
                try {
                    AppLogger.i("尝试备用方案...", TAG)
                    val fallbackResult = fallbackBlock()
                    Result.success(fallbackResult)
                } catch (fallbackError: Exception) {
                    AppLogger.e("备用方案也失败: ${fallbackError.message}", fallbackError, TAG)
                    Result.failure(e)
                }
            } else {
                Result.failure(e)
            }
        }
    }
}

object NetworkChecker {
    
    fun isNetworkError(exception: Exception): Boolean {
        return exception is SocketTimeoutException ||
               exception is UnknownHostException ||
               (exception is IOException && exception.message?.contains("network", ignoreCase = true) == true)
    }
    
    fun getErrorMessage(exception: Exception): String {
        return when (exception) {
            is SocketTimeoutException -> "网络连接超时，请检查网络后重试"
            is UnknownHostException -> "无法连接到服务器，请检查网络设置"
            is IOException -> exception.message ?: "网络通信错误"
            else -> "发生错误: ${exception.message}"
        }
    }
    
    fun isRateLimitError(exception: Exception): Boolean {
        return exception is IOException && 
               exception.message?.contains("频率", ignoreCase = true) == true
    }
}
