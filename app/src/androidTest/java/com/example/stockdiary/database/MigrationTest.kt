package com.example.stockdiary.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * 数据库迁移端到端校验。
 *
 * 设计取舍：
 * - 用 [MigrationTestHelper.createDatabase] 按 1.json 的 schema 建一个 v1 库（同时校验 v1 自身
 *   的 schema 与 1.json 一致），再交给真实的迁移链升到 v17。
 * - 这里**不**用 `runMigrationsAndValidate` 的严格 JSON 校验：历史上 5~15.json 永久缺失，
 *   无法为整个区间提供 JSON 基准。改为自建 PRAGMA 校验，聚焦真正会崩的点：早期迁移漏建的
 *   表 / 列、应删除的旧表、列类型是否对齐、以及数据是否随迁移保留。
 * - `transactions.quantity` 的类型分歧（v1 INTEGER / 17.json REAL）已由 MIGRATION_16_17 修掉，
 *   所以这里可以额外断言它是 REAL；在此之前只能刻意回避这条校验。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"
    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            StockDatabase::class.java
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To17_createsMissingTablesPreservesData() {
        // 1) 建一个 v1 库并写入两条记录：dividend 在 v1 已存在（验证数据随迁移保留），
        //    transactions 也在 v1 存在（验证 16_17 的改表没把数据弄丢）
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO dividend (id, symbol, name, amount, date) " +
                    "VALUES (1, 'sh600519', '贵州茅台', 10.0, '2024-06-01')"
            )
            execSQL(
                "INSERT INTO transactions (id, symbol, name, type, quantity, price, date, fee) " +
                    "VALUES (1, 'sh600519', '贵州茅台', 'BUY', 100, 1800.0, '2024-06-01', 5.0)"
            )
            close()
        }

        // 2) 用真实迁移链从 v1 升到 v17
        val db: SupportSQLiteDatabase = Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                StockDatabase::class.java,
                TEST_DB
            )
            .addMigrations(*StockDatabase.allMigrations())
            .build()
            .openHelper
            .writableDatabase

        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            val list = mutableListOf<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            list
        }

        // 3) 本次修复前根本不存在、会导致老用户升级直接崩的表，必须出现
        for (t in listOf(
            "assets",
            "asset_snapshots",
            "asset_snapshot_details",
            "stock_basic",
            "fund_nav_cache",
            "dividend",
            "transactions"
        )) {
            assertTrue("迁移后缺少表 $t", t in tables)
        }

        // 4) 旧表 portfolio 应在 12_13 迁移里被删除
        assertFalse("portfolio 应已被删除", "portfolio" in tables)

        // 5) 早期迁移漏建、本次补上的关键列必须存在
        assertColumnExists(db, "assets", "symbol")
        assertColumnExists(db, "stock_basic", "isETF")
        assertColumnExists(db, "asset_snapshots", "totalCost")
        assertColumnExists(db, "asset_snapshot_details", "cost")

        // 6) 新表 fund_nav_cache 已按 15_16 建好
        assertColumnExists(db, "fund_nav_cache", "nav")

        // 7) 16_17 把 transactions.quantity 的声明类型从 INTEGER 改成了 REAL（与实体 Double 对齐），
        //    这是全库唯一一处历史类型分歧。改表后 Room 的 schema 校验才能通过。
        assertEquals("REAL", columnType(db, "transactions", "quantity"))

        // 8) 数据随迁移保留（含 16_17 改表搬过去的那条 transactions）
        val dividendCount = db.query(
            "SELECT COUNT(*) FROM dividend WHERE symbol='sh600519'"
        ).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        assertEquals(1, dividendCount)

        val quantity = db.query(
            "SELECT quantity FROM transactions WHERE id=1"
        ).use { c ->
            c.moveToFirst()
            c.getDouble(0)
        }
        assertEquals(100.0, quantity, 0.0)

        db.close()
    }

    /** 返回该列在 PRAGMA table_info 里的声明类型（如 `REAL` / `INTEGER` / `TEXT`）。 */
    private fun columnType(db: SupportSQLiteDatabase, table: String, column: String): String? {
        db.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == column) return c.getString(2)
            }
        }
        return null
    }

    private fun assertColumnExists(
        db: SupportSQLiteDatabase,
        table: String,
        column: String
    ) {
        val exists = db.query("PRAGMA table_info($table)").use { c ->
            var found = false
            while (c.moveToNext()) {
                if (c.getString(1) == column) found = true
            }
            found
        }
        assertTrue("表 $table 缺少列 $column", exists)
    }
}
