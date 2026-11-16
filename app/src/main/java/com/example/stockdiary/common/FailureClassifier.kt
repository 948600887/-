package com.example.stockdiary.common

/**
 * 失败文案 → 失败类别的**唯一入口**（纯函数，无 Android / 协程依赖）。
 *
 * ## 为什么要有它
 *
 * 同一套「这是 4xx 还是 5xx」「这是限流吗」「这是重试也不会变的确定性失败吗」
 * 的判定此前散在三处、各写各的正则：
 * - `SyncStrategy.classifyError` 用 `\b4\d\d\b` / `\b5\d\d\b` 判 4xx/5xx；
 * - `NetworkRetryHelper.shouldRetry` 另用 `(?<!\d)403(?!\d)` 判 403/429；
 * - `NetworkChecker.isNetworkError` 再按 `message.contains("network")` 判一次。
 *
 * 同一句 `API响应错误: 403` 在不同链路里可能被归成不同类别，诊断日志与源统计
 * 各说各话。这里把「从文案里认状态码」与「业务确定性失败词组」收口到一处，
 * 上层只做映射，不再各自解析字符串。
 *
 * ## 刻意**不**统一的部分
 *
 * - **各源的重试次数、退避窗口、熔断档位保持原样**：那是源策略，互不相同是合理的；
 * - **请求级 vs 轮次级「是否重试」的差异保持原样**：例如 `UnknownHostException`
 *   请求级不重试（几秒内不会突然可解析，交给源熔断），轮次级归 `NETWORK`（下一轮
 *   还能再试）。两者语义不同，统一会改变真机行为，见 `docs/PROJECT_MEMORY.md`。
 */
object FailureClassifier {

    /**
     * 独立的 3 位 HTTP 状态码 token：`(?<!\d)([45]\d\d)(?!\d)`。
     *
     * 裸子串匹配会踩坑：成交额 14035、错误码 4291、金额 40300、股票代码 sz000403、
     * 时间戳带 403 段……这些其实都不是 HTTP 状态码。前后各加一个「非数字」断言后，
     * `HTTP 403` / `status=403` / `code 403 limit` 仍能命中，长数字不会误伤。
     */
    private val HTTP_STATUS_TOKEN = Regex("""(?<!\d)([45]\d\d)(?!\d)""")

    /** 文案里出现的全部 4xx / 5xx 状态码（按出现顺序，去重交给调用方按需处理） */
    fun httpStatusCodesIn(message: String?): List<Int> {
        val text = message ?: return emptyList()
        return HTTP_STATUS_TOKEN.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
    }

    /** 文案里是否出现了 5xx 服务端错误状态码 */
    fun hasServerErrorStatus(message: String?): Boolean =
        httpStatusCodesIn(message).any { it in 500..599 }

    /** 文案里是否出现了 4xx 客户端错误状态码 */
    fun hasClientErrorStatus(message: String?): Boolean =
        httpStatusCodesIn(message).any { it in 400..499 }

    /**
     * 文案里是否出现了**限流**类状态码（403 被封 / 429 Too Many Requests）。
     *
     * 这类失败重试只会火上浇油（还会加速被封），请求级必须一次就放弃。
     * 注意 403 在轮次级仍属于 4xx → `DATA`，两条链路对它的处置不同是有意的。
     */
    fun isRateLimited(message: String?): Boolean =
        httpStatusCodesIn(message).any { it == 403 || it == 429 }

    /**
     * 限流 / 无效标的等业务文案。这些本身就是「词组」，按子串匹配不会有歧义。
     */
    private val NON_RETRYABLE_PHRASES: List<String> = listOf(
        "频率过高", "请求过于频繁", "rate limit",
        "请检查股票代码", "未获取到股票数据", "代码不存在", "无效的代码"
    )

    /**
     * 是否是「重试也不会改变结果」的确定性失败。
     *
     * 各类数据源目前把业务失败统一包成 `IOException` 往外抛（HTTP 403、代码不存在、
     * 返回空串都是同一类型），请求级重试层只能靠文案识别。这是现状下的最小侵入方案。
     */
    fun isDeterministicBusinessFailure(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return NON_RETRYABLE_PHRASES.any { message.contains(it, ignoreCase = true) } ||
            isRateLimited(message)
    }
}
