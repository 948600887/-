package com.example.stockdiary.network

import com.example.stockdiary.common.APIEndpoints
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 币安现货余额的一条记录。
 *
 * 账户接口（`/api/v3/account`）按基础资产给余额：BTC、ETH、USDT……
 * [free] 是可用、[locked] 是挂单冻结，两者相加才是真实持仓。
 */
data class BinanceBalance(
    val asset: String,
    val free: Double,
    val locked: Double,
    /** 余额来源。默认 SPOT；借币负债(LOAN_DEBT)与抵押资产(LOAN_COLLATERAL)带标记，
     *  让导入预览内核能把它们与同币种现货区分开、独立成行，而不是按 symbol 合并抵消。 */
    val kind: Kind = Kind.SPOT,
    /**
     * 该余额是否来自币安**定期/锁仓**理财（Locked Staking）。
     * 仅对 [kind]==SPOT 且 [asset] 是 LD 理财壳的持仓有意义：true=定期锁仓、false=活期理财。
     * 由 [mergeEarnIntoBalances] 按底层资产归组时填入（见其注释），经 buildPreview → 落库
     * 一路透传到 [com.example.stockdiary.database.Asset.isEarnLocked]，展示层据此把「锁仓」
     * 标拆成「理财（活期）/ 锁仓（定期）」。注意与上面的 `locked: Double`（现货冻结量）是两回事。
     */
    val isEarnLocked: Boolean = false
) {
    /** 可用 + 冻结 = 该资产的实际持有量 */
    val total: Double
        get() = free + locked

    /** 余额来源种类。预览内核按它决定 symbol 是否需要加负债/抵押后缀（避免与现货抵消）。 */
    enum class Kind {
        /** 现货 / 理财合并后的普通余额 */
        SPOT,

        /** 理财（Simple Earn）持仓，由 mergeEarnIntoBalances 产出；展示层按 isEarnProduct 另行打标 */
        EARN,

        /** 活期借币（Flexible Loan）的负债，值为负，预览里应显示为「借币负债」 */
        LOAN_DEBT,

        /** 借币时抵押给币安的担保资产，值仍为正（用户自己的钱、只是被锁定），预览里应显示为「抵押」 */
        LOAN_COLLATERAL
    }
}

/**
 * 简单理财（Simple Earn）的一条持仓。
 *
 * 币安的 Earn 持仓**不等于**现货余额：官方对账单实测（账户 1040426493，2026-10-22）
 * 显示 Spot & Margin = 0.00 USD 而 Earn = 209.86 USD，钱全在理财里。`/api/v3/account`
 * 只反映现货口径，理财里还有一部分并没落在现货余额上——只导入现货就会少算。
 *
 * @param amount 该产品的持有数量（活期取 `totalAmount`，定期取 `amount`）
 * @param locked true = 定期/锁仓，false = 活期
 */
data class BinanceEarnPosition(
    val asset: String,
    val amount: Double,
    val locked: Boolean
)

/**
 * 币安接口失败的三大类——决定给用户看**哪一条**排障建议。
 *
 * ## 为什么必须分类，而不是一律「请检查 API Key 权限」
 *
 * 打同一个接口失败有三种完全不同的原因，排障动作也完全不同：
 *
 * - [NETWORK]：超时 / DNS 解析不了 / 连不上 / 连接被中断。→ 用户该查网络与代理节点；
 * - [SERVER_OR_RATE_LIMIT]：HTTP 429 / 418（限流、IP 被临时封）或 5xx（币安服务端抖动）。
 *   → 与用户的 API Key **一点关系都没有**，等几分钟就好；
 * - [PERMISSION_OR_OTHER]：401 / 403 / -2015（没开「读取」权限）、451（地区限制）、参数错。
 *   → 这时才该提示用户去翻 API Key 勾选项、IP 白名单、地区限制。
 *
 * 旧文案一律说「请确认 API Key 已勾选读取权限、IP 白名单与地区限制都放行」，
 * 网络抖动和被限流的用户照着折腾一整轮也解决不了问题（真机日志实锤：第 2 页失败原因是
 * `SocketTimeoutException`，提示却让用户去查 IP 白名单）。
 */
enum class BinanceFailureKind {

    /** 网络类：超时 / DNS 解析不了 / 连不上 / 连接中断 */
    NETWORK,

    /** 限流或服务端异常：HTTP 418 / 429 / 5xx。**刻意不重试**——币安限流时重试会加重封禁 */
    SERVER_OR_RATE_LIMIT,

    /** 权限 / 地区 / 参数等「用户侧可修」的失败：401 / 403 / -2015 / 451 */
    PERMISSION_OR_OTHER
}

/**
 * 币安返回**非 2xx** 时的失败。
 *
 * 单独一个类型（而不是裸 [Exception]）是为了让上层能**按 HTTP 状态码**分类，
 * 而不是去 message 里 `contains("429")` 猜——猜字符串既脆又容易误判
 * （币安业务码本身就可能是 -1003、-2015 这些数字）。
 *
 * 注意：它**不是** [IOException]，所以 [BinanceAccountAPI] 的单页重试不会重试它
 * （限流/权限失败重试一百次也一样，只会白刷接口、加重限流）。
 *
 * @param httpCode    HTTP 状态码（如 429 / 418 / 500 / 403 / 451）
 * @param binanceCode 币安响应体里的 `code`（缺省时回落 httpCode）
 */
internal class BinanceApiException(
    val httpCode: Int,
    val binanceCode: Int,
    message: String
) : Exception(message)

/**
 * 一个理财接口（活期 / 定期）的拉取结果：拿到多少条，以及这一页出了什么问题。
 *
 * 与 [BinanceEarnFetch] 的分工：那是「两个接口汇总后给调用方」的结果，
 * 这是单个接口内部的搬运容器——**部分成功也要两边都留住**：
 * 形状坏的行丢掉了，但好行已经解析出来了，绝不能为了报问题把好数据一起扔掉。
 *
 * @param positions 本页解析出的持仓（部分成功时非空）
 * @param failures  本页的人话问题（请求失败 / 响应形状不对 / 行内字段缺失）；空 = 本页完全正常
 * @param failureKind 失败的类别（网络 / 限流或服务端 / 权限）。由 [BinanceAccountAPI.fetchEarnPage]
 *        在 catch 里按**异常类型**判定后透传，**绝不靠字符串 include 硬猜**——失败文案里本来
 *        就有「超时」这类词，猜字符串迟早猜错。首个非「权限类」的失败决定最终文案分支。
 * @param httpCode 限流/服务端类失败对应的 HTTP 状态码，仅用于文案里给出可核对的信息
 */
internal data class EarnPageOutcome(
    val positions: List<BinanceEarnPosition>,
    val failures: List<String>,
    val failureKind: BinanceFailureKind = BinanceFailureKind.PERMISSION_OR_OTHER,
    val httpCode: Int? = null
)

/**
 * [parseEarnPositions] 的结果：持仓 + 逐项诊断。
 *
 * 存在的意义是把「真的没有理财持仓」和「接口给了但我们没读懂」分开。旧实现一律
 * 返回空列表，两者无从分辨，结果是真机上「少了一块」却零提示（见 [parseEarnPositions]）。
 *
 * @param positions  解析出的持仓
 * @param bodyNotJson 响应体连 JSON 对象都不是（HTML / 空串 / JSON 数组…/解析异常）时给异常类名
 * @param missingRows 响应体里没有 rows 数组（接口形状可能已变）
 * @param badRows     因**形状问题**被跳过的行及其原因（缺资产名 / 缺数量字段）
 * @param emptyRows   因数量 ≤ 0 被跳过的行数（已赎回空壳，属正常，不算问题）
 * @param rowCount    rows 里的总行数
 */
internal data class EarnParseResult(
    val positions: List<BinanceEarnPosition>,
    val bodyNotJson: String? = null,
    val missingRows: Boolean = false,
    val badRows: List<String> = emptyList(),
    val emptyRows: Int = 0,
    val rowCount: Int = 0,
    /**
     * 币安返回的 `total` 字段（接口声明的持仓总数）。> 0 时 [fetchEarnPage] 用它精确决定翻页数，
     * 不再靠「本页行数 < 单页上限」猜末页——能正确处理「恰好满一页」不白打下一页空请求，
     * 也能在「接口实际条数 ≠ 本页行数之和」时仍拉全。解析不到时为 0（视为未知，回退启发式）。
     */
    val total: Int = 0
) {
    /**
     * 这一页「是否伪装成了成功」。
     *
     * 只有形状类问题算：空壳行（数量为 0）与「rows 是空数组」都是真实状态，不算。
     */
    val hasShapeProblem: Boolean
        get() = bodyNotJson != null || missingRows || badRows.isNotEmpty()

    /** 可直接给用户看的问题清单（[hasShapeProblem] 为 false 时为空） */
    fun failures(kind: String): List<String> = buildList {
        bodyNotJson?.let { add("$kind：响应体不是 JSON（$it），接口形状可能已变") }
        if (missingRows) add("$kind：响应体里没有 rows 数组，接口形状可能已变")
        if (badRows.isNotEmpty()) {
            add("$kind：${badRows.size}/$rowCount 行被跳过（${badRows.joinToString("、")}）")
        }
    }
}

/**
 * 理财持仓的拉取结果：拿到多少条，以及哪些接口没拉成。
 *
 * ## 为什么要把失败带出来
 *
 * 两个理财接口都需要 API Key 开通 Simple Earn 读取权限，额度/权限/网络随时可能不满足。
 * 从前失败一律降级成空列表，用户看到的只是「持仓比官方对账单少了一大块」（实测
 * 2026-10-22 对账单：Earn 209.86 USD，现货口径只导到 ~125 USD），却完全不知道原因，
 * 只能怀疑 App 算错。现在把失败原因带进预览页，用户能直接看到该去开哪个权限。
 *
 * @param positions 成功拉到的持仓（活期 + 定期；失败的那半边为空）
 * @param failures  失败的人话原因（如「活期：币安拒绝请求（-2015）：…」）；空 = 全部成功
 * @param failureKind 失败的类别（网络 / 限流或服务端 / 权限），决定 [warning] 走哪条排障建议。
 *        网络与限流都**不该**让用户去翻 API Key 权限——那是纯粹的误导。
 * @param httpCode 限流/服务端类失败对应的 HTTP 状态码（用于文案里写明「HTTP 429」）。
 */
