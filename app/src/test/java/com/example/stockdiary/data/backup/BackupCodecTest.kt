package com.example.stockdiary.data.backup

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetDao
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetSnapshotDetail
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.DividendDao
import com.example.stockdiary.database.FundNavCache
import com.example.stockdiary.database.FundNavCacheDao
import com.example.stockdiary.database.SnapshotDao
import com.example.stockdiary.database.StockBasic
import com.example.stockdiary.database.StockBasicDao
import com.example.stockdiary.database.StockDatabase
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 全量备份闭环的 JVM 回归。
 *
 * 分两组：
 *  - [BackupCodec] 的编解码契约（纯函数，真 org.json，由 testImplementation 提供，
 *    否则 android.jar 的 stub 会让 optXxx 返回 null、整份数据静默变空）；
 *  - [BackupManager] 的恢复事务契约。沿用 [com.example.stockdiary.data.BrokerageImportServiceTest]
 *    验证过的手法：`mockk<StockDatabase>(relaxed = true)` + 把 `transactionExecutor` 指到
 *    Unconfined，让 [androidx.room.withTransaction] 的区块在 JVM 上同步执行，
 *    再用「调用日志」精确断言「先清 7 张表、再逐表写回」。
 *
 * 本测试只编译/运行于 `testDebugUnitTest`，不含任何 Android Context、Room in-memory 或真机依赖。
 */
class BackupCodecTest {

    // ------------------------------------------------------------------
    // 代表性样本：null 字段、Boolean 真/假、Double 精度、中文/引号/换行、空列表
    // ------------------------------------------------------------------

    private fun samplePayload(): BackupPayload = BackupPayload(
        formatVersion = BACKUP_FORMAT_VERSION,
        dbVersion = 18,
        exportedAt = 1_700_000_000_123L,
        assets = listOf(
            Asset(
                id = 1L,
                name = "贵州茅台 \"A\"\n第二行",
                type = AssetType.STOCK,
                symbol = "sh600519",
                account = "默认账户",
                balance = 0.00012345,
                purchasePrice = 1688.88,
                currentPrice = -0.00001,
                currency = "CNY",
                lastSyncTime = 1_700_000_000_000L,
                lastUpdateTime = 1_700_000_001_000L,
                notes = "含中文、\"引号\"与\n换行",
                interestRate = 0.0325,
                dueDate = "2026-12-31",
                millionCopiesIncome = 1.2345,
                isEarnLocked = true
            ),
            Asset(
                id = 2L,
                name = "币安活期理财",
                type = AssetType.CRYPTO,
                symbol = null,
                account = "币安",
                balance = 100.0,
                purchasePrice = 0.0,
                currentPrice = null,
                currency = "USDT",
                lastSyncTime = 0L,
                lastUpdateTime = 0L,
                notes = null,
                interestRate = null,
                dueDate = null,
                millionCopiesIncome = null,
                isEarnLocked = false
            )
        ),
        snapshots = listOf(
            AssetSnapshot(
                id = 1L,
                timestamp = 1_700_000_000_000L,
                date = "2025-11-14",
                totalAssets = 0.00012345,
                totalLiabilities = -0.00001,
                netAssets = 123456.789,
                totalCost = 100000.0,
                totalProfit = 23456.789,
                yieldRate = 23.456,
                stockValue = 50000.0,
                fundValue = 30000.0,
                cryptoValue = 20000.0,
                bankDepositValue = 23456.789,
                stockCost = 40000.0,
                fundCost = 25000.0,
                cryptoCost = 15000.0,
                bankDepositCost = 20000.0
            )
        ),
        snapshotDetails = listOf(
            AssetSnapshotDetail(
                id = 7L,
                snapshotId = 1L,
                assetId = 1L,
                assetName = "贵州茅台 \"A\"",
                assetType = AssetType.STOCK,
                balance = 0.00012345,
                price = 1688.88,
                totalValue = 0.00012345 * 1688.88,
                cost = 1000.0,
                profit = -0.00001,
                yieldRate = 0.5
            )
        ),
        dividends = listOf(
            Dividend(
                id = 1,
                symbol = "sh600519",
                name = "贵州茅台 \"A\"",
                amount = 0.00012345,
                date = "2025-06-01",
                payDate = null,
                isManual = true
            ),
            Dividend(
                id = 2,
                symbol = "sh510300",
                name = "沪深300ETF",
                amount = 12.5,
                date = "2025-07-01",
                payDate = "2025-07-20",
                isManual = false
            )
        ),
        transactions = listOf(
            Transaction(
                id = 3L,
                symbol = "sh600519",
                name = "贵州茅台",
                type = "BUY",
                quantity = 0.00012345,
                price = 1688.88,
                date = "2025-05-01",
                fee = -0.00001
            )
        ),
        stockBasics = listOf(
            StockBasic(code = "sh600519", name = "贵州茅台", market = "sh", listDate = null, isETF = false),
            StockBasic(code = "sh510300", name = "沪深300ETF", market = "sh", listDate = "2012-05-28", isETF = true)
        ),
        fundNavCaches = listOf(
            FundNavCache(
                code = "000306",
                name = "某场外基金",
                nav = 1.2345,
                changePercent = -0.00001,
                navDate = "2025-11-14",
                millionCopiesIncome = null,
                fetchedAt = 1_700_000_000_000L
            ),
            FundNavCache(
                code = "000001",
                name = "货币基金",
                nav = 1.0,
                changePercent = null,
                navDate = "2025-11-14",
                millionCopiesIncome = 0.00012345,
                fetchedAt = 0L
            )
        )
    )

