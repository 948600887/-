package com.example.stockdiary.data.price

import android.content.Context
import com.example.stockdiary.network.SinaStockData
import com.example.stockdiary.utils.AppLogger
import kotlin.jvm.Volatile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CancellationException

object PriceCacheManager {
    private const val TAG = "PriceCacheManager"
    private const val CACHE_FILE_NAME = "price_cache.json"

    private const val TRADING_HOUR_TTL = 5 * 60 * 1000L
    private const val BEFORE_4PM_TTL = 30 * 60 * 1000L
    private const val AFTER_4PM_TTL = Long.MAX_VALUE
    private const val WEEKEND_TTL = Long.MAX_VALUE

    private const val MAX_MEMORY_CACHE_SIZE = 200

    // 统一的有界缓存实现（见 BoundedTtlCache）：结构/淘汰/快照与其他取价链路共用一份，
    // 这里的动态 TTL 口径与磁盘持久层仍由本对象独占，不随合并改变。
    private val memoryCache = BoundedTtlCache<String, CachedPriceData>(MAX_MEMORY_CACHE_SIZE)
    private var cacheFile: File? = null
    @Volatile
    private var lastSaveTime = 0L
    private val diskLock = Any()

    // ===== 磁盘缓存的惰性加载 =====
    // init() 从前在调用线程（StockDiaryApplication.onCreate → 主线程）上同步
    // loadFromDisk()：读整份 price_cache.json + 逐条 JSON 解析。
    // 实测 130 个条目要 80~148ms，这段是启动阶段纯粹的白等——
    // 而且它只服务「断网时读旧价」这一条路径，大多数冷启动根本读不到它。
    // 现在改成：init 只登记文件并起后台加载，真正要读的路径才等它完成。
    @Volatile
    private var diskLoaded = false

    /** 后台加载完成信号；读路径在它上面等（通常早已 countDown） */
    private val loadLatch = java.util.concurrent.CountDownLatch(1)
    private val loadLock = Any()
    private var loadStarted = false
    // SharedPreferences 单条字符串有 ~1MB 限制，改为文件存储避免大缓存崩溃

