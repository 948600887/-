package com.example.stockdiary.data.sync

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import com.example.stockdiary.database.AssetType
import kotlin.math.min

/**
 * 同步错误类型
 */
enum class SyncErrorType {
    NETWORK,      // 网络不可用/超时/DNS 解析失败
    SERVER,       // 服务端返回 5xx 或空数据
    DATA,         // 数据解析失败或代码不存在
    UNKNOWN       // 其他未知错误
}

/**
 * 智能同步策略
 * 用于识别交易时段、分类错误、计算退避重试间隔
 */
object SyncStrategy {

    // A 股交易时段（北京时间）
    private val MORNING_START = LocalTime.of(9, 30)
    private val MORNING_END = LocalTime.of(11, 30)
    private val AFTERNOON_START = LocalTime.of(13, 0)
    private val AFTERNOON_END = LocalTime.of(15, 0)

    private val ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai")

    /**
     * 判断当前是否处于 A 股交易时段（工作日 9:30-11:30、13:00-15:00）
     */
    fun isInTradingHours(dateTime: LocalDateTime = LocalDateTime.now(ZONE_SHANGHAI)): Boolean {
        val dayOfWeek = dateTime.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) return false

        val time = dateTime.toLocalTime()
        val inMorning = !time.isBefore(MORNING_START) && !time.isAfter(MORNING_END)
        val inAfternoon = !time.isBefore(AFTERNOON_START) && !time.isAfter(AFTERNOON_END)
        return inMorning || inAfternoon
    }

    /**
     * 获取当前建议的同步间隔（毫秒）
     * 交易时段内更短，非交易时段更长
     */
    fun getCurrentSyncIntervalMillis(
        isInTradingHours: Boolean = isInTradingHours()
    ): Long {
        return if (isInTradingHours) {
            TRADING_INTERVAL_MILLIS
        } else {
            NON_TRADING_INTERVAL_MILLIS
        }
    }

    /**
     * 某类资产当前是否应被后台自动同步（按用户偏好 + 交易时段分频）。
     *
     * 三类的市场时钟本就不同，不该被 A 股时钟一刀切：
     * - **STOCK / FUND**：默认**仅盘后 / 非交易时段**刷（捕获收盘价即止），
     *   盘中要刷需打开 `stockIntradaySync`；
     * - **CRYPTO**：默认**不自动刷**（24/7 市场，与 A 股时钟错拍），
     *   要自动刷需打开 `cryptoAutoSync`；
     * - 其它类型：后台从不自动同步。
     *
     * 抽成纯函数便于单测跨时段 / 偏好组合；[com.example.stockdiary.data.sync.PriceSyncWorker]
     * 每轮按它过滤资产。手动刷新不受此约束（用户显式要求，照旧全刷）。
     */
    fun shouldAutoSyncType(
        type: AssetType,
        inTradingHours: Boolean,
        stockIntradaySync: Boolean,
        cryptoAutoSync: Boolean
    ): Boolean = when (type) {
        AssetType.STOCK, AssetType.FUND -> stockIntradaySync || !inTradingHours
        AssetType.CRYPTO -> cryptoAutoSync
        else -> false
    }

    /**
     * 分类同步异常
     */
    fun classifyError(error: Throwable?): SyncErrorType {
        return when (error) {
            is UnknownHostException,
            is SocketTimeoutException,
            is java.net.ConnectException -> SyncErrorType.NETWORK
            is IOException -> {
                val message = error.message?.lowercase() ?: ""
                when {
                    message.contains("timeout") -> SyncErrorType.NETWORK
                    message.contains("unable to resolve") -> SyncErrorType.NETWORK
                    // 5xx 服务端错误：匹配关键字或独立的 5xx 状态码（避免误匹配含 "5" 的端口号等）
                    message.contains("internal server") ||
                        message.contains("bad gateway") ||
                        message.contains("service unavailable") ||
                        message.contains("gateway timeout") ||
                        message.contains("server error") ||
                        STATUS_5XX.containsMatchIn(message) -> SyncErrorType.SERVER
                    // 4xx 数据/请求错误：匹配关键字或独立的 4xx 状态码
                    message.contains("not found") ||
                        message.contains("bad request") ||
                        message.contains("no such") ||
                        message.contains("does not exist") ||
                        STATUS_4XX.containsMatchIn(message) -> SyncErrorType.DATA
                    else -> SyncErrorType.UNKNOWN
                }
            }
            is IllegalArgumentException,
            is NumberFormatException,
            is IndexOutOfBoundsException -> SyncErrorType.DATA
            else -> SyncErrorType.UNKNOWN
        }
    }

    /**
     * 计算下次重试延迟（指数退避，按错误类型区分上限）
     * @param consecutiveFailures 连续失败次数
     * @param errorType 错误类型
     */
    fun calculateRetryDelayMillis(
        consecutiveFailures: Int,
        errorType: SyncErrorType
    ): Long {
        val baseDelay = when (errorType) {
            SyncErrorType.NETWORK -> NETWORK_BASE_DELAY_MILLIS
            SyncErrorType.SERVER -> SERVER_BASE_DELAY_MILLIS
            SyncErrorType.DATA -> DATA_BASE_DELAY_MILLIS
            SyncErrorType.UNKNOWN -> UNKNOWN_BASE_DELAY_MILLIS
        }
        val maxDelay = when (errorType) {
            SyncErrorType.NETWORK -> NETWORK_MAX_DELAY_MILLIS
            SyncErrorType.SERVER -> SERVER_MAX_DELAY_MILLIS
            SyncErrorType.DATA -> DATA_MAX_DELAY_MILLIS
            SyncErrorType.UNKNOWN -> UNKNOWN_MAX_DELAY_MILLIS
        }
        val exponential = baseDelay * (1 shl consecutiveFailures.coerceAtMost(6))
        return min(exponential, maxDelay)
    }

    /**
     * 判断某类错误是否值得重试
     */
    fun isRetryable(errorType: SyncErrorType): Boolean {
        return errorType != SyncErrorType.DATA
    }

    private const val TRADING_INTERVAL_MILLIS = 15 * 60 * 1000L       // 15 分钟
    private const val NON_TRADING_INTERVAL_MILLIS = 4 * 60 * 60 * 1000L // 4 小时

    /** 交易时段同步间隔（分钟），供 UI 文案与调度器引用，避免各处硬编码「15」 */
    const val TRADING_INTERVAL_MINUTES = TRADING_INTERVAL_MILLIS / 60_000L

    /** 非交易时段同步间隔（小时），供 UI 文案引用，避免各处硬编码「4」 */
    const val NON_TRADING_INTERVAL_HOURS = NON_TRADING_INTERVAL_MILLIS / 3_600_000L

    /**
     * 调度器使用的最小同步间隔下限（分钟）。WorkManager 退避下限是 10 分钟，
     * 这里取与交易时段一致的 15，作为「间隔不低于多少」的唯一真相源。
     */
    const val MIN_SYNC_INTERVAL_MINUTES = 15

    private const val NETWORK_BASE_DELAY_MILLIS = 10_000L
    private const val NETWORK_MAX_DELAY_MILLIS = 5 * 60 * 1000L

    private const val SERVER_BASE_DELAY_MILLIS = 30_000L
    private const val SERVER_MAX_DELAY_MILLIS = 10 * 60 * 1000L

    private const val DATA_BASE_DELAY_MILLIS = 60_000L
    private const val DATA_MAX_DELAY_MILLIS = 60_000L

    private const val UNKNOWN_BASE_DELAY_MILLIS = 30_000L
    private const val UNKNOWN_MAX_DELAY_MILLIS = 5 * 60 * 1000L

    // 匹配独立的 4xx/5xx HTTP 状态码，使用词边界避免误匹配端口号等（如 5432、8080）
    private val STATUS_4XX = Regex("\\b4\\d\\d\\b")
    private val STATUS_5XX = Regex("\\b5\\d\\d\\b")
}