    // ------------------------------------------------------------------
    // BackupCodec 契约
    // ------------------------------------------------------------------

    @Test
    fun `往返_七张表逐字段相等`() {
        val payload = samplePayload()
        val json = BackupCodec.encode(payload)
        // 可读性/编码：JSON 文本必须是 UTF-8 可表示的普通文本，且含中文与换行转义
        assertTrue(json.contains("贵州茅台"))
        assertTrue(json.contains("formatVersion"))
        val decoded = BackupCodec.decode(json)
        // data class equals 逐字段比较（含 Double 的 bit 级相等）
        assertEquals(payload, decoded)
    }

    @Test
    fun `往返_空库七张表全空`() {
        val payload = BackupPayload(
            formatVersion = BACKUP_FORMAT_VERSION,
            dbVersion = 18,
            exportedAt = 123L
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(payload))
        assertEquals(payload, decoded)
        assertTrue(decoded.assets.isEmpty())
        assertTrue(decoded.snapshots.isEmpty())
        assertTrue(decoded.snapshotDetails.isEmpty())
        assertTrue(decoded.dividends.isEmpty())
        assertTrue(decoded.transactions.isEmpty())
        assertTrue(decoded.stockBasics.isEmpty())
        assertTrue(decoded.fundNavCaches.isEmpty())
    }

    @Test
    fun `格式版本高于当前_抛UnsupportedBackupVersionException`() {
        val json = JSONObject().apply {
            put("formatVersion", BACKUP_FORMAT_VERSION + 1)
            put("dbVersion", 18)
            put("exportedAt", 1L)
            put("assets", JSONArray())
        }.toString()
        assertThrows(UnsupportedBackupVersionException::class.java) { BackupCodec.decode(json) }
        assertThrows(UnsupportedBackupVersionException::class.java) { BackupCodec.summary(json) }
    }

