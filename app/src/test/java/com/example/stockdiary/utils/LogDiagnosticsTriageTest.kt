package com.example.stockdiary.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogDiagnostics.buildTriageSummary] 回归（2026-11-13）。
 *
 * 这份摘要是给排查者看的：装新包 → 刷新 → 导出诊断 → 第一屏就要能判断
 * 「这轮是几点刷的、真实 514 几次、哪些链路根本没被测到」。
 * 最容易出错、也最危险的不是解析不出数字，而是**把「没解析到」显示成 0**
 * —— 0 会被读成「514 已归零、修复成功」，据此继续叠方案就是白做一版。
 *
 * fixture 全部抄自真机诊断 goldvault_diagnostics_20261113_160149.txt，
 * 保证测的是线上真实日志形态，而不是凭空造的好看格式。
 */
class LogDiagnosticsTriageTest {

    private fun line(ts: String, body: String) = "[$ts] $body"

    /** 11-12 的一轮旧刷新，用于验证「多轮取时间最新」 */
    private val oldRound = listOf(
        line("2026-11-12 19:57:05.100", "[INFO/AssetSyncManager] 价格同步开始 [轮次#17457000][仪表盘刷新]：257 个资产"),
        line(
            "2026-11-12 19:57:20.200",
            "[INFO/DividendRepository] [分红预取轮次#7] 汇总：参与 110 只，本轮发起抓取 37 只，" +
                "源应答并写下结论 8 只（含空结果），未取到 29 只（数据源未应答（含 514/429 限流） 29 / " +
                "退避/预算/缓存复用等 0），本轮真实 514/429 响应 7 次，缓存或TTL有效无需重抓 22 只"
        ),
        line(
            "2026-11-12 19:57:25.300",
            "[INFO/AssetSyncManager] === 同步轮次 #17457000 汇总 [仪表盘刷新] === | 资产 257：成功 250 / " +
                "失败 0 / 跳过 7（退市 1） | 超时截断=false · 总耗时 12.000s"
        )
    )

    /** 11-13 16:01 那一轮（真机诊断原文） */
    private val newRound = listOf(
        line(
            "2026-11-13 16:01:11.763",
            "[INFO/AssetSyncManager] 价格同步开始 [轮次#17457153][仪表盘刷新]：257 个资产，forceRefresh=true，时间预算=70000ms"
        ),
        line("2026-11-13 16:01:18.442", "[DEBUG/FundDividendApi] GET https://fundf10.eastmoney.com/fhsp_001323.html"),
        line(
            "2026-11-13 16:01:20.656",
            "[INFO/DividendRepository] [分红预取轮次#1] 汇总：参与 110 只，本轮发起抓取 80 只，" +
                "源应答并写下结论 26 只（含空结果），未取到 54 只（数据源未应答（含 514/429 限流） 54 / " +
                "退避/预算/缓存复用等 0），本轮真实 514/429 响应 6 次（退避窗口内被短路的标的计入上一档），" +
                "缓存或TTL有效无需重抓 30 只 | 未取到代码[54]：fund_000032,fund_000186…+52"
        ),
        line(
            "2026-11-13 16:01:22.890",
            "[INFO/AssetSyncManager] === 同步轮次 #17457153 汇总 [仪表盘刷新] === | 资产 257：成功 256 / " +
                "失败 0 / 跳过 1（退市 1） | 数据源：新浪财经 成12/源败0/符败0/耗时327ms · " +
                "基金-轻量源 成3/源败0/符败0/耗时360ms | 超时截断=false · 总耗时 10.369s"
        )
    )

    /** 逐标的 WARN：48 行退避短路 + 6 行真 514 + 2 行预算顺延 */
    private val warnLines =
        (1..48).map { line("2026-11-13 16:01:20.700", "[WARN/FundDividendApi] 天天基金限流退避中，跳过 00003$it 本次抓取") } +
            (1..6).map { line("2026-11-13 16:01:20.710", "[WARN/FundDividendApi] OkHttp HTTP 请求失败: 514") } +
            (1..2).map { line("2026-11-13 16:01:20.720", "[WARN/DividendRepository] 分红预取达到时间预算，跳过 fund_00003$it（留待按需抓取）") }

    private fun fullLog(order: List<String> = oldRound + newRound + warnLines) = order.joinToString("\n")

    @Test
    fun 从真实日志行提取验收指标_数字准确() {
        val s = LogDiagnostics.buildTriageSummary(fullLog())
        assertTrue(s, s.contains("★ 本轮真实 514/429 响应: 6 次"))
        assertTrue(s, s.contains("资产=257") && s.contains("成功=256") && s.contains("失败=0") && s.contains("跳过=1"))
        assertTrue(s, s.contains("参与=110") && s.contains("发起=80") && s.contains("源应答=26"))
        assertTrue(s, s.contains("未取到=54") && s.contains("TTL豁免=30"))
        assertTrue(s, s.contains("耗时_s=10.369"))
        assertTrue(s, s.contains("最近一次手动刷新: 2026-11-13 16:01:11.763"))
    }

