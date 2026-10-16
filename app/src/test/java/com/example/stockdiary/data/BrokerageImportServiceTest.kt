package com.example.stockdiary.data

import android.content.Context
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetDao
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionDao
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.utils.StockCodeManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

/**
 * 交割单导入服务 [BrokerageImportService] 的回归。
 *
 * 策略：沿用项目惯例，直接测纯函数 / 内部方法，不为了测而引入 DI 或 Room in-memory。
 * 数据库访问通过 `mockkObject(StockDatabase)` 把 `getInstance` 换成 relaxed mock，
 * DAO 调用全部由 MockK 接管；这样能确定性地验证：
 *  - 加权成本随多次买入累积（[applyBuy] / [BrokerageImportService.commitTransaction] 回放）
 *  - 卖出减仓 / 卖光清仓（[applySell]）
 *  - commit 去重（同标的+同方向+同数量+同价格+同日期 → 跳过）与异常行/用户跳过计数
 *  - 编码 / BOM 探测（[BrokerageImportService.detectCharset]）
 *
 * `[commit]` 内部用 `withTransaction` 包裹单笔处理；这里把 mock 库的 `transactionExecutor`
 * 指到 `Dispatchers.Unconfined.asExecutor()`，让事务块同步执行，从而能在 JVM 单测里跑通真实
 * 的 commit 路径（不依赖真机 / 真 Room）。
 */
class BrokerageImportServiceTest {

    private val ctx = mockk<Context>(relaxed = true)
    private val mockDb = mockk<StockDatabase>(relaxed = true)
    private val assetDao = mockk<AssetDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val stockDataManager = mockk<com.example.stockdiary.data.StockDataManager>(relaxed = true)
    private lateinit var service: BrokerageImportService

    @Before
    fun setUp() {
        every { ctx.applicationContext } returns ctx
        mockkObject(StockDatabase)
        every { StockDatabase.getInstance(any()) } returns mockDb
        every { mockDb.assetDao() } returns assetDao
        every { mockDb.transactionDao() } returns txDao
        mockkObject(StockDataManager)
        every { StockDataManager.getInstance(any()) } returns stockDataManager
        // 让 withTransaction 的区块同步执行，便于在 JVM 单测里跑通真实 commit 路径
        every { mockDb.transactionExecutor } returns Dispatchers.Unconfined.asExecutor()
        service = BrokerageImportService(ctx)
    }

    @After
    fun tearDown() = unmockkAll()

    // ---------- 构造辅助 ----------

    private fun tx(
        date: String = "2024-01-01",
        symbol: String = "sh600519",
        name: String = "贵州茅台",
        type: String = TransactionType.BUY.value,
        quantity: Double = 100.0,
        price: Double = 10.0,
        fee: Double = 0.0,
        account: String = "默认账户",
        rowNumber: Int = 1,
        errors: List<String> = emptyList(),
        isDuplicate: Boolean = false,
        skip: Boolean = false
    ) = BrokerageImportService.ParsedTransaction(
        date, symbol, name, type, quantity, price, fee, account, rowNumber, errors, isDuplicate, skip
    )

    /** 库里已存在的一条同名交易，用于让 findDuplicate 命中（Transaction 必填字段较多，统一在这里构造） */
    private fun duplicateTx() = Transaction(
        symbol = "sh600519",
        name = "贵州茅台",
        type = TransactionType.BUY.value,
        quantity = 100.0,
        price = 10.0,
        date = "2024-01-01"
    )

    // ============================================================
    // 纯函数：列映射 / CSV / 买卖方向 / 日期 / 代码归一化 / 编码探测
    // ============================================================

    @Test
    fun `guessColumnMapping 自动识别中英文列`() {
        val headers = listOf("证券代码", "证券名称", "成交日期", "买卖", "成交数量", "成交价格", "手续费", "资金账号")
        val m = service.guessColumnMapping(headers)
        assertEquals(0, m.symbolCol)
        assertEquals(1, m.nameCol)
        assertEquals(2, m.dateCol)
        assertEquals(3, m.typeCol)
        assertEquals(4, m.quantityCol)
        assertEquals(5, m.priceCol)
        assertEquals(6, m.feeCol)
        assertEquals(7, m.accountCol)
    }

