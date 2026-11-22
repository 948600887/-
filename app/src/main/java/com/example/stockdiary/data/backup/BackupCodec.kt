package com.example.stockdiary.data.backup

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.database.AssetSnapshotDetail
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.FundNavCache
import com.example.stockdiary.database.StockBasic
import com.example.stockdiary.database.Transaction
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 备份**格式**版本：只描述本 JSON 结构的字段布局，与 Room 数据库版本（v18）无关。
 *
 * 之所以和 DB 版本解耦：备份文件是「7 张表的行快照」，不是数据库文件，不涉及
 * schema 迁移链。DB 版本只作为元信息写进 [BackupPayload.dbVersion] 供排查用，
 * 恢复时不参与任何建表/迁移决策。
 */
const val BACKUP_FORMAT_VERSION = 1

/**
 * 备份文件损坏、缺关键结构、字段类型错或含非法枚举值。
 *
 * 统一成可捕获的自定义异常，禁止把 [JSONException] 裸抛到 UI 层（用户只会看到
 * 一串英文堆栈）。[BackupCodec.decode] / [BackupCodec.summary] 的所有失败路径都收敛到它。
 */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 备份由更高格式版本的应用写出，当前版本读不懂其字段布局。
 *
 * 遇到高版本必须**拒绝恢复**而不是「按已知字段猜着读」——猜读会静默丢掉新字段，
 * 用户以为恢复成功、账目却少了东西，比明确报错危险得多。
 */
class UnsupportedBackupVersionException(
    val foundVersion: Int,
    val supportedVersion: Int
) : Exception("备份格式版本 $foundVersion 高于当前支持的 $supportedVersion，请升级应用后再恢复")

/**
 * 一次全量备份的完整内容：Room 7 张表的全部行 + 元信息。
 *
 * 刻意**不含**显示偏好（含 annualDividendCache / 指纹等派生缓存）、币安 API 凭据
 * （绑本机 Android Keystore，跨设备必解不开）、日志与崩溃报告。恢复旧缓存只会显示错值，
 * 恢复凭据则是把一个解密不了的密文带过去，两者都不该进备份。
 */
data class BackupPayload(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val dbVersion: Int = 0,
    val exportedAt: Long = 0L,
    val assets: List<Asset> = emptyList(),
    val snapshots: List<AssetSnapshot> = emptyList(),
    val snapshotDetails: List<AssetSnapshotDetail> = emptyList(),
    val dividends: List<Dividend> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val stockBasics: List<StockBasic> = emptyList(),
    val fundNavCaches: List<FundNavCache> = emptyList()
)

/** 只读解析出的备份摘要，供恢复前的二次确认框展示。不含任何账目明细。 */
data class BackupSummary(
    val formatVersion: Int = 0,
    val dbVersion: Int = 0,
    val exportedAt: Long = 0L,
    val assetCount: Int = 0,
    val snapshotCount: Int = 0,
    val snapshotDetailCount: Int = 0,
    val dividendCount: Int = 0,
    val transactionCount: Int = 0,
    val stockBasicCount: Int = 0,
    val fundNavCacheCount: Int = 0
)

/**
 * Room 7 张表 <-> UTF-8 JSON 的**纯 JVM** 编解码器。
 *
 * ## 为什么手写显式字段、不用反射 / Gson
 *
 * 实体字段会随版本增减，反射式序列化会把「当时恰好有的字段」全盘写出去，读回来再
 * 靠构造器默认值补，极易在旧备份上悄悄产生半份数据。这里每个字段逐一 put / get：
 * 写入端一眼能核对「到底备份了什么」，读取端对**未知字段忽略**、对**缺失字段安全回退**
 * （兼容旧格式），对**类型错 / 关键字段缺失**抛 [InvalidBackupException]。
 *
 * ## 数值精度与坏数据防御
 *
 * Double 一律走 `put(double)` / `getDouble` 一族（org.json 用 `Double.toString` 保 bit 级
 * 精度，项目内 DividendIncomeCacheVerifyTest 已坐实），不用 Float、不经过字符串手拼。
 * 可空字段写 `JSONObject.NULL`，读回 null；不会把 null 静默记成 0。
 *
 * NaN / Infinity 必须在写 JSON **之前**拦掉：org.json 的 `put(String, double)` 遇到非有限
 * 值会抛 `JSONException`，让整份备份导出失败（且异常是英文、用户看不懂）；解码侧同理，
 * `1e400` 会被 org.json 解析成 BigDecimal、`toDouble()` 变 Infinity 后静默写进 Room。
 * 另外主键（id / snapshotId / assetId）缺失不能回退 0 —— Room 会重分配自增 id，而明细的
 * snapshotId 仍是旧值，恢复出孤儿明细；故 decode 后校验主键必填与各表主键唯一。
 * 但外键「必填」不等于「指向现存父行」：snapshotId / assetId 刻意不校验引用一致性，
 * 因为现网库本身可能已有孤儿明细，强校验会把这类合法备份永久锁死在恢复阶段。
 *
 * 本 object 不依赖 Android Context、不碰 Room，可在 `testDebugUnitTest` 里直接跑。
 */
