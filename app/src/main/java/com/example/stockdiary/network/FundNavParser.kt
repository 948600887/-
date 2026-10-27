package com.example.stockdiary.network

import org.json.JSONObject

/**
 * 轻量净值源（天天基金移动端 FundMNBasicInformation）的**纯解析**。
 *
 * ## 为什么要有这个源
 *
 * [FundAPI] 原来取净值走 `fund.eastmoney.com/pingzhongdata/{code}.js`：那份响应是
 * 基金的**完整历史净值**，实测 0.5~2MB，而我们要的只是最新一期一个数。
 * 106 只标的在一次刷新里叠起来就是几十到上百 MB 的峰值内存，足以让系统
 * 在刷新过程中把进程回收（用户看到的是「点刷新黑屏然后闪退」，零崩溃报告）。
 *
 * 移动端接口 `FundMNewApi/FundMNBasicInformation` 只回最新一期净值，约 2.5KB，
 * 且**带基金名称**（[FundAPI.FundInfo.name] 非空是行情门面的硬契约，不能丢）。
 *
 * ## 为什么解析要抽成纯函数
 *
 * 纯 String 进、[FundAPI.FundInfo] 出，不碰网络不碰时钟，才能用真实抓到的
 * 报文做单测 —— 尤其是货币基金那条分支（见下）。
 *
 * ## 货币基金的坑（本文件最关键的十几行）
 *
 * 货币基金的单位净值恒为 1.0000，接口 `Datas.DWJZ` 里给的是**每万份收益**
 * （实测 004972 返回 `DWJZ="0.2812"`，与 legacy 文件里
 * `Data_millionCopiesIncome` 的最后一项完全一致）。
 * 把它当成单位净值，货币基金的市值会被乘成 0.2812 倍 —— 比我们要修的内存
 * 问题严重得多，因为它是**静默算错**，用户只会觉得「钱少了」。
 *
 * 所以货币基金一律：`price = 1.0`、`netWorth = 1.0`、
 * `millionCopiesIncome = DWJZ`、`changePercent = null`。
 */
object FundNavParser {

    /**
     * 货币基金的类型码：`Datas.FUNDTYPE` 为该值时 `DWJZ` 是每万份收益而非单位净值。
     *
     * 与 [MONEY_FUND_TYPE_TEXT] 取**或**：类型码是权威字段，类型文案是兜底，
     * 两个信号只要有一个说是货币基金就按货币基金处理（宁可按 1.0 处理，
     * 也不要把 0.2812 当净值）。
     */
    internal const val MONEY_FUND_TYPE_CODE = "005"

    /** 货币基金的类型文案（如 `货币型-普通货币`）。 */
    private const val MONEY_FUND_TYPE_TEXT = "货币"

    /** 货币基金的单位净值恒为 1.0。 */
    private const val MONEY_FUND_UNIT_NAV = 1.0

    /**
     * 解析 `FundMNBasicInformation` 的响应。
     *
     * 任一环节不成立都返回 null（调用方据此回退到 legacy 数据源），
     * **绝不返回半截数据**：
     *
     * 1. 报文可解析为 JSON 对象；
     * 2. `ErrCode` 存在且非 0 → 视为业务失败（不带头/带错 UA 时东财会回 61136）；
     * 3. `Datas` 是对象（不存在/为 null/为数组一律失败 —— 不存在的基金码
     *    返回的就是 `Datas: null`）；
     * 4. `Datas.SHORTNAME` 非空（[FundAPI.FundInfo.name] 的非空契约）；
     * 5. `Datas.FSRQ` 非空（日期为空的净值落库后从此无法判断新旧）；
     * 6. `Datas.DWJZ` 能解析成有限且 > 0 的数 —— 与 [FundAPI] 里 legacy 路径
     *    `naw <= 0.0` 直接丢弃的语义保持一致。
     *
     * @param json HTTP 响应体
     * @param fundCode 请求时用的基金码，`Datas.FCODE` 缺失时用它兜底
     * @return 解析成功的最新一期净值；失败返回 null
     */
    fun parseBasicInfo(json: String, fundCode: String): FundAPI.FundInfo? {
        if (json.isBlank()) return null

        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }

        if (hasBusinessError(root)) return null

        val datas = root.optJSONObject("Datas") ?: return null

        val name = datas.optString("SHORTNAME", "").trim()
        if (name.isEmpty()) return null

        val navDate = datas.optString("FSRQ", "").trim()
        if (navDate.isEmpty()) return null

        val rawNav = datas.optString("DWJZ", "").trim()
        val nav = rawNav.toDoubleOrNull()
        if (nav == null || !nav.isFinite()) return null
        // 货币基金 DWJZ 是「每万份收益」而非单位净值，当天无收益时合法为 0；
        // 其单位净值恒为 1.0（下方 isMoneyFund 分支处理），故不在此按 <=0 拒绝，
        // 否则货币基金偶发 0 收益会被整条剔除、退化到 legacy 路径。
        if (!isMoneyFund(datas) && nav <= 0.0) return null

        val code = datas.optString("FCODE", "").trim().ifEmpty { fundCode }

        // 货币基金：DWJZ 是每万份收益，单位净值恒为 1.0（见类注释）
        if (isMoneyFund(datas)) {
            return FundAPI.FundInfo(
                name = name,
                code = code,
                price = MONEY_FUND_UNIT_NAV,
                netWorth = MONEY_FUND_UNIT_NAV,
                changePercent = null,
                date = navDate,
                millionCopiesIncome = nav
            )
        }

        return FundAPI.FundInfo(
            name = name,
            code = code,
            price = nav,
            // 与 legacy 路径一致：netWorth 沿用单位净值，不要在这里改成 LJJZ
            // （累计净值），否则会静默改变下游的口径
            netWorth = nav,
            changePercent = datas.optString("RZDF", "").trim().toDoubleOrNull(),
            date = navDate,
            millionCopiesIncome = null
        )
    }

    /**
     * 业务层是否报错：`ErrCode` 存在且不等于 0。
     *
     * `ErrCode` 缺失时不算错 —— 该字段在部分响应里本来就不带，
     * 真正的可用性由后面的 `Datas` / 净值字段把关。
     */
    private fun hasBusinessError(root: JSONObject): Boolean {
        if (!root.has("ErrCode") || root.isNull("ErrCode")) return false
        return root.optInt("ErrCode", -1) != 0
    }

    /** 货币基金判定：类型码与类型文案取或，任一命中即按货币基金处理。 */
    private fun isMoneyFund(datas: JSONObject): Boolean {
        val typeCode = datas.optString("FUNDTYPE", "").trim()
        if (typeCode == MONEY_FUND_TYPE_CODE) return true
        return datas.optString("FTYPE", "").trim().contains(MONEY_FUND_TYPE_TEXT)
    }
}