    @Test
    fun `坏JSON_抛InvalidBackupException`() {
        listOf("不是 json", "{", "", "[]").forEach { bad ->
            assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(bad) }
        }
    }

    @Test
    fun `缺关键字段_抛InvalidBackupException`() {
        // 顶层缺 formatVersion：无法确认「这是不是本 App 的备份」
        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.decode("{\"assets\":[]}")
        }
        // 行内缺必填 name：不能静默记成空串、返回半份资产
        val row = JSONObject().apply {
            put("id", 1L)
            put("type", "STOCK")
            put("account", "默认账户")
        }
        val json = JSONObject().apply {
            put("formatVersion", BACKUP_FORMAT_VERSION)
            put("assets", JSONArray().put(row))
        }.toString()
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(json) }
    }

    @Test
    fun `字段类型错_抛InvalidBackupException`() {
        // id 放字符串：不能静默记 0
        val row = JSONObject().apply {
            put("id", "不是数字")
            put("name", "x")
            put("type", "STOCK")
            put("account", "a")
        }
        val json = JSONObject().apply {
            put("formatVersion", BACKUP_FORMAT_VERSION)
            put("assets", JSONArray().put(row))
        }.toString()
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(json) }

        // 表字段写成对象而非数组
        val wrongArrayType = JSONObject().apply {
            put("formatVersion", BACKUP_FORMAT_VERSION)
            put("assets", JSONObject())
        }.toString()
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(wrongArrayType) }

        // 未知枚举名不能悄悄回退成 STOCK
        val unknownEnum = JSONObject().apply {
            put("formatVersion", BACKUP_FORMAT_VERSION)
            put(
                "assets",
                JSONArray().put(JSONObject().apply {
                    put("id", 1L)
                    put("name", "x")
                    put("type", "NOT_A_TYPE")
                    put("account", "a")
                })
            )
        }.toString()
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(unknownEnum) }
    }

    @Test
    fun `未知字段忽略_缺失可空字段安全回退`() {
        val row = JSONObject().apply {
            put("id", 9L)
            put("name", "最简资产")
            put("type", "FUND")
            put("account", "默认账户")
            put("futureFieldFromV2", 12345)
        }
        val json = JSONObject().apply {
            put("formatVersion", BACKUP_FORMAT_VERSION)
            put("dbVersion", 18)
            put("exportedAt", 42L)
            put("assets", JSONArray().put(row))
        }.toString()
        val decoded = BackupCodec.decode(json)
        assertEquals(1, decoded.assets.size)
        val asset = decoded.assets.single()
        assertEquals("最简资产", asset.name)
        assertNull(asset.symbol)
        assertNull(asset.currentPrice)
        assertNull(asset.notes)
        assertEquals("CNY", asset.currency)
        assertFalse(asset.isEarnLocked)
        // 缺失的 6 张表回退成空列表，而不是抛错或半份数据
        assertTrue(decoded.snapshots.isEmpty())
        assertTrue(decoded.snapshotDetails.isEmpty())
        assertTrue(decoded.dividends.isEmpty())
        assertTrue(decoded.transactions.isEmpty())
        assertTrue(decoded.stockBasics.isEmpty())
        assertTrue(decoded.fundNavCaches.isEmpty())
    }

    @Test
    fun `summary_只读解析条数正确`() {
        val payload = samplePayload()
        val s = BackupCodec.summary(BackupCodec.encode(payload))
        assertEquals(BACKUP_FORMAT_VERSION, s.formatVersion)
        assertEquals(18, s.dbVersion)
        assertEquals(payload.exportedAt, s.exportedAt)
        assertEquals(2, s.assetCount)
        assertEquals(1, s.snapshotCount)
        assertEquals(1, s.snapshotDetailCount)
        assertEquals(2, s.dividendCount)
        assertEquals(1, s.transactionCount)
        assertEquals(2, s.stockBasicCount)
        assertEquals(2, s.fundNavCacheCount)
    }

    @Test
    fun `summary_坏文件抛InvalidBackupException`() {
        assertThrows(InvalidBackupException::class.java) { BackupCodec.summary("{") }
        assertThrows(InvalidBackupException::class.java) { BackupCodec.summary("{\"assets\":[]}") }
    }

    // ------------------------------------------------------------------
    // BackupManager 恢复事务契约
    // ------------------------------------------------------------------

    private lateinit var db: StockDatabase
    private lateinit var assetDao: AssetDao
    private lateinit var snapshotDao: SnapshotDao
    private lateinit var dividendDao: DividendDao
    private lateinit var transactionDao: TransactionDao
    private lateinit var stockBasicDao: StockBasicDao
    private lateinit var fundNavCacheDao: FundNavCacheDao
    private lateinit var manager: BackupManager
    private lateinit var calls: MutableList<String>

    @Before
    fun setUp() {
        db = mockk<StockDatabase>(relaxed = true)
        assetDao = mockk<AssetDao>(relaxed = true)
        snapshotDao = mockk<SnapshotDao>(relaxed = true)
        dividendDao = mockk<DividendDao>(relaxed = true)
        transactionDao = mockk<TransactionDao>(relaxed = true)
        stockBasicDao = mockk<StockBasicDao>(relaxed = true)
        fundNavCacheDao = mockk<FundNavCacheDao>(relaxed = true)
        calls = mutableListOf()

        every { db.assetDao() } returns assetDao
        every { db.snapshotDao() } returns snapshotDao
        every { db.dividendDao() } returns dividendDao
        every { db.transactionDao() } returns transactionDao
        every { db.stockBasicDao() } returns stockBasicDao
        every { db.fundNavCacheDao() } returns fundNavCacheDao
        // 让 withTransaction 的区块同步执行（项目内 BrokerageImportServiceTest 已验证的手法）
        every { db.transactionExecutor } returns Dispatchers.Unconfined.asExecutor()

        // 用调用日志精确记录事务内的每一步；suspend 方法一律 coEvery
        coEvery { assetDao.deleteAllAssets() } answers { calls.add("clear.assets"); Unit }
        coEvery { snapshotDao.deleteAllSnapshots() } answers { calls.add("clear.snapshots"); Unit }
        coEvery { snapshotDao.deleteAllSnapshotDetails() } answers { calls.add("clear.snapshotDetails"); Unit }
        coEvery { dividendDao.deleteAll() } answers { calls.add("clear.dividends"); Unit }
        coEvery { transactionDao.deleteAllTransactions() } answers { calls.add("clear.transactions"); Unit }
        coEvery { stockBasicDao.clearAll() } answers { calls.add("clear.stockBasics"); Unit }
        coEvery { fundNavCacheDao.clear() } answers { calls.add("clear.fundNavCaches"); Unit }
        coEvery { assetDao.insertAssets(any()) } answers { calls.add("insert.assets"); Unit }
        coEvery { snapshotDao.insertAllSnapshots(any()) } answers { calls.add("insert.snapshots"); Unit }
        coEvery { snapshotDao.insertAllSnapshotDetails(any()) } answers { calls.add("insert.snapshotDetails"); Unit }
        coEvery { dividendDao.insertAll(any()) } answers { calls.add("insert.dividends"); Unit }
        coEvery { transactionDao.insertAll(any()) } answers { calls.add("insert.transactions"); Unit }
        coEvery { stockBasicDao.insertAll(any()) } answers { calls.add("insert.stockBasics"); Unit }
        coEvery { fundNavCacheDao.upsertAll(any()) } answers { calls.add("insert.fundNavCaches"); Unit }

        manager = BackupManager(db)
    }

    @After
    fun tearDown() = unmockkAll()

    private val expectedOneRound: List<String>
        get() = listOf(
            "clear.assets",
            "clear.snapshots",
            "clear.snapshotDetails",
            "clear.dividends",
            "clear.transactions",
            "clear.stockBasics",
            "clear.fundNavCaches",
            "insert.assets",
            "insert.snapshots",
            "insert.snapshotDetails",
            "insert.dividends",
            "insert.transactions",
            "insert.stockBasics",
            "insert.fundNavCaches"
        )

    @Test
    fun `恢复契约_先清七张表再逐表写回`() = runBlocking {
        val payload = samplePayload()
        manager.restore(payload)
        assertEquals(expectedOneRound, calls)
        // 逐表写回的确实是同一份数据（不是空列表或半份）
        coVerify(exactly = 1) { assetDao.insertAssets(payload.assets) }
        coVerify(exactly = 1) { snapshotDao.insertAllSnapshots(payload.snapshots) }
        coVerify(exactly = 1) { snapshotDao.insertAllSnapshotDetails(payload.snapshotDetails) }
        coVerify(exactly = 1) { dividendDao.insertAll(payload.dividends) }
        coVerify(exactly = 1) { transactionDao.insertAll(payload.transactions) }
        coVerify(exactly = 1) { stockBasicDao.insertAll(payload.stockBasics) }
        coVerify(exactly = 1) { fundNavCacheDao.upsertAll(payload.fundNavCaches) }
    }

    @Test
    fun `恢复契约_版本过高时不进事务不写任何表`() {
        val tooNew = samplePayload().copy(formatVersion = BACKUP_FORMAT_VERSION + 1)
        assertThrows(UnsupportedBackupVersionException::class.java) {
            runBlocking { manager.restore(tooNew) }
        }
        assertTrue("事务外校验失败不得写任何表", calls.isEmpty())
        coVerify(exactly = 0) { assetDao.deleteAllAssets() }
        coVerify(exactly = 0) { assetDao.insertAssets(any()) }
        coVerify(exactly = 0) { snapshotDao.deleteAllSnapshots() }
        coVerify(exactly = 0) { snapshotDao.insertAllSnapshots(any()) }
        coVerify(exactly = 0) { fundNavCacheDao.clear() }
        coVerify(exactly = 0) { fundNavCacheDao.upsertAll(any()) }
    }

    @Test
    fun `恢复契约_非法版本不写任何表`() {
        val illegal = samplePayload().copy(formatVersion = 0)
        assertThrows(InvalidBackupException::class.java) {
            runBlocking { manager.restore(illegal) }
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `恢复契约_事务内抛错时异常向外传递`() {
        val boom = IllegalStateException("模拟写库失败")
        coEvery { dividendDao.insertAll(any()) } throws boom
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.restore(samplePayload()) }
        }
        assertEquals("模拟写库失败", thrown.message)
        // 清表与前面的写入确实发生在事务内（异常前），异常没有被 BackupManager 吞掉
        assertTrue(calls.contains("clear.assets"))
        assertTrue(calls.contains("insert.snapshots"))
        assertFalse(calls.contains("insert.dividends"))
    }

    @Test
    fun `恢复契约_同一payload恢复两次幂等`() = runBlocking {
        val payload = samplePayload()
        manager.restore(payload)
        manager.restore(payload)
        // 两轮都是「先清后写」，第二轮把第一轮写的整表替换，不产生叠加
        assertEquals(expectedOneRound + expectedOneRound, calls)
        coVerify(exactly = 2) { assetDao.insertAssets(payload.assets) }
        coVerify(exactly = 2) { fundNavCacheDao.upsertAll(payload.fundNavCaches) }
    }

    @Test
    fun `buildPayload_从七个DAO读全表`() = runBlocking {
        val expected = samplePayload()
        coEvery { assetDao.getAllAssetsSync() } returns expected.assets
        coEvery { snapshotDao.getAllSnapshotsSync() } returns expected.snapshots
        coEvery { snapshotDao.getAllSnapshotDetailsSync() } returns expected.snapshotDetails
        coEvery { dividendDao.getAllSync() } returns expected.dividends
        coEvery { transactionDao.getAllTransactionsSync() } returns expected.transactions
        coEvery { stockBasicDao.getAll() } returns expected.stockBasics
        coEvery { fundNavCacheDao.getAll() } returns expected.fundNavCaches

        val payload = manager.buildPayload()

        assertEquals(BACKUP_FORMAT_VERSION, payload.formatVersion)
        assertTrue(payload.exportedAt > 0L)
        assertEquals(expected.assets, payload.assets)
        assertEquals(expected.snapshots, payload.snapshots)
        assertEquals(expected.snapshotDetails, payload.snapshotDetails)
        assertEquals(expected.dividends, payload.dividends)
        assertEquals(expected.transactions, payload.transactions)
        assertEquals(expected.stockBasics, payload.stockBasics)
        assertEquals(expected.fundNavCaches, payload.fundNavCaches)
    }
}