object BackupCodec {

    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_DB_VERSION = "dbVersion"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_ASSETS = "assets"
    private const val KEY_SNAPSHOTS = "snapshots"
    private const val KEY_SNAPSHOT_DETAILS = "snapshotDetails"
    private const val KEY_DIVIDENDS = "dividends"
    private const val KEY_TRANSACTIONS = "transactions"
    private const val KEY_STOCK_BASICS = "stockBasics"
    private const val KEY_FUND_NAV_CACHES = "fundNavCaches"

    /** 把 [payload] 编码成缩进 2 空格、UTF-8 可读的 JSON 文本。 */
    fun encode(payload: BackupPayload): String {
        // 在写任何 JSON 之前全量校验：org.json 的 put(double) 遇 NaN/Infinity 会抛
        // JSONException 让整份备份导出失败，且是英文异常。这里换成可读的中文自定义异常。
        requireFinitePayload(payload)
        val root = JSONObject()
        root.put(KEY_FORMAT_VERSION, payload.formatVersion)
        root.put(KEY_DB_VERSION, payload.dbVersion)
        root.put(KEY_EXPORTED_AT, payload.exportedAt)
        root.put(KEY_ASSETS, JSONArray().apply { payload.assets.forEach { put(encodeAsset(it)) } })
        root.put(KEY_SNAPSHOTS, JSONArray().apply { payload.snapshots.forEach { put(encodeSnapshot(it)) } })
        root.put(
            KEY_SNAPSHOT_DETAILS,
            JSONArray().apply { payload.snapshotDetails.forEach { put(encodeSnapshotDetail(it)) } }
        )
        root.put(KEY_DIVIDENDS, JSONArray().apply { payload.dividends.forEach { put(encodeDividend(it)) } })
        root.put(KEY_TRANSACTIONS, JSONArray().apply { payload.transactions.forEach { put(encodeTransaction(it)) } })
        root.put(KEY_STOCK_BASICS, JSONArray().apply { payload.stockBasics.forEach { put(encodeStockBasic(it)) } })
        root.put(KEY_FUND_NAV_CACHES, JSONArray().apply { payload.fundNavCaches.forEach { put(encodeFundNavCache(it)) } })
        return root.toString(2)
    }

    /**
     * 只读解析摘要：不构造任何实体、不写库（本 object 根本没有数据库引用）。
     *
     * 仍复用 [parseRoot] 做格式版本与 JSON 合法性校验：坏文件在「读取备份」阶段就报错，
     * 不该让用户看到一个看似正常的确认框、点下去才失败。
     */
    fun summary(json: String): BackupSummary {
        val root = parseRoot(json)
        // 缺表的备份必须在这一步就拒绝：用户还没点「确认恢复」，不该先看到一个
        // 正常的摘要框。这里报的错与 decode 完全一致，避免两阶段口径不一致。
        requireAllTables(root)
        return BackupSummary(
            formatVersion = root.strictIntField(KEY_FORMAT_VERSION),
            dbVersion = root.intField(KEY_DB_VERSION, 0),
            exportedAt = root.longField(KEY_EXPORTED_AT, 0L),
            assetCount = rowsArray(root, KEY_ASSETS)?.length() ?: 0,
            snapshotCount = rowsArray(root, KEY_SNAPSHOTS)?.length() ?: 0,
            snapshotDetailCount = rowsArray(root, KEY_SNAPSHOT_DETAILS)?.length() ?: 0,
            dividendCount = rowsArray(root, KEY_DIVIDENDS)?.length() ?: 0,
            transactionCount = rowsArray(root, KEY_TRANSACTIONS)?.length() ?: 0,
            stockBasicCount = rowsArray(root, KEY_STOCK_BASICS)?.length() ?: 0,
            fundNavCacheCount = rowsArray(root, KEY_FUND_NAV_CACHES)?.length() ?: 0
        )
    }

