package com.example.stockdiary.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.stockdiary.utils.AppLogger

@Database(
    entities = [
        Asset::class,
        AssetSnapshot::class,
        AssetSnapshotDetail::class,
        Dividend::class,
        Transaction::class,
        StockBasic::class
    ],
    version = 15,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun dividendDao(): DividendDao
    abstract fun transactionDao(): TransactionDao
    abstract fun stockBasicDao(): StockBasicDao
    
    companion object {
        @Volatile
        private var INSTANCE: StockDatabase? = null
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 兜底旧版本，无需实际变更
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stock_basic ADD COLUMN isETF INTEGER DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE portfolio ADD COLUMN account TEXT DEFAULT '默认账户'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_symbol ON assets(symbol)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_type ON assets(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_account ON assets(account)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_currency ON assets(currency)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_snapshots_date ON asset_snapshots(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_snapshots_timestamp ON asset_snapshots(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_snapshot_details_snapshotId ON asset_snapshot_details(snapshotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_snapshot_details_assetId ON asset_snapshot_details(assetId)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 空迁移，版本对齐
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN totalCost REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN totalProfit REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN yieldRate REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN stockCost REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN fundCost REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN cryptoCost REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN bankDepositCost REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshot_details ADD COLUMN cost REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshot_details ADD COLUMN profit REAL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshot_details ADD COLUMN yieldRate REAL DEFAULT 0.0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='portfolio'")
                    .use { cursor ->
                        if (cursor.moveToFirst()) {
                            db.execSQL(
                                """
                                INSERT OR IGNORE INTO assets (name, type, symbol, account, balance, purchasePrice, currentPrice, currency, lastSyncTime, lastUpdateTime)
                                SELECT
                                    p.name,
                                    CASE
                                        WHEN substr(p.symbol, 1, 2) = 'sh' AND (substr(p.symbol, 3, 3) IN ('510','511','512','513','515','516','517','518','588') OR substr(p.symbol, 3, 3) = '159') THEN 'FUND'
                                        WHEN substr(p.symbol, 1, 2) = 'sz' AND substr(p.symbol, 3, 3) = '159' THEN 'FUND'
                                        WHEN substr(p.symbol, 1, 2) = 'bj' AND (substr(p.symbol, 3, 1) = '8' OR substr(p.symbol, 3, 1) = '4') THEN 'FUND'
                                        ELSE 'STOCK'
                                    END,
                                    p.symbol,
                                    COALESCE(p.account, '默认账户'),
                                    CAST(p.quantity AS REAL),
                                    p.purchasePrice,
                                    p.currentPrice,
                                    'CNY',
                                    0,
                                    0
                                FROM portfolio p
                                WHERE p.symbol NOT IN (SELECT symbol FROM assets WHERE symbol IS NOT NULL)
                                """
                            )
                            AppLogger.d("Portfolio 数据迁移到 Asset 完成", "StockDatabase")
                        }
                    }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dividend_symbol ON dividend (symbol)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 清理分红表中 symbol + date 的重复记录，保留 id 最小的一条
                db.execSQL(
                    """
                    DELETE FROM dividend
                    WHERE EXISTS (
                        SELECT 1 FROM dividend d2
                        WHERE d2.symbol = dividend.symbol
                          AND d2.date = dividend.date
                          AND d2.id < dividend.id
                    )
                    """.trimIndent()
                )
                // 创建唯一索引防止未来重复
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dividend_symbol_date ON dividend (symbol, date)")
                AppLogger.d("分红表唯一索引迁移完成", "StockDatabase")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 新增 isManual 字段，标记用户手动添加/修正的分红记录，全量刷新时保留
                db.execSQL("ALTER TABLE dividend ADD COLUMN isManual INTEGER NOT NULL DEFAULT 0")
                AppLogger.d("分红表 isManual 字段迁移完成", "StockDatabase")
            }
        }

        /**
         * 删除废弃的 portfolio 表。
         *
         * 这张表在 MIGRATION_8_9 里已经把数据全量并进 assets，之后代码零引用，
         * 留着只是让 Room schema 里挂一张没人用的表。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS portfolio")
                AppLogger.d("废弃的 portfolio 表已删除", "StockDatabase")
            }
        }

        /**
         * 分红表新增 payDate（分红发放日）。
         *
         * 除息日只说明「你有没有资格拿」，钱真正到账往往还要等 1~4 周。
         * 以前 App 把除息日当成到账日，日历和通知都会提前报。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dividend ADD COLUMN payDate TEXT")
                AppLogger.d("分红表 payDate 字段迁移完成", "StockDatabase")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assets ADD COLUMN millionCopiesIncome REAL")
                AppLogger.d("资产表 millionCopiesIncome 字段迁移完成", "StockDatabase")
            }
        }

        @JvmStatic
        fun getInstance(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    "stock_diary_db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}