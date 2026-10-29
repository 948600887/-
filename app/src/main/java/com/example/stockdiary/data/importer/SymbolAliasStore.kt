package com.example.stockdiary.data.importer

import android.annotation.SuppressLint
import android.content.Context
import com.example.stockdiary.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 「证券名称 → 证券代码」记忆库。
 *
 * 银河对账单截图没有证券代码，过去每行都得手填。
 * 用户第一次填过之后，这里把「名称 → 代码」记下来；下次同名标的
 * 由 [SymbolResolver] 自动回填，不再问第二遍。
 *
 * 存 SharedPreferences 而不是 Room：
 * - 它不是业务数据（删了只是多手填一次，不会丢账），不值得占用数据库表；
 * - 读取发生在 UI 交互路径上，SP 的进程内缓存读写足够快。
 *
 * 用 [Gson] 序列化成 JSON 字符串；解析失败时宁可清空重来（回到"手填"），
 * 也不能让坏数据卡死整个导入流程。
 */
class SymbolAliasStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 复用单例 Gson，避免每次读写都 new Gson() 重建 TypeAdapter 缓存 */
    private val gson = Gson()

    /** 返回「名称 → 代码」全表（键已归一化） */
    @Synchronized
    fun all(): Map<String, String> =
        readRaw().mapKeys { (name, _) -> SymbolResolver.key(name) }
            .filterKeys { it.isNotBlank() }

    /** 返回原始存储内容（名称未归一化，仅供设置页展示/排查） */
    @Synchronized
    fun allRaw(): Map<String, String> = readRaw()

    /**
     * 记住一个名称对应的代码。
     *
     * @return 是否发生了变化（用于决定是否要回写磁盘）
     */
    @Synchronized
    fun put(name: String, symbol: String): Boolean {
        val k = SymbolResolver.key(name)
        if (k.isBlank() || symbol.isBlank()) return false

        val current = readRaw()
        // 用归一化名作为写入键，避免「中证红利」与「中证 红利」存成两条
        val existingKey = current.keys.firstOrNull { SymbolResolver.key(it) == k }
        if (existingKey != null && current[existingKey] == symbol) return false

        val next = LinkedHashMap(current)
        if (existingKey != null) next.remove(existingKey)
        next[k] = symbol
        write(trimToLimit(next))
        AppLogger.d("记住证券名称 → 代码: $k = $symbol", "SymbolAlias")
        return true
    }

    @Synchronized
    fun remove(name: String) {
        val k = SymbolResolver.key(name)
        if (k.isBlank()) return
        val next = LinkedHashMap(readRaw())
        val existingKey = next.keys.firstOrNull { SymbolResolver.key(it) == k } ?: return
        next.remove(existingKey)
        write(next)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_MAP).apply()
    }

    private fun readRaw(): Map<String, String> {
        val json = prefs.getString(KEY_MAP, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            // 坏数据不应拖垮导入：清掉重来即可（最坏结果是用户再手填一次）
            AppLogger.w("证券名称记忆数据损坏，已清空: ${e.message}", "SymbolAlias")
            prefs.edit().remove(KEY_MAP).apply()
            emptyMap()
        }
    }

    /**
     * 同步写盘。
     *
     * 这里刻意用 commit() 而非 apply()：写入频率极低（用户手填一次才写一次），
     * 而 apply() 的异步落盘在「填完立刻杀进程」时会丢。IO 线程上调用，不会卡 UI。
     */
    private fun write(map: Map<String, String>) {
        runCatching {
            prefs.edit().putString(KEY_MAP, gson.toJson(map)).commit()
        }.onFailure {
            AppLogger.e("写入证券名称记忆失败: ${it.message}", it, "SymbolAlias")
        }
    }

    /** 超上限时淘汰最早的条目（LinkedHashMap 保持插入顺序） */
    private fun trimToLimit(map: LinkedHashMap<String, String>): LinkedHashMap<String, String> {
        if (map.size <= MAX_ENTRIES) return map
        val keep = LinkedHashMap<String, String>()
        map.entries.toList().takeLast(MAX_ENTRIES).forEach { keep[it.key] = it.value }
        AppLogger.w("证券名称记忆超过 $MAX_ENTRIES 条，已淘汰最早的 ${map.size - MAX_ENTRIES} 条", "SymbolAlias")
        return keep
    }

    companion object {
        private const val PREFS_NAME = "symbol_alias_store"
        private const val KEY_MAP = "name_to_symbol"
        private const val MAX_ENTRIES = 1000

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: SymbolAliasStore? = null

        fun getInstance(context: Context): SymbolAliasStore =
            instance ?: synchronized(this) {
                instance ?: SymbolAliasStore(context.applicationContext).also { instance = it }
            }
    }
}