    /**
     * 从已解码的载荷**重算**摘要。
     *
     * 恢复前二次 decode 后用它和确认时缓存的摘要比对，识别「用户在确认框停留期间文件被替换」。
     * 纯函数、不读文件、不写库，可直接在 JVM 单测里钉死。
     */
    fun summaryOf(payload: BackupPayload): BackupSummary = BackupSummary(
        formatVersion = payload.formatVersion,
        dbVersion = payload.dbVersion,
        exportedAt = payload.exportedAt,
        assetCount = payload.assets.size,
        snapshotCount = payload.snapshots.size,
        snapshotDetailCount = payload.snapshotDetails.size,
        dividendCount = payload.dividends.size,
        transactionCount = payload.transactions.size,
        stockBasicCount = payload.stockBasics.size,
        fundNavCacheCount = payload.fundNavCaches.size
    )

    /**
     * 比对「二次 decode 重算的摘要」与「确认时缓存的摘要」是否为同一份备份。
     *
     * 口径按 QA 规格钉死：exportedAt + formatVersion + 7 张表条数。dbVersion 只是排查元信息，
     * 同一份文件它必然一致，不纳入身份判定；各表条数覆盖「换了另一份备份但导出时间恰好相同」。
     */
    fun hasSameIdentity(payload: BackupPayload, cached: BackupSummary): Boolean {
        val now = summaryOf(payload)
        return now.formatVersion == cached.formatVersion &&
            now.exportedAt == cached.exportedAt &&
            now.assetCount == cached.assetCount &&
            now.snapshotCount == cached.snapshotCount &&
            now.snapshotDetailCount == cached.snapshotDetailCount &&
            now.dividendCount == cached.dividendCount &&
            now.transactionCount == cached.transactionCount &&
            now.stockBasicCount == cached.stockBasicCount &&
            now.fundNavCacheCount == cached.fundNavCacheCount
    }

    /** 把 JSON 文本完整解码成 [BackupPayload]；任何结构问题都抛可捕获的自定义异常。 */
    fun decode(json: String): BackupPayload {
        val root = parseRoot(json)
        val payload = BackupPayload(
            formatVersion = root.strictIntField(KEY_FORMAT_VERSION),
            dbVersion = root.intField(KEY_DB_VERSION, 0),
            exportedAt = root.longField(KEY_EXPORTED_AT, 0L),
            assets = decodeRows(root, KEY_ASSETS) { decodeAsset(it) },
            snapshots = decodeRows(root, KEY_SNAPSHOTS) { decodeSnapshot(it) },
            snapshotDetails = decodeRows(root, KEY_SNAPSHOT_DETAILS) { decodeSnapshotDetail(it) },
            dividends = decodeRows(root, KEY_DIVIDENDS) { decodeDividend(it) },
            transactions = decodeRows(root, KEY_TRANSACTIONS) { decodeTransaction(it) },
            stockBasics = decodeRows(root, KEY_STOCK_BASICS) { decodeStockBasic(it) },
            fundNavCaches = decodeRows(root, KEY_FUND_NAV_CACHES) { decodeFundNavCache(it) }
        )
        validatePayload(payload)
        // 放在行级校验之后：字段错误仍优先报字段错误；但缺表一定在返回 payload 前拦掉。
        // 不这样做的后果是 [com.example.stockdiary.data.backup.BackupManager.restore]
        // 会把缺失的表演成空表，先清空再写 0 行，等于静默清库。
        requireAllTables(root)
        return payload
    }

