package com.example.stockdiary.network.cache

import com.example.stockdiary.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap

object CacheManager {
    private const val TAG = "CacheManager"
    
    private val memoryCache = ConcurrentHashMap<String, CacheEntry<*>>()
    private const val DEFAULT_TTL = 5 * 60 * 1000L
    
    data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val ttl: Long = DEFAULT_TTL
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - timestamp > ttl
    }
    
    fun <T> put(key: String, data: T, ttl: Long = DEFAULT_TTL) {
        memoryCache[key] = CacheEntry(data, System.currentTimeMillis(), ttl)
        AppLogger.d("Cache put: $key (TTL: ${ttl / 1000}s)", TAG)
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, forceRefresh: Boolean = false): T? {
        val entry = memoryCache[key] ?: return null
        
        if (forceRefresh || entry.isExpired) {
            memoryCache.remove(key)
            AppLogger.d("Cache expired/removed: $key", TAG)
            return null
        }
        
        AppLogger.d("Cache hit: $key", TAG)
        return entry.data as T
    }
    
    fun invalidate(key: String) {
        memoryCache.remove(key)
        AppLogger.d("Cache invalidated: $key", TAG)
    }
    
    fun invalidateAll() {
        memoryCache.clear()
        AppLogger.i("All cache cleared", TAG)
    }
    
    fun <T> getOrPut(key: String, ttl: Long = DEFAULT_TTL, factory: () -> T): T {
        get<T>(key)?.let { return it }
        val data = factory()
        put(key, data, ttl)
        return data
    }
    
    fun getCacheInfo(): Map<String, CacheInfo> {
        return memoryCache.mapValues { (key, entry) ->
            CacheInfo(
                key = key,
                age = System.currentTimeMillis() - entry.timestamp,
                ttl = entry.ttl,
                remainingTTL = entry.ttl - (System.currentTimeMillis() - entry.timestamp),
                isExpired = entry.isExpired
            )
        }
    }
    
    fun cleanExpired() {
        val expiredKeys = memoryCache.filter { it.value.isExpired }.keys
        expiredKeys.forEach { memoryCache.remove(it) }
        AppLogger.d("Cleaned ${expiredKeys.size} expired entries", TAG)
    }
    
    fun getStats(): CacheStats {
        val entries = memoryCache.values
        return CacheStats(
            totalEntries = entries.size,
            expiredEntries = entries.count { it.isExpired },
            totalSize = entries.mapNotNull { it.data }.sumOf { estimateSize(it) }
        )
    }
    
    private fun estimateSize(data: Any): Int {
        return when (data) {
            is String -> data.length
            is Number -> 8
            is Collection<*> -> data.size * 100
            else -> 100
        }
    }
    
    data class CacheInfo(
        val key: String,
        val age: Long,
        val ttl: Long,
        val remainingTTL: Long,
        val isExpired: Boolean
    ) {
        val ageSeconds: Long get() = age / 1000
        val remainingSeconds: Long get() = remainingTTL / 1000
    }
    
    data class CacheStats(
        val totalEntries: Int,
        val expiredEntries: Int,
        val totalSize: Int
    ) {
        val activeEntries: Int get() = totalEntries - expiredEntries
    }
}


