package com.example.stockdiary.data.price

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PriceCacheManager] 磁盘缓存「旧中文源名」兼容性回归（G3 数据质量债）。
 *
 * 磁盘旧缓存里可能残留中文源名（如 `"新浪财经"`）。G3 把落盘默认值统一成
 * [SourceIds.SINA]，但**不改 JSON 格式**——老缓存照常能读回来。本测试构造一份
 * 含旧中文 `source` 的 `price_cache.json`，驱动私有 `loadFromDisk` 加载，
 * 断言：
 * 1. 旧格式仍能读回（raw source == `"新浪财经"`），证明 JSON 格式兼容；
 * 2. 读回的 raw source 经 [SourceIds.normalizeLegacySource] 归一为稳定 ID `sina`，
 *    证明血缘口径不会被旧数据污染。
 *
 * 直接用反射驱动 `loadFromDisk`（不依赖单例 init 状态 / 后台线程），并在 finally 里
 * 还原 `cacheFile` 字段、清理注入的缓存条目，避免污染同 JVM 内的其它用例。
 */
@Suppress("UNCHECKED_CAST")
class PriceCacheManagerSourceTest {

    @Test
    fun `磁盘旧中文源名经loadFromDisk读回_且可被normalizeLegacySource归一`() {
        val mgrClass = PriceCacheManager::class.java
        val cacheFileField = mgrClass.getDeclaredField("cacheFile").apply { isAccessible = true }
        val memoryCacheField = mgrClass.getDeclaredField("memoryCache").apply { isAccessible = true }
        val memoryCache =
            memoryCacheField.get(PriceCacheManager) as BoundedTtlCache<String, PriceCacheManager.CachedPriceData>

        val originalCacheFile = cacheFileField.get(PriceCacheManager) as? File

        val dir = Files.createTempDirectory("pcm_cache_src").toFile()
        val jsonFile = File(dir, "price_cache.json")
        // 旧格式：source 字段是中文名「新浪财经」，其余字段照旧（不改 JSON 格式）
        val json = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("code", "sh600519")
                    put("timestamp", System.currentTimeMillis())
                    put("source", "新浪财经")
                    put("name", "贵州茅台")
                    put("price", 1680.0)
                    put("change", 0.0)
                    put("changePercent", 0.0)
                    put("open", 0.0)
                    put("high", 0.0)
                    put("low", 0.0)
                    put("volume", 0L)
                    put("prevClose", 0.0)
                    put("amount", 0.0)
                    put("date", "")
                    put("time", "")
                }
            )
        }
        jsonFile.writeText(json.toString())

        try {
            // 清掉注入 code 可能已有的残留，确保 putIfAbsent 能写入
            memoryCache.remove("sh600519")
            // 指向我们的测试文件
            cacheFileField.set(PriceCacheManager, jsonFile)
            // 直接驱动私有 loadFromDisk（不依赖后台线程 / 单例 init 状态）
            mgrClass.getDeclaredMethod("loadFromDisk").apply { isAccessible = true }
                .invoke(PriceCacheManager)

            val entry = memoryCache.getValue("sh600519")
                ?: error("loadFromDisk 后内存缓存应含 sh600519")
            val rawSource =
                entry.javaClass.getDeclaredField("source").apply { isAccessible = true }
                    .get(entry) as String

            // 1) 旧格式仍能读回（不改 JSON 格式即可兼容）
            assertEquals("新浪财经", rawSource)
            // 2) 读回的旧中文名可被归一为稳定 ID
            assertEquals(SourceIds.SINA, SourceIds.normalizeLegacySource(rawSource))
        } finally {
            // 还原单例状态，避免污染同 JVM 内的其它用例
            cacheFileField.set(PriceCacheManager, originalCacheFile)
            memoryCache.remove("sh600519")
            jsonFile.delete()
            dir.delete()
        }
    }
}