    @Test
    fun `parseCsvText 支持引号包裹字段_转义引号_引号内逗号`() {
        val csv = "a,b,c\n1,2,3\n\"x,y\",\"q\"\"z\"\n"
        val parsed = service.parseCsvText(csv)
        assertEquals(listOf("a", "b", "c"), parsed.headers)
        // 第三行：第一个字段 "x,y"（含逗号），第二个字段 "q""z" → 转义成 q"z
        val row = parsed.rows.last()
        assertEquals("x,y", row[0])
        assertEquals("q\"z", row[1])
    }

    @Test
    fun `parseType 识别买与卖的各种写法`() {
        assertEquals(TransactionType.BUY.value, service.parseType("买入"))
        assertEquals(TransactionType.BUY.value, service.parseType("买"))
        assertEquals(TransactionType.BUY.value, service.parseType("B"))
        assertEquals(TransactionType.BUY.value, service.parseType("BUY"))
        assertEquals(TransactionType.SELL.value, service.parseType("卖出"))
        assertEquals(TransactionType.SELL.value, service.parseType("卖"))
        assertEquals(TransactionType.SELL.value, service.parseType("S"))
        assertEquals(TransactionType.SELL.value, service.parseType("SELL"))
        assertNull(service.parseType("分红"))
        assertNull(service.parseType(""))
    }

    @Test
    fun `parseDate 归一化为 yyyy-MM-dd`() {
        assertEquals("2024-01-05", service.parseDate("2024-01-05"))
        assertEquals("2024-01-05", service.parseDate("2024/01/05"))
        assertEquals("2024-01-05", service.parseDate("2024.01.05"))
        assertEquals("2024-01-05", service.parseDate("20240105"))
        assertEquals("2024-01-05", service.parseDate("2024年1月5日"))
        assertEquals("2024-01-05", service.parseDate("2024-01-05 09:30:00"))
        assertNull(service.parseDate("乱码一串"))
    }

    @Test
    fun `normalizedSymbol 裸码补齐市场前缀_空串保持空`() {
        assertEquals("sh600519", service.normalizedSymbol("600519"))
        assertEquals("sz000858", service.normalizedSymbol("000858"))
        assertEquals("", service.normalizedSymbol(""))
        assertEquals(StockCodeManager.normalizeSymbol("600519"), service.normalizedSymbol("600519"))
    }

