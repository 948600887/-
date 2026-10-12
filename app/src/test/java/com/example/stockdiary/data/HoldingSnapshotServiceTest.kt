package com.example.stockdiary.data

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持仓快照对齐的回归。AssetRepository 是需 Context 的 final 类，用 MockK 伪造。
 *
 * 重点在三条用户拍板的规则：同代码合并、已存在则覆盖数量、未变不写库。
 */
class HoldingSnapshotServiceTest {

    private val repo = mockk<AssetRepository>(relaxed = true)
    private val service = HoldingSnapshotService(repo)

    private fun holding(
        symbol: String,
        name: String,
        quantity: Double,
        raw: String = quantity.toString()
    ) = ParsedHolding(
        name = name,
        symbol = symbol,
        quantity = quantity,
        lineNumber = 1,
        rawQuantity = raw
    )

    private fun parsed(vararg holdings: ParsedHolding, date: String? = "2026-10-09") =
        HoldingSnapshotResult(
            holdings = holdings.toList(),
            dataDate = date,
            incomplete = emptyList()
        )

    private fun assetOf(
        id: Long,
        symbol: String,
        name: String,
        balance: Double,
        account: String = "老账户"
    ) = Asset(
        id = id,
        name = name,
        type = AssetType.FUND,
        symbol = symbol,
        account = account,
        balance = balance,
        purchasePrice = 0.0
    )

    // ---------- 三种状态 ----------

    @Test
    fun 库里没有的判为新增() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()

        val preview = service.buildPreview(parsed(holding("510210", "综指ETF", 14400.0, "1.44万")))

