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
 * 全量刷新（`forceRefresh=true`）时，是否允许「先删掉该标的全部自动记录、再插回本次解析到的记录」。
 *
 * **纯函数**，不碰 Context / Room / 网络，便于单测（见 A16 回归）。
 *
 * **A16 修复（阻断）—— 残缺解析洗库**：R1 只护住了「空结果」分支（[DividendRepository]
 * 拿到空结果时先看库里有没有旧自动记录，有就保留）。但 `classifyFundDividendPage` 对
 * `recordCount > 0` 一律认成 `HAS_RECORDS`，全量刷新路径就**无条件** `deleteNonManualBySymbol`
 * 再只插回本次 `validRecords` —— 一旦某次解析残缺（HTML 200、表头在、但只解析出一小部分行：
 * 页面改版 / 表头索引失败 / 日期或金额校验批量丢弃），就会把「首次 10 条、二次仅 2 条」里的
 * 另外 8 条洗掉，年化收入当场缩水，下次抓全了又涨回来 —— 正是用户原始「刷新跳动」的残留根因。
 *
 * 判定规则：本次解析到的有效记录数**不少于**库里已有的自动记录数，才认为是「完整或更多」的刷新、
 * 可以安全替换；否则（解析到的比库里已有的少）说明这次解析大概率不完整，**绝不**删库，
 * 只做 upsert（按 symbol+date 跳过已存在），从而保留旧记录、不丢数据。
 * 首抓（库里 0 条）解析到 N>0 条 → `N >= 0` 成立 → 正常插入。
 *
 * 这是 R1「宁保守、不可误删」原则的延伸：真正停止分红的标的会多留一段旧值，等下次完整抓取纠正，
 * 代价远小于把真有分红的标的洗成少一截。
 *
 * @param existingAutoRecordCount 库里该标的**当前**的自动记录数（不含手动记录、amount>0）
 * @param incomingValidRecordCount 本次解析并通过过滤的有效记录数
 */
internal fun shouldReplaceRecordsOnFullRefresh(
    existingAutoRecordCount: Int,
    incomingValidRecordCount: Int
): Boolean {
    // 负数无意义（调用方传错），按「不准替换」处理，避免误删
    if (existingAutoRecordCount < 0 || incomingValidRecordCount < 0) return false
    // 解析到的有效记录不少于库里已有的，说明这次是「完整或更多」的刷新，可以安全替换
    return incomingValidRecordCount >= existingAutoRecordCount
}

/**
 * 落库后是否允许把本次解析结果写进内存缓存。
 *
 * **纯函数**，不碰 Context / Room / 网络，便于单测。
 *
 * A16 的第二半：残缺解析时 [shouldReplaceRecordsOnFullRefresh] 已让 DB 保留旧记录，
 * 若内存缓存仍无条件写入本次的残缺集合，[DividendRepository] 的 `tryGetFromCache`
 * 会在 24h 内优先命中这份残缺数据 —— DB 与缓存口径不一致，年化收入照样缩水。
 * 因此全量刷新只有「允许替换」时才更新缓存；增量路径不删库、只补新记录，照常更新。
 *
 * @param isIncremental 是否为增量补抓（不删库）
 * @param replaceAllowed 本次全量刷新是否通过 [shouldReplaceRecordsOnFullRefresh]
 */
internal fun shouldUpdateDividendCache(isIncremental: Boolean, replaceAllowed: Boolean): Boolean =
    isIncremental || replaceAllowed

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

// ==========================================================================
// §10 分红预取轮次汇总文案（纯观测）
//
// 只把「本轮各计数器 + 未取到代码清单」拼成一行 INFO 文本，不碰 Context / Room /
// 网络，也不参与任何抓取 / 缓存 / 退避决策。抽成文件级纯函数是为了能用 JVM 单测
// 钉死计数口径与清单截断（见 DividendPrefetchSummaryTest），不需要 Robolectric。
// ==========================================================================

/**
 * 汇总行里最多列出多少个「本轮未取到数」的标的代码。
 *
 * app.log 是环形缓冲（活跃文件 8MB 轮转、归档裁到最后 8 万行），一轮刷新里
 * 逐标的的 WARN 本就会被冲掉大半。汇总行必须自带清单，但清单又不能无限长：
 * 取 12 个，余下的用 `…+N` 交代数量，既够定位「大概是哪批标的」，又不撑爆单行。
 */
internal const val DIVIDEND_PREFETCH_MAX_CODES = 12

/**
 * 代码清单的字符上限（不含 `…+N` 尾巴）。
 *
 * 双保险：条数上限管不住「代码异常长」的情况（例如误把一段 URL 当代码传进来），
 * 再加一道字符上限，保证这一行始终是个短行。
 */
internal const val DIVIDEND_PREFETCH_MAX_CODE_CHARS = 160

/**
 * 把「本轮未取到数」的标的代码清单拼成一行，规范化去重后排序、按上限截断。
 *
 * **纯函数**：调用方（[DividendRepository.prefetchDividends]）传入的已经是
 * `StockCodeManager.normalizeSymbol` 规范化过的代码，这里再做一次 [distinct] 是
 * 防御性的——并发收尾时万一同一代码被记了两次，也不该在清单里占两个位置。
 * 排序是为了让日志输出稳定（并发完成顺序不确定），便于按代码 grep 与用例断言。
 *
 * 截断有两层，先撞哪层算哪层，被省略的**条数**统一由 `…+N` 交代：
 * - 条数上限 [maxCodes]；
 * - 字符上限 [maxChars]（第一条无条件纳入，避免单个长代码把清单变成空串）。
 *
 * @param codes 规范化后的标的代码（可含重复）
 * @param maxCodes 最多列出的条数
 * @param maxChars 清单的字符预算
 * @return 逗号分隔的一行文本；空清单返回「无」；超限时以 `…+N` 结尾
 */