    @Test
    fun `detectCharset 带 BOM 的 UTF-8 正确探测`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41, 0x42)
        assertEquals(Charset.forName("UTF-8"), service.detectCharset(bom))
    }

    @Test
    fun `detectCharset 带 BOM 的 UTF-16LE 正确探测`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00)
        assertEquals(Charset.forName("UTF-16LE"), service.detectCharset(bom))
    }

    @Test
    fun `detectCharset 无 BOM 的合法 UTF-8 直接判 UTF-8`() {
        val bytes = "hello中文".toByteArray(Charset.forName("UTF-8"))
        assertEquals(Charset.forName("UTF-8"), service.detectCharset(bytes))
    }

    @Test
    fun `detectCharset 无 BOM 的 GBK 字节回退 GBK`() {
        // 0xC4 0xE3 是 GBK 的「你」，对 UTF-8 严格解码会失败 → 回退 GBK
        val gbk = byteArrayOf(0xC4.toByte(), 0xE3.toByte())
        assertEquals(Charset.forName("GBK"), service.detectCharset(gbk))
    }

    // ============================================================
    // applyBuy：加权成本累积
    // ============================================================

    @Test
    fun `applyBuy 新建资产时手续费摊入参考单价`() = runBlocking {
        coEvery { assetDao.getAssetBySymbolAndAccount(any(), any()) } returns null
        val slot = io.mockk.slot<Asset>()
        coEvery { assetDao.insertAsset(capture(slot)) } returns 1L

        // 100 股 @10 元，手续费 5 元 → 成本价 = 10 + 5/100 = 10.05
        service.applyBuy(tx(quantity = 100.0, price = 10.0, fee = 5.0), now = 1L)

        val a = slot.captured
        assertEquals("sh600519", a.symbol)
        assertEquals(100.0, a.balance, 1e-9)
        assertEquals(10.05, a.purchasePrice, 1e-9)
    }

    @Test
    fun `applyBuy 多次买入加权成本正确累积`() = runBlocking {
        val current = AtomicReference<Asset?>(
            Asset(id = 1L, name = "贵州茅台", type = AssetType.STOCK, symbol = "sh600519",
                account = "默认账户", balance = 100.0, purchasePrice = 10.0, lastUpdateTime = 0L)
        )
        coEvery { assetDao.getAssetBySymbolAndAccount(any(), any()) } coAnswers { current.get() }
        coEvery { assetDao.updateBalanceAndPrice(any(), any(), any(), any()) } coAnswers {
            current.set(current.get()!!.copy(balance = secondArg(), purchasePrice = thirdArg()))
            Unit
        }

        // 已有 100 股 @10；再买 100 股 @10 → 成本 (100*10 + 100*10)/200 = 10
        service.applyBuy(tx(quantity = 100.0, price = 10.0, fee = 0.0), now = 1L)
        // 再买 100 股 @20 含手续费 10 → 成本 (200*10 + 100*20 + 10)/300 = 4010/300 ≈ 13.3667
        service.applyBuy(tx(quantity = 100.0, price = 20.0, fee = 10.0), now = 2L)

        assertEquals(300.0, current.get()!!.balance, 1e-9)
        assertEquals(4010.0 / 300.0, current.get()!!.purchasePrice, 1e-9)
    }

    // ============================================================
    // applySell：减仓 / 清仓
    // ============================================================

    @Test
    fun `applySell 部分卖出只减仓不清仓`() = runBlocking {
        val current = AtomicReference<Asset?>(
            Asset(id = 1L, name = "贵州茅台", type = AssetType.STOCK, symbol = "sh600519",
                account = "默认账户", balance = 100.0, purchasePrice = 10.0, lastUpdateTime = 0L)
        )
        coEvery { assetDao.getAssetBySymbolAndAccount(any(), any()) } coAnswers { current.get() }
        val balSlot = io.mockk.slot<Double>()
        coEvery { assetDao.updateBalance(any(), capture(balSlot), any()) } returns Unit

        service.applySell(tx(type = TransactionType.SELL.value, quantity = 30.0), now = 1L)

        assertEquals(70.0, balSlot.captured, 1e-9)
        coVerify(exactly = 0) { assetDao.deleteAsset(any()) }
    }

    @Test
    fun `applySell 卖光等于持仓时清仓`() = runBlocking {
        val current = AtomicReference<Asset?>(
            Asset(id = 1L, name = "贵州茅台", type = AssetType.STOCK, symbol = "sh600519",
                account = "默认账户", balance = 100.0, purchasePrice = 10.0, lastUpdateTime = 0L)
        )
        coEvery { assetDao.getAssetBySymbolAndAccount(any(), any()) } coAnswers { current.get() }
        coEvery { assetDao.deleteAsset(any()) } returns Unit

        service.applySell(tx(type = TransactionType.SELL.value, quantity = 100.0), now = 1L)

        coVerify(exactly = 1) { assetDao.deleteAsset(any()) }
        coVerify(exactly = 0) { assetDao.updateBalance(any(), any(), any()) }
    }

    @Test
    fun `applySell 无持仓时静默跳过不崩`() = runBlocking {
        coEvery { assetDao.getAssetBySymbolAndAccount(any(), any()) } returns null

        service.applySell(tx(type = TransactionType.SELL.value, quantity = 50.0), now = 1L)

        coVerify(exactly = 0) { assetDao.deleteAsset(any()) }
        coVerify(exactly = 0) { assetDao.updateBalance(any(), any(), any()) }
    }

    // ============================================================
    // commitTransaction：去重 + 写流水
    // ============================================================

    @Test
    fun `commitTransaction 库内已存在相同记录时去重跳过`() = runBlocking {
        coEvery { txDao.findDuplicate(any(), any(), any(), any(), any()) } returns duplicateTx()
        val r = service.commitTransaction(tx())

        assertFalse("命中重复应返回 false（跳过）", r)
        coVerify(exactly = 0) { assetDao.insertAsset(any()) }
        coVerify(exactly = 0) { assetDao.updateBalanceAndPrice(any(), any(), any(), any()) }
        coVerify(exactly = 0) { txDao.insert(any()) }
    }

    @Test
    fun `commitTransaction 无重复时写持仓与流水`() = runBlocking {
        coEvery { txDao.findDuplicate(any(), any(), any(), any(), any()) } returns null
        coEvery { txDao.insert(any()) } returns 1L
        coEvery { assetDao.insertAsset(any()) } returns 1L
        coEvery { assetDao.getAssetBySymbolAndAccount(any(), any()) } returns null

        val r = service.commitTransaction(tx())

        assertTrue("无重复应返回 true（写入）", r)
        coVerify(exactly = 1) { txDao.insert(any()) }
    }

    // ============================================================
    // commit 端到端：按日期升序回放 + 计数器
    // ============================================================

    private fun installStatefulAsset(): AtomicReference<Asset?> {
        val current = AtomicReference<Asset?>(null)
        coEvery { assetDao.getAssetBySymbolAndAccount(any(), any()) } coAnswers { current.get() }
        coEvery { assetDao.insertAsset(any()) } coAnswers {
            val a = firstArg<Asset>()
            current.set(a.copy(id = 1L))
            1L
        }
        coEvery { assetDao.updateBalanceAndPrice(any(), any(), any(), any()) } coAnswers {
            current.set(current.get()!!.copy(balance = secondArg(), purchasePrice = thirdArg()))
            Unit
        }
        coEvery { assetDao.updateBalance(any(), any(), any()) } coAnswers {
            current.set(current.get()!!.copy(balance = secondArg()))
            Unit
        }
        coEvery { assetDao.deleteAsset(any()) } coAnswers { current.set(null) }
        coEvery { txDao.insert(any()) } returns 1L
        coEvery { txDao.findDuplicate(any(), any(), any(), any(), any()) } returns null
        return current
    }

    @Test
    fun `commit 多次买入按日期升序回放_加权成本正确累积`() = runBlocking {
        val current = installStatefulAsset()

        val tx1 = tx(date = "2024-01-01", type = TransactionType.BUY.value, quantity = 100.0, price = 10.0, fee = 0.0)
        val tx2 = tx(date = "2024-01-02", type = TransactionType.BUY.value, quantity = 100.0, price = 20.0, fee = 0.0)

        val result = service.commit(listOf(tx2, tx1)) // 故意乱序传入，commit 内部会按日期排序

        assertEquals(2, result.successCount)
        assertEquals(200.0, current.get()!!.balance, 1e-9)
        // ((100*10) + (100*20)) / 200 = 15
        assertEquals(15.0, current.get()!!.purchasePrice, 1e-9)
    }

    @Test
    fun `commit 异常行与用户跳过分别计数_去重行单独计数`() = runBlocking {
        installStatefulAsset()
        // 仅对去重行（数量 999）让 findDuplicate 命中
        coEvery { txDao.findDuplicate(any(), any(), any(), any(), any()) } coAnswers {
            if (thirdArg<Double>() == 999.0) duplicateTx() else null
        }

        val buy = tx(date = "2024-01-01", type = TransactionType.BUY.value, quantity = 10.0, price = 1.0)
        val sell = tx(date = "2024-01-02", type = TransactionType.SELL.value, quantity = 5.0, price = 1.0)
        val err = tx(date = "2024-01-03", type = TransactionType.BUY.value, quantity = 1.0, price = 1.0, errors = listOf("日期无法解析"))
        val skip = tx(date = "2024-01-04", type = TransactionType.BUY.value, quantity = 1.0, price = 1.0, skip = true)
        val dup = tx(date = "2024-01-05", type = TransactionType.BUY.value, quantity = 999.0, price = 1.0)

        val result = service.commit(listOf(buy, sell, err, skip, dup))

        assertEquals(2, result.successCount)     // buy + sell 成功写入
        assertEquals(1, result.skippedDuplicate)   // dup 命中库内重复
        assertEquals(1, result.skippedByUser)      // skip 行由用户取消
        assertEquals(1, result.skippedError)     // err 行带异常被自动跳过
        assertEquals(0, result.failedCount)
    }
}
