package com.example.stockdiary.data.repository

/**
 * 分红抓取策略的纯函数与常量（退避窗 / 空结果复用 / 批量切块），从 [DividendRepository]
 * 抽出独立成文件，仍位于 data.repository 包，调用方 import 路径不变。
 *
 * 这些符号不碰 Context / Room / 网络，可独立 JVM 单测钉死规则（见
 * DividendRepositoryCachePolicyTest / DividendRepositoryPrefetchBudgetTest / BatchChunkingTest）。
 */

/**
 * 分红抓取失败退避窗（指数退避，见 [computeFailureBackoff]）。
 * 定义为文件级 private，使类内成员与文件级纯函数 [shouldSkipAfterFailure]/[computeFailureBackoff] 均可访问。
 */
private const val FAILURE_BACKOFF_BASE_MS = 5L * 60 * 1000            // 首档 5 分钟
private const val FAILURE_BACKOFF_MAX_MS = 6L * 60 * 60 * 1000        // 封顶 6 小时
private const val FAILURE_BACKOFF_BASE_MS_NEVER = 1L * 60 * 1000      // 从未成功：首档 1 分钟
private const val FAILURE_BACKOFF_MAX_MS_NEVER = 1L * 60 * 60 * 1000   // 从未成功：封顶 1 小时
// ==========================================================================
// §9 文件级私有辅助（纯函数，可直接 JVM 单测）
// shouldReuseEmptyResult（空结果复用判定）/ isFailedWithin（失败退避窗）/ chunkSecurityCodes（东财合并请求的代码分块，按编码后长度估算成本）。
// ==========================================================================

/**
 * 库里一条分红记录都没有时，该不该把它当成「缓存命中」而不是「没抓过」。
 *
 * **纯函数**，不碰 Context / Room / 网络，所以能直接 JVM 单测。
 * 抽出来是因为这条判断错了代价都不小，而且两个方向的错都很隐蔽：
 *
 * - 该认而不认 → 87% 的标的（实测）每次冷启动重抓一遍，接口被打到 514 限流，
 *   进程长时间占着内存做解析，于是更容易被系统回收 → 又一轮重抓
 * - 不该认而认 → 一只真有分红的标的被压 24 小时不抓，界面上分红一直是空的
 *
 * @param dbRecordCount 库里该代码的记录条数
 * @param lastFetchedAt 上次抓取完成的时间戳；null 表示从没抓过
 * @param now 当前时间戳
 * @param ttl 认定「抓过」的有效期
 */
internal fun shouldReuseEmptyResult(
    dbRecordCount: Int,
    lastFetchedAt: Long?,
    now: Long,
    ttl: Long
): Boolean {
    // 库里有记录就走正常的库恢复路径，轮不到这里
    if (dbRecordCount > 0) return false
    // 从没抓过 → 真的没数据，必须去抓
    val fetched = lastFetchedAt ?: return false
    if (fetched <= 0L) return false
    // 抓过，但太久之前 → 结论可能已经过期（一只基金可能刚宣布首次分红）
    if (now - fetched >= ttl) return false
    // 时间戳来自未来（改过系统时间）→ 不认，宁可重抓也别无限期压住
    if (fetched > now) return false
    return true
}

/**
 * 失败退避窗长度（指数退避）。
 *
 * 按**连续失败次数** `count` 指数增长：`base × 2^(count-1)`，封顶 `max`。
 * 偶发抖动（count 小）很快自愈，持续失败的源不会无限放大请求量。
 * `everSucceeded = false`（历史上从未成功过，可能是刚加的、其实有效的标的）
 * 用更短的档位，让它更快重试，避免被当成「永久失败」长时间屏蔽。
 *
 * 用 `Long` 运算 + `shift` 上限 30，防止 `2^shift` 溢出 `Int`；`coerceAtMost(max)`
 * 封顶，`base > 0` 保证结果非负。
 *
 * @param count 连续失败次数（≥1）
 * @param everSucceeded 该 symbol 历史上是否成功抓取过
 */
internal fun computeFailureBackoff(count: Int, everSucceeded: Boolean): Long {
    val base = if (everSucceeded) FAILURE_BACKOFF_BASE_MS else FAILURE_BACKOFF_BASE_MS_NEVER
    val max = if (everSucceeded) FAILURE_BACKOFF_MAX_MS else FAILURE_BACKOFF_MAX_MS_NEVER
    val shift = (count - 1).coerceAtLeast(0).coerceAtMost(30)
    return (base.toLong() shl shift).coerceAtMost(max.toLong())
}

