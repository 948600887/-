package com.example.stockdiary.data

import com.example.stockdiary.network.isRealDividendPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isRealDividendPage] 的回归。判据取自真实页面（fundf10.eastmoney.com/fhsp_*.html）。
 *
 * 这条判断决定了「解析出 0 条」之后要不要把同一个 URL 再抓一遍：
 * 判错的方向是**把"确实没有分红"当成失败**，于是 87% 的空标的被抓第二遍，
 * 双倍流量 + 多解析一遍 40KB DOM，还把接口往 HTTP 514 限流上撞
 * （实测 106 次）。反向判错（把反爬页当成真页面）则会静默丢掉真数据。
 */
class FundDividendPageShapeTest {

    /** 真实页面：有分红的基金（110011），表头在、数据行在 */
    private val pageWithDividends = """
        <table class='w782 comm cfxq'>
        <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
        <tr><td>2021年</td><td>2021-02-25</td><td>2021-02-25</td><td>每10份派现金9.0000元</td><td>2021-02-26</td></tr>
        </table>
    """.trimIndent()

    /** 真实页面：从没分过红的基金（010657 / 012349 / 014662 实测都是这样） */
    private val pageWithoutDividends = """
        <table class='w782 comm cfxq'>
        <tr><th>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th>分红发放日</th></tr>
        <tr><td colspan="5">暂无分红信息!</td></tr>
        </table>
    """.trimIndent()

    // ---------- 该认的：拿到的是真分红页，0 条就是答案 ----------

    @Test
    fun 有数据行的分红页当然要认() {
        assertTrue(isRealDividendPage(pageWithDividends))
    }

    @Test
    fun 没有数据行但表头在也要认_空结果是真实答案() {
        // 这是最关键的一条。以前就栽在这里：把「确实没有分红」当成失败去重抓
        assertTrue(isRealDividendPage(pageWithoutDividends))
    }

    @Test
    fun 只有表头没有空提示也认() {
        // 有些页面渲染出表头但空态文案不一样，表头本身就是判据
        assertTrue(isRealDividendPage("<th>权益登记日</th>"))
    }

    @Test
    fun 只有空提示没有表头也认() {
        assertTrue(isRealDividendPage("<td colspan=\"5\">暂无分红信息!</td>"))
    }

    // ---------- 不该认的：拿到的根本不是分红页 ----------

    @Test
    fun 反爬页不认_该换条路重试() {
        assertFalse(isRealDividendPage("<html><body>访问过于频繁，请稍后再试</body></html>"))
    }

    @Test
    fun 空字符串不认() {
        assertFalse(isRealDividendPage(""))
    }

    @Test
    fun 空页面不认() {
        assertFalse(isRealDividendPage("<html><body></body></html>"))
    }

    @Test
    fun 只有分红两个字不算_那可能是别的页面碰巧提到分红() {
        // 首页/公告页里到处都有「分红」二字。只用「分红」当判据会把它们全认成真页面，
        // 于是反爬页只要含「分红」就永不重试 —— 静默丢数据
        assertFalse(isRealDividendPage("<div>本基金的分红政策详见招募说明书</div>"))
    }
}
