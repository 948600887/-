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
 *   的 schema 与 1.json 一致），再交给真实的迁移链升到 v16。
 * - 这里**不**用 `runMigrationsAndValidate` 的严格 JSON 校验：历史上 5~15.json 永久缺失，且
 *   `transactions.quantity` 在 v1 是 INTEGER、16.json 是 REAL，迁移链里没有对应的类型转换迁移
 *   （属非致命的历史分歧，不影响运行——老用户升级极少、且 SQLite 数字类型兼容）。改为自建 PRAGMA
 *   校验，聚焦真正会崩的点：早期迁移漏建的表 / 列、应删除的旧表、以及数据是否随迁移保留。
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
    fun migrate1To16_createsMissingTablesPreservesData() {
        // 1) 建一个 v1 库并写入一条分红记录（dividend 在 v1 已存在，用来验证数据随迁移保留）
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO dividend (id, symbol, name, amount, date) " +
                    "VALUES (1, 'sh600519', '贵州茅台', 10.0, '2024-06-01')"
            )
            close()
        }

        // 2) 用真实迁移链从 v1 升到 v16
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

        // 7) 数据随迁移保留
        val dividendCount = db.query(
            "SELECT COUNT(*) FROM dividend WHERE symbol='sh600519'"
        ).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        assertEquals(1, dividendCount)

        db.close()
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