    /**
     * decode 后的跨表一致性校验（B8-5）：各表主键唯一。
     *
     * 主键重复会让 Room 的 REPLACE/Upsert 静默吞行，必须在**事务外**拦掉，绝不写库。
     *
     * `snapshotId` / `assetId` 刻意都不校验「外键是否指向现存父行」（与 assetId 原口径一致）：
     * 现网库本就可能已有孤儿明细——createSnapshot 同日覆盖只删快照不删明细、去重同理，
     * 且恢复自身写回明细后下一份备份还会持续造孤儿。强制校验会让这些用户导出成功、
     * 确认框正常，点恢复却被拒、零写库且反复重试无解，等于把合法备份永久锁死。
     * 两个外键的**必填**（非缺失 / 类型正确）由 `decodeSnapshotDetail` 的 `requiredLong` 保证，
     * 每条明细的 id 唯一由上方 `assertUniqueKeys` 保证；这里只放弃跨表引用这一条约束。
     */
    private fun validatePayload(payload: BackupPayload) {
        assertUniqueKeys("assets.id", payload.assets.map { it.id })
        assertUniqueKeys("asset_snapshots.id", payload.snapshots.map { it.id })
        assertUniqueKeys("asset_snapshot_details.id", payload.snapshotDetails.map { it.id })
        assertUniqueKeys("dividend.id", payload.dividends.map { it.id })
        assertUniqueKeys("transactions.id", payload.transactions.map { it.id })
        assertUniqueKeys("stock_basic.code", payload.stockBasics.map { it.code })
        assertUniqueKeys("fund_nav_cache.code", payload.fundNavCaches.map { it.code })
        // dividend 除了主键，还有一条**业务唯一键**（实体上的 UNIQUE(symbol,date)）。
        // 主键不重复不代表业务键不重复，而 DividendDao.insertAll 是 REPLACE ——
        // 同标的同除息日的两行会被静默吃掉一行，界面仍显示「恢复成功」，用户少了一条分红。
        // 与「主键重复必须拒绝」同口径：在事务外拦掉，绝不写库。
        assertUniqueKeys(
            "dividend.(symbol,date)",
            payload.dividends.map { it.symbol to it.date }
        )
    }

    private fun <T> assertUniqueKeys(table: String, keys: List<T>) {
        if (keys.size == keys.toSet().size) return
        val duplicate = keys.groupBy { it }.entries.first { it.value.size > 1 }.key
        throw InvalidBackupException("备份表 $table 存在重复主键：$duplicate")
    }

    /** 导出前全量校验 Double：任何 NaN/Infinity 都在写 JSON 之前变成可读的中文异常。 */
    private fun requireFinitePayload(payload: BackupPayload) {
        payload.assets.forEach {
            requireFinite("assets.balance", it.balance)
            requireFinite("assets.purchasePrice", it.purchasePrice)
            requireFinite("assets.currentPrice", it.currentPrice)
            requireFinite("assets.interestRate", it.interestRate)
            requireFinite("assets.millionCopiesIncome", it.millionCopiesIncome)
        }
        payload.snapshots.forEach {
            requireFinite("snapshots.totalAssets", it.totalAssets)
            requireFinite("snapshots.totalLiabilities", it.totalLiabilities)
            requireFinite("snapshots.netAssets", it.netAssets)
            requireFinite("snapshots.totalCost", it.totalCost)
            requireFinite("snapshots.totalProfit", it.totalProfit)
            requireFinite("snapshots.yieldRate", it.yieldRate)
            requireFinite("snapshots.stockValue", it.stockValue)
            requireFinite("snapshots.fundValue", it.fundValue)
            requireFinite("snapshots.cryptoValue", it.cryptoValue)
            requireFinite("snapshots.bankDepositValue", it.bankDepositValue)
            requireFinite("snapshots.stockCost", it.stockCost)
            requireFinite("snapshots.fundCost", it.fundCost)
            requireFinite("snapshots.cryptoCost", it.cryptoCost)
            requireFinite("snapshots.bankDepositCost", it.bankDepositCost)
        }
        payload.snapshotDetails.forEach {
            requireFinite("snapshotDetails.balance", it.balance)
            requireFinite("snapshotDetails.price", it.price)
            requireFinite("snapshotDetails.totalValue", it.totalValue)
            requireFinite("snapshotDetails.cost", it.cost)
            requireFinite("snapshotDetails.profit", it.profit)
            requireFinite("snapshotDetails.yieldRate", it.yieldRate)
        }
        payload.dividends.forEach { requireFinite("dividends.amount", it.amount) }
        payload.transactions.forEach {
            requireFinite("transactions.quantity", it.quantity)
            requireFinite("transactions.price", it.price)
            requireFinite("transactions.fee", it.fee)
        }
        payload.fundNavCaches.forEach {
            requireFinite("fundNavCaches.nav", it.nav)
            requireFinite("fundNavCaches.changePercent", it.changePercent)
            requireFinite("fundNavCaches.millionCopiesIncome", it.millionCopiesIncome)
        }
    }