        assertEquals(1, preview.newItems.size)
        assertEquals(14400.0, preview.newItems[0].quantity, 0.001)
        assertEquals("1.44万", preview.newItems[0].rawQuantity)
        assertEquals(1, preview.pendingCount)
    }

    @Test
    fun 数量不同的判为覆盖() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns
            listOf(assetOf(7L, "515080", "中证红利", balance = 10000.0))

        val preview = service.buildPreview(parsed(holding("515080", "中证红利", 21700.0, "2.17万")))

        assertEquals(1, preview.updatedItems.size)
        val item = preview.updatedItems[0]
        assertEquals(21700.0, item.quantity, 0.001)
        // 覆盖量取差值，供界面提示「1 万 → 2.17 万」
        assertEquals(11700.0, item.delta, 0.001)
    }

    @Test
    fun 数量一致的判为未变_不写库() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns
            listOf(assetOf(7L, "515080", "中证红利", balance = 21700.0))

        val preview = service.buildPreview(parsed(holding("515080", "中证红利", 21700.0)))

        assertEquals(1, preview.unchangedItems.size)
        assertEquals(0, preview.pendingCount)

        val result = service.commit(preview, account = "中国结算")
        assertEquals(0, result.inserted)
        assertEquals(0, result.updated)
        assertEquals(1, result.skipped)
        coVerify(exactly = 0) { repo.insertAssets(any()) }
        coVerify(exactly = 0) { repo.updateBalance(any(), any()) }
    }

    // ---------- 同代码合并 ----------

    @Test
    fun 同代码多行合并成一条且数量相加() = runBlocking {
        // 中国结算按托管机构分行：同一只 510300 在两个券商各有持仓
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()

        val preview = service.buildPreview(
            parsed(
                holding("510300", "沪深300ETF", 1000.0),
                holding("510300", "沪深300ETF", 2000.0)
            )
        )

        assertEquals("两行该合成一条", 1, preview.items.size)
        assertEquals(3000.0, preview.items[0].quantity, 0.001)
        // 原文与来源都留着，界面要能说明「这条是两笔加起来的」
        assertEquals(2, preview.items[0].sources.size)
        assertEquals("1000.0+2000.0", preview.items[0].rawQuantity)
    }

    @Test
    fun 同名不同代码不会被合并() = runBlocking {
        // 真实截图里两行都叫「黄金LOF」，代码不同
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()

        val preview = service.buildPreview(
            parsed(
                holding("160719", "黄金LOF", 262.0),
                holding("164701", "黄金LOF", 156.0)
            )
        )

        assertEquals(2, preview.items.size)
    }

    // ---------- 落库 ----------

    @Test
    fun 新建的资产按代码推断类型并留空参考单价() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            parsed(
                holding("601988", "中国银行", 900.0),
                holding("510210", "综指ETF", 14400.0)
            )
        )
        val result = service.commit(preview, account = "中国结算")

        assertEquals(2, result.inserted)
        val bySymbol = captured.captured.associateBy { it.symbol }
        assertEquals(AssetType.STOCK, bySymbol.getValue("601988").type)
        assertEquals(AssetType.FUND, bySymbol.getValue("510210").type)
        // 参考单价必须留 0：它是「无行情时的市值兜底价」，
        // 塞个假数字会让总市值凭空多出来
        assertEquals(0.0, bySymbol.getValue("601988").purchasePrice, 0.001)
        assertEquals("中国结算", bySymbol.getValue("601988").account)
    }

    @Test
    fun 覆盖时只改数量_账户和名称保持库里原样() = runBlocking {
        val existing = assetOf(7L, "515080", "我改过的名字", balance = 10000.0, account = "我的券商")
        coEvery { repo.getAssetsBySymbols(any()) } returns listOf(existing)
        val balanceSlot = slot<Double>()
        coEvery { repo.updateBalance(7L, capture(balanceSlot)) } returns Unit

        val preview = service.buildPreview(parsed(holding("515080", "中证红利", 21700.0)))
        val result = service.commit(preview, account = "中国结算")

        assertEquals(1, result.updated)
        assertEquals(21700.0, balanceSlot.captured, 0.001)
        // 只动数量：不调 updateAsset（那会连带改掉名称与账户）
        coVerify(exactly = 0) { repo.updateAsset(any()) }
        coVerify(exactly = 0) { repo.insertAssets(any()) }
    }

    @Test
    fun 账户名留空时用默认账户兜底() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(parsed(holding("601988", "中国银行", 900.0)))
        service.commit(preview, account = "   ")

        assertEquals(HoldingSnapshotService.DEFAULT_ACCOUNT, captured.captured[0].account)
    }

    @Test
    fun 新增与覆盖混在一起各自走对分支() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns
            listOf(assetOf(7L, "515080", "中证红利", balance = 10000.0))
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            parsed(
                holding("515080", "中证红利", 21700.0),
                holding("601988", "中国银行", 900.0)
            )
        )
        val result = service.commit(preview, account = "中国结算")

        assertEquals(1, result.inserted)
        assertEquals(1, result.updated)
        assertEquals(listOf("601988"), captured.captured.map { it.symbol })
    }

    @Test
    fun 空快照不写库() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()

        val preview = service.buildPreview(HoldingSnapshotResult(emptyList(), null, emptyList()))
        val result = service.commit(preview, account = "中国结算")

        assertTrue(preview.items.isEmpty())
        assertEquals(0, result.inserted + result.updated + result.skipped)
        coVerify(exactly = 0) { repo.insertAssets(any()) }
    }

    @Test
    fun 数据日期原样透传给预览() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()
        val preview = service.buildPreview(parsed(holding("601988", "中国银行", 900.0)))
        assertEquals("2026-10-09", preview.dataDate)
    }

    @Test
    fun 没读懂的行要带出来给界面提示() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()
        val withIncomplete = HoldingSnapshotResult(
            holdings = listOf(holding("601988", "中国银行", 900.0)),
            dataDate = null,
            incomplete = listOf("160719")
        )
        val preview = service.buildPreview(withIncomplete)
        assertEquals(listOf("160719"), preview.incomplete)
    }
}