    @Test
    fun 多轮日志取时间最新的一轮_而不是最后一行或旧轮次() {
        // 刻意把旧轮次放到最后：轮转日志「新文件在前、文件内时间正序」，
        // 简单取 last{} 会挑错轮次
        val s = LogDiagnostics.buildTriageSummary(fullLog(newRound + warnLines + oldRound))
        assertTrue(s, s.contains("★ 本轮真实 514/429 响应: 6 次"))
        assertFalse("不能拿旧轮的 7 次当结论", s.contains("响应: 7 次"))
        assertTrue(s, s.contains("成功=256"))
        assertFalse("不能拿旧轮的 250 当结论", s.contains("成功=250"))
    }

    @Test
    fun 日志覆盖时间范围与逐标的下界计数() {
        val s = LogDiagnostics.buildTriageSummary(fullLog())
        assertTrue(s, s.contains("日志覆盖: 2026-11-12 19:57:05.100 ~ 2026-11-13 16:01:22.890"))
        assertTrue(s, s.contains("本轮日志可见计数"))
        assertTrue(s, s.contains("限流退避短路=48"))
        assertTrue(s, s.contains("514请求失败=6"))
        assertTrue(s, s.contains("预算顺延=2"))
        assertTrue(s, s.contains("下界"))
    }

    @Test
    fun 下界计数只算本轮_不把历史轮次的WARN混进来() {
        // 真实诊断跨 8 天，整份日志有几百行退避 WARN；若混进来，
        // 会与「本轮真实 514=6」并列成两个量级差很大的数字，反而看不懂
        val oldWarns = (1..30).map {
            line("2026-11-12 19:57:10.000", "[WARN/FundDividendApi] 天天基金限流退避中，跳过 000100 本次抓取")
        }
        val s = LogDiagnostics.buildTriageSummary(fullLog(oldRound + oldWarns + newRound + warnLines))
        assertTrue(s, s.contains("限流退避短路=48"))
        assertFalse("历史 30 行不能计入本轮", s.contains("限流退避短路=78"))
        assertTrue(s, s.contains("514请求失败=6"))
    }

    @Test
    fun 链路没被测到时显式提醒_不要静默给0() {
        val s = LogDiagnostics.buildTriageSummary(fullLog())
        assertTrue(s, s.contains("交割单/ImportBrokerage=0 行 ⚠️本次导出未覆盖"))
        assertTrue(s, s.contains("币安导入=0 行 ⚠️本次导出未覆盖"))
    }

    @Test
    fun 汇总行缺失时写未解析到_绝不显示0次() {
        val log = listOf(
            line("2026-11-13 16:10:00.000", "[DEBUG/Foo] 普通日志，没有刷新轮次"),
            line("2026-11-13 16:10:01.000", "[WARN/FundDividendApi] 天天基金限流退避中，跳过 000032 本次抓取")
        ).joinToString("\n")
        val s = LogDiagnostics.buildTriageSummary(log)
        assertTrue(s, s.contains("未解析到（不可当作 0）"))
        assertFalse("缺失绝不能变成 0 次", s.contains("响应: 0 次"))
        assertTrue(s, s.contains("最近一轮价格同步 [未解析到]"))
        assertTrue(s, s.contains("未解析到（本份日志可能不含手动刷新）"))
        assertTrue(s, s.contains("资产=未解析到"))
    }

    @Test
    fun 空日志也给明确说明_不给空页面() {
        val s = LogDiagnostics.buildTriageSummary("")
        assertTrue(s, s.contains("app.log 为空或不存在，无法解析"))
    }

    @Test
    fun composeReport头部包含摘要_且排在设备状态快照之前() {
        val report = LogDiagnostics.Report(
            crashes = emptyList(),
            appLog = fullLog(),
            appLogPath = "/sdcard/app.log",
            crashFileCount = 0,
            deviceState = "数据源: 新浪财经=正常"
        )
        val text = LogDiagnostics.composeReport(report, envInfo = "金库 1.0.0(11)")
        assertTrue(text.contains("--- 排障摘要"))
        assertTrue(text.contains("★ 本轮真实 514/429 响应: 6 次"))
        assertTrue(
            "摘要必须在快照之前（第一屏可见）",
            text.indexOf("--- 排障摘要") < text.indexOf("--- 设备状态快照 ---")
        )
        assertTrue("分享/界面都必须带环境信息", text.contains("金库 1.0.0(11)"))
    }
}
