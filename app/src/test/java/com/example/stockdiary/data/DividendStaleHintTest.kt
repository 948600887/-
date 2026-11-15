package com.example.stockdiary.data

import com.example.stockdiary.data.repository.DividendDataFreshness
import com.example.stockdiary.data.repository.staleDividendHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分红「空结果保留旧值」的用户可见提示（纯函数口径）。
 *
 * 背景：sz161226 诊断里，源返回空但库里 30 条旧记录被保留，`markFetched` 又把
 * 「抓过时间」刷成 now，于是年化收入长期沿用旧记录、界面一声不吭。
 * 修法不是清库（那会真的丢记录），而是把「这次没取到、数据来自 N 天前」说出来。
 * 这里钉死文案的四个分支，避免 N 天算错或该消失时不消失。
 */
class DividendStaleHintTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `从未发生过空结果_不提示`() {
        assertNull(staleDividendHint(DividendDataFreshness(lastDataAt = now - dayMs, lastMissedAt = null), now))
    }

    @Test
    fun `空结果之后又成功取到_提示消失`() {
        val freshness = DividendDataFreshness(
            lastDataAt = now - 1_000L,
            lastMissedAt = now - dayMs
        )
        assertNull("最近一次取到了新数据，旧结论应被覆盖", staleDividendHint(freshness, now))
    }

    @Test
    fun `空结果保留旧值_提示来自N天前`() {
        val freshness = DividendDataFreshness(
            lastDataAt = now - 5 * dayMs,
            lastMissedAt = now - dayMs
        )
        assertEquals(
            "分红数据最近一次未取到，当前沿用 5 天前的记录",
            staleDividendHint(freshness, now)
        )
    }

    @Test
    fun `不足一天也按一天计_避免出现0天前`() {
        val freshness = DividendDataFreshness(
            lastDataAt = now - 1_000L,
            lastMissedAt = now - 500L
        )
        assertEquals(
            "分红数据最近一次未取到，当前沿用 1 天前的记录",
            staleDividendHint(freshness, now)
        )
    }

    @Test
    fun `老用户升级上来还没有旧数据时间戳_不编造天数`() {
        val freshness = DividendDataFreshness(lastDataAt = null, lastMissedAt = now - dayMs)
        assertEquals(
            "分红数据最近一次未取到，当前沿用更早的记录",
            staleDividendHint(freshness, now)
        )
    }
}
