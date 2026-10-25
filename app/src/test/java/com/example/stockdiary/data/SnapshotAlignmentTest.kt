package com.example.stockdiary.data

import com.example.stockdiary.data.importer.SnapshotAlignment.RawHolding
import com.example.stockdiary.data.importer.SnapshotAlignment
import com.example.stockdiary.data.importer.SnapshotAlignment.CommitPolicy
import com.example.stockdiary.data.importer.SnapshotAlignment.CommitRow
import com.example.stockdiary.data.importer.SnapshotAlignment.ItemStatus
import com.example.stockdiary.data.importer.SnapshotAlignment.SnapshotAssetWriter
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照对齐内核的回归。
 *
 * 两条导入链路（持仓快照 / 币安）共用这套内核；链路级行为由
 * [HoldingSnapshotServiceTest] 与 [BinanceImportServiceTest] 守着，
 * 这里锁的是内核自身的规则：同代码合并、浮点容差、四种状态判定、
 * 净值/币种合并口径，以及 commit 各分支的写库动作。
 *
 * 全部是纯 JVM 测试：[SnapshotAssetWriter] 用记录调用的假实现，
 * 不需要 Context、不需要 MockK。
 */
class SnapshotAlignmentTest {

    /** 记录调用的假写手：验证 commit 各分支调了什么、没调什么 */
    private class FakeWriter : SnapshotAssetWriter {
        val inserted = mutableListOf<List<Asset>>()
        val balanceUpdates = mutableListOf<Pair<Long, Double>>()
        val priceUpdates = mutableListOf<Pair<Long, Double>>()

        override suspend fun insertAssets(assets: List<Asset>) {
            inserted.add(assets)
        }

        override suspend fun updateBalance(id: Long, balance: Double) {
            balanceUpdates.add(id to balance)
        }

        override suspend fun updatePriceById(id: Long, price: Double) {
            priceUpdates.add(id to price)
        }

        /** 假实现没有真数据库：直接执行即可（真仓库会开 Room 事务） */
        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
    }

    private fun raw(
        symbol: String,
        quantity: Double,
        name: String = "名称",
        rawQuantity: String? = null,
        nav: Double? = null,
        navDate: String? = null,
        currency: String? = null
    ) = RawHolding(
        symbol = symbol,
        name = name,
        quantity = quantity,
        rawQuantity = rawQuantity,
        nav = nav,
        navDate = navDate,
        currency = currency
    )

