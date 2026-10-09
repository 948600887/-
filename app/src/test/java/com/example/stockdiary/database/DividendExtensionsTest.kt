package com.example.stockdiary.database

import com.example.stockdiary.data.DividendRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分红折算口径：**每十份（股）金额 → 持仓能拿到多少钱**。
 *
 * 这条公式以前散落在 6 个文件 8 个地方，改一次口径要改 8 处，已经因此出过一次 bug。
 * 现在只有 [perTenSharesToAmount] 一处实现，这里把口径锁死。
 */
class DividendExtensionsTest {

    @Test
    fun `每十份派063持仓1000份就是63元`() {
        assertEquals(63.0, perTenSharesToAmount(0.63, 1000.0), 0.001)
    }

    @Test
    fun `Dividend 按持仓折算`() {
        val d = Dividend(symbol = "sh510210", name = "综指ETF", amount = 0.76, date = "2023-06-20")
        assertEquals(76.0, d.amountFor(1000.0), 0.001)
    }

    @Test
    fun `DividendRecord 按持仓折算`() {
        val r = DividendRecord("2023-08-23", "0.36", "", "2023")
        assertEquals(36.0, r.amountFor(1000.0), 0.001)
    }

    @Test
    fun `金额解析不出数字时按0处理不崩`() {
        val r = DividendRecord("2023-08-23", "abc", "", "2023")
        assertEquals(0.0, r.amountFor(1000.0), 0.001)
    }

    @Test
    fun `零持仓时金额为0`() {
        assertEquals(0.0, perTenSharesToAmount(5.0, 0.0), 0.001)
    }
}
