package com.example.stockdiary.data

import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.importer.estimatedValueCny
import com.example.stockdiary.data.importer.filterDustByValue
import com.example.stockdiary.data.importer.priceKeyFor
import com.example.stockdiary.data.importer.referencePriceForImport
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.BinanceAPI
import com.example.stockdiary.network.BinanceBalance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 币安持仓导入的回归。AssetRepository 是需 Context 的 final 类，用 MockK 伪造。
 *
 * 重点几条规则：dust 过滤、基础资产→交易对映射、NEW/UPDATE/UNCHANGED 判定、
 * 以及新建资产的字段口径（type=CRYPTO、currentPrice 留空、币种 USD）。
 */
class BinanceImportServiceTest {

    private val repo = mockk<AssetRepository>(relaxed = true)

    init {
        // 落库整批走事务：假仓库没有真 DB，直接执行块内容
        coEvery { repo.inTransaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>()()
        }
    }
    private val service = BinanceImportService(repo)

    private fun balance(asset: String, free: Double, locked: Double = 0.0) =
        BinanceBalance(asset = asset, free = free, locked = locked)

    /** 造一条库里已有的加密资产，symbol 用交易对形态（与落库/取价一致） */
    private fun cryptoAsset(
        id: Long,
        symbol: String,
        balance: Double,
        account: String = "币安"
    ) = Asset(
        id = id,
        name = symbol.removeSuffix("USDT"),
        type = AssetType.CRYPTO,
        symbol = symbol,
        account = account,
        balance = balance,
        purchasePrice = 0.0
    )

    // ---------- dust 过滤 ----------

    @Test
    fun dust零头被过滤掉() = runBlocking {
        val preview = service.buildPreview(
            balances = listOf(
                balance("BTC", free = 0.5),
                balance("SHIB", free = 0.0, locked = 0.0),      // 全零
                balance("DUST", free = 1e-9, locked = 1e-9)      // 低于 1e-8 阈值
            ),
            existingAssets = emptyList()
        )

        assertEquals(1, preview.items.size)
        assertEquals("BTCUSDT", preview.items[0].symbol)
    }

    @Test
    fun 刚好等于阈值的不算dust() = runBlocking {
        val preview = service.buildPreview(
            balances = listOf(balance("XRP", free = 1e-8)),
            existingAssets = emptyList()
        )
        assertEquals(1, preview.items.size)
    }

    // ---------- 三种状态 ----------