    // 落盘专用后台线程：JSON 序列化 + writeText 是磁盘 IO，而 cachePrice 的
    // 调用点很散（刷新链路、各数据源成功回调），绝不能在调用方线程上同步写盘 ——
    // 从前 maybeSaveToDisk 直接在调用线程 file.writeText，刷新时主线程会被
    // 整份缓存的序列化 + 落盘卡住（60 秒节流只决定频率，不决定线程）
    private val diskWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PriceCacheWriter").apply { isDaemon = true }
    }

    data class CachedPriceData(
        val data: SinaStockData,
        val timestamp: Long,
        val source: String
    )

    fun init(context: Context) {
        if (cacheFile == null) {
            cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        }
        // 只登记文件 + 起后台加载，不在调用线程（可能是主线程）上同步解析整份缓存
        ensureLoadStarted()
    }

    /**
     * 登记一次磁盘加载（幂等）。文件不存在或是空的就直接放行，不为空文件起线程。
     */
    private fun ensureLoadStarted() {
        synchronized(loadLock) {
            if (loadStarted) return
            loadStarted = true
            val file = cacheFile
            if (file == null || !file.exists() || file.length() == 0L) {
                diskLoaded = true
                loadLatch.countDown()
                return
            }
        }
        try {
            diskWriter.execute {
                try {
                    loadFromDisk()
                } finally {
                    diskLoaded = true
                    loadLatch.countDown()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 起不了后台线程（进程退出等极端情况）：标记为已加载，
            // 让读路径别在 latch 上永久等待；最坏结果是这一轮读不到磁盘缓存
            diskLoaded = true
            loadLatch.countDown()
        }
    }

    /**
     * 读路径调用：确保磁盘缓存已就位。
     *
     * 后台加载通常在这之前就完成了（init 之后任何一次读取都隔着一整轮启动），
     * 所以这里是零等待；只有「init 后立刻就读」才会真的等，且等的也是 IO 线程
     * 而非启动线程。若 init 从未被调用（单测直连本对象），就地同步补一次加载。
     */
    private fun awaitDiskLoaded() {
        if (diskLoaded) return
        synchronized(loadLock) {
            if (!loadStarted) {
                loadStarted = true
                val file = cacheFile
                if (file == null || !file.exists() || file.length() == 0L) {
                    diskLoaded = true
                    loadLatch.countDown()
                    return
                }
                loadFromDisk()
                diskLoaded = true
                loadLatch.countDown()
                return
            }
        }
        // 后台加载已在进行：等它完成（磁盘 IO 已完成大半，通常 <10ms）
        loadLatch.await()
    }

    private fun isTradingDay(): Boolean {
        val dayOfWeek = java.time.LocalDate.now().dayOfWeek
        return dayOfWeek != java.time.DayOfWeek.SATURDAY && dayOfWeek != java.time.DayOfWeek.SUNDAY
    }

    private fun isBefore4PM(): Boolean {
        val hour = java.time.LocalTime.now().hour
        return hour < 16
    }

    private fun isTradingHour(): Boolean {
        val hour = java.time.LocalTime.now().hour
        return hour in 9..15
    }

    private fun calculateTTL(): Long {
        return when {
            !isTradingDay() -> WEEKEND_TTL
            isTradingHour() -> TRADING_HOUR_TTL
            isBefore4PM() -> BEFORE_4PM_TTL
            else -> AFTER_4PM_TTL
        }
    }

    fun shouldRefreshAt4PM(code: String): Boolean {
        val cached = memoryCache.get(code) ?: return true

        val cachedDate = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(cached.timestamp),
            java.time.ZoneId.systemDefault()
        ).toLocalDate()
        val nowDate = java.time.LocalDate.now()

        val sameDay = cachedDate == nowDate

        if (isTradingDay() && !isBefore4PM()) {
            return !sameDay
        }

        return false
    }

    fun isCacheValid(code: String): Boolean {
        awaitDiskLoaded()
        val cached = memoryCache.get(code) ?: return false

        if (shouldRefreshAt4PM(code)) {
            return false
        }

        val ttl = calculateTTL()
        if (ttl == Long.MAX_VALUE) {
            return true
        }

        return System.currentTimeMillis() - cached.timestamp < ttl
    }

    fun getCachedPrice(code: String): SinaStockData? {
        awaitDiskLoaded()
        return if (isCacheValid(code)) {
            memoryCache.getValue(code)?.data
        } else {
            memoryCache.remove(code)
            null
        }
    }

    fun cachePrice(code: String, data: SinaStockData, source: String) {
        memoryCache.put(code, CachedPriceData(data, System.currentTimeMillis(), source))
        trimMemoryCacheIfNeeded()
        maybeSaveToDisk()
    }

    fun cachePrices(prices: Map<String, SinaStockData>, source: String) {
        val now = System.currentTimeMillis()
        memoryCache.putAll(
            prices.mapValues { (_, data) -> CachedPriceData(data, now, source) },
            now
        )
        trimMemoryCacheIfNeeded()
        maybeSaveToDisk()
    }

    private fun maybeSaveToDisk() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime > 60000) {
            lastSaveTime = now
            // 快照是纯内存拷贝，微秒级，留在调用线程拿；
            // 真正慢的 JSON 序列化 + writeText 丢给 diskWriter，调用方零阻塞
            val snapshot = memoryCache.snapshot()
            if (snapshot.isEmpty()) return
            try {
                diskWriter.execute { writeSnapshotToDisk(snapshot) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 执行器被拒绝（进程退出等极端情况）：只丢一次落盘，内存缓存还在
            }
        }
    }

    /**
     * 内存缓存淘汰：先清过期（这里固定用交易时段 5 分钟作为过期阈值，是原有口径），
     * 仍超上限再按写入时间从旧到新淘汰。具体淘汰算法在 [BoundedTtlCache.trim]，
     * 与其它取价链路共用一份实现。
     */
    private fun trimMemoryCacheIfNeeded() {
        memoryCache.trim(System.currentTimeMillis() - TRADING_HOUR_TTL)
    }

    fun invalidateCache(code: String) {
        memoryCache.remove(code)
    }

    fun invalidateAll() {
        memoryCache.clear()
        synchronized(diskLock) {
            try {
                cacheFile?.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("清空缓存文件失败: ${e.message}", tag = TAG)
            }
        }
    }

    private fun writeSnapshotToDisk(snapshot: Map<String, BoundedTtlCache.Entry<CachedPriceData>>) {
        val file = cacheFile ?: return

        synchronized(diskLock) {
            try {
                val jsonArray = JSONArray()

                snapshot.forEach { (code, entry) ->
                    val cached = entry.value
                    val jsonObject = JSONObject()
                    jsonObject.put("code", code)
                    jsonObject.put("timestamp", cached.timestamp)
                    jsonObject.put("source", cached.source)
                    jsonObject.put("name", cached.data.name)
                    jsonObject.put("price", cached.data.price)
                    jsonObject.put("change", cached.data.change)
                    jsonObject.put("changePercent", cached.data.changePercent)
                    jsonObject.put("open", cached.data.open)
                    jsonObject.put("high", cached.data.high)
                    jsonObject.put("low", cached.data.low)
                    jsonObject.put("volume", cached.data.volume)
                    // 昨收必须落盘：断网时 App 只读这份缓存，丢了它涨跌幅会按
                    // 「price - 0」重算，界面上一片 +100% 式的异常值。
                    jsonObject.put("prevClose", cached.data.prevClose)
                    jsonObject.put("amount", cached.data.amount)
                    jsonObject.put("date", cached.data.date)
                    jsonObject.put("time", cached.data.time)
                    jsonArray.put(jsonObject)
                }

                // 原子落盘：先写临时文件再 rename 覆盖。直接 file.writeText 若在
                // 进程中途被杀时被打断，会留下半截文件，下次 loadFromDisk 解析失败 →
                // 外层 catch 把整份缓存清空删掉，离线兜底数据全丢。
                val tmpFile = File(file.parentFile, "${file.name}.tmp")
                try {
                    tmpFile.writeText(jsonArray.toString())
                    if (!tmpFile.renameTo(file)) {
                        // rename 失败（极少见）：退回直接写，也别丢这次落盘
                        file.writeText(jsonArray.toString())
                        tmpFile.delete()
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    throw e
                }
                AppLogger.d("缓存已保存到磁盘: ${snapshot.size} 条", TAG)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("保存缓存失败: ${e.message}", tag = TAG)
            }
        }
    }

    private fun loadFromDisk() {
        val file = cacheFile ?: return
        synchronized(diskLock) {
            try {
                if (!file.exists() || file.length() == 0L) return

                val jsonString = file.readText()
                val jsonArray = JSONArray(jsonString)

                var skipped = 0
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.optJSONObject(i)
                    if (jsonObject == null) { skipped++; continue }

                    // 一律 optXxx + 逐条校验：**一条坏数据只跳过它自己**。
                    // 早年用 getString/getDouble/getLong，任一条目字段缺失或类型错就抛，
                    // 被外层 catch 连累成「清空整个内存缓存 + 删掉缓存文件」——
                    // 一条坏数据毁掉全部离线兜底数据，代价远大于问题本身。
                    val code = jsonObject.optString("code")
                    if (code.isEmpty()) { skipped++; continue }
                    val timestamp = jsonObject.optLong("timestamp", 0L)
                    val source = jsonObject.optString("source", SourceIds.SINA)

                    // 价格非正 = 坏数据：进了缓存会让市值按 0 显示
                    val price = jsonObject.optDouble("price", 0.0)
                    if (price <= 0.0) { skipped++; continue }

                    val data = SinaStockData(
                        name = jsonObject.optString("name"),
                        code = code,
                        price = price,
                        change = jsonObject.optDouble("change", 0.0),
                        changePercent = jsonObject.optDouble("changePercent", 0.0),
                        open = jsonObject.optDouble("open", 0.0),
                        high = jsonObject.optDouble("high", 0.0),
                        low = jsonObject.optDouble("low", 0.0),
                        volume = jsonObject.optLong("volume", 0L),
                        prevClose = jsonObject.optDouble("prevClose", 0.0),
                        amount = jsonObject.optDouble("amount", 0.0),
                        date = jsonObject.optString("date", ""),
                        time = jsonObject.optString("time", "")
                    )

                    // 不覆盖内存里已有的条目：后台加载与抓取回调可能并发
                    //（加载还没跑完时，一次刷新已经写进了新价），
                    // 用磁盘上的旧值盖掉刚抓到的新价，会让它因时间戳旧而被判过期、
                    // 下一轮又重抓一遍。putIfAbsent 保证磁盘只做「冷启动兜底」。
                    memoryCache.putIfAbsent(
                        code,
                        CachedPriceData(data, timestamp, source),
                        timestamp
                    )
                }

                AppLogger.d(
                    "从磁盘加载缓存: ${memoryCache.size()} 条" +
                        if (skipped > 0) "（跳过 $skipped 条坏数据）" else "",
                    TAG
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("加载缓存失败: ${e.message}", tag = TAG)
                memoryCache.clear()
                try {
                    file.delete()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    fun getCacheStats(): CacheStats {
        awaitDiskLoaded()
        val now = System.currentTimeMillis()
        val ttl = calculateTTL()
        val entries = memoryCache.snapshot().values
        val valid = entries.count { now - it.timestamp < ttl }
        return CacheStats(
            totalCount = entries.size,
            validCount = valid,
            expiredCount = entries.size - valid
        )
    }

    data class CacheStats(
        val totalCount: Int,
        val validCount: Int,
        val expiredCount: Int
    )
}