data class BinanceEarnFetch(
    val positions: List<BinanceEarnPosition>,
    val failures: List<String>,
    val failureKind: BinanceFailureKind = BinanceFailureKind.PERMISSION_OR_OTHER,
    val httpCode: Int? = null
) {
    /**
     * 给用户看的一句话；全部拉取成功时为 null。
     *
     * ## 为什么要按失败原因分流（2026-11-01 修，2026-11-02 补 5xx/429）
     *
     * 旧文案不管什么原因都让用户去确认「读取 / Enable Reading」权限与 IP 白名单。
     * 但真机上失败的真实原因大多是这两类，**与权限毫无关系**：
     * - `SocketTimeoutException`（网络/代理抖了一下）；
     * - HTTP 429 / 418 / 5xx（币安限流或服务端异常，等几分钟就好）。
     * 用户照着「检查权限」的提示去翻 API Key 设置只会白折腾，而且越查越怀疑自己。
     * 所以三类失败各给各的建议，权限/地区提示只在真正是权限问题时才出现。
     */
    val warning: String?
        get() = if (failures.isEmpty()) {
            null
        } else {
            "理财持仓没拉全（${failures.joinToString("；")}）。" +
                // 一条都没拿到才是「只导入现货」；拿到一部分时数据照导，别误导用户
                (if (positions.isEmpty()) "本次只导入现货，总资产会少算；"
                else "本次仍会导入已拉到的那部分，其余的会少算；") +
                when (failureKind) {
                    BinanceFailureKind.NETWORK ->
                        "网络或代理不稳定（超时/连不上），请检查网络后重新拉取。"
                    BinanceFailureKind.SERVER_OR_RATE_LIMIT ->
                        "币安接口限流或服务端异常" +
                            (httpCode?.let { "（HTTP $it）" } ?: "") +
                            "，请过几分钟再试。"
                    BinanceFailureKind.PERMISSION_OR_OTHER ->
                        "请确认 API Key 已勾选「读取 / Enable Reading」、IP 白名单与地区限制都放行后重新拉取。"
                }
        }
}

/**
 * 币安**签名账户接口**客户端（只读）。
 *
 * 与 [BinanceAPI] 的分工：那个是公开行情（ticker/klines，无需密钥）；
 * 这个走需要 API Key 签名的账户查询，用来拉用户的现货持仓。
 * 两者共用同一套 OkHttp（[NetworkClient.binanceClient]）与 BASE_URL。
 *
 * ## 为什么单独一个文件而不是并进 [BinanceAPI]
 *
 * 签名链路（HMAC、密钥头、错误码解析）和公开行情几乎没有共用逻辑，
 * 混在一起会让「改签名」和「改行情」互相牵连。分开后签名这块可以独立单测
 * （见 [sign]），也不必担心给公开接口误加密钥头。
 *
 * ## 安全约定（务必遵守）
 *
 * - **只读**：只调用 `/account` 查询，不做任何下单/撤单/转账。
 * - API Key / Secret **绝不进日志、绝不进异常 message**：URL 里带 signature
 *   （由 secret 派生），请求头带 X-MBX-APIKEY，两者都不允许出现在
 *   AppLogger 或 Result.failure 的文案里。日志只记「GET /account (signed)」。
 * - 币安返回的业务错误（如 -2015 密钥无效）可以带出来给用户看——那是排障必需，
 *   且币安的 msg 本身不含密钥；但绝不允许把本地 secret 拼进去。
 */
/**
 * 币安**活期借币（Flexible Loan）**的一笔负债。
 *
 * 用户用「活期借币」借出的币（如 USDT），币安**不计入**现货余额 `/api/v3/account`，
 * 只走独立的 `/sapi/v2/loan/flexible/ongoing/orders` 接口。导入时若不拉这笔负债，
 * 总资产就只有「借来的钱」、没有「欠的钱」，净资产被虚高。诊断实锤：用户账户有
 * `USDT 活期借币 -50.80033811` 而 App 此前完全没导入（核对页只列 LD 理财）。
 *
 * [debt] 是币安返回的正数负债（`totalDebt` 字段），导入时取负作为**负余额**进入合并链路，
 * 与同代码持仓自然抵消、纯负债落成数量为负的加密资产参与净值扣减（见
 * [com.example.stockdiary.data.importer.BinanceImportService.buildPreview] 的负余额注释）。
 *
 * @param asset 借入的基础资产（USDT / BNB…）
 * @param debt  未还负债数量（正数）；调用方转负
 */
/**
 * 一笔活期借币（Flexible Loan）订单的**负债 + 抵押**快照。
 *
 * 币安活期借币是「抵押一种资产、借出另一种币」：
 * - [asset]/[debt] 是**借入的负债**（USDT/BTC…），导入时取负作为负余额；
 * - [collateralCoin]/[collateralAmount] 是**抵押给币安的担保资产**（仍是用户自己的资产，
 *   只是被币安锁定），必须作为正余额加回，否则净资产只减借来的钱、没加回押出去的资产，
 *   被严重低估（例如抵押 1 BTC 借 5000 USDT，正确净额是 1 BTC − 5000 USDT，
 *   漏掉抵押就会算成 − 5000 USDT）。接口响应里同一订单同时携带这两类字段。
 *
 * [collateralCoin]/[collateralAmount] 带默认值（null/0）——老订单或数据缺失时这两项为空，
 * 此时只导入负债、不影响负债解析（负债口径完全独立）。
 *
 * @param asset 借入的基础资产（USDT / BNB…）
 * @param debt  未还负债数量（正数）；调用方转负
 * @param collateralCoin 抵押给币安的担保资产基础名（BNB/BTC…）；无抵押记录时为 null
 * @param collateralAmount 抵押数量（正数）；供调用方转成正余额（锁定态）用
 */
data class BinanceLoanDebt(
    val asset: String,
    val debt: Double,
    val collateralCoin: String? = null,
    val collateralAmount: Double = 0.0
)

/**
 * 活期借币负债的拉取结果：拿到多少笔，以及哪些接口没拉成。
 *
 * 与 [BinanceEarnFetch] 同口径：借币接口需要 API Key 开通 Loan 读取权限，
 * 失败也降级（不阻断导入），但把原因带出去让用户看见——否则净资产虚高却查不出为什么。
 *
 * @param debts    成功拉到的负债（正数，调用方转负）
 * @param failures 失败的人话原因；空 = 全部成功
 * @param failureKind 失败类别（网络 / 限流或服务端 / 权限），与 [BinanceEarnFetch.warning] 同口径。
 *        借币接口同样会被 429 限流或被代理拖超时，一律叫用户去查「Loan 读取权限」是同一类误导。
 * @param httpCode 限流/服务端类失败对应的 HTTP 状态码
 */
data class BinanceLoanFetch(
    val debts: List<BinanceLoanDebt>,
    val failures: List<String>,
    val failureKind: BinanceFailureKind = BinanceFailureKind.PERMISSION_OR_OTHER,
    val httpCode: Int? = null
) {
    /** 给用户看的一句话；全部拉取成功时为 null */
    val warning: String?
        get() = if (failures.isEmpty()) {
            null
        } else {
            "借币负债没拉到（${failures.joinToString("；")}）。本次不计入借币负债，净资产会虚高；" +
                when (failureKind) {
                    BinanceFailureKind.NETWORK ->
                        "网络或代理不稳定（超时/连不上），请检查网络后重新拉取。"
                    BinanceFailureKind.SERVER_OR_RATE_LIMIT ->
                        "币安接口限流或服务端异常" +
                            (httpCode?.let { "（HTTP $it）" } ?: "") +
                            "，请过几分钟再试。"
                    BinanceFailureKind.PERMISSION_OR_OTHER ->
                        "请确认 API Key 已勾选 Loan 读取权限、IP 白名单与地区限制都放行后重新拉取。"
                }
        }
}

object BinanceAccountAPI {

    /** 账户查询接口（签名，需 API Key）。必须走 api.binance.com——公开行情镜像不服务 /account */
    private const val ACCOUNT_URL = APIEndpoints.Binance.BASE_URL + "/account"

    /**
     * 简单理财持仓接口（签名）：活期 / 定期。
     *
     * 与 [ACCOUNT_URL] 同主机（都只能走 api.binance.com，镜像不服务签名接口），
     * 因此**同样不喂熔断器**（理由见 [getAccount] 的 451 注释）。
     */
    /**
     * 币安 **SAPI** 接口基址。⚠️ 关键点：SAPI（`/sapi/v1/...`）与现货 REST（`/api/v3/...`）是
     * **两套不同的基址**，SAPI **不在** `/api/v3` 之下。早期误把现货基址（含 `/api/v3`）拼到
     * SAPI 路径前，生成 `https://api.binance.com/api/v3/sapi/v1/...` 这个根本不存在的路径，
     * 被网关回**空 body 404**——表现为「理财活期/定期永远拉不到、且伪装成接口未开通」。
     * 这里单独用 SAPI 基址修正（现货 `/account` 仍走 [ACCOUNT_URL] 的 `/api/v3`）。
     */
    private const val EARN_SAPI_BASE = "https://api.binance.com/sapi/v1"

    private const val EARN_FLEXIBLE_URL =
        EARN_SAPI_BASE + "/simple-earn/flexible/position"
    private const val EARN_LOCKED_URL =
        EARN_SAPI_BASE + "/simple-earn/locked/position"

    /**
     * 借币（Flexible Loan）接口基址：v2。币安 `/sapi/v1/loan/...` 已于 2024-04-24 退役，
     * 必须用 v2，否则 404（伪装成「接口未开通」）。与 [EARN_SAPI_BASE] 同理，
     * SAPI 不在 `/api/v3` 之下，拼上去就是 `/api/v3/sapi/v2/...` 这种不存在的路径。
     */
    private const val LOAN_SAPI_BASE = "https://api.binance.com/sapi/v2"