    @Test
    fun 库里没有的判为新增() = runBlocking {
        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.5)),
            existingAssets = emptyList()
        )
        assertEquals(1, preview.newItems.size)
        assertEquals(0.5, preview.newItems[0].quantity, 1e-9)
        assertEquals(1, preview.pendingCount)
    }

    @Test
    fun 数量不同的判为覆盖() = runBlocking {
        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.8)),
            existingAssets = listOf(cryptoAsset(7L, "BTCUSDT", balance = 0.5))
        )
        assertEquals(1, preview.updatedItems.size)
        assertEquals(0.8, preview.updatedItems[0].quantity, 1e-9)
        // delta 供界面提示「0.5 → 0.8」
        assertEquals(0.3, preview.updatedItems[0].delta, 1e-9)
    }

    @Test
    fun 数量一致的判为未变_不写库() = runBlocking {
        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.5)),
            existingAssets = listOf(cryptoAsset(7L, "BTCUSDT", balance = 0.5))
        )
        assertEquals(1, preview.unchangedItems.size)
        assertEquals(0, preview.pendingCount)

        val result = service.commit(preview, account = "币安")
        assertEquals(0, result.inserted)
        assertEquals(0, result.updated)
        assertEquals(1, result.skipped)
        coVerify(exactly = 0) { repo.insertAssets(any()) }
        coVerify(exactly = 0) { repo.updateBalance(any(), any()) }
    }

    @Test
    fun free加locked才是持仓量() = runBlocking {
        val preview = service.buildPreview(
            balances = listOf(balance("ETH", free = 1.0, locked = 2.0)),
            existingAssets = emptyList()
        )
        assertEquals(3.0, preview.items[0].quantity, 1e-9)
    }

    // ---------- symbol 映射与匹配 ----------

    @Test
    fun 映射成交易对后才能匹配到库里已有的加密资产() = runBlocking {
        // 币安给裸 BTC，库存形态是 BTCUSDT。映射错了就会每次导入都造重复资产
        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.8)),
            existingAssets = listOf(cryptoAsset(7L, "BTCUSDT", balance = 0.5))
        )
        assertEquals(0, preview.newItems.size)
        assertEquals(1, preview.updatedItems.size)
    }

    @Test
    fun 同名不同资产不会被错误合并() = runBlocking {
        // BTC 与 ETH 是两个不同条目，各自独立
        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 1.0), balance("ETH", free = 2.0)),
            existingAssets = emptyList()
        )
        assertEquals(2, preview.items.size)
    }

    // ---------- 落库 ----------

    @Test
    fun 新建的加密资产字段口径正确() = runBlocking {
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.5)),
            existingAssets = emptyList()
        )
        service.commit(preview, account = "")

        val asset = captured.captured.single()
        assertEquals(AssetType.CRYPTO, asset.type)
        assertEquals("BTCUSDT", asset.symbol)
        // 现价留空让后续行情同步填；参考单价 0 不凭空造市值
        assertEquals(null, asset.currentPrice)
        assertEquals(0.0, asset.purchasePrice, 1e-9)
        // 币安现货以 USDT(≈美元)计价，标 USD 才能按美元汇率折算成人民币
        assertEquals("USD", asset.currency)
        // 账户留空走默认「币安」
        assertEquals(BinanceImportService.DEFAULT_ACCOUNT, asset.account)
    }

    @Test
    fun 覆盖时只改数量_不碰账户名称币种() = runBlocking {
        val existing = cryptoAsset(7L, "BTCUSDT", balance = 0.5, account = "我的币安")
        val balanceSlot = slot<Double>()
        coEvery { repo.updateBalance(7L, capture(balanceSlot)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.8)),
            existingAssets = listOf(existing)
        )
        val result = service.commit(preview, account = "别的账户")

        assertEquals(1, result.updated)
        assertEquals(0.8, balanceSlot.captured, 1e-9)
        // 只调 updateBalance：不 insert（会造重复）、不 updateAsset（会冲掉名称/账户）
        coVerify(exactly = 0) { repo.insertAssets(any()) }
        coVerify(exactly = 0) { repo.updateAsset(any()) }
    }

    @Test
    fun 新建与覆盖混在一起各自走对分支() = runBlocking {
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(
                balance("BTC", free = 0.8),   // 库里已有 → 覆盖
                balance("ETH", free = 2.0)    // 库里没有 → 新建
            ),
            existingAssets = listOf(cryptoAsset(7L, "BTCUSDT", balance = 0.5))
        )
        val result = service.commit(preview, account = "币安")

        assertEquals(1, result.inserted)
        assertEquals(1, result.updated)
        assertEquals(listOf("ETHUSDT"), captured.captured.map { it.symbol })
    }

    @Test
    fun 账户名留空时用默认账户兜底() = runBlocking {
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.5)),
            existingAssets = emptyList()
        )
        service.commit(preview, account = "   ")
        assertEquals(BinanceImportService.DEFAULT_ACCOUNT, captured.captured[0].account)
    }

    @Test
    fun 空结果不写库() = runBlocking {
        val preview = service.buildPreview(
            balances = listOf(balance("SHIB", free = 0.0)),
            existingAssets = emptyList()
        )
        assertTrue(preview.items.isEmpty())
        val result = service.commit(preview, account = "币安")
        assertEquals(0, result.inserted + result.updated + result.skipped)
        coVerify(exactly = 0) { repo.insertAssets(any()) }
    }

    // ---------- 市值估算 estimatedValueCny ----------

    @Test
    fun 稳定币按1美元估算_无需查价() = runBlocking {
        // USDT 锚定 1 美元，不查价；100 USDT × 1.0 × 7.25(USD汇率) = 725 元
        val value = estimatedValueCny("USDT", 100.0) { null }
        assertEquals(725.0, value!!, 1e-6)
    }

    @Test
    fun 普通币按交易对价估算() = runBlocking {
        val priceOf: (String) -> Double? = { if (it == "BTCUSDT") 67000.0 else null }
        // 0.01 BTC × 67000 美元 × 7.25 = 4857.5 元
        val value = estimatedValueCny("BTC", 0.01, priceOf)
        assertEquals(4857.5, value!!, 1e-6)
    }

    @Test
    fun 估不出价返回null而不猜() = runBlocking {
        // 查不到价 → null（调用方应保留，不能猜个价把持仓滤掉）
        val value = estimatedValueCny("NEWCOIN", 1000.0) { null }
        assertEquals(null, value)
    }

    @Test
    fun 查价键_稳定币为null_其它补USDT() {
        assertEquals(null, priceKeyFor("USDT"))
        assertEquals(null, priceKeyFor("usdc"))
        assertEquals("BTCUSDT", priceKeyFor("BTC"))
        assertEquals("SOLUSDT", priceKeyFor("sol"))
    }

    // ---------- 稳定币理财壳（LDUSDT 等）口径一致 ----------

    @Test
    fun 稳定币理财壳_LDUSDT_查价键为null() {
        // LDUSDT 剥掉 LD 后底层是稳定币 USDT，没有现货交易对 → 查价键 null
        assertEquals(null, priceKeyFor("LDUSDT"))
        assertEquals(null, priceKeyFor("ldusdt"))
    }

    @Test
    fun 稳定币理财壳_LDUSDT_导入参考价按1美元计() {
        // 查价键为 null 即按定义价 1.0 美元计（priceKeyFor 统一判定，不再单独判稳定币集合），
        // 与预览 estimatedValueCny 同口径——否则落库 currentPrice 留空 → 市值恒 0
        // （用户看到的「USDT 价格默认为 0」）。
        assertEquals(1.0, referencePriceForImport("LDUSDT", emptyMap())!!, 1e-9)
        assertEquals(1.0, referencePriceForImport("ldusdc", emptyMap())!!, 1e-9)
    }

    @Test
    fun 稳定币理财壳_LDUSDT_市值按1美元估算_与导入参考价一致() {
        // 100 LDUSDT × 1.0 × 7.25(USD 汇率) = 725 元；必须与上面的参考价口径一致，
        // 否则又回到「预览 1.0、落库 0」的分裂。
        val value = estimatedValueCny("LDUSDT", 100.0) { null }
        assertEquals(725.0, value!!, 1e-6)
    }

    // ---------- 按市值过滤 filterDustByValue ----------

    @Test
    fun 低于阈值滤掉_高于阈值保留_无价保留_计数正确() = runBlocking {
        val priceOf: (String) -> Double? = {
            when (it) {
                "BTCUSDT" -> 67000.0   // 0.01 → 4857.5 元，保留
                "SHIBUSDT" -> 0.00001  // 100000 → 7.25 元，低于 10 滤掉
                else -> null           // 查不到
            }
        }
        val balances = listOf(
            balance("BTC", free = 0.01),
            balance("SHIB", free = 100000.0),
            balance("NEWCOIN", free = 500.0)   // 估不出价 → 一律保留
        )

        val (kept, filtered) = filterDustByValue(balances, priceOf, minValueCny = 10.0)

        assertEquals(1, filtered)
        assertEquals(setOf("BTC", "NEWCOIN"), kept.map { it.asset }.toSet())
    }

    @Test
    fun 取不到价时全保留_不过滤() = runBlocking {
        // 模拟 getPrices 失败：priceOf 全 null → 一个都滤不掉，绝不因取价挂掉丢持仓
        val balances = listOf(
            balance("BTC", free = 0.01),
            balance("SHIB", free = 100000.0)
        )
        val (kept, filtered) = filterDustByValue(balances, { null }, minValueCny = 10.0)

        assertEquals(0, filtered)
        assertEquals(balances.size, kept.size)
    }

    @Test
    fun 阈值为0时全部保留() = runBlocking {
        val priceOf: (String) -> Double? = { 67000.0 }
        val (kept, filtered) = filterDustByValue(
            listOf(balance("BTC", free = 0.0001)),  // 市值很小但 > 0
            priceOf,
            minValueCny = 0.0
        )
        assertEquals(0, filtered)
        assertEquals(1, kept.size)
    }

    // ---------- 导入即预填参考价 ----------

    @Test
    fun 新建时预填拉取阶段取到的价() = runBlocking {
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.5)),
            existingAssets = emptyList()
        )
        service.commit(preview, account = "币安", prices = mapOf("BTCUSDT" to 67000.0))

        val asset = captured.captured.single()
        // 现价当场就有：行情同步还没跑（或暂时连不上），总资产也已经是对的
        assertEquals(67000.0, asset.currentPrice!!, 1e-9)
        // 参考单价仍是 0（它是无行情兜底，不是成本），市值走 currentPrice
        assertEquals(0.0, asset.purchasePrice, 1e-9)
        assertEquals(33500.0, asset.totalValue, 1e-6)
    }

    @Test
    fun LD持仓落库原始LD代码_按底层价预填() = runBlocking {
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("LDBNB", free = 2.0)),
            existingAssets = emptyList()
        )
        // 取价键仍是底层交易对 BNBUSDT（与拉取阶段 priceKeyFor 生成的请求列表一致），
        // 但落库 symbol 用原始 LD 代码，避免和现货 BNB 撞去重键（见 P1）
        service.commit(preview, account = "币安", prices = mapOf("BNBUSDT" to 600.0))

        val asset = captured.captured.single()
        // 落库 symbol 是原始 LD 代码 LDBNB（不是底层交易对 BNBUSDT，否则与现货撞键被合并）；
        // name 也保留原始 LD 代码
        assertEquals("LDBNB", asset.symbol)
        assertEquals("LDBNB", asset.name)
        assertEquals(600.0, asset.currentPrice!!, 1e-9)
        assertEquals(1200.0, asset.totalValue, 1e-9)
    }

    @Test
    fun LD与现货不撞去重键_各自独立落库() = runBlocking {
        // P1 修复：LD 理财壳（LDBNB）落库用原始代码 LDBNB，而不是底层交易对 BNBUSDT，
        // 否则会和现货 BNB（symbol=BNBUSDT）撞同一个去重键、被 SnapshotAlignment 合并成一行
        val preview = service.buildPreview(
            balances = listOf(balance("LDBNB", free = 2.0), balance("BNB", free = 1.0)),
            existingAssets = emptyList()
        )
        assertEquals(2, preview.items.size)
        val symbols = preview.items.map { it.symbol }.toSet()
        assertTrue("LD 与现货必须分属不同 symbol", symbols.contains("LDBNB") && symbols.contains("BNBUSDT"))
    }

    @Test
    fun 白名单外稳定币_PYUSD_按1美元估_不再漏兜底() {
        // P2 修复：BINANCE_STABLECOINS 增补 PYUSD 等，查价键为 null → 按定义价 1.0 计，
        // 否则退化成裸码 PYUSD 去查价 → 查不到 → 市值显 0 / 价格待更新
        assertEquals(1.0, referencePriceForImport("PYUSD", emptyMap())!!, 1e-9)
        assertEquals(1.0, referencePriceForImport("pyusd", emptyMap())!!, 1e-9)
        // 100 PYUSD × 1.0 × 7.25(USD 汇率) = 725 元
        assertEquals(725.0, estimatedValueCny("PYUSD", 100.0) { null }!!, 1e-6)
    }

    @Test
    fun 命名规律兜底_白名单外USD稳定币按1美元估() {
        // Dim3 健壮性：白名单没来得及收的新稳定币（假设 NEWUSD），按「以 USD 收尾」的
        // 命名规律兜底成 1.0，不再退化成裸码查价 → 查不到 → 市值 0 / 价格待更新
        assertEquals(1.0, referencePriceForImport("NEWUSD", emptyMap())!!, 1e-9)
        assertEquals(725.0, estimatedValueCny("NEWUSD", 100.0) { null }!!, 1e-6)
        // 普通币不命中兜底：仍是查价路径，查不到返回 null（绝不猜成 1.0）
        assertEquals(null, referencePriceForImport("NEWCOIN", emptyMap()))
    }

    @Test
    fun isLikelyStablecoin_白名单与命名规律都识别() {
        // 白名单命中
        assertTrue(BinanceAPI.isLikelyStablecoin("USDT"))
        assertTrue(BinanceAPI.isLikelyStablecoin("PYUSD"))
        assertTrue(BinanceAPI.isLikelyStablecoin("USDG"))
        // 命名规律（小写也识别）
        assertTrue(BinanceAPI.isLikelyStablecoin("newusd"))
        // 非稳定币不命中：真实现货、理财壳走各自查价路径
        assertFalse(BinanceAPI.isLikelyStablecoin("BTC"))
        assertFalse(BinanceAPI.isLikelyStablecoin("NEWCOIN"))
        assertFalse(BinanceAPI.isLikelyStablecoin("LDBNB"))
        assertFalse(BinanceAPI.isLikelyStablecoin("LDO"))
    }

    @Test
    fun 覆盖时同样刷新现价() = runBlocking {
        val existing = cryptoAsset(7L, "BTCUSDT", balance = 0.5)
        val priceSlot = slot<Double>()
        coEvery { repo.updatePriceById(7L, capture(priceSlot)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("BTC", free = 0.8)),
            existingAssets = listOf(existing)
        )
        service.commit(preview, account = "币安", prices = mapOf("BTCUSDT" to 67000.0))

        assertEquals(67000.0, priceSlot.captured, 1e-9)
    }

    @Test
    fun 查不到价不编造_留空等行情同步() = runBlocking {
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("NEWCOIN", free = 1000.0)),
            existingAssets = emptyList()
        )
        // 取价失败降级为空 map：绝不写 0 以外的假数据，也不让导入失败
        service.commit(preview, account = "币安", prices = emptyMap())

        val asset = captured.captured.single()
        assertEquals(null, asset.currentPrice)
        assertEquals(0.0, asset.purchasePrice, 1e-9)
    }

    @Test
    fun 稳定币按1美元预填() = runBlocking {
        val captured = slot<List<Asset>>()
        coEvery { repo.insertAssets(capture(captured)) } returns Unit

        val preview = service.buildPreview(
            balances = listOf(balance("USDT", free = 100.0)),
            existingAssets = emptyList()
        )
        // 稳定币没有交易对可查但锚定 1 美元——不预填的话它的市值在同步前是 0
        service.commit(preview, account = "币安", prices = emptyMap())

        val asset = captured.captured.single()
        assertEquals(1.0, asset.currentPrice!!, 1e-9)
        assertEquals(100.0, asset.totalValue, 1e-9)
    }

    @Test
    fun 预填价拒绝非正数_按查不到价处理() {
        // getPrices 理论上只回正价，这里防御性钉住：0 / 负 / NaN 一律当查不到价
        assertEquals(null, referencePriceForImport("BTC", mapOf("BTCUSDT" to 0.0)))
        assertEquals(null, referencePriceForImport("BTC", mapOf("BTCUSDT" to -1.0)))
        assertEquals(null, referencePriceForImport("BTC", mapOf("BTCUSDT" to Double.NaN)))
        assertEquals(67000.0, referencePriceForImport("BTC", mapOf("BTCUSDT" to 67000.0))!!, 1e-9)
        // LD 走底层键；LDO 是真实现货走自己的交易对；LDUSDT 底层是稳定币 → 按 1.0 美元计
        // （修复前这里返回 null，导致落库 currentPrice 留空→市值恒 0，即「USDT 价格默认为 0」）
        assertEquals(600.0, referencePriceForImport("LDBNB", mapOf("BNBUSDT" to 600.0))!!, 1e-9)
        assertEquals(2.5, referencePriceForImport("LDO", mapOf("LDOUSDT" to 2.5))!!, 1e-9)
        assertEquals(1.0, referencePriceForImport("LDUSDT", emptyMap())!!, 1e-9)
        // 稳定币没有键也是 1.0（定义价，与行情同步同口径）
        assertEquals(1.0, referencePriceForImport("USDT", emptyMap())!!, 1e-9)
        // 冷门币查不到 → null
        assertEquals(null, referencePriceForImport("NEWCOIN", emptyMap()))
    }
}
