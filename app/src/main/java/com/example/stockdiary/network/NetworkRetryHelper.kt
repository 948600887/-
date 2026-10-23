package com.example.stockdiary.network

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
                // 这些都属于"确定失败"，打一次就够了。
                val msg = exception.message.orEmpty()
                if (isNonRetryableBusinessFailure(msg)) {
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

    /**
     * 限流 / 无效标的等业务文案。
     *
     * 这些本身就是「词组」，按子串匹配不会有歧义，因此可以保留子串语义。
     */
    private val NON_RETRYABLE_PHRASES: List<String> = listOf(
        "频率过高", "请求过于频繁", "rate limit",
        "请检查股票代码", "未获取到股票数据", "代码不存在", "无效的代码"
    )

    /**
     * 重试只会火上浇油的 HTTP 状态码：403（限流/被封 IP）、429（Too Many Requests）。
     */
    private val NON_RETRYABLE_HTTP_STATUS_CODES: List<Int> = listOf(403, 429)

    /**
     * 状态码必须是**独立的数字 token** 才成立：`(?<!\d)403(?!\d)`。
     *
     * 裸子串匹配（旧实现）会踩这个坑：任何正好包含这几位数字的文案都会被判成
     * 「不必重试」——成交额 14035、错误码 4291、金额 40300、股票代码 sz000403、
     * 时间戳带 403 段……这些其实都是可恢复的失败，被误判后一次请求就放弃，
     * 表现为「行情莫名取不到」且日志里看不出原因。前后各加一个"非数字"断言即可规避。
     */
    private val NON_RETRYABLE_HTTP_STATUS: List<Regex> = NON_RETRYABLE_HTTP_STATUS_CODES.map { code ->
        Regex("""(?<!\d)$code(?!\d)""")
    }

    /**
     * 判断是否是"重试也不会改变结果"的确定性失败。
     *
     * 各类数据源目前把业务失败统一包成 [IOException] 往外抛（HTTP 403、代码不存在、
     * 返回空串都是同一类型），重试层只能靠文案识别。这是现状下的最小侵入方案。
     *
     * 匹配策略分两类：
     * - [NON_RETRYABLE_PHRASES]：中文/英文词组，子串匹配（ignoreCase）；
     * - [NON_RETRYABLE_HTTP_STATUS]：HTTP 状态码，按完整数字 token 匹配，
     *   只认 "403" / "429" 本体（"HTTP 403"、"status=403"、"code 403" 也能命中），
     *   不会被 14035、4291、40300 这类更长的数字误伤。
     */
    private fun isNonRetryableBusinessFailure(message: String): Boolean {
        return NON_RETRYABLE_PHRASES.any { message.contains(it, ignoreCase = true) } ||
            NON_RETRYABLE_HTTP_STATUS.any { it.containsMatchIn(message) }
    }
    
    suspend fun <T> executeWithFallback(
        primaryBlock: suspend () -> Result<T>,
        fallbackBlock: (suspend () -> T)? = null
    ): Result<T> {
        return try {
            primaryBlock()
        } catch (e: CancellationException) {
            throw e
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