    /**
     * 活期借币在途负债接口（签名，需 API Key 开通 Loan 读取权限）。
     *
     * 与 [EARN_SAPI_BASE] 同主机（都只能走 api.binance.com，镜像不服务签名接口），
     * 因此**同样不喂熔断器**（理由见 [getAccount] 的 451 注释）。
     */
    private const val LOAN_FLEXIBLE_URL =
        LOAN_SAPI_BASE + "/loan/flexible/ongoing/orders"

    /**
     * 理财持仓单页条数（币安 simple-earn 接口 `size` 参数）。
     * 与 [fetchEarnPage] 配合做翻页：拉到一页满 [EARN_PAGE_SIZE] 行就继续拉下一页，
     * 直到某页行数不足一页（末页）为止——确保持仓数 > 单页上限时后面的页不被漏掉。
     */
    private const val EARN_PAGE_SIZE = 100

    /**
     * 理财持仓翻页的**安全上限**：每页至多 [EARN_PAGE_SIZE] 条，上限 50 页 = 5000 条，
     * 远超任何真实账户的理财标的数，纯粹防止接口异常（如某页永远返回满页）导致死循环。
     */
    private const val MAX_EARN_PAGES = 50

    /** 批量最新价接口（公开免签）。走 data-api.binance.vision（不做地区封锁），用于导入时估算市值 */
    private const val PRICE_URL = APIEndpoints.Binance.MARKET_DATA_BASE_URL + APIEndpoints.Binance.TICKER

    /**
     * 批量取价每批的交易对个数上限。
     *
     * symbols 是 JSON 数组且要整体 URL 编码，一批太多 URL 会超出服务端/中间件
     * 的长度限制。真实账户的去重交易对通常远少于此数，分片主要是防御性的。
     *
     * 防御性调小到 50：一批里混入一个已下架/脏符号会让整批 400（见 [getPrices]
     * 的「对半重试」隔离逻辑），批越小、被一个坏符号拖垮的好符号越少。
     */
    private const val PRICE_BATCH = 50

    /**
     * recvWindow：请求有效期窗口（毫秒）。
     *
     * 币安默认 5000，但对手机来说偏紧——弱网/系统休眠后 timestamp 容易超出窗口
     * 报 -1021。放到 60000 留足余量，只读接口放宽这个没有安全代价。
     */
    private const val RECV_WINDOW = 60000

    private val client = NetworkClient.binanceClient

    /**
     * 单页请求的默认重试退避（毫秒）：**最多 2 次重试、共 3 次尝试**，退避 500ms → 1200ms。
     *
     * ## 为什么要重试
     *
     * 真机日志实锤（2026-11-01 20:59:52）：「币安理财接口[活期] 第2页拉取失败，停止翻页：
     * SocketTimeoutException」——一次弱网抖动就让整块（USDT 144.99 在第 2 页）漏掉，
     * 用户侧表现为「导入的资产比币安 App 少」，且毫无办法。单页请求是最小可重试单位，
     * 重试两次的成本（最坏 1.7s + 两次请求）远小于漏掉一笔持仓。
     *
     * ## 为什么做成可注入参数
     *
     * 单测要能**真实覆盖**重试逻辑，又不能干等 1.7 秒（绝不用 Thread.sleep 撑时间）。
     * 测试传空列表（不重试）或全 0（立即重试）即可，生产走这个默认值。
     */
    internal val DEFAULT_PAGE_RETRY_BACKOFF_MS: List<Long> = listOf(500L, 1200L)

    /**
     * 判定一次失败是否属**网络类**（超时 / 连不上 / DNS 解析不了 / 连接被中断）。
     *
     * ## 为什么不能靠 message 猜
     *
     * 失败文案里本来就带「超时」这类词（例如「第2页拉取失败（timeout）」），在拼接处
     * `contains("timeout")` 看着能用，但只要有人调整文案就静默失效，而且区分不了
     * 「币安拒绝请求（-2015）: Invalid API-key」这种**权限**问题。所以判定只认异常类型，
     * 由 [fetchEarnPage] 在 catch 里算好、经 [EarnPageOutcome.failureKind] 一路透传。
     *
     * 反过来也要注意：`IOException` 不等于网络失败。本文件里「币安返回了空响应」也是
     * [IOException]，那是响应异常，不是连不上——它不该被引导用户去查网络/代理。
     */
    internal fun isNetworkFailure(e: Throwable): Boolean {
        // 三种最典型的连不上：读超时、DNS 解析不了、TCP 连接被拒
        if (e is SocketTimeoutException || e is UnknownHostException || e is ConnectException) {
            return true
        }
        // OkHttp 的 callTimeout / 连接中断都以 InterruptedIOException 收尾（含 "timeout"）
        if (e is InterruptedIOException) return true
        // 其余 IOException（含 OkHttp 的 "unexpected end of stream"、"connection reset" 等）
        // 按类名 + message 关键词兜底判定，绝不把权限类失败算成网络失败。
        val text = (e::class.simpleName.orEmpty() + " " + e.message.orEmpty()).lowercase(Locale.US)
        return NETWORK_FAILURE_KEYWORDS.any { text.contains(it) }
    }

    /** [isNetworkFailure] 的关键词表（小写比对）。刻意窄：宁可漏判成「权限类」，也不误判。 */
    private val NETWORK_FAILURE_KEYWORDS = listOf(
        "sockettimeout",
        "unknownhost",
        "connectexception",
        "connect timed out",
        "failed to connect",
        "connection reset",
        "connection refused",
        "unexpected end of stream",
        "timeout",
        "interrupted"
    )

    /**
     * 把一次失败归到 [BinanceFailureKind] 三类之一——**只认类型与状态码，不猜字符串**。
     *
     * 顺序敏感：先判网络（IO 层异常可能是任意状态码下的连接问题），再判限流/服务端，
     * 剩下的才是权限/地区/参数。
     *
     * 注意 418 / 429 / 5xx 是**币安侧的临时状态**（限流、IP 临时封、服务端抖动），
     * 用户侧没有任何可改的设置；451（地区限制）与 401/403/-2015（权限）才是用户可修的，
     * 所以 451 归 [BinanceFailureKind.PERMISSION_OR_OTHER]，保住原有的「换代理节点」提示。
     */
    internal fun classifyFailure(e: Throwable): BinanceFailureKind = when {
        isNetworkFailure(e) -> BinanceFailureKind.NETWORK
        e is BinanceApiException && (e.httpCode == 418 || e.httpCode == 429 || e.httpCode >= 500) ->
            BinanceFailureKind.SERVER_OR_RATE_LIMIT
        else -> BinanceFailureKind.PERMISSION_OR_OTHER
    }