    private fun requireFinite(field: String, value: Double?) {
        if (value != null && (value.isNaN() || value.isInfinite())) {
            throw InvalidBackupException("备份含非有限数值（NaN/Infinity）：$field，无法导出")
        }
    }

    // ---------------------------------------------------------------------
    // 写入端：每个字段显式列出，顺序与实体声明一致，便于逐列核对
    // ---------------------------------------------------------------------

    private fun encodeAsset(a: Asset): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("name", a.name)
        put("type", a.type.name)
        putStringOrNull("symbol", a.symbol)
        put("account", a.account)
        put("balance", a.balance)
        put("purchasePrice", a.purchasePrice)
        putDoubleOrNull("currentPrice", a.currentPrice)
        put("currency", a.currency)
        put("lastSyncTime", a.lastSyncTime)
        put("lastUpdateTime", a.lastUpdateTime)
        putStringOrNull("notes", a.notes)
        putDoubleOrNull("interestRate", a.interestRate)
        putStringOrNull("dueDate", a.dueDate)
        putDoubleOrNull("millionCopiesIncome", a.millionCopiesIncome)
        put("isEarnLocked", a.isEarnLocked)
    }

    private fun encodeSnapshot(s: AssetSnapshot): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("timestamp", s.timestamp)
        put("date", s.date)
        put("totalAssets", s.totalAssets)
        put("totalLiabilities", s.totalLiabilities)
        put("netAssets", s.netAssets)
        put("totalCost", s.totalCost)
        put("totalProfit", s.totalProfit)
        put("yieldRate", s.yieldRate)
        put("stockValue", s.stockValue)
        put("fundValue", s.fundValue)
        put("cryptoValue", s.cryptoValue)
        put("bankDepositValue", s.bankDepositValue)
        put("stockCost", s.stockCost)
        put("fundCost", s.fundCost)
        put("cryptoCost", s.cryptoCost)
        put("bankDepositCost", s.bankDepositCost)
    }

    private fun encodeSnapshotDetail(d: AssetSnapshotDetail): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("snapshotId", d.snapshotId)
        put("assetId", d.assetId)
        put("assetName", d.assetName)
        put("assetType", d.assetType.name)
        put("balance", d.balance)
        put("price", d.price)
        put("totalValue", d.totalValue)
        put("cost", d.cost)
        put("profit", d.profit)
        put("yieldRate", d.yieldRate)
    }

    private fun encodeDividend(d: Dividend): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("symbol", d.symbol)
        put("name", d.name)
        put("amount", d.amount)
        put("date", d.date)
        putStringOrNull("payDate", d.payDate)
        put("isManual", d.isManual)
    }

    private fun encodeTransaction(t: Transaction): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("symbol", t.symbol)
        put("name", t.name)
        put("type", t.type)
        put("quantity", t.quantity)
        put("price", t.price)
        put("date", t.date)
        put("fee", t.fee)
    }

    private fun encodeStockBasic(s: StockBasic): JSONObject = JSONObject().apply {
        put("code", s.code)
        put("name", s.name)
        put("market", s.market)
        putStringOrNull("listDate", s.listDate)
        put("isETF", s.isETF)
    }

    private fun encodeFundNavCache(f: FundNavCache): JSONObject = JSONObject().apply {
        put("code", f.code)
        put("name", f.name)
        put("nav", f.nav)
        putDoubleOrNull("changePercent", f.changePercent)
        put("navDate", f.navDate)
        putDoubleOrNull("millionCopiesIncome", f.millionCopiesIncome)
        put("fetchedAt", f.fetchedAt)
    }

    private fun JSONObject.putStringOrNull(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.putDoubleOrNull(key: String, value: Double?) {
        if (value == null) {
            put(key, JSONObject.NULL)
        } else {
            requireFinite(key, value)
            put(key, value)
        }
    }

    // ---------------------------------------------------------------------
    // 读取端：缺失字段安全回退，类型错/关键字段缺失抛 InvalidBackupException
    // ---------------------------------------------------------------------

    /** 取原始值；缺失或显式 null 统一返回 null，交给各 field helper 决定回退还是报错。 */
    private fun JSONObject.raw(key: String): Any? =
        if (!has(key) || isNull(key)) null else get(key)

    private fun JSONObject.requiredString(key: String): String {
        val value = raw(key) ?: throw InvalidBackupException("备份缺少必填字段 $key")
        return value as? String
            ?: throw InvalidBackupException("备份字段 $key 应为字符串，实际 ${value.javaClass.simpleName}")
    }

    /** 可空字符串：缺失/null 回退 null；类型错不静默吞。 */
    private fun JSONObject.optionalString(key: String): String? {
        val value = raw(key) ?: return null
        return value as? String
            ?: throw InvalidBackupException("备份字段 $key 应为字符串或 null，实际 ${value.javaClass.simpleName}")
    }

    private fun JSONObject.stringField(key: String, default: String): String =
        optionalString(key) ?: default

    private fun JSONObject.longField(key: String, default: Long = 0L): Long {
        val value = raw(key) ?: return default
        val number = value as? Number
            ?: throw InvalidBackupException("备份字段 $key 应为整数，实际 ${value.javaClass.simpleName}")
        val asDouble = number.toDouble()
        if (asDouble.isNaN() || asDouble.isInfinite()) {
            throw InvalidBackupException("备份字段 $key 含非有限数值：$value")
        }
        return number.toLong()
    }

    private fun JSONObject.intField(key: String, default: Int = 0): Int {
        val value = raw(key) ?: return default
        return strictInt(key, value)
    }

    /** 必填整数：缺失直接报错，绝不静默回退 0（B8-3）。 */
    private fun JSONObject.strictIntField(key: String): Int {
        val value = raw(key) ?: throw InvalidBackupException("备份缺少关键字段 $key")
        return strictInt(key, value)
    }

    /**
     * 整型严格解析：`1.9` 不能截成 1 放行，`1e400` 不能变成 Infinity 再截断。
     * org.json 把超范围数字解析成 BigDecimal，先转 double 判有限与无小数，再转 Int。
     */
    private fun strictInt(key: String, value: Any): Int {
        if (value !is Number) {
            throw InvalidBackupException("备份字段 $key 应为整数，实际 ${value.javaClass.simpleName}")
        }
        val asDouble = value.toDouble()
        if (asDouble.isNaN() || asDouble.isInfinite() ||
            asDouble != asDouble.toLong().toDouble() ||
            asDouble < Int.MIN_VALUE.toDouble() || asDouble > Int.MAX_VALUE.toDouble()
        ) {
            throw InvalidBackupException("备份字段 $key 应为 32 位整数，实际 $value")
        }
        return value.toInt()
    }

    private fun JSONObject.doubleField(key: String, default: Double = 0.0): Double {
        val value = raw(key) ?: return default
        val number = value as? Number
            ?: throw InvalidBackupException("备份字段 $key 应为数字，实际 ${value.javaClass.simpleName}")
        val result = number.toDouble()
        if (result.isNaN() || result.isInfinite()) {
            throw InvalidBackupException("备份字段 $key 含非有限数值：$value")
        }
        return result
    }

    private fun JSONObject.optionalDouble(key: String): Double? {
        val value = raw(key) ?: return null
        val number = value as? Number
            ?: throw InvalidBackupException("备份字段 $key 应为数字或 null，实际 ${value.javaClass.simpleName}")
        val result = number.toDouble()
        if (result.isNaN() || result.isInfinite()) {
            throw InvalidBackupException("备份字段 $key 含非有限数值：$value")
        }
        return result
    }

    /**
     * 必填 Long 主键 / 外键（B8-4）。
     *
     * 缺 id 若静默回退 0，Room 会重分配自增 id，而明细里的 snapshotId / assetId 仍是旧值，
     * 恢复完得到一批永远查不出来的孤儿明细。宁可拒绝恢复，也不能写半份数据。
     */
    private fun JSONObject.requiredLong(key: String): Long {
        val value = raw(key) ?: throw InvalidBackupException("备份缺少必填字段 $key（主键/外键不可省略）")
        val number = value as? Number
            ?: throw InvalidBackupException("备份字段 $key 应为整数，实际 ${value.javaClass.simpleName}")
        val asDouble = number.toDouble()
        if (asDouble.isNaN() || asDouble.isInfinite() || asDouble != asDouble.toLong().toDouble()) {
            throw InvalidBackupException("备份字段 $key 应为整数，实际 $value")
        }
        return number.toLong()
    }

    /** 必填 Int 主键（dividend.id 是 Int）。 */
    private fun JSONObject.requiredInt(key: String): Int {
        val value = requiredLong(key)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw InvalidBackupException("备份字段 $key 超出 32 位整数范围：$value")
        }
        return value.toInt()
    }

    private fun JSONObject.boolField(key: String, default: Boolean = false): Boolean {
        val value = raw(key) ?: return default
        return value as? Boolean
            ?: throw InvalidBackupException("备份字段 $key 应为布尔值，实际 ${value.javaClass.simpleName}")
    }

    /** AssetType 存的是枚举名（与 Room Converters 一致）。未知枚举名视为文件损坏，不回退 STOCK。 */
    private fun JSONObject.assetTypeField(key: String): AssetType {
        val name = requiredString(key)
        return try {
            AssetType.valueOf(name)
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("备份含未知资产类型 '$name'", e)
        }
    }

    /** 表字段必须是数组；类型错必须报错。缺失由 [requireAllTables] 在入口统一拒绝。 */
    private fun rowsArray(root: JSONObject, key: String): JSONArray? {
        val value = root.raw(key) ?: return null
        return value as? JSONArray
            ?: throw InvalidBackupException("备份字段 $key 应为数组，实际 ${value.javaClass.simpleName}")
    }

    private fun <T> decodeRows(root: JSONObject, key: String, parse: (JSONObject) -> T): List<T> {
        val array = rowsArray(root, key) ?: return emptyList()
        return List(array.length()) { index ->
            val row = array.optJSONObject(index)
                ?: throw InvalidBackupException("备份字段 $key 第 $index 项不是 JSON 对象")
            try {
                parse(row)
            } catch (e: InvalidBackupException) {
                throw e
            } catch (e: JSONException) {
                throw InvalidBackupException("备份字段 $key 第 $index 项解析失败: ${e.message}", e)
            }
        }
    }

    /**
     * 解析根对象并校验格式版本。
     *
     * 版本检查刻意放在最前：高版本备份即使其余字段也坏了，也应报「请升级应用」而不是
     * 一堆看不懂的字段错误。formatVersion 是唯一必填的顶层字段（它是判断「这是不是本 App
     * 的备份文件」的最低门槛）；元信息允许缺失回退，但 7 张表数组**不允许缺失**——
     * 缺失会被 [requireAllTables] 拒绝，绝不能当成空表去覆盖现有数据。
     */
    private fun parseRoot(json: String): JSONObject {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw InvalidBackupException("备份文件不是合法的 JSON：${e.message}", e)
        }
        if (root.raw(KEY_FORMAT_VERSION) == null) {
            throw InvalidBackupException("备份缺少关键字段 formatVersion，无法确认格式版本")
        }
        // 必须严格整型：1.9 不能截成 1 放行，1e400 不能变成 Infinity 再截断
        val formatVersion = root.strictIntField(KEY_FORMAT_VERSION)
        if (formatVersion > BACKUP_FORMAT_VERSION) {
            throw UnsupportedBackupVersionException(formatVersion, BACKUP_FORMAT_VERSION)
        }
        if (formatVersion < 1) {
            throw InvalidBackupException("备份格式版本非法：$formatVersion")
        }
        return root
    }

    /** 7 张表在备份里的键与用户可读名称，用于「缺表」时的明确报错。 */
    private val REQUIRED_TABLES = listOf(
        KEY_ASSETS to "资产",
        KEY_SNAPSHOTS to "历史快照",
        KEY_SNAPSHOT_DETAILS to "快照明细",
        KEY_DIVIDENDS to "分红记录",
        KEY_TRANSACTIONS to "交易记录",
        KEY_STOCK_BASICS to "股票基础信息",
        KEY_FUND_NAV_CACHES to "基金净值缓存"
    )

    /**
     * formatVersion >= 1 的备份必须包含全部 7 张表数组。
     *
     * 缺表在旧实现里会被 [decodeRows] 当成空表，随后 [com.example.stockdiary.data.backup.BackupManager.restore]
     * 先清空再写入 0 行——文件被手改、传输截断或格式演进时就会静默清库。这里在
     * **解析阶段**直接拒绝，并指名缺哪张表，让用户能判断是文件不完整还是选错了文件。
     *
     * 兼容性：只约束「表键是否存在」，不要求表有内容——合法的空库备份（7 个空数组）
     * 仍可正常恢复；formatVersion 缺失或 <1 的文件由 [parseRoot] 更早拒绝。
     */
    private fun requireAllTables(root: JSONObject) {
        val missing = REQUIRED_TABLES
            .filter { (key, _) -> root.isNull(key) }
            .map { (key, name) -> "「$name」($key)" }
        if (missing.isNotEmpty()) {
            throw InvalidBackupException(
                "备份文件缺少数据表：${missing.joinToString("、")}。" +
                    "为避免清空现有数据，已拒绝恢复；请选择完整的备份文件。"
            )
        }
    }

    // ---------------------------------------------------------------------
    // 逐表解码
    // ---------------------------------------------------------------------

    private fun decodeAsset(o: JSONObject): Asset = Asset(
        id = o.requiredLong("id"),
        name = o.requiredString("name"),
        type = o.assetTypeField("type"),
        symbol = o.optionalString("symbol"),
        account = o.requiredString("account"),
        balance = o.doubleField("balance"),
        purchasePrice = o.doubleField("purchasePrice"),
        currentPrice = o.optionalDouble("currentPrice"),
        currency = o.stringField("currency", "CNY"),
        lastSyncTime = o.longField("lastSyncTime"),
        lastUpdateTime = o.longField("lastUpdateTime"),
        notes = o.optionalString("notes"),
        interestRate = o.optionalDouble("interestRate"),
        dueDate = o.optionalString("dueDate"),
        millionCopiesIncome = o.optionalDouble("millionCopiesIncome"),
        isEarnLocked = o.boolField("isEarnLocked")
    )

    private fun decodeSnapshot(o: JSONObject): AssetSnapshot = AssetSnapshot(
        id = o.requiredLong("id"),
        timestamp = o.longField("timestamp"),
        date = o.requiredString("date"),
        totalAssets = o.doubleField("totalAssets"),
        totalLiabilities = o.doubleField("totalLiabilities"),
        netAssets = o.doubleField("netAssets"),
        totalCost = o.doubleField("totalCost"),
        totalProfit = o.doubleField("totalProfit"),
        yieldRate = o.doubleField("yieldRate"),
        stockValue = o.doubleField("stockValue"),
        fundValue = o.doubleField("fundValue"),
        cryptoValue = o.doubleField("cryptoValue"),
        bankDepositValue = o.doubleField("bankDepositValue"),
        stockCost = o.doubleField("stockCost"),
        fundCost = o.doubleField("fundCost"),
        cryptoCost = o.doubleField("cryptoCost"),
        bankDepositCost = o.doubleField("bankDepositCost")
    )

    private fun decodeSnapshotDetail(o: JSONObject): AssetSnapshotDetail = AssetSnapshotDetail(
        id = o.requiredLong("id"),
        snapshotId = o.requiredLong("snapshotId"),
        assetId = o.requiredLong("assetId"),
        assetName = o.requiredString("assetName"),
        assetType = o.assetTypeField("assetType"),
        balance = o.doubleField("balance"),
        price = o.doubleField("price"),
        totalValue = o.doubleField("totalValue"),
        cost = o.doubleField("cost"),
        profit = o.doubleField("profit"),
        yieldRate = o.doubleField("yieldRate")
    )

    private fun decodeDividend(o: JSONObject): Dividend = Dividend(
        id = o.requiredInt("id"),
        symbol = o.requiredString("symbol"),
        name = o.requiredString("name"),
        amount = o.doubleField("amount"),
        date = o.requiredString("date"),
        payDate = o.optionalString("payDate"),
        isManual = o.boolField("isManual")
    )

    private fun decodeTransaction(o: JSONObject): Transaction = Transaction(
        id = o.requiredLong("id"),
        symbol = o.requiredString("symbol"),
        name = o.requiredString("name"),
        type = o.requiredString("type"),
        quantity = o.doubleField("quantity"),
        price = o.doubleField("price"),
        date = o.requiredString("date"),
        fee = o.doubleField("fee")
    )

    private fun decodeStockBasic(o: JSONObject): StockBasic = StockBasic(
        code = o.requiredString("code"),
        name = o.requiredString("name"),
        market = o.requiredString("market"),
        listDate = o.optionalString("listDate"),
        isETF = o.boolField("isETF")
    )

    private fun decodeFundNavCache(o: JSONObject): FundNavCache = FundNavCache(
        code = o.requiredString("code"),
        name = o.requiredString("name"),
        nav = o.doubleField("nav"),
        changePercent = o.optionalDouble("changePercent"),
        navDate = o.requiredString("navDate"),
        millionCopiesIncome = o.optionalDouble("millionCopiesIncome"),
        fetchedAt = o.longField("fetchedAt")
    )
}
