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

    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedPriceData>()
    private var cacheFile: File? = null
    @Volatile
    private var lastSaveTime = 0L
    private val diskLock = Any()
    private const val MAX_MEMORY_CACHE_SIZE = 200
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
            loadFromDisk()
        }
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
        val cached = memoryCache[code] ?: return true

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
        val cached = memoryCache[code] ?: return false

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
        return if (isCacheValid(code)) {
            memoryCache[code]?.data
        } else {
            memoryCache.remove(code)
            null
        }
    }

    fun cachePrice(code: String, data: SinaStockData, source: String = "sina") {
        memoryCache[code] = CachedPriceData(data, System.currentTimeMillis(), source)
        trimMemoryCacheIfNeeded()
        maybeSaveToDisk()
    }

    fun cachePrices(prices: Map<String, SinaStockData>, source: String = "sina") {
        val now = System.currentTimeMillis()
        prices.forEach { (code, data) ->
            memoryCache[code] = CachedPriceData(data, now, source)
        }
        trimMemoryCacheIfNeeded()
        maybeSaveToDisk()
    }

    private fun maybeSaveToDisk() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime > 60000) {
            lastSaveTime = now
            // 快照是纯内存拷贝，微秒级，留在调用线程拿；
            // 真正慢的 JSON 序列化 + writeText 丢给 diskWriter，调用方零阻塞
            val snapshot = memoryCache.toMap()
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

    private fun trimMemoryCacheIfNeeded() {
        if (memoryCache.size <= MAX_MEMORY_CACHE_SIZE) return

        val expireThreshold = System.currentTimeMillis() - TRADING_HOUR_TTL
        val expiredKeys = memoryCache.filter { it.value.timestamp < expireThreshold }.keys
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { memoryCache.remove(it) }
        }

        if (memoryCache.size > MAX_MEMORY_CACHE_SIZE) {
            val sorted = memoryCache.entries.sortedBy { it.value.timestamp }
            val removeCount = memoryCache.size - MAX_MEMORY_CACHE_SIZE
            sorted.take(removeCount).forEach { memoryCache.remove(it.key) }
        }
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

    private fun writeSnapshotToDisk(snapshot: Map<String, CachedPriceData>) {
        val file = cacheFile ?: return

        synchronized(diskLock) {
            try {
                val jsonArray = JSONArray()

                snapshot.forEach { (code, cached) ->
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

                file.writeText(jsonArray.toString())
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

                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)

                    val code = jsonObject.getString("code")
                    val timestamp = jsonObject.getLong("timestamp")
                    val source = jsonObject.optString("source", "sina")

                    val data = SinaStockData(
                        name = jsonObject.getString("name"),
                        code = code,
                        price = jsonObject.getDouble("price"),
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

                    memoryCache[code] = CachedPriceData(data, timestamp, source)
                }

                AppLogger.d("从磁盘加载缓存: ${memoryCache.size} 条", TAG)
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
        val now = System.currentTimeMillis()
        val valid = memoryCache.count { now - it.value.timestamp < calculateTTL() }
        return CacheStats(
            totalCount = memoryCache.size,
            validCount = valid,
            expiredCount = memoryCache.size - valid
        )
    }

    data class CacheStats(
        val totalCount: Int,
        val validCount: Int,
        val expiredCount: Int
    )
}