    /**
     * 把「单页请求」包进重试：**最多 [retryDelaysMs].size 次重试**，退避取列表里的值。
     *
     * ## 契约
     *
     * - [CancellationException] **原样抛出**，绝不重试（不能把「用户关掉弹窗」记成网络失败）；
     * - 只重试 [IOException]（超时 / 连不上 / DNS / 连接中断 / 响应体为空都算 IO 层；
     *   [binanceError] 抛的是普通 [Exception]，HTTP 4xx 与币安业务码**不重试**——重试一百次
     *   也还是权限不足，只会白刷接口）；
     * - 每次重试之间都 `ensureActive()` 检查协程是否已取消，取消了立刻停手；
     * - 重试成功记一条 DEBUG（第 N 页第 k 次尝试成功），便于真机日志一眼看出「是重试救回来的」。
     *
     * @param kind    人话标签（活期/定期/借币），只用于日志
     * @param page    页号，只用于日志
     * @param block   实际的单页请求；必须**幂等**（只是拉数据，不写库）
     * @return block 的返回值（第一次成功即返回）
     * @throws Exception 非网络类异常，或重试用尽后的最后一次异常
     */
    private suspend fun <T> retryPageRequest(
        kind: String,
        page: Int,
        retryDelaysMs: List<Long>,
        block: suspend () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                val result = block()
                if (attempt > 0) {
                    // 第 attempt+1 次尝试成功：真机日志里一眼可见「这页是靠重试救回来的」
                    AppLogger.d(
                        "币安${kind}接口 第${page}页 第${attempt + 1}次尝试成功",
                        "BinanceAccountAPI"
                    )
                }
                return result
            } catch (e: CancellationException) {
                // 取消不重试、不包装：原样上抛
                throw e
            } catch (e: Exception) {
                // 重试判据用「是不是 IO 层异常」：这不是分类，而是「换个时机可能就好了」。
                // 币安的 HTTP 4xx / 业务码走 binanceError（普通 Exception）→ 不重试。
                if (e !is IOException || attempt >= retryDelaysMs.size) throw e
                val delayMs = retryDelaysMs[attempt]
                attempt++
                // 重试之间必须检查取消：切后台/关弹窗后不该继续打接口
                currentCoroutineContext().ensureActive()
                AppLogger.d(
                    "币安${kind}接口 第${page}页 第${attempt}次尝试失败（${e::class.simpleName}），" +
                        "${delayMs}ms 后重试",
                    "BinanceAccountAPI"
                )
                if (delayMs > 0L) delay(delayMs)
                // 退避期间可能被取消：下一轮请求前再确认一次协程仍活跃
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /**
     * HMAC-SHA256 签名，输出小写 hex。
     *
     * ## 为什么抽成纯函数
     *
     * 签名是这套链路里唯一「错一位全盘皆错」又**不依赖网络**的逻辑，
     * 抽成 [sign] 就能在 JVM 单测里用币安官方文档的已知向量直接验，
     * 不必连网、也不必伪造 OkHttp。整条 getAccount 依赖网络，不强行单测。
     *
     * @param secret API Secret
     * @param query  待签名的 query 串（如 `timestamp=...&recvWindow=...`，**不含** signature）
     * @return 64 位小写 hex 字符串
     */
    internal fun sign(secret: String, query: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        // 密钥与数据都按 UTF-8 编字节：币安文档即此口径，换编码会得到不同签名
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(query.toByteArray(Charsets.UTF_8))
        // 256 bit / 4 bit-per-hex = 64 个 hex 字符
        return raw.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * 拉取现货账户余额（只读）。
     *
     * @param apiKey    币安 API Key
     * @param apiSecret 币安 API Secret
     * @return 成功时返回**未过滤**的全部余额（含 dust，过滤交给导入服务）；
     *         失败时 [Result.failure] 的 message 带币安 code+msg，便于用户排障。
     */
    suspend fun getAccount(apiKey: String, apiSecret: String): Result<List<BinanceBalance>> =
        getAccount(apiKey, apiSecret, client)

    /**
     * internal 重载：允许单测注入假 client（自定义 [okhttp3.Interceptor] 模拟币安语义）。
     *
     * 与 [getPrices] 的 internal 重载完全同惯例：公开 API 语义与 [getAccount] 一致——
     * 只是把用于发请求的那个 client 提出来，便于测试时用走假 Interceptor 的 client
     * 替换 [NetworkClient.binanceClient]（账户接口要 HMAC 签名、又需要真实密钥，
     * 用假 client 才能在单测里复现「连不上 / 451 地区封锁」这类网络与 HTTP 语义）。
     */
    internal suspend fun getAccount(
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient
    ): Result<List<BinanceBalance>> =
        withContext(Dispatchers.IO) {
            try {
                // timestamp 用本地时钟：币安按它判断请求是否在 recvWindow 内。
                // 手机时钟不准会报 -1021，那是用户侧问题，这里如实把错误带出去。
                val timestamp = System.currentTimeMillis()
                val query = "timestamp=$timestamp&recvWindow=$RECV_WINDOW"
                val signature = sign(apiSecret, query)
                val url = "$ACCOUNT_URL?$query&signature=$signature"

                // 日志只记端点路径：url 里的 signature 由 secret 派生，属于机密，
                // 一旦进 AppLogger 落盘就等于把密钥写进了可分享的日志文件
                AppLogger.d("GET /account (signed, recvWindow=$RECV_WINDOW)", "BinanceAccountAPI")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-MBX-APIKEY", apiKey)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    val body = resp.body?.string() ?: return@withContext Result.failure(
                        Exception("币安返回了空响应")
                    )

                    if (!resp.isSuccessful) {
                        // 451/403 = 地区封锁（api.binance.com 在部分地区直接回 451）。
                        // ⚠️ 这里**不再喂熔断器**：公开行情已改走 data-api.binance.vision（不做地区
                        // 封锁），熔断器现在只保护那台镜像主机。账户接口只能走 api.binance.com
                        // （签名 /account 镜像不服务），封锁地区里账户必然失败——若把它的 451/连不上
                        // 喂进同一个熔断器，就等于把能用的镜像行情也跟着熔断短路，等于白修。
                        // 账户的失败直接透传给导入流程提示用户（下方 binanceError 解析 body 业务码）。
                        // 其余 4xx/5xx 也直接失败返回，不碰熔断器。
                        return@withContext Result.failure(binanceError(body, resp.code))
                    }

                    // 2xx：解析余额并返回。账户路径刻意不碰熔断器（理由见上方 451 注释）。
                    return@withContext Result.success(parseBalances(body))
                }
            } catch (e: CancellationException) {
                // 协程取消必须原样抛回，不能被 catch(Exception) 吞成失败结果
                throw e
            } catch (e: Exception) {
                // 网络级失败（连不上 / 超时 / 域名解析不了 / IO 错误）：**不喂熔断器**。
                // 账户接口只走 api.binance.com（签名 /account 镜像不服务），与公开行情镜像
                // data-api.binance.vision 是两台可达性不同的主机——封锁地区账户必失败、而镜像
                // 行情可达，一旦把账户失败喂进熔断器，就会把能用的镜像行情也熔断短路。导入是用户
                // 手动触发、不在后台同步里反复重试，失败直接透传给导入流程提示即可。
                // JSON 解析等非 IO 异常更不该顶爆熔断——它们不是"主机不可达"。
                // 连接类失败（连不上 / 超时 / 域名解析不了）换成一句人话。
                // OkHttp 的原始 message 形如 "failed to connect to api.binance.com/2001:… (port 443)
                // from /2409:… (port 48944) after 10000ms"——既看不懂，又把设备 IP/端口
                // 抖给用户。币安国际站在部分网络/地区连不上是常态，这句提示更对症。
                // 日志也只记异常类型，不记 e.message（同样含 IP），避免地址写进可分享的日志。
                val userFacing = if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                    Exception("连不上币安服务器，可能被网络或地区限制，请检查网络或代理后重试")
                } else {
                    e
                }
                if (userFacing !== e) {
                    // 连接类失败：堆栈全是 OkHttp 内部帧，对定位没有任何帮助，
                    // 却会把日志刷成几十行（真机日志里见过完整栈）。异常类型已写进 message，够用了。
                    AppLogger.e(
                        "拉取币安账户失败: ${e::class.simpleName}（连接类，未记录堆栈）",
                        tag = "BinanceAccountAPI"
                    )
                } else {
                    // 其它异常（解析错、业务错）：堆栈有诊断价值，照旧带上
                    AppLogger.e("拉取币安账户失败: ${e::class.simpleName}", e, "BinanceAccountAPI")
                }
                Result.failure(userFacing)
            }
        }

    /**
     * 拉取简单理财持仓（活期 + 定期，只读）。
     *
     * ## 为什么必须单拉
     *
     * 官方对账单证实用户的钱可能 100% 在 Earn 钱包（现货 0.00），而 `/api/v3/account`
     * 是现货口径，两者并不相等（实测导入后比币安自己的总资产少 ~34.5 USD）。
     * 理财持仓要走签名接口单独取。
     *
     * ## 失败依然不打断导入，但**不再假装成功**
     *
     * 这两个接口需要 API Key 开通相应读取权限，权限不足/接口调整/网络失败都是常态，
     * 因此失败仍降级成「只用现货」，绝不把整个导入打回去——余额才是关键数据。
     * 但失败原因会随 [BinanceEarnFetch.failures] 一起返回，由预览页明确告诉用户
     * 「理财没拉全、总资产会少算」，而不是让它静默地少一块。
     *
     * @return 活期 + 定期持仓，以及各接口的失败原因（全部成功时为空）
     */
    suspend fun getEarnPositions(apiKey: String, apiSecret: String): BinanceEarnFetch =
        getEarnPositions(apiKey, apiSecret, client)

    /**
     * internal 重载：允许单测注入假 client，与 [getAccount] 同惯例。
     *
     * @param retryDelaysMs 单页失败后的重试退避（毫秒）。默认 [DEFAULT_PAGE_RETRY_BACKOFF_MS]
     *        （500 / 1200，共 3 次尝试）；**单测传空列表即「不重试」、传全 0 即「立即重试」**，
     *        这样重试逻辑可以被真实覆盖，又不必让单测干等 1.7 秒（绝不能靠 Thread.sleep 撑时间）。
     */
    internal suspend fun getEarnPositions(
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient,
        retryDelaysMs: List<Long> = DEFAULT_PAGE_RETRY_BACKOFF_MS
    ): BinanceEarnFetch = withContext(Dispatchers.IO) {
        // 两个接口互不依赖，并发取省一次串行往返；各自失败互不影响。
        // 不用 runCatching：它会连 CancellationException 一起吞掉（项目铁律：取消不吞）。
        val flexible = async {
            fetchEarnOrFailure(
                EARN_FLEXIBLE_URL, apiKey, apiSecret, httpClient, locked = false,
                retryDelaysMs = retryDelaysMs
            )
        }
        val lockedPositions = async {
            fetchEarnOrFailure(
                EARN_LOCKED_URL, apiKey, apiSecret, httpClient, locked = true,
                retryDelaysMs = retryDelaysMs
            )
        }
        // 诊断探针（非集成）：权限全开却 simple-earn 拉不到 USDT 时，顺手探一下 Staking，
        // 排除「这笔钱其实在锁仓质押而非 Simple Earn」。只记日志、绝不并入合并链路。
        val stakingProbe = async { probeStakingPositions(apiKey, apiSecret, httpClient) }
        val flexibleResult = flexible.await()
        val lockedResult = lockedPositions.await()
        stakingProbe.await()

        BinanceEarnFetch(
            positions = flexibleResult.positions + lockedResult.positions,
            failures = flexibleResult.failures + lockedResult.failures,
            // 首个「非权限类」失败决定文案分支：网络/限流比权限更可能是真因，也更好排查。
            // 两边都是权限类时自然是权限提示（保持原行为）。
            failureKind = firstActionableKind(flexibleResult, lockedResult),
            httpCode = firstActionableHttpCode(flexibleResult, lockedResult)
        )
    }

    /**
     * 从两个接口的结果里挑出「该优先提示」的失败类别：非 [BinanceFailureKind.PERMISSION_OR_OTHER] 优先。
     *
     * 活期先于定期，是为了让结果稳定可测（不随并发实际完成顺序漂移）。
     */
    private fun firstActionableKind(
        flexible: EarnPageOutcome,
        locked: EarnPageOutcome
    ): BinanceFailureKind = when {
        flexible.failureKind != BinanceFailureKind.PERMISSION_OR_OTHER -> flexible.failureKind
        locked.failureKind != BinanceFailureKind.PERMISSION_OR_OTHER -> locked.failureKind
        else -> BinanceFailureKind.PERMISSION_OR_OTHER
    }

    /** 与 [firstActionableKind] 同源：取该失败对应的 HTTP 状态码，供文案写「HTTP 429」 */
    private fun firstActionableHttpCode(flexible: EarnPageOutcome, locked: EarnPageOutcome): Int? =
        when {
            flexible.failureKind != BinanceFailureKind.PERMISSION_OR_OTHER -> flexible.httpCode
            locked.failureKind != BinanceFailureKind.PERMISSION_OR_OTHER -> locked.httpCode
            else -> null
        }

    /**
     * 拉取活期借币（Flexible Loan）负债（只读）。
     *
     * ## 为什么必须单拉
     *
     * 活期借币是**独立于现货和理财**的负债：币安不把它计入 `/api/v3/account` 余额，
     * 只走 `/sapi/v2/loan/flexible/ongoing/orders`。不拉这笔，导入的资产只有「借来的钱」、
     * 没有「欠的钱」，净资产被虚高（诊断实锤用户账户有 `USDT 活期借币 -50.80033811`
     * 而 App 此前完全没导入）。与 `mergeEarnIntoBalances` 之后把负债表示成负余额进入合并链路，
     * 同代码持仓会自然抵消（持有 100、欠 30 → 净 70），正是币安自己的净额口径。
     *
     * ## 失败依然不打断导入，但**不再假装成功**
     *
     * 借币接口需要 API Key 开通 Loan 读取权限，权限不足/接口调整/网络失败都是常态，
     * 因此失败降级成「不计入借币」，绝不把整个导入打回去——余额才是关键数据。
     * 但失败原因会随 [BinanceLoanFetch.failures] 一起返回，由预览页明确告诉用户
     * 「借币没拉到、净资产会虚高」，而不是让它静默地虚高一块。
     */
    suspend fun getLoans(apiKey: String, apiSecret: String): BinanceLoanFetch =
        getLoans(apiKey, apiSecret, client)

    /**
     * internal 重载：允许单测注入假 client，与 [getAccount] 同惯例。
     *
     * @param retryDelaysMs 单页失败后的重试退避（毫秒），语义同 [getEarnPositions]。
     */
    internal suspend fun getLoans(
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient,
        retryDelaysMs: List<Long> = DEFAULT_PAGE_RETRY_BACKOFF_MS
    ): BinanceLoanFetch = withContext(Dispatchers.IO) {
        try {
            val debts = fetchLoanPage(LOAN_FLEXIBLE_URL, apiKey, apiSecret, httpClient, retryDelaysMs)
            BinanceLoanFetch(debts = debts, failures = emptyList())
        } catch (e: CancellationException) {
            // 协程取消必须原样重抛：横竖都不能把「用户关掉弹窗」记成一次权限失败
            throw e
        } catch (e: Exception) {
            // 降级：不阻断导入，只把原因带回预览页。
            // 失败类别决定文案：限流/服务端异常与网络问题都不该让用户去查 Loan 权限
            warnLoanFailure(e)
            BinanceLoanFetch(
                debts = emptyList(),
                failures = listOf(e.message ?: "拉取失败"),
                failureKind = classifyFailure(e),
                httpCode = (e as? BinanceApiException)?.httpCode
            )
        }
    }

    /**
     * 拉借币在途负债（**全部分页**，与 [fetchEarnPage] 同口径）。
     *
     * 非 2xx（未开通权限 / 接口下线 / 被地区限制）与空响应都**抛异常**，由 [getLoans]
     * 收成 Result 并把原因带回预览页——绝不在这里静默返回空列表，那等于把「少算一块负债」
     * 伪装成「本来就没有」。
     *
     * 旧实现写死 `current=1`，在途借币 > 100 时会被截断（与 earn 同源的潜在 bug）。
     * 现改为翻页：优先用响应 `total` 精确翻页，`total` 不可用时回退到「本页行数 < 单页上限」。
     *
     * 每页走 [retryPageRequest]：与 earn 同源的单页重试（弱网下一次超时不该把整块负债丢掉）。
     */
    private suspend fun fetchLoanPage(
        url: String,
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient,
        retryDelaysMs: List<Long>
    ): List<BinanceLoanDebt> {
        val all = mutableListOf<BinanceLoanDebt>()
        var current = 1
        var rowsFetched = 0
        var total: Int? = null
        while (current <= MAX_EARN_PAGES) {
            val page = retryPageRequest("借币", current, retryDelaysMs) {
                fetchLoanSinglePage(url, apiKey, apiSecret, httpClient, current)
            }
            all.addAll(page.debts)
            // 借币响应同样含 total / rows，优先用 total 精确翻页，否则回退到「本页行数 < 单页上限」
            if (total == null && page.pageTotal > 0) total = page.pageTotal
            rowsFetched += page.rowCount
            val lastPage = page.rowCount < EARN_PAGE_SIZE
            val exhausted = total != null && rowsFetched >= total
            if (lastPage || exhausted) break
            current++
        }
        return all
    }

    /**
     * 拉借币接口的第 [page] 页（`current=page`）。
     *
     * 从 [fetchLoanPage] 的循环体里抽出来，好让**单页请求**能被 [retryPageRequest] 包住重试
     * （重试的最小单位必须是「一次请求」，不能整段翻页重来——那会把已拉到的页重复累加）。
     */
    private fun fetchLoanSinglePage(
        url: String,
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient,
        page: Int
    ): LoanPageResult {
        val timestamp = System.currentTimeMillis()
        // current/size 必须参与签名：它们是 query 的一部分，缺了会被判签名无效
        // （与 fetchEarnPage 同口径，size 复用 EARN_PAGE_SIZE：真实账户的在途借币通常个位数）
        val query =
            "current=$page&size=$EARN_PAGE_SIZE&timestamp=$timestamp&recvWindow=$RECV_WINDOW"
        val signature = sign(apiSecret, query)
        val request = Request.Builder()
            // 日志只记端点路径，url 里的 signature 由 secret 派生，绝不落盘
            .url("$url?$query&signature=$signature")
            .addHeader("X-MBX-APIKEY", apiKey)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        val body = httpClient.newCall(request).execute().use { resp ->
            val b = resp.body?.string()
                ?: throw IOException("币安返回了空响应")
            if (!resp.isSuccessful) {
                // 诊断：把币安真实 HTTP 码与响应体落盘（不含密钥/签名，安全），便于排查
                // 404/403/-2015 等。注意只记端点路径常量，绝不记带 signature 的完整请求 URL。
                AppLogger.d(
                    "币安借币接口 ${url} 返回 HTTP ${resp.code}，响应体=$b",
                    "BinanceAccountAPI"
                )
                // 币安的 msg 说明原因（权限不足/参数错），不含密钥，可以带出去。
                // 注意：这里抛的是普通 Exception（不是 IOException）→ 不会被当成网络失败重试。
                throw binanceError(b, resp.code)
            }
            b
        }
        val debts = parseLoanOrders(body)
        val (pageTotal, rowCount) = try {
            val json = JSONObject(body)
            json.optInt("total", 0) to (json.optJSONArray("rows")?.length() ?: 0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0 to debts.size
        }
        return LoanPageResult(debts = debts, pageTotal = pageTotal, rowCount = rowCount)
    }

    /** [fetchLoanSinglePage] 的搬运容器：本页负债 + 本页声明的总数 + 本页行数 */
    private data class LoanPageResult(
        val debts: List<BinanceLoanDebt>,
        val pageTotal: Int,
        val rowCount: Int
    )

    /**
     * 解析借币负债响应：`{"rows":[{"loanCoin":"USDT","totalDebt":"50.80033811",...}],"total":N}`。
     *
     * 负债额在 **`totalDebt`**（不是 `amount`）。debt <= 0 的行（已还清空壳）没有导入意义，
     * 跳过，交给现货侧的 dust 过滤口径。
     */
    /**
     * 解析借币订单响应：`{"rows":[{"loanCoin":"USDT","totalDebt":"50.8",
     * "collateralCoin":"BNB","collateralAmount":"1.23",...}],"total":N}`。
     *
     * 每笔订单同时含**负债**（loanCoin/totalDebt）与**抵押资产**（collateralCoin/
     * collateralAmount）两部分，都要解析——漏掉抵押资产会让净资产被低估（见
     * [BinanceLoanDebt] 注释）。debt <= 0 的行（已还清空壳）没有导入意义，跳过；
     * 但抵押字段的解析与负债跳过互不影响：只要 debt > 0，哪怕 collateral 缺失也照常导入负债。
     */
    internal fun parseLoanOrders(body: String): List<BinanceLoanDebt> {
        return try {
            val rows = JSONObject(body).optJSONArray("rows") ?: return emptyList()
            val result = mutableListOf<BinanceLoanDebt>()
            for (i in 0 until rows.length()) {
                val obj = rows.getJSONObject(i)
                val asset = obj.optString("loanCoin")
                val debt = obj.optString("totalDebt").toDoubleOrNull() ?: continue
                // 数量为 0 / 已还空的行没有导入意义
                if (asset.isBlank() || debt <= 0.0) continue
                // 抵押资产：仍是用户自己的资产，单独解析，绝不被「负债跳过」逻辑误伤。
                // collateralAmount 解析不到按 0 处理（该订单无抵押记录），不阻断负债导入。
                val collateralCoin = obj.optString("collateralCoin").takeIf { it.isNotBlank() }
                val collateralAmount = obj.optString("collateralAmount").toDoubleOrNull() ?: 0.0
                result.add(
                    BinanceLoanDebt(
                        asset = asset,
                        debt = debt,
                        collateralCoin = collateralCoin,
                        collateralAmount = collateralAmount
                    )
                )
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析币安借币负债失败: ${e::class.simpleName}", e, "BinanceAccountAPI")
            emptyList()
        }
    }

    private fun warnLoanFailure(e: Throwable) {
        if (e is CancellationException) throw e
        // 这条只记日志、不阻断导入：失败原因已通过 BinanceLoanFetch.failures 带给预览页，
        // 用户能直接看到该去开哪个权限。绝大多数情况是「未开通 Loan 读取权限 /
        // 接口调整 / 该地区不支持」而返回 404——属预期、已降级处理，不该每轮导入刷 WARN，
        // 降级到 DEBUG，避免诊断日志被这类"非故障"刷屏。
        AppLogger.d(
            "拉取币安借币负债异常: ${e::class.simpleName}，本次导入不计入借币负债",
            "BinanceAccountAPI"
        )
    }

    /**
     * 拉一个理财接口，把异常收成 [EarnPageOutcome]（失败不抛给调用方，但保留原因）。
     *
     * 为什么不用 [Result]：一条接口既要带「拉到的持仓」又要带「形状问题的原因」，
     * Result 只能二选一（成了就没地方放失败、败了就丢掉已解析出的持仓）。
     * 部分成功时两边都要留住——部分数据照导、问题照报。
     *
     * 取消必须原样重抛：横竖都不能把「用户关掉弹窗」记成一次权限失败。
     */
    private suspend fun fetchEarnOrFailure(
        url: String,
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient,
        locked: Boolean,
        retryDelaysMs: List<Long>
    ): EarnPageOutcome =
        try {
            fetchEarnPage(url, apiKey, apiSecret, httpClient, locked, retryDelaysMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = earnKindLabel(locked)
            warnEarnFailure(kind, e)
            EarnPageOutcome(
                positions = emptyList(),
                failures = listOf("$kind：${e.message ?: "拉取失败"}"),
                // 整条接口都没拉成时也要分流：网络/限流不该让用户去翻 API Key 权限
                failureKind = classifyFailure(e),
                httpCode = (e as? BinanceApiException)?.httpCode
            )
        }

    /**
     * 拉一个理财接口（活期 / 定期）的**全部分页**。
     *
     * ## 为什么要从「拉一页」改成「翻页拉全」
     *
     * 币安 simple-earn 接口按页返回，单页上限 [EARN_PAGE_SIZE]（100）。旧实现写死只拉
     * `current=1` 第一页，当某接口持仓数 > 100 时，第 2 页及之后的持仓（含 USDT 等）
     * 被整批漏掉——表现就是「导入后持仓比币安 App 少一大块、却零警告」。真机已实锤：
     * 用户活期理财返回 100 行（恰好一页上限）但 USDT(144.99) 不在其中，合并 `新增 0 项`。
     * 现改为翻页循环：从 page=1 递增 `current`，**优先用币安返回的 `total` 字段精确翻页**
     * （已拉行数 >= total 即停，避免恰好满一页时多打一页空请求），`total` 不可用时回退到
     * 「本页行数 < [EARN_PAGE_SIZE]（即末页）」启发式；同时触达 [MAX_EARN_PAGES] 安全上限
     * 才强制停，确保分页前的持仓一枚不漏。
     *
     * ## 失败绝不连坐
     *
     * 翻页中途某页失败（非 2xx / 网络异常）时，**前面已拉到的页照常保留**（部分成功），
     * 失败原因记进 [EarnPageOutcome.failures] 由预览页提示用户，绝不把「少算一块」
     * 伪装成「本来就没有」——这是 [fetchEarnPage] 一贯的契约。取消（CancellationException）
     * 原样重抛，横竖不能把「用户关弹窗」记成一次权限失败。
     *
     * HTTP 200 但响应形状异常时，本页不抛（交给 [parseEarnPositions] 部分成功），
     * 但已解析出的持仓照常带回去，形状问题也记进 failures。
     *
     * ## 单页重试
     *
     * 每页都经 [retryPageRequest] 包一层（最多 2 次重试，退避见 [DEFAULT_PAGE_RETRY_BACKOFF_MS]）。
     * 重试的最小单位是**一页**，不是整段翻页——整段重来会把已拉到的页重复累加。
     * 重试用尽仍失败才走原来的「保留已拉到的页、记 failure、停止翻页」契约。
     *
     * @param retryDelaysMs 单页重试退避（毫秒），空列表 = 不重试（单测用）
     */
    private suspend fun fetchEarnPage(
        url: String,
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient,
        locked: Boolean,
        retryDelaysMs: List<Long> = DEFAULT_PAGE_RETRY_BACKOFF_MS
    ): EarnPageOutcome {
        val kind = earnKindLabel(locked)
        val allPositions = mutableListOf<BinanceEarnPosition>()
        val allFailures = mutableListOf<String>()
        // 首个非「权限类」的失败决定最终文案分支（网络 / 限流），并记住它的 HTTP 码
        var failureKind = BinanceFailureKind.PERMISSION_OR_OTHER
        var failureHttpCode: Int? = null
        var current = 1
        var rowsFetched = 0
        // 币安声明的总条数：优先用它精确翻页；为 0/负数时视为未知，回退到「本页行数 < 单页上限」启发式
        var total: Int? = null
        while (current <= MAX_EARN_PAGES) {
            val parsed = try {
                // 单页重试：弱网下一次 SocketTimeout 不该让整块持仓从此消失
                retryPageRequest(kind, current, retryDelaysMs) {
                    fetchEarnSinglePage(url, apiKey, apiSecret, httpClient, locked, current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 翻页中途某页失败（且重试用尽）：保留已拉到的，记录原因，停止翻页。
                // 绝不让「第 2 页挂了」把第 1 页已拉到的数据一起连坐丢光。
                allFailures.add(
                    "$kind：第${current}页拉取失败（${e.message ?: "未知错误"}），" +
                        "已拉到的前 ${current - 1} 页照常导入"
                )
                // 网络/限流类失败单独归类：决定 warning 走「检查网络」「等几分钟」还是「检查权限/IP」
                val pageKind = classifyFailure(e)
                if (failureKind == BinanceFailureKind.PERMISSION_OR_OTHER &&
                    pageKind != BinanceFailureKind.PERMISSION_OR_OTHER
                ) {
                    failureKind = pageKind
                    failureHttpCode = (e as? BinanceApiException)?.httpCode
                }
                AppLogger.w(
                    "币安理财接口[$kind] 第${current}页拉取失败，停止翻页：${e::class.simpleName}",
                    "BinanceAccountAPI"
                )
                break
            }
            allPositions.addAll(parsed.positions)
            if (parsed.hasShapeProblem || parsed.bodyNotJson != null) {
                allFailures.addAll(parsed.failures(kind))
            }
            // 首条带 total 的页定下总数；之后用「已拉行数 >= 总数」判定拉全，避免多打空页
            if (total == null && parsed.total > 0) total = parsed.total
            rowsFetched += parsed.rowCount
            val lastPage = parsed.rowCount < EARN_PAGE_SIZE
            val exhausted = total != null && rowsFetched >= total
            if (lastPage || exhausted) break
            current++
        }
        return EarnPageOutcome(
            positions = allPositions,
            failures = allFailures,
            failureKind = failureKind,
            httpCode = failureHttpCode
        )
    }

    /**
     * 拉理财接口的第 [page] 页（`current=page`）。
     *
     * 非 2xx（未开通权限 / 接口下线 / 被地区限制）与空响应**抛异常**，由 [fetchEarnPage]
     * 决定如何降级（保留已拉到的页）；2xx 但响应形状异常时不抛，照常返回 [EarnParseResult]
     * （部分成功由调用方处理）。每页都留一条诊断日志，下一轮真机导入即可一眼判定
     * 「接口根本没给 / 给了但形状不对 / 真的没有持仓」。
     */
    private suspend fun fetchEarnSinglePage(
        url: String,
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient,
        locked: Boolean,
        page: Int
    ): EarnParseResult {
        val timestamp = System.currentTimeMillis()
        // current/size 必须参与签名：它们是 query 的一部分，缺了会被判签名无效
        val query =
            "current=$page&size=$EARN_PAGE_SIZE&timestamp=$timestamp&recvWindow=$RECV_WINDOW"
        val signature = sign(apiSecret, query)
        val request = Request.Builder()
            // 日志只记端点路径，url 里的 signature 由 secret 派生，绝不落盘
            .url("$url?$query&signature=$signature")
            .addHeader("X-MBX-APIKEY", apiKey)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            val body = resp.body?.string()
                ?: throw IOException("币安返回了空响应")
            if (!resp.isSuccessful) {
                // 诊断：把币安真实 HTTP 码与响应体落盘（不含密钥/签名，安全），便于排查
                // 404/403/-2015 等——此前因空 body 404 被兜底成「HTTP 404」，盲猜了很久。
                // 注意只记端点路径常量，绝不记带 signature 的完整请求 URL。
                AppLogger.d(
                    "币安理财接口 ${url} 返回 HTTP ${resp.code}，响应体=$body",
                    "BinanceAccountAPI"
                )
                // 币安的 msg 说明原因（权限不足/参数错），不含密钥，可以带出去
                throw binanceError(body, resp.code)
            }
            val parsed = parseEarnPositions(body, locked)
            val kind = earnKindLabel(locked)
            // 诊断：在原有「响应 N 行 → 有效 M 行」后面直接标出 USDT 在不在本页，以及本页前 3 个资产。
            // 目的：真机「USDT 144.99 到底在不在 simple-earn 返回里」这个问题，一眼可判——
            // 在 → 是我们合并/落库丢了；不在 → 是接口侧没给（权限/地区/钱不在这里），别再改合并公式。
            AppLogger.d(
                "币安理财接口[$kind] 第${page}页 响应 ${parsed.rowCount} 行 → 有效 ${parsed.positions.size} 行" +
                    (if (parsed.emptyRows > 0) "，空壳 ${parsed.emptyRows} 行" else "") +
                    (if (parsed.badRows.isNotEmpty()) {
                        "，被跳过 ${parsed.badRows.size} 行（${parsed.badRows.joinToString("、")}）"
                    } else "") +
                    "，含USDT=${describeUsdt(parsed.positions)}" +
                    "，本页前3个资产=${describeHeadAssets(parsed.positions)}",
                "BinanceAccountAPI"
            )
            // 形状异常（非 JSON / 无 rows / 行内缺字段）**或接口回了 200 但 0 条有效持仓**时，
            // 把响应体原文截前 600 字落盘：响应体是币安回包，不含签名/密钥，安全。下一轮真机导入
            // 即可一眼看到币安到底回了什么（错误码 JSON / 干净空数组 / HTML 墙）。
            if (parsed.positions.isEmpty() || parsed.hasShapeProblem || parsed.bodyNotJson != null) {
                val snippet = body.take(600).replace("\r", " ").replace("\n", " ")
                val reason = when {
                    parsed.bodyNotJson != null ->
                        "响应不是合法 JSON(${parsed.bodyNotJson})"
                    parsed.hasShapeProblem ->
                        "响应形状异常(缺 rows/字段)"
                    else ->
                        "返回 0 条有效持仓(钱可能不在 Simple Earn 活期/定期)"
                }
                AppLogger.w(
                    "币安理财接口[$kind] 第${page}页 $reason，响应体前600字=$snippet",
                    "BinanceAccountAPI"
                )
            }
            return parsed
        }
    }

    /** 活期 / 定期的人话标签（用于失败原因与诊断日志） */
    private fun earnKindLabel(locked: Boolean): String = if (locked) "定期" else "活期"

    /**
     * 诊断用：本页有没有 USDT，有就带上金额（`是(144.99343452)` / `否`）。
     *
     * 只进日志、不参与任何判定——它是给「USDT 到底在不在接口返回里」这个反复排查的问题
     * 一个确定性答案，而不是又一次靠猜。
     */
    private fun describeUsdt(positions: List<BinanceEarnPosition>): String {
        val usdt = positions.firstOrNull { it.asset.uppercase(Locale.US) == "USDT" }
            ?: return "否"
        return "是(${formatAmount(usdt.amount)})"
    }

    /** 诊断用：本页前 3 个资产（`USDT:144.99、BUSD:12.3…`），看清接口到底给了什么 */
    private fun describeHeadAssets(positions: List<BinanceEarnPosition>): String {
        if (positions.isEmpty()) return "无"
        return positions.take(3).joinToString("、") {
            "${it.asset}:${formatAmount(it.amount)}"
        }
    }

    /**
     * 把金额格式化成诊断日志里好读的形态：最多 8 位小数、去掉尾随 0。
     *
     * 固定用 [Locale.US]：日志是要拿去 grep 的，绝不能让某些语言环境把小数点变成逗号。
     */
    private fun formatAmount(value: Double): String =
        String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')

    /**
     * 解析理财持仓响应：`{"rows":[{...}],"total":N}`。
     *
     * 活期行用 `totalAmount`、定期行用 `amount`，两个字段名不一致，按 [locked] 取对应字段，
     * 取不到就退回另一个（接口微调时不至于整页失效）。
     *
     * ## 为什么要把「解析出了问题」也返回出去（而不是只返回持仓列表）
     *
     * 曾经的返回值是裸 [List]，于是下面这几种情况**全部伪装成成功**（旧代码 `return emptyList()`），
     * 用户侧表现就是「数量比官方少一截，但预览页干干净净、零警告」——真机就是这个现象：
     * 官方 USDT 活期 142.08565983，App 只到 124.59510564，而预览页没有任何提示。
     *
     * - HTTP 200 但响应体不是 JSON 对象（网关/地区页返回 HTML、空串、JSON 数组…）；
     * - HTTP 200 但响应体里**没有 rows 数组**（接口形状变了）；
     * - rows 里有行、但行内缺资产名或数量字段（改了字段名 / 非对象行）。
     *
     * 只有「rows 存在且为空数组」才是真的没有理财持仓，那不是问题；
     * 「某行数量为 0」也不是问题（已赎回的空壳，本来就没有钱）。
     *
     * @return 解析出的持仓 + 逐项诊断；[EarnParseResult.failures] 给出可直接给用户看的原因
     */
    internal fun parseEarnPositions(body: String, locked: Boolean): EarnParseResult {
        return try {
            val bodyJson = JSONObject(body)
            val rows = bodyJson.optJSONArray("rows")
            if (rows == null) {
                // 响应体里连 rows 数组都没有：接口形状变了，一条都没拿到，绝不是「没有持仓」
                return EarnParseResult(positions = emptyList(), missingRows = true)
            }
            // 币安声明的持仓总数；解析不到（字段缺失/非数字）按 0 处理，由调用方回退到启发式翻页
            val total = bodyJson.optInt("total", 0)
            val primary = if (locked) "amount" else "totalAmount"
            val fallback = if (locked) "totalAmount" else "amount"
            val positions = mutableListOf<BinanceEarnPosition>()
            val badRows = mutableListOf<String>()
            var emptyRows = 0
            for (i in 0 until rows.length()) {
                // 用 opt 而非 get：非对象行只该跳过这一行，不该把整页带崩（旧代码会整页失效）
                val obj = rows.optJSONObject(i)
                if (obj == null) {
                    badRows.add("第${i + 1}行不是对象")
                    continue
                }
                val asset = obj.optString("asset")
                val raw = obj.optString(primary).takeIf { it.isNotBlank() }
                    ?: obj.optString(fallback)
                val amount = raw.toDoubleOrNull()
                if (amount == null) {
                    // 两个数量字段都没有（或不可解析）：这是数据丢失，必须让用户知道
                    badRows.add("第${i + 1}行缺数量字段 $primary/$fallback")
                    continue
                }
                if (asset.isBlank()) {
                    badRows.add("第${i + 1}行缺资产名")
                    continue
                }
                if (amount <= 0.0) {
                    // 数量为 0 的空壳行（已赎回）：属正常，不计失败，只记进诊断日志
                    emptyRows++
                    continue
                }
                positions.add(BinanceEarnPosition(asset = asset, amount = amount, locked = locked))
            }
            EarnParseResult(
                positions = positions,
                badRows = badRows,
                emptyRows = emptyRows,
                rowCount = rows.length(),
                total = total
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("解析币安理财持仓失败: ${e::class.simpleName}", e, "BinanceAccountAPI")
            EarnParseResult(
                positions = emptyList(),
                bodyNotJson = e::class.simpleName ?: "解析失败"
            )
        }
    }

    /**
     * 诊断探针（非集成）：顺手拉一下 Staking 持仓接口，把原始响应体前 600 字落盘。
     *
     * ## 为什么要有这个探针
     *
     * 用户已确认 API Key 读取权限全开，但 simple-earn 接口在真机上回 0 条（App 因此只用
     * 被借币抵押压小的 LD 壳，少算 142.08−124.59≈17.49 USDT）。权限没问题的前提下，最可能是
     * 这笔钱其实躺在 **Staking（锁仓质押）** 而不是 Simple Earn——而 Staking 是另一套接口、
     * 另一套响应形状，App 当前根本不查。这个探针只记日志、绝不并入合并链路（并入需要单独处理
     * Staking 的 `data[]` 形状且要补单测，不能顺手乱加），目的只是「一次真机拉取就看清钱在哪」。
     * 4xx / 异常都静默吞掉，绝不影响导入主流程。
     */
    private suspend fun probeStakingPositions(
        apiKey: String,
        apiSecret: String,
        httpClient: OkHttpClient
    ) {
        val timestamp = System.currentTimeMillis()
        val query =
            "product=STAKING&current=1&size=$EARN_PAGE_SIZE&timestamp=$timestamp&recvWindow=$RECV_WINDOW"
        val signature = sign(apiSecret, query)
        val request = Request.Builder()
            // 只记端点路径，signature 由 secret 派生，绝不落盘
            .url("${EARN_SAPI_BASE}/staking/position?$query&signature=$signature")
            .addHeader("X-MBX-APIKEY", apiKey)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                val snippet = body.take(600).replace("\r", " ").replace("\n", " ")
                AppLogger.w(
                    "币安诊断探针[Staking] HTTP ${resp.code}，响应体前600字=$snippet",
                    "BinanceAccountAPI"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 探针失败绝不影响导入：可能没开 Staking 权限 / 接口调整 / 网络抖动，都只是少一条日志
            AppLogger.w(
                "币安诊断探针[Staking] 请求异常: ${e::class.simpleName} ${e.message ?: ""}",
                "BinanceAccountAPI"
            )
        }
    }

    private fun warnEarnFailure(kind: String, e: Throwable) {
        if (e is CancellationException) throw e
        // 这条只记日志、不阻断导入：失败原因已通过 BinanceEarnFetch.failures 带给预览页，
        // 用户能直接看到该去开哪个权限。绝大多数情况是「未开通 Simple Earn 读取权限 /
        // 接口调整 / 该地区不支持」而返回 404——属预期、已降级处理，不该每轮导入刷 WARN，
        // 降级到 DEBUG，避免诊断日志被这类"非故障"刷屏。
        AppLogger.d(
            "拉取币安${kind}理财持仓异常: ${e::class.simpleName}，本次导入只用现货",
            "BinanceAccountAPI"
        )
    }

    /**
     * 批量取最新价（公开免签）。
     *
     * 用于导入时估算各持仓的人民币市值，好按价值过滤零碎。走公开的
     * `/ticker/price?symbols=[...]`，无需 API Key，也不带任何密钥信息。
     *
     * ## 为什么失败不抛、而是返回空 Map
     *
     * 取价只是「按市值过滤」的增强：取不到价就不过滤、全量导入，
     * 绝不能因为一次公开行情请求挂了就让整个导入失败——余额才是关键数据。
     * 所以内部吞掉异常，尽量返回已取到的部分。
     *
     * ## 整批 400 → 对半重试（隔离坏符号）
     *
     * 币安 `ticker/price?symbols=[...]` 是「**一个符号非法 → 整批 400**」语义。
     * 真实账户 ~1800 个余额里常混有已下架/脏符号，一批里只要有一个就会让整批
     * 80 个全废（见根因诊断）。这里用队列做「整批失败 → 对半重试」：把含坏符号
     * 的批拆小，逐步隔离出单个非法符号（直接丢弃），好符号照常取到价。
     * **正常无坏符号时每批只发一次，零额外请求。**
     *
     * ## 日志口径
     *
     * 只记批次大小，不记 symbols 内容——那等于把用户的持仓构成写进可分享日志。
     *
     * @param pairs 交易对列表（如 BTCUSDT）
     * @return Map<交易对, 最新价>；失败/无数据时返回空 Map（调用方据此降级为不过滤）
     */
    suspend fun getPrices(pairs: List<String>): Map<String, Double> = getPrices(pairs, client)

    /**
     * internal 重载：允许单测注入假 client（自定义 [okhttp3.Interceptor] 模拟币安语义）。
     *
     * 公开 API 语义与 [getPrices] 完全一致——只是把用于发请求的那个 client 提出来，
     * 便于测试时用走假 Interceptor 的 client 替换 [NetworkClient.binanceClient]。
     */
    internal suspend fun getPrices(pairs: List<String>, httpClient: OkHttpClient): Map<String, Double> =
        withContext(Dispatchers.IO) {
            if (pairs.isEmpty()) return@withContext emptyMap()
            val result = mutableMapOf<String, Double>()
            try {
                // 队列驱动「整批 400 → 对半重试」：正常无坏符号时每批只发一次，零额外请求。
                val queue = ArrayDeque<List<String>>().apply {
                    addAll(pairs.distinct().chunked(PRICE_BATCH))
                }
                while (queue.isNotEmpty()) {
                    val batch = queue.removeFirst()
                    val fetched = fetchBatch(batch, httpClient)
                    if (fetched != null) {
                        result.putAll(fetched)
                        continue
                    }
                    // 整批失败：币安语义「一个符号非法 → 整批 400」。对半拆开继续试，
                    // 逐步隔离出坏符号。单条仍失败说明这条本身非法，直接丢弃（不写库、
                    // 不影响其它批）。
                    if (batch.size > 1) {
                        val mid = batch.size / 2
                        // 先压后半、再压前半，保持原批内顺序优先（仅影响排查，不影响结果）
                        queue.addFirst(batch.takeLast(batch.size - mid))
                        queue.addFirst(batch.take(mid))
                    }
                    // batch.size == 1 仍失败：单个非法符号，丢弃（上面 if 不进即此分支）
                }
                result
            } catch (e: CancellationException) {
                // 协程取消必须原样抛回：取消不是"主机不可达"，绝不能被记成网络失败
                throw e
            } catch (e: Exception) {
                // 网络级失败（连不上 / 超时 / 域名解析不了 / IO 错误）喂给主机级熔断器，
                // 与 getAccount / fetchBatch 口径一致——批量取价此前也漏接 gate。
                // 注意：JSON 解析等非 IO 异常不记账——它们不是"主机不可达"，不该顶爆熔断。
                //
                // 「我们自己放弃」不算主机不可达的证据，绝不喂熔断器。
                // 典型是切后台（MainActivity onStop 取消同步/导入）：协程取消了，但阻塞中的
                // execute() 不会被打断，仍要跑到 callTimeout 才以 InterruptedIOException 收尾。
                // 这些失败若计入，一次切后台就能凑满阈值、把整个主机熔断 5 分钟——
                // 真机日志实锤：20:06:59 切后台 → 20:07:05 六个 35s 超时 → 触发熔断。
                // 与行情路径 [UnifiedPriceService.getCryptoPrice] 同口径：用 isCancellationArtifact
                // + isActive 双判，只有「协程仍活跃时的真实网络 IO 失败（超时/连不上/DNS）」才记账；
                // 取消/中断类（CancellationException、InterruptedIOException: timeout 且已不在前台）
                // 一律跳过。注意 isCancellationArtifact 刻意**不**把带 timeout 的
                // InterruptedIOException 当取消——真超时本身是网络证据，是否计入只看 isActive。
                val gaveUp = !currentCoroutineContext().isActive || isCancellationArtifact(e)
                if (!gaveUp && e is IOException) {
                    BinanceHostGate.onNetworkFailure(System.currentTimeMillis())
                }
                // 只记异常类型，不记 message（可能含 URL）；返回已取到的部分（通常为空）
                AppLogger.e("批量取价失败: ${e::class.simpleName}", e, "BinanceAccountAPI")
                result
            }
        }

    /**
     * 对一批交易对发一次 `/ticker/price` 请求。
     *
     * 抽出独立挂起函数，便于 [getPrices] 在「对半重试」里反复调用，也便于测试
     * 用注入的假 client 走真实请求路径。
     *
     * @return 成功（2xx）时返回 交易对→价 的 map；整批失败（非 2xx）或空响应返回
     *         null，交给 [getPrices] 做「对半重试 / 丢弃单条」的隔离处理。
     *         网络异常不在这里吞——上抛给 [getPrices] 的兜底
     *         （CancellationException 原样重抛，其余记日志并返回已取到的部分）。
     */
    private suspend fun fetchBatch(batch: List<String>, httpClient: OkHttpClient): Map<String, Double>? {
        val encoded = buildSymbolsParam(batch)
        val url = "$PRICE_URL?symbols=$encoded"

        // 只记批次大小，不记 symbols（避免把用户持仓构成写进可分享日志，与现有口径一致）
        AppLogger.d("GET /ticker/price (batch=${batch.size})", "BinanceAccountAPI")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        val response = httpClient.newCall(request).execute()
        // 记账放在 response.use 之前，所以语义就是「只要拿到 HTTP 响应就算主机可达」：
        // 含 451 地区封锁、含一个坏符号导致整批 400——与 getAccount / 行情路径统一口径，
        // 让主机级熔断器复位连续失败计数。非 2xx 不是"主机不可达"，不该顶爆熔断；
        // 真正的"连不上"由 [getPrices] 的兜底 catch 记 [BinanceHostGate.onNetworkFailure]。
        BinanceHostGate.onSuccess()
        response.use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val map = mutableMapOf<String, Double>()
            parsePrices(body).forEach { (symbol, price) -> map[symbol] = price }
            return map
        }
    }

    /**
     * 构造 /ticker/price 的 symbols 查询参数值（JSON 数组字符串）。
     *
     * **必须用无空格分隔**：`["BTCUSDT","LTCUSDT"]` 而非 `["BTCUSDT", "LTCUSDT"]`。
     * [getPrices] 里曾经用默认的 `", "` 分隔符，空格经 [URLEncoder] 变成 `+`，
     * 币安的 symbols 解析器不认，整批返回 400 —— 2026-10-17 修。
     */
    internal fun buildSymbolsParam(pairs: List<String>): String {
        // 用 buildString 显式拼，绝不给逗号后面留空格——
        // 一旦有空格，URLEncoder 会编成 '+'，币安 symbols 解析器不认 → 整批 400。
        val json = buildString {
            append('[')
            pairs.forEachIndexed { index, symbol ->
                if (index > 0) append(',')
                append('"').append(symbol).append('"')
            }
            append(']')
        }
        return URLEncoder.encode(json, "UTF-8")
    }

    /**
     * 把币安的错误响应转成带 code+msg 的异常。
     *
     * body 形如 `{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}`。
     * 币安的 msg 是排障的关键（密钥无效 / IP 不在白名单 / 权限不足是三种不同问题），
     * 必须带给用户；解析不出结构化字段时退回 HTTP 状态码。
     *
     * internal：行情门面的加密货币取价也走这套口径（同样要区分"被地区限制"和"代码写错"），
     * 错误提示只此一份实现。
     */
    internal fun binanceError(body: String, httpCode: Int): BinanceApiException {
        // 地区限制：币安按条款 "b. Eligibility" 挡部分地区/IP，常返回 451 或
        // {"code":0,"msg":"Service unavailable from a restricted location..."}。
        // 只给英文原文用户不知道该干嘛，补一句可操作的中文——换允许地区的代理节点即可。
        val geoHint = "（当前代理/VPN 节点所在地区被币安限制，更换到允许地区的节点后重试）"
        return try {
            val obj = JSONObject(body)
            val code = obj.optInt("code", httpCode)
            val msg = obj.optString("msg").ifBlank { "HTTP $httpCode" }
            val isGeo = httpCode == 451 ||
                msg.contains("restricted location", true) ||
                msg.contains("Eligibility", true)
            // 注意这里**不**认裸的 "Service unavailable"：503/500 的响应体常常就写着
            // "Service Unavailable"，那种是服务端抖动（等几分钟），让用户去换代理节点是
            // 另一种误导——正是本轮在消除的同一类问题。真正的 451 由状态码兜住。
            // httpCode 单独带着：上层要按 418/429/5xx 分流出「限流或服务端异常」文案，
            // 不能去 message 里 contains("429") 猜（币安业务码本身也是数字，会误判）
            BinanceApiException(
                httpCode = httpCode,
                binanceCode = code,
                message = "币安拒绝请求（$code）：$msg" + if (isGeo) geoHint else ""
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 非 JSON body（451 有时返回纯文本/HTML）：按状态码兜底，451 同样给地区提示
            BinanceApiException(
                httpCode = httpCode,
                binanceCode = httpCode,
                message = "币安请求失败（HTTP $httpCode）" + if (httpCode == 451) geoHint else ""
            )
        }
    }

    /**
     * 解析 `balances` 数组。
     *
     * free/locked 在币安响应里是**字符串**（如 "0.00000000"），不是数字，
     * 按字符串取出再 toDoubleOrNull，解析不到记 0（不影响其它行）。
     */
    private fun parseBalances(body: String): List<BinanceBalance> {
        val arr = JSONObject(body).getJSONArray("balances")
        val result = mutableListOf<BinanceBalance>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                BinanceBalance(
                    asset = obj.getString("asset"),
                    free = obj.optString("free").toDoubleOrNull() ?: 0.0,
                    locked = obj.optString("locked").toDoubleOrNull() ?: 0.0
                )
            )
        }
        return result
    }

    /**
     * 解析批量最新价响应：`[{"symbol":"BTCUSDT","price":"67000.00"},...]`。
     *
     * price 同样是字符串。价格为 0 或解析不了的行跳过——0 价没有估算意义，
     * 混进 map 会让对应资产被当成「估得出价但市值为 0」而误滤掉。
     */
    private fun parsePrices(body: String): Map<String, Double> {
        val arr = JSONArray(body)
        val map = mutableMapOf<String, Double>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val symbol = obj.optString("symbol")
            val price = obj.optString("price").toDoubleOrNull()
            if (symbol.isNotBlank() && price != null && price > 0.0) {
                map[symbol] = price
            }
        }
        return map
    }
}
