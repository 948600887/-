package com.example.stockdiary.network

import com.example.stockdiary.common.FailureClassifier
import com.example.stockdiary.common.Result
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import kotlinx.coroutines.CancellationException

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
            } catch (e: CancellationException) {
                throw e
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
        // 停牌：重试一万次这只票也没有报价，白白浪费请求额度
        if (QuoteSuspendedException.isSuspended(exception)) {
            AppLogger.w("标的停牌/无报价，不重试: ${exception.message}", TAG)
            return false
        }
        return when (exception) {
            is SocketTimeoutException -> {
                AppLogger.w("网络超时，将重试", TAG)
                true
            }
            is UnknownHostException -> {
                // DNS 解析失败**不重试**：域名在几秒内不会突然变得可解析
                // （用户没开代理、没换网络之前重试结果完全一样），而重试会把单次同步的
                // 等待放大 maxRetries 倍——真机日志里币安主机被墙时，前三只加密资产
                // 各等 20+ 秒、整轮开头 20 秒「已完成 0/240」就是这么来的。
                // 恢复交给数据源级熔断器：失败计数 → 熔断冷却 → 半开探测自愈，
                // 那条路径有 5 分钟冷却和真实请求试水，比在这里盲目重试更合适。
                AppLogger.w("无法解析域名（主机不可达），不重试", TAG)
                false
            }
            is IOException -> {
                // 不是所有 IOException 都值得重试：
                // - 新浪 403 是限流，重试只会火上浇油（还会加速被封）
                // - "未获取到股票数据/请检查股票代码"是代码无效或退市，重试 N 次结果完全一样
                // 判定收口在 FailureClassifier（请求级与轮次级共用的唯一分类入口）。
                val msg = exception.message.orEmpty()
                if (FailureClassifier.isDeterministicBusinessFailure(msg)) {
                    AppLogger.w("业务侧的确定性失败，不重试: $msg", TAG)
                    false
                } else {
                    AppLogger.w("IO异常: $msg，将重试", TAG)
                    true
                }
            }
            else -> false
        }
    }
}