internal fun formatSkippedCodes(
    codes: List<String>,
    maxCodes: Int = DIVIDEND_PREFETCH_MAX_CODES,
    maxChars: Int = DIVIDEND_PREFETCH_MAX_CODE_CHARS
): String {
    val distinct = codes.distinct().sorted()
    if (distinct.isEmpty()) return "无"

    val safeMaxCodes = maxCodes.coerceAtLeast(1)
    val safeMaxChars = maxChars.coerceAtLeast(1)
    val shown = ArrayList<String>(safeMaxCodes.coerceAtMost(distinct.size))
    var usedChars = 0
    for (code in distinct) {
        if (shown.size >= safeMaxCodes) break
        // 逗号只在第二项起才占字符；第一条无条件纳入，保证清单不为空
        val cost = code.length + if (shown.isEmpty()) 0 else 1
        if (shown.isNotEmpty() && usedChars + cost > safeMaxChars) break
        shown.add(code)
        usedChars += cost
    }

    val omitted = distinct.size - shown.size
    return shown.joinToString(",") + if (omitted > 0) "…+$omitted" else ""
}

/**
 * 组装分红预取阶段收尾的**一行** INFO 汇总文案。
 *
 * **纯函数**：只做计数与字符串拼接，不触发任何抓取 / 重试 / 退避 / 缓存写入。
 * 口径说明（都与 [DividendRepository.prefetchDividends] 的观测簿记一一对应）：
 * - `totalSymbols`：本轮参与标的数（规范化去重后）；
 * - `attempted`：本轮发起逐条抓取的标的数（含被时间预算拦下的）；
 * - `success`：本轮源应答并写下新结论的标的数（`markFetched` 时间戳 >= 轮次起点）；
 * - `skippedCodes`：本轮未取到新数据的代码，`unavailable` 其中本轮新记
 *   「数据源未应答（含 514/429 限流）」的条数（`markFailed` 时间戳 >= 轮次起点）：
 *   真实 514/429 响应与 60s 退避窗口内短路都会抛 `DividendSourceUnavailableException`
 *   并写本轮 `markFailed`，故都归这一档；
 * - 未取到里剩下的 = 「退避/预算/缓存复用等」条数，即本轮未写 `markFailed` 的情形
 *   （时间预算跳过、上一轮失败退避窗复用旧值、内部缓存 / DB 命中、普通异常兜底）；
 * - 参与数减发起数 = 缓存或 TTL 有效、本轮无需重抓的标的数；
 * - `rateLimitDelta`：本轮真实 514/429 **响应**次数（只增计数做差），不是受影响标的数 ——
 *   退避窗口内被短路的标的不再推进信号，它们计入「数据源未应答」那一档。
 *
 * 「复用缓存 / 沿用旧值的精确数量」与「最大数据年龄」刻意**不在此汇总**：
 * 前者散落在 `getDividendsBySymbol` 内部（内存缓存命中、失败兜底读库、TTL 分支），
 * 后者在库记录里根本没有时间戳，要精确统计就得改缓存 / DAO / DB 结构，
 * 与本轮「只加日志」的边界冲突，故省略，日志里只给出「无需重抓」这个无歧义的差值。
 *
 * @param roundId 本轮分红预取序号（进程内自增，仅用于日志归属）
 * @return 单行中文文本，不含账户名 / 金额 / 密钥，可直接交给 `AppLogger.i`
 */
internal fun formatDividendPrefetchSummary(
    roundId: Int,
    totalSymbols: Int,
    attempted: Int,
    success: Int,
    unavailable: Int,
    rateLimitDelta: Int,
    skippedCodes: List<String>,
    maxCodes: Int = DIVIDEND_PREFETCH_MAX_CODES,
    maxChars: Int = DIVIDEND_PREFETCH_MAX_CODE_CHARS
): String {
    val distinct = skippedCodes.distinct()
    val unfetched = distinct.size
    // 调用方计数错位时也钳到 [0, unfetched]：汇总行宁可少报，也不能出现
    // 「未取到 1 只（未应答 99 只）」这种自相矛盾的数字
    val reportedUnavailable = unavailable.coerceIn(0, unfetched)
    val backoffOrSkipped = unfetched - reportedUnavailable
    val reused = (totalSymbols - attempted).coerceAtLeast(0)
    val rateLimited = rateLimitDelta.coerceAtLeast(0)
    val codesText = formatSkippedCodes(skippedCodes, maxCodes, maxChars)

    return "[分红预取轮次#$roundId] 汇总：参与 $totalSymbols 只，" +
        "本轮发起抓取 $attempted 只，成功拿到新数据 $success 只，" +
        "未取到 $unfetched 只（数据源未应答（含 514/429 限流） $reportedUnavailable / 退避/预算/缓存复用等 $backoffOrSkipped），" +
        "本轮真实 514/429 响应 $rateLimited 次（退避窗口内被短路的标的计入上一档），" +
        "缓存或TTL有效无需重抓 $reused 只 | " +
        "未取到代码[$unfetched]：$codesText"
}