/**
 * 上一轮抓取**没问到**（网络失败 / 限流）之后，这一轮该不该先歇一会儿。
 *
 * **纯函数**，不碰 Context / Room / 网络，便于单测。
 *
 * 「失败」与「确实没有分红」必须分开记。写成 [shouldReuseEmptyResult] 那种结论，
 * 一次网络抖动就被固化成「这只没有分红」最长 7 天且界面静默；但干脆不记也不行 ——
 * 场外基金没有批量入口（天天基金每基金一个页面），上百只标的每轮各重打一次失败
 * 的接口就是请求风暴。所以失败只压一个指数退避窗（见 [computeFailureBackoff]）
 * ，过期自动重试，用户手动刷新（forceRefresh）随时可以绕过。
 *
 * @param lastFailedAt 上次失败的时间戳；null 表示没失败过
 * @param now 当前时间戳
 * @param ttl 退避窗长度
 */
internal fun shouldSkipAfterFailure(lastFailedAt: Long?, now: Long, ttl: Long): Boolean {
    val failed = lastFailedAt ?: return false
    if (failed <= 0L) return false
    // 时间戳来自未来（改过系统时间 / 时钟回拨）也算在窗内：
    // 宁可多等一窗不重打，也不要把请求量放大回去
    return now - failed < ttl
}
/**
 * 单个批量请求最多带多少个 SECURITY_CODE。
 *
 * 50 个代码的 filter 编码后约 2KB，离服务端/代理常见的 8KB 上限有足够余量；
 * 再大一是没有额外收益，二是把「414 或 OkHttp 拒绝」的概率推高 ——
 * 而批量失败意味着整批预取退化成逐条级联，正是这次要修掉的请求风暴。
 */
internal const val MAX_SECURITY_CODES_PER_REQUEST = 50

/**
 * 单个批量请求 URL 的长度预算（字符）。
 *
 * 按最坏情况估算：URLEncoder 会把引号、括号、逗号全部转义成 %XX，
 * 一个代码最坏占 3 倍长度。4KB 预算下即使代码全是 `SH.510050` 这种
 * 9 字符长码，也只会先撞数量上限，URL 永远不会接近 8KB。
 */
internal const val MAX_BATCH_URL_LENGTH = 4000

/**
 * 批量请求 URL 的固定部分基数：域名 + reportName / columns / pageSize /
 * sortColumns 等参数，再加编码放大的经验估计。切块时把它算进每一块的预算。
 */
internal const val BATCH_URL_FIXED_OVERHEAD = 600

/** 批量分块请求的并发上限：块数本来就少（百个代码 ≈ 2~4 块），别把东财当成压测对象 */
internal const val MAX_CONCURRENT_BATCH_CHUNKS = 3

/**
 * 把一批 SECURITY_CODE 切成分块，让每块的批量请求 URL 都控制在安全长度内。
 *
 * ## 为什么必须切
 *
 * 批量查询要把所有代码拼进 `filter=(SECURITY_CODE in ("a","b",…))`。
 * 59 个标的加上 ETF 的三种格式变体就是 100+ 个代码，URL 有好几 KB：
 * 服务端可能返回 414，OkHttp/代理也可能直接拒绝 —— 而批量失败意味着
 * 整批预取退化成逐条级联（那就是这次要修的请求风暴），所以必须在发出去之前切好。
 *
 * 双保险，先撞哪个算哪个：数量上限 [MAX_SECURITY_CODES_PER_REQUEST] 与
 * 长度预算 [MAX_BATCH_URL_LENGTH]（含固定基数 [BATCH_URL_FIXED_OVERHEAD]，
 * 每个代码按最坏 3 倍编码长度估）。
 *
 * **纯函数**：不碰网络与 Context，切块规则错了（丢代码 / 超长 URL）能直接单测。
 *
 * @param codes 一批 SECURITY_CODE，结果保持顺序、不重不漏
 * @return 分块结果；单个代码就超预算时独占一块 —— 宁可发一个可能被拒的长请求，
 *         也不能把它切丢了（丢了它就会走逐条级联，正是要避免的）
 */
internal fun chunkSecurityCodes(
    codes: List<String>,
    maxCodesPerChunk: Int = MAX_SECURITY_CODES_PER_REQUEST,
    maxUrlLength: Int = MAX_BATCH_URL_LENGTH,
    fixedOverhead: Int = BATCH_URL_FIXED_OVERHEAD
): List<List<String>> {
    if (codes.isEmpty()) return emptyList()
    val safeCount = maxCodesPerChunk.coerceAtLeast(1)
    val chunks = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var usedLength = fixedOverhead
    for (code in codes) {
        // 引号 + 逗号 + 括号开销，最坏情况每个字符都被百分号编码
        val encodedCost = (code.length + 3) * 3
        val wouldExceedCount = current.size >= safeCount
        val wouldExceedLength = usedLength + encodedCost > maxUrlLength
        if (current.isNotEmpty() && (wouldExceedCount || wouldExceedLength)) {
            chunks += current
            current = mutableListOf()
            usedLength = fixedOverhead
        }
        current += code
        usedLength += encodedCost
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}