    private fun asset(
        id: Long,
        symbol: String,
        balance: Double,
        name: String = "库里名",
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

    private fun cryptoAsset(id: Long, symbol: String, balance: Double) = Asset(
        id = id,
        name = "库里名",
        type = AssetType.CRYPTO,
        symbol = symbol,
        account = "老账户",
        balance = balance,
        purchasePrice = 0.0
    )

    private fun align(
        rows: List<RawHolding>,
        existing: List<Asset> = emptyList(),
        multiAccountConflict: Boolean = true
    ) = SnapshotAlignment.align(rows, existing, multiAccountConflict) { it }

    // ---------- 同代码合并 ----------

    @Test
    fun 同代码多行合并成一条且数量相加() {
        val items = align(
            listOf(
                raw("sh510300", 1000.0, rawQuantity = "1000"),
                raw("sh510300", 2000.0, rawQuantity = "2000")
            )
        )

        assertEquals("两行该合成一条", 1, items.size)
        assertEquals(3000.0, items[0].quantity, 1e-9)
        // 原文数量用 "+" 连接，预览页要能说明「这条是两笔加起来的」
        assertEquals("1000+2000", items[0].rawQuantity)
        assertEquals(2, items[0].sources.size)
    }

    @Test
    fun 不同代码不合并且保持首次出现顺序() {
        val items = align(
            listOf(
                raw("sz159919", 262.0),
                raw("sh601988", 900.0)
            )
        )

        assertEquals(2, items.size)
        assertEquals(listOf("sz159919", "sh601988"), items.map { it.symbol })
        // 单行时原文数量就是它自己；没给 rawQuantity 时为空串
        assertEquals("", items[0].rawQuantity)
    }

    @Test
    fun 空输入返回空列表() {
        assertTrue(align(emptyList()).isEmpty())
    }

    // ---------- 三种状态 + 冲突 ----------

    @Test
    fun 库里没有的判为新增() {
        val items = align(listOf(raw("sh601988", 900.0)))

        assertEquals(ItemStatus.NEW, items[0].status)
        assertTrue(items[0].matched.isEmpty())
        assertTrue(items[0].matchedAccounts.isEmpty())
        assertNull(items[0].nav)
    }

    @Test
    fun 数量不同的判为覆盖() {
        val items = align(
            rows = listOf(raw("sh515080", 21700.0)),
            existing = listOf(asset(7L, "sh515080", 10000.0))
        )

        assertEquals(ItemStatus.UPDATE, items[0].status)
        assertEquals(1, items[0].matched.size)
    }

    @Test
    fun 数量一致的判为未变() {
        val items = align(
            rows = listOf(raw("sh515080", 21700.0)),
            existing = listOf(asset(7L, "sh515080", 21700.0))
        )

        assertEquals(ItemStatus.UNCHANGED, items[0].status)
    }

    @Test
    fun 浮点容差内判未变_超出判覆盖() {
        // 表示误差级别（1e-10）挡在容差内
        val withinTolerance = align(
            rows = listOf(raw("sh600000", 100.0)),
            existing = listOf(asset(1L, "sh600000", 100.0000000001))
        )
        assertEquals(ItemStatus.UNCHANGED, withinTolerance[0].status)

        // 真实差异（1e-3）必须判覆盖
        val beyondTolerance = align(
            rows = listOf(raw("sh600000", 100.0)),
            existing = listOf(asset(1L, "sh600000", 100.001))
        )
        assertEquals(ItemStatus.UPDATE, beyondTolerance[0].status)
    }

    @Test
    fun 加密货币第10位变动判为覆盖_浮点噪声仍判未变() {
        // 加密币数量常见 10 位小数：旧容差 1e-6 会把 1e-10 级真实变动吞成「未变」而跳过更新。
        // 库里 0.1234567890、快照 0.1234567891（差 1e-10）——必须用 1e-12 的加密专用容差判为 UPDATE
        val changed = align(
            rows = listOf(raw("BTCUSDT", 0.1234567891)),
            existing = listOf(cryptoAsset(1L, "BTCUSDT", 0.1234567890))
        )
        assertEquals(ItemStatus.UPDATE, changed[0].status)

        // 表示误差级别（1e-15）必须仍判未变，否则每次同步都误判为变动
        val noise = align(
            rows = listOf(raw("BTCUSDT", 0.1234567890)),
            existing = listOf(cryptoAsset(1L, "BTCUSDT", 0.123456789000001))
        )
        assertEquals(ItemStatus.UNCHANGED, noise[0].status)
    }

    @Test
    fun 多账户合计正好等于快照数量时判未变() {
        val items = align(
            rows = listOf(raw("sh601988", 900.0)),
            existing = listOf(
                asset(1L, "sh601988", 600.0, account = "券商A"),
                asset(2L, "sh601988", 300.0, account = "券商B")
            )
        )

        assertEquals(ItemStatus.UNCHANGED, items[0].status)
        // 命中的全部账户都要带出来，界面要告诉用户是哪几条
        assertEquals(listOf("券商A", "券商B"), items[0].matchedAccounts)
    }

    @Test
    fun 多账户合计对不上时判冲突() {
        val items = align(
            rows = listOf(raw("sh601988", 2000.0)),
            existing = listOf(
                asset(1L, "sh601988", 600.0, account = "券商A"),
                asset(2L, "sh601988", 300.0, account = "券商B")
            )
        )

        assertEquals(ItemStatus.CONFLICT, items[0].status)
        assertEquals(2, items[0].matched.size)
    }

    @Test
    fun 关闭冲突开关时多账户按合计走覆盖或未变() {
        val duplicated = listOf(
            asset(1L, "sh601988", 600.0, account = "券商A"),
            asset(2L, "sh601988", 300.0, account = "券商B")
        )

        // 币安链路：合计对不上 → 覆盖（没有冲突概念）
        val mismatch = align(
            rows = listOf(raw("sh601988", 2000.0)),
            existing = duplicated,
            multiAccountConflict = false
        )
        assertEquals(ItemStatus.UPDATE, mismatch[0].status)

        // 合计相等 → 未变
        val match = align(
            rows = listOf(raw("sh601988", 900.0)),
            existing = duplicated,
            multiAccountConflict = false
        )
        assertEquals(ItemStatus.UNCHANGED, match[0].status)
    }

    // ---------- 净值与币种的合并口径 ----------

    @Test
    fun 净值取日期最新的一条与行的先后无关() {
        val olderFirst = align(
            listOf(
                raw("fund_000001", 100.0, nav = 1.0, navDate = "2026-10-08"),
                raw("fund_000001", 100.0, nav = 1.2, navDate = "2026-10-09")
            )
        )
        assertEquals(1.2, olderFirst[0].nav!!, 1e-9)
        assertEquals("2026-10-09", olderFirst[0].navDate)

        // 新的排前面也一样按日期取，不按行的顺序
        val newerFirst = align(
            listOf(
                raw("fund_000001", 100.0, nav = 1.2, navDate = "2026-10-09"),
                raw("fund_000001", 100.0, nav = 1.0, navDate = "2026-10-08")
            )
        )
        assertEquals(1.2, newerFirst[0].nav!!, 1e-9)
    }

    @Test
    fun 没有净值时nav与navDate都为null() {
        val items = align(listOf(raw("sh601988", 900.0)))

        assertNull(items[0].nav)
        assertNull(items[0].navDate)
    }

    @Test
    fun 净值日期都缺时取第一条() {
        // 与旧实现逐行同码（maxByOrNull 取首个最大值）：日期全缺时稳定取第一行，
        // 不因为行序摇摆而改净值
        val items = align(
            listOf(
                raw("fund_000001", 100.0, nav = 1.0),
                raw("fund_000001", 100.0, nav = 2.0)
            )
        )

        assertEquals(1.0, items[0].nav!!, 1e-9)
    }

    @Test
    fun 币种一致时取该值_不一致时给null不猜() {
        val agreed = align(
            listOf(
                raw("fund_000001", 100.0, currency = "CNY"),
                raw("fund_000001", 100.0, currency = "CNY")
            )
        )
        assertEquals("CNY", agreed[0].currency)

        val conflicted = align(
            listOf(
                raw("fund_000001", 100.0, currency = "CNY"),
                raw("fund_000001", 100.0, currency = "USD")
            )
        )
        assertNull(conflicted[0].currency)
    }

    @Test
    fun 库里多条同名资产时matchedAccounts按顺序透传() {
        val items = align(
            rows = listOf(raw("sh601988", 900.0)),
            existing = listOf(
                asset(3L, "sh601988", 100.0, account = "丙账户"),
                asset(1L, "sh601988", 200.0, account = "甲账户"),
                asset(2L, "sh601988", 300.0, account = "乙账户")
            ),
            multiAccountConflict = false
        )

        assertEquals(listOf("丙账户", "甲账户", "乙账户"), items[0].matchedAccounts)
    }

    // ---------- commit ----------

    /** 记录 assetTypeOf 被问过哪些代码，验证策略真的被逐行咨询 */
    private val typedSymbols = mutableListOf<String>()

    private val policy = CommitPolicy(
        defaultAccount = "默认账户",
        assetTypeOf = { symbol ->
            typedSymbols.add(symbol)
            if (symbol.startsWith("fund_")) AssetType.FUND else AssetType.STOCK
        },
        defaultCurrency = "CNY"
    )

    private fun commit(rows: List<CommitRow>, account: String, writer: FakeWriter = FakeWriter()) =
        runBlocking { SnapshotAlignment.commit(rows, writer, account, policy) }

    private fun row(
        symbol: String,
        quantity: Double,
        status: ItemStatus,
        existing: Asset? = null,
        nav: Double? = null,
        currency: String? = null,
        name: String = "名称"
    ) = CommitRow(
        symbol = symbol,
        name = name,
        quantity = quantity,
        status = status,
        existing = existing,
        nav = nav,
        currency = currency
    )

    @Test
    fun 新建的资产字段口径正确() {
        val writer = FakeWriter()
        typedSymbols.clear()

        val result = commit(
            rows = listOf(row("fund_000001", 100.0, ItemStatus.NEW, nav = 1.2345)),
            account = "",
            writer = writer
        )

        assertEquals(1, result.inserted)
        val asset = writer.inserted.single().single()
        // 类型走策略推断：带 fund_ 前缀的是场外基金
        assertEquals(AssetType.FUND, asset.type)
        assertEquals(listOf("fund_000001"), typedSymbols)
        // 账户留空走策略默认名
        assertEquals("默认账户", asset.account)
        // 参考单价必须留 0：塞假数字会凭空造出市值
        assertEquals(0.0, asset.purchasePrice, 1e-9)
        // 净值直接写进 currentPrice：官方给的数，不用等行情同步
        assertEquals(1.2345, asset.currentPrice!!, 1e-9)
        // 没给币种时回落策略默认币种
        assertEquals("CNY", asset.currency)
    }

    @Test
    fun 账户名非空时用传入账户() {
        val writer = FakeWriter()

        commit(
            rows = listOf(row("sh601988", 900.0, ItemStatus.NEW)),
            account = "中国结算",
            writer = writer
        )

        assertEquals("中国结算", writer.inserted.single().single().account)
    }

    @Test
    fun 负数量的新资产落库为数量为负的加密货币_不抛异常() = runBlocking {
        // 回归：此前 Asset.init 的 require(balance >= 0) 会在 commit 构造 Asset 时抛
        // IllegalArgumentException，导致导入任何含借币/欠币（负余额）的钱包直接崩溃，
        // 且 Room 每次读库也走构造器，负余额一旦写入后每次读取都会崩。
        // 现在是加密货币允许负余额，必须能正常落库、数量原样保留为负。
        val writer = FakeWriter()
        val cryptoPolicy = CommitPolicy(
            defaultAccount = "币安",
            assetTypeOf = { AssetType.CRYPTO },
            defaultCurrency = "USD"
        )
        val result = SnapshotAlignment.commit(
            rows = listOf(
                CommitRow(
                    symbol = "USDTUSDT", name = "USDT", quantity = -30.0,
                    status = ItemStatus.NEW, existing = null, nav = 1.0, currency = "USD"
                )
            ),
            writer = writer,
            account = "币安",
            policy = cryptoPolicy
        )
        assertEquals(1, result.inserted)
        val asset = writer.inserted.flatten().single()
        assertEquals(AssetType.CRYPTO, asset.type)
        assertEquals(-30.0, asset.balance, 1e-9)
        // 稳态校验：负余额加密货币的资产值应为负，参与净值时自动扣减
        assertEquals(-30.0, asset.totalValue, 1e-9)
    }

    @Test
    fun 覆盖只改数量_有净值时连价格一起刷新() {
        val writer = FakeWriter()
        val existing = asset(7L, "sh515080", 10000.0)

        val result = commit(
            rows = listOf(row("sh515080", 21700.0, ItemStatus.UPDATE, existing = existing, nav = 1.5)),
            account = "中国结算",
            writer = writer
        )

        assertEquals(1, result.updated)
        assertEquals(listOf(7L to 21700.0), writer.balanceUpdates)
        // 份额变了价格也该刷新，否则用户看到「数量更新了、市值还是上周的」
        assertEquals(listOf(7L to 1.5), writer.priceUpdates)
        // 只调 updateBalance/updatePriceById：不 insert（会造重复）
        assertTrue(writer.inserted.isEmpty())
    }

    @Test
    fun 覆盖时没有existing可更新则跳过不写库() {
        val writer = FakeWriter()

        val result = commit(
            rows = listOf(row("sh515080", 21700.0, ItemStatus.UPDATE, existing = null)),
            account = "中国结算",
            writer = writer
        )

        assertEquals(0, result.updated)
        assertTrue(writer.balanceUpdates.isEmpty())
        assertTrue(writer.inserted.isEmpty())
    }

    @Test
    fun 净值非正数时不写currentPrice也不更新价格() {
        val writer = FakeWriter()

        commit(
            rows = listOf(row("fund_000001", 100.0, ItemStatus.NEW, nav = 0.0)),
            account = "",
            writer = writer
        )
        assertNull(writer.inserted.single().single().currentPrice)

        val existing = asset(7L, "fund_000001", 100.0)
        commit(
            rows = listOf(row("fund_000001", 200.0, ItemStatus.UPDATE, existing = existing, nav = -1.0)),
            account = "",
            writer = writer
        )
        assertEquals(listOf(7L to 200.0), writer.balanceUpdates)
        assertTrue(writer.priceUpdates.isEmpty())
    }

    @Test
    fun 未变跳过计数且不写库() {
        val writer = FakeWriter()

        val result = commit(
            rows = listOf(row("sh515080", 21700.0, ItemStatus.UNCHANGED)),
            account = "中国结算",
            writer = writer
        )

        assertEquals(1, result.skipped)
        assertEquals(0, result.inserted + result.updated + result.conflicts)
        assertTrue(writer.inserted.isEmpty())
        assertTrue(writer.balanceUpdates.isEmpty())
    }

    @Test
    fun 冲突计数且一条都不动() {
        val writer = FakeWriter()

        val result = commit(
            rows = listOf(row("sh601988", 2000.0, ItemStatus.CONFLICT)),
            account = "中国结算",
            writer = writer
        )

        assertEquals(1, result.conflicts)
        assertEquals(0, result.inserted + result.updated + result.skipped)
        assertTrue(writer.inserted.isEmpty())
        assertTrue(writer.balanceUpdates.isEmpty())
    }

    @Test
    fun 新增与覆盖混在一起各自走对分支() {
        val writer = FakeWriter()
        val existing = asset(7L, "sh515080", 10000.0)

        val result = commit(
            rows = listOf(
                row("sh515080", 21700.0, ItemStatus.UPDATE, existing = existing),
                row("sh601988", 900.0, ItemStatus.NEW),
                row("sh510210", 14400.0, ItemStatus.NEW),
                row("sh600000", 100.0, ItemStatus.UNCHANGED),
                row("sh601988", 2000.0, ItemStatus.CONFLICT)
            ),
            account = "中国结算",
            writer = writer
        )

        assertEquals(2, result.inserted)
        assertEquals(1, result.updated)
        assertEquals(1, result.skipped)
        assertEquals(1, result.conflicts)
        // 新增批量一次性插入，顺序即行顺序
        assertEquals(listOf("sh601988", "sh510210"), writer.inserted.single().map { it.symbol })
        assertEquals(listOf(7L to 21700.0), writer.balanceUpdates)
    }

    @Test
    fun 空结果不写库() {
        val writer = FakeWriter()

        val result = commit(rows = emptyList(), account = "中国结算", writer = writer)

        assertEquals(0, result.inserted + result.updated + result.skipped + result.conflicts)
        assertTrue(writer.inserted.isEmpty())
    }
}
