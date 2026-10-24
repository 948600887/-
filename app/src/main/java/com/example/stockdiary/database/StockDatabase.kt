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
        StockBasic::class,
        FundNavCache::class
    ],
    version = 17,
    // 必须开着：项目铁律是「迁移 SQL 取自 Room 自己导出的 JSON，不手敲」。
    // 当前导出的 schema JSON 只有 1/2/3/4/16/17 —— **5~15.json 永久缺失**：
    // 那段时间 exportSchema 是关的，kapt 一次都没生成过，git 历史里也查无此文件
    // （`git log --all` 确认 5~15.json 从未被提交过），因此无法从仓库恢复。
    // 缺失不会让 App 崩溃——本库**不**靠 schema JSON 做运行时校验，而是靠手写、逐列对齐的
    // 代码迁移（MIGRATION_1_2 … MIGRATION_16_17 全部存在，且无 fallbackToDestructiveMigration，
    // 漏一条老用户升级就会崩）。真正的坑在**测试侧**：若引入 Room 的 MigrationTestHelper
    // 做区间 schema 一致性校验，校验 4→17 会因为没有 5~15.json 基准而失败，届时须先 reconstruct
    // 那些版本（checkout 对应 tag 跑 kapt）或显式跳过该区间。
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun dividendDao(): DividendDao
    abstract fun transactionDao(): TransactionDao
    abstract fun stockBasicDao(): StockBasicDao
    abstract fun fundNavCacheDao(): FundNavCacheDao
    
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
                // 历史上 stock_basic 在 v2 之前就存在（1.json 里没有，但 2_3 已经在 ALTER 它），
                // 可早期迁移链里**从未建过这张表**——所有 1~15 迁移里没有任何 CREATE stock_basic，
                // 导致 v1 用户升级到 v3 时在 `ALTER TABLE stock_basic` 这一步直接崩（no such table）。
                // 这里用最终 schema 的「基础列（去掉后续 ALTER 才加的 isETF）」补建，IF NOT EXISTS
                // 保证已经在 v2/v3 真机上升级过的用户不会重复建表，也不会与 2_3 原 ALTER 冲突。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stock_basic` (" +
                        "`code` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`market` TEXT NOT NULL, " +
                        "`listDate` TEXT, " +
                        "PRIMARY KEY(`code`))"
                )
                db.execSQL("ALTER TABLE stock_basic ADD COLUMN isETF INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE portfolio ADD COLUMN account TEXT DEFAULT '默认账户'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // assets 表在早期迁移链里从未被 CREATE（v1/v4 的 schema JSON 里都没有它，
                // 可 4_5 已经在给它建索引、8_9 还要往里 INSERT），老用户升级必崩于 no such table。
                // 用最终 schema 的「基础列（去掉 14_15 才加的 millionCopiesIncome）」补建，
                // 后续 4_5 索引 / 7_8 无改动 / 8_9 写入 / 14_15 补列 全部顺势成立。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `assets` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`symbol` TEXT, " +
                        "`account` TEXT NOT NULL, " +
                        "`balance` REAL NOT NULL, " +
                        "`purchasePrice` REAL NOT NULL, " +
                        "`currentPrice` REAL, " +
                        "`currency` TEXT NOT NULL, " +
                        "`lastSyncTime` INTEGER NOT NULL, " +
                        "`lastUpdateTime` INTEGER NOT NULL, " +
                        "`notes` TEXT, " +
                        "`interestRate` REAL, " +
                        "`dueDate` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_symbol ON assets(symbol)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_type ON assets(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_account ON assets(account)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_currency ON assets(currency)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 同上：asset_snapshots / asset_snapshot_details 在早期迁移链里从未被 CREATE，
                // 可 5_6 已经在建索引、7_8 还要 ALTER 加列。补建基础列（去掉 7_8 才加的列），
                // 后续 5_6 索引 / 7_8 补列 全部顺势成立。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `asset_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`totalAssets` REAL NOT NULL, " +
                        "`totalLiabilities` REAL NOT NULL, " +
                        "`netAssets` REAL NOT NULL, " +
                        "`stockValue` REAL NOT NULL, " +
                        "`fundValue` REAL NOT NULL, " +
                        "`cryptoValue` REAL NOT NULL, " +
                        "`bankDepositValue` REAL NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `asset_snapshot_details` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`snapshotId` INTEGER NOT NULL, " +
                        "`assetId` INTEGER NOT NULL, " +
                        "`assetName` TEXT NOT NULL, " +
                        "`assetType` TEXT NOT NULL, " +
                        "`balance` REAL NOT NULL, " +
                        "`price` REAL NOT NULL, " +
                        "`totalValue` REAL NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_snapshots_date ON asset_snapshots(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_snapshots_timestamp ON asset_snapshots(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_snapshot_details_snapshotId ON asset_snapshot_details(snapshotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_snapshot_details_assetId ON asset_snapshot_details(assetId)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 空迁移，版本对齐
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN totalCost REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN totalProfit REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN yieldRate REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN stockCost REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN fundCost REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN cryptoCost REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshots ADD COLUMN bankDepositCost REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshot_details ADD COLUMN cost REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshot_details ADD COLUMN profit REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE asset_snapshot_details ADD COLUMN yieldRate REAL NOT NULL DEFAULT 0.0")
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

        /**
         * 新增场外基金净值持久化缓存表 `fund_nav_cache`。
         *
         * ## 为什么必须手写这条迁移
         *
         * 本库**没有** `fallbackToDestructiveMigration`：Room 打开数据库时若发现
         * 「版本升了但没有对应迁移」会直接抛 `IllegalStateException`，表现为老用户
         * 升级后一打开 App 就崩，且没法自愈。所以只要动了 `version`，这条迁移
         * 就必须存在，而且要和 Room 按实体生成的建表语句**逐列一致**
         * （Room 会校验列名 / 类型 / NOT NULL / 索引名，不一致同样崩）。
         *
         * 建表语句取自 Room 自己导出的 16.json（exportSchema 打开后由 kapt 生成），
         * 不是手敲的：手敲容易漏 `NOT NULL` 或把索引名写错。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fund_nav_cache` (" +
                        "`code` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`nav` REAL NOT NULL, " +
                        "`changePercent` REAL, " +
                        "`navDate` TEXT NOT NULL, " +
                        "`millionCopiesIncome` REAL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`code`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fund_nav_cache_navDate` " +
                        "ON `fund_nav_cache` (`navDate`)"
                )
                AppLogger.d("基金净值持久化缓存表迁移完成", "StockDatabase")
            }
        }

        /**
         * 把 `transactions.quantity` 的声明类型从 INTEGER 对齐成 REAL。
         *
         * ## 背景
         *
         * `transactions` 是 v1 就存在的表，当时 `quantity` 声明为 INTEGER；而实体
         * [Transaction.quantity] 从某个版本起是 `Double`，Room 导出的 16.json 里该列已是 REAL。
         * 中间**从来没有**一条迁移做过这个类型转换，于是「v1 schema 里 INTEGER / v16 schema 里 REAL」
         * 成为一个历史分歧。
         *
         * ## 为什么不改已有迁移，而是新增 16→17
         *
         * 16 的迁移链已经对外发布过（当前线上用户全部是 v16 全新安装），往回改 `MIGRATION_15_16`
         * 或更早的迁移，会让「已升级过的库」与「未升级过的库」走到不同的分支，且无从区分。
         * 新增一条末端迁移对已发布的链零侵入，且对 v1~v16 的任意起点都成立。
         *
         * ## 为什么值得修（虽然不影响运行）
         *
         * SQLite 是动态类型、只认 type affinity：INTEGER 亲和列里写入 100.5 照样存成 REAL，
         * 读取走 `Cursor.getDouble` 也正常，所以**运行时不会错**。真正的代价在校验侧——
         * Room 的 `runMigrationsAndValidate` / `MigrationTestHelper` 严格比对会因此失败，
         * `tools/validate_migration.py` 也长期挂着一条 `transactions.quantity: type INTEGER != REAL`。
         * 只要这条分歧在，任何「迁移链是否可信」的自动检查都没法全绿，等于把唯一的防线拆了。
         *
         * ## 做法
         *
         * SQLite 没有 `ALTER COLUMN TYPE`，标准做法是「建新表 → 复制 → 删旧表 → 改名」。
         * 建表语句逐字取自 Room 导出的 17.json（不是手敲的）。
         * `transactions` 没有索引、没有被别的表外键引用，所以只需搬表本身。
         *
         * 搬运语句刻意用**默认的 ABORT** 而不是 `INSERT OR IGNORE`：搬表迁移里埋一个
         * 「静默跳过坏行」的开关是坏味道——数据少了却不报错，比崩掉危险得多。
         * 见下方 migrate() 里的注释。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transactions_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`symbol` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`quantity` REAL NOT NULL, " +
                        "`price` REAL NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`fee` REAL NOT NULL)"
                )
                // 显式写列名：两侧列序一致时 `SELECT *` 也能对上，但一旦将来有人给
                // transactions 加列，隐式对齐会静默错位。CAST 是防御性的——INTEGER 亲和列里
                // 本来就可能存着 REAL 值，显式转换保证搬过去的是 REAL。
                //
                // 用默认 ABORT 而不是 OR IGNORE：OR IGNORE 会把违反约束的行**悄悄跳过**，
                // 搬完发现少了几行却没有任何异常——QA 实测构造一条 quantity=NULL 的脏行，
                // 源表 3 行搬完只剩 2 行、id=2 被无声吞掉。当前 schema 下 notNull 自 v1 起
                // 就存在，不可能触发，但这属于「搬表迁移埋着静默丢数据」的坏味道：将来谁给
                // transactions 加个 UNIQUE 约束或可空列，就可能悄悄少数据且无人知晓。
                // 用 ABORT 则问题直接崩在迁移里，由 Room 的事务整体回滚，
                // 远好过静默丢（宁可升级失败、也不能让用户的数据凭空消失）。
                db.execSQL(
                    "INSERT INTO `transactions_new` " +
                        "(`id`, `symbol`, `name`, `type`, `quantity`, `price`, `date`, `fee`) " +
                        "SELECT `id`, `symbol`, `name`, `type`, " +
                        "CAST(`quantity` AS REAL), `price`, `date`, `fee` " +
                        "FROM `transactions`"
                )
                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
                // 不搬 sqlite_sequence：DROP TABLE 会连带删掉本表的自增序号，之后
                // SQLite 以「当前 MAX(id)+1」重新开始分配。没有表外键引用 transactions.id，
                // 也没有任何代码依赖它的连续性，复用被删记录的 id 无后果；
                // 而跨 SQLite 版本去操作 sqlite_sequence 反而更容易出岔子。
                AppLogger.d("transactions.quantity 类型对齐 REAL 迁移完成", "StockDatabase")
            }
        }

        /**
         * 全部手写迁移，按版本升序。抽成方法既给 `getInstance` 用（避免重复罗列），
         * 也让 Room 的 `MigrationTestHelper` 测试能直接拿到同一份迁移数组做端到端校验。
         */
        @JvmStatic
        fun allMigrations(): Array<Migration> = arrayOf(
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
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17
        )

        @JvmStatic
        fun getInstance(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    "stock_diary_db"
                )
                    .addMigrations(*allMigrations())
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}