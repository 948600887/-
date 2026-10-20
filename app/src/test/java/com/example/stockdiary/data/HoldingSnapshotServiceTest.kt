package com.example.stockdiary.data

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.importer.HoldingSnapshotResult
import com.example.stockdiary.data.importer.HoldingSnapshotService
import com.example.stockdiary.data.importer.ParsedHolding
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.StockCodeManager
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
 * 重点在几条用户拍板的规则：同代码合并、已存在则覆盖数量、未变不写库，
 * 以及「同代码在库里有多个账户各持一份」时不替用户做决定。
 */
class HoldingSnapshotServiceTest {

    private val repo = mockk<AssetRepository>(relaxed = true)
    private val service = HoldingSnapshotService(repo)

    init {
        // 落库整批走事务：假仓库没有真 DB，直接执行块内容
        coEvery { repo.inTransaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>()()
        }
    }

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

    /**
     * 造一条**库里已有**的资产。
     *
     * 这里刻意走一次 [StockCodeManager.normalizeSymbol]：库里存的从来不是截图上的裸码，
     * 而是带市场前缀的形态（600519 → sh600519）。测试如果不按这个形态造数据，
     * 就永远测不到「规范化」这件事，也就复现不了每次导入都造重复资产那个 bug。
     */
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
        symbol = StockCodeManager.normalizeSymbol(symbol),
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
        assertEquals(AssetType.STOCK, bySymbol.getValue("sh601988").type)
        assertEquals(AssetType.FUND, bySymbol.getValue("sh510210").type)
        // 参考单价必须留 0：它是「无行情时的市值兜底价」，
        // 塞个假数字会让总市值凭空多出来
        assertEquals(0.0, bySymbol.getValue("sh601988").purchasePrice, 0.001)
        assertEquals("中国结算", bySymbol.getValue("sh601988").account)
    }

    @Test
    fun 场外号段裸码经buildPreview落成fund_前缀且类型为基金() = runBlocking {
        // 锁死历史坑：场外号段裸码（004502）必须经由 normalizeSymbol 落成 fund_004502
        // 且资产类型为 FUND，不能误标成股票、也不能同一只基金两种形态翻倍。
        // 必须走 buildPreview → normalizeSymbol 真路径，不要只断言裸码。
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(parsed(holding("004502", "中银如意宝货币A", 100.0)))
        service.commit(preview, account = "中国结算")

        val asset = captured.captured.single()
        assertEquals("fund_004502", asset.symbol)
        assertEquals(AssetType.FUND, asset.type)
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
        assertEquals(listOf("sh601988"), captured.captured.map { it.symbol })
    }

    // ---------- 代码形态：裸码必须规范化 ----------

    @Test
    fun 查库用的是带市场前缀的规范化代码() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()

        service.buildPreview(parsed(holding("601988", "中国银行", 900.0)))

        // 截图给的是裸码 601988，库存形态是 sh601988。
        // 拿裸码去查一条都匹配不上，表现为「每次导入都在造重复资产」
        coVerify { repo.getAssetsBySymbols(listOf("sh601988")) }
    }

    @Test
    fun 规范化后能匹配到库里已有的持仓_不再重复新建() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns
            listOf(assetOf(7L, "601988", "中国银行", balance = 500.0))

        val preview = service.buildPreview(parsed(holding("601988", "中国银行", 900.0)))

        assertEquals(0, preview.newItems.size)
        assertEquals(1, preview.updatedItems.size)
        assertEquals(400.0, preview.updatedItems[0].delta, 0.001)
    }

    @Test
    fun 深市代码的带前缀形态也不会漏() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()

        service.buildPreview(parsed(holding("159919", "沪深300ETF", 1000.0)))

        coVerify { repo.getAssetsBySymbols(listOf("sz159919")) }
    }

    // ---------- 同代码在多个账户各持一份 ----------

    private val duplicatedInTwoAccounts: List<Asset> = listOf(
        assetOf(1L, "601988", "中国银行", balance = 600.0, account = "券商A"),
        assetOf(2L, "601988", "中国银行", balance = 300.0, account = "券商B")
    )

    @Test
    fun 多账户合计正好等于快照数量时什么都不用做() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns duplicatedInTwoAccounts

        val preview = service.buildPreview(parsed(holding("601988", "中国银行", 900.0)))

        assertEquals(1, preview.unchangedItems.size)
        assertEquals(0, preview.pendingCount)
    }

    @Test
    fun 多账户合计对不上时判冲突而不是挑一条覆盖() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns duplicatedInTwoAccounts

        val preview = service.buildPreview(parsed(holding("601988", "中国银行", 2000.0)))

        assertEquals(1, preview.conflictItems.size)
        assertEquals(listOf("券商A", "券商B"), preview.conflictItems[0].matchedAccounts)
        // 冲突是「没法处理」，不是「处理完了」——混进 pendingCount 会让界面
        // 显示「导入 1 条」却一条都没导
        assertEquals(0, preview.pendingCount)
    }

    @Test
    fun 冲突项落库时一条都不动但会报出来() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns duplicatedInTwoAccounts

        val preview = service.buildPreview(parsed(holding("601988", "中国银行", 2000.0)))
        val result = service.commit(preview, account = "中国结算")

        assertEquals(1, result.conflicts)
        assertEquals(0, result.inserted)
        assertEquals(0, result.updated)
        coVerify(exactly = 0) { repo.insertAssets(any()) }
        coVerify(exactly = 0) { repo.updateBalance(any(), any()) }
    }

    // ---------- 长截图 ----------

    @Test
    fun 代码过多时分片查询_不撞SQLite的变量个数上限() = runBlocking {
        coEvery { repo.getAssetsBySymbols(any()) } returns emptyList()

        val many = (0 until 1200).map { holding("6%05d".format(it), "股票$it", 100.0) }
        service.buildPreview(parsed(*many.toTypedArray()))

        // SQLite 的 IN 子句变量上限是 999，1200 条按 500 一片要查 3 次
        coVerify(exactly = 3) { repo.getAssetsBySymbols(any()) }
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
