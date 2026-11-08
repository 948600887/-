package com.example.stockdiary.data.price

import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内有界缓存：**所有取价链路共用的唯一存储实现**。
 *
 * 只统一「怎么存」：有界容量、写入时间戳、淘汰、清空、快照。
 * **不统一「多久算新鲜」**：各链路的 TTL 口径是刻意不同的 —— 股票按交易时段动态
 * 5 / 30 分钟、16 点后与周末复用收盘价；基金 5 分钟且「已是今日净值」即便 forceRefresh
 * 也复用；加密 60 秒；新浪内存 5 分钟。因此 [trim] 的过期阈值由调用方传入，
 * 读取时是否新鲜也由调用方自己判定。本类合并只为消掉五份手写的淘汰 / 计数代码，
 * 绝不用来把新鲜度改成同一个数。
 *
 * 淘汰顺序与原先各链路一致：先按 [trim] 传入的过期阈值清掉过期条目，
 * 仍超上限再按写入时间从旧到新淘汰；传 [Long.MIN_VALUE] 表示「不按过期清，
 * 只在超上限时淘汰最旧」（新浪内存 / StockDataManager 原行为）。
 */
class BoundedTtlCache<K : Any, V : Any>(private val maxEntries: Int) {

    data class Entry<V>(val value: V, val timestamp: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    /**
     * 整段淘汰加锁：先读 size 决定淘汰多少、再排序删除，这组操作本身不是原子的。
     * 但**只锁 trim 不够**——[put] 不参与这把锁，仍可能「快照之后、删除之前」把同一个 key
     * 写成新价，随后按 key 删除会把这条新价一起删掉。因此淘汰一律用
     * `map.remove(key, 快照里的 Entry)`（ConcurrentHashMap 的「值相等才删」原子操作）：
     * 时间戳/值已变就删不掉，并发写入的新价不会被当成旧条目误删。
     */
    private val trimLock = Any()

    fun get(key: K): Entry<V>? = map[key]

    fun getValue(key: K): V? = map[key]?.value

    fun put(key: K, value: V, timestamp: Long = System.currentTimeMillis()) {
        map[key] = Entry(value, timestamp)
    }

    /** 批量写入同一时间戳（一次批量行情回填整批价，时间戳应当一致） */
    fun putAll(values: Map<K, V>, timestamp: Long = System.currentTimeMillis()) {
        values.forEach { (key, value) -> map[key] = Entry(value, timestamp) }
    }

    /** 只在不覆盖已有条目的前提下写入，返回是否真的写入（磁盘冷启动回填用） */
    fun putIfAbsent(key: K, value: V, timestamp: Long = System.currentTimeMillis()): Boolean =
        map.putIfAbsent(key, Entry(value, timestamp)) == null

    fun remove(key: K): Entry<V>? = map.remove(key)

    fun clear() {
        map.clear()
    }

    fun size(): Int = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    /** 快照：转为普通 Map，调用方可安全遍历 / 序列化（不持有 ConcurrentHashMap 的实时视图） */
    fun snapshot(): Map<K, Entry<V>> = map.toMap()

    /**
     * 超上限时淘汰。
     *
     * @param expireBeforeMillis 时间戳早于它的条目视为过期、优先清掉；
     *   传 [Long.MIN_VALUE] 表示不做过期清理，只在超上限时淘汰最旧条目。
     */
    fun trim(expireBeforeMillis: Long) {
        if (map.size <= maxEntries) return
        synchronized(trimLock) {
            if (map.size <= maxEntries) return

            if (expireBeforeMillis != Long.MIN_VALUE) {
                map.entries.toList()
                    .filter { it.value.timestamp < expireBeforeMillis }
                    // 条件删除：仅当当前值与快照完全一致（含时间戳）才删。
                    // 若并发 put 已写入新值，这里删不掉，避免把刚刷新的价当旧条目误删。
                    .forEach { map.remove(it.key, it.value) }
            }

            val overflow = map.size - maxEntries
            if (overflow > 0) {
                map.entries.sortedBy { it.value.timestamp }
                    .take(overflow)
                    .forEach { map.remove(it.key, it.value) }
            }
        }
    }
}
