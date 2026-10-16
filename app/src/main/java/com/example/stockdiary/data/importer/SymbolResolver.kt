package com.example.stockdiary.data.importer

/**
 * 证券「名称 → 代码」解析器（纯函数，不依赖 Context/Android 框架，可单元测试）。
 *
 * ## 为什么需要它
 *
 * 银河证券对账单截图里只有证券名称（如「中证红利」「科创50」），**没有代码**。
 * 过去每一行都得用户手填 6 位代码；同一个标的每月导入一次就要重复填一遍，非常烦。
 *
 * ## 思路：把「已经知道答案」的地方全用上
 *
 * | 来源 | 说明 | 优先级 |
 * |---|---|---|
 * | 记忆 | 用户以前手填过的 名称→代码，[SymbolAliasStore] 持久化 | 最高（用户亲手确认过） |
 * | 持仓 | 库里 `assets` 表的 name→symbol | ← |
 * | 历史流水 | `transactions` 表的 name→symbol | ← |
 * | 证券库 | `stock_basic` 全市场表 | ← |
 * | 内置库 | StockPresets 种子数据 | 最低 |
 *
 * 只要用户以前成功导入过一次（或从别处建过持仓），以后同标的就再也不用填。
 *
 * ## 安全边界（比"省事"更重要）
 *
 * **自动填错代码会把 A 标的的买卖记到 B 标的名下**，成本、持仓、盈亏全错，
 * 而且这种错误是静默的——比让用户多敲 6 个数字严重得多。
 *
 * 因此规则只有一条：**名称完全一致 且 代码唯一，才自动回填**。
 * 同名多代码（沪深300ETF 有 sh510300 / sz159919 两只；AB 股、A+H 同理）
 * 一律判定为歧义，**不做自动回填**，只在候选列表里让用户点一下选。
 */
object SymbolResolver {

    /** 匹配来源（UI 上要告诉用户"这行代码是哪来的"，便于他判断可信度） */
    enum class Source(val label: String) {
        /** 用户以前手填并记住的 */
        ALIAS("记忆"),

        /** 库里已有持仓 */
        HOLDING("持仓"),

        /** 历史成交流水 */
        HISTORY("历史流水"),

        /** stock_basic 全市场证券库 */
        BASIC("证券库"),

        /** 内置预置列表 */
        PRESET("内置库")
    }

    data class Candidate(
        val symbol: String,
        val name: String,
        val source: Source
    )

    /** 一个「名称 → 代码」条目 */
    data class NameSymbol(val name: String, val symbol: String)

    /**
     * 多源索引。建一次，供一批交易的所有行复用（避免逐行查库）。
     *
     * 每个 map 的键是 [key] 归一化后的名称，值已经是**唯一**的代码；
     * 没能判定的歧义名称放进 [ambiguous]。
     */
    data class Index(
        val alias: Map<String, String> = emptyMap(),
        val holding: Map<String, String> = emptyMap(),
        val history: Map<String, String> = emptyMap(),
        val basic: Map<String, String> = emptyMap(),
        val preset: Map<String, String> = emptyMap(),
        /** 同名多个代码：只能做候选，不能自动填 */
        val ambiguous: Map<String, List<Candidate>> = emptyMap()
    ) {
        /** 5 个源按优先级合并后的「可回填」映射（低优先级先放，高优先级覆盖） */
        val merged: Map<String, String> by lazy {
            val out = LinkedHashMap<String, String>()
            out.putAll(preset)
            out.putAll(basic)
            out.putAll(history)
            out.putAll(holding)
            out.putAll(alias)
            out
        }
    }

    /** 名称归一化键：全角转半角、去空白、去常见装饰符、英文转小写 */
    fun key(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\u3000' -> Unit                       // 全角空格直接丢弃
                // 全角 → 半角。转换结果仍要小写化：OCR 偶尔把英文输出成全角（"ＥＴＦ"），
                // 不小写就会和库里的半角 "ETF" 对不上
                ch.code in 0xFF01..0xFF5E ->
                    sb.append((ch.code - 0xFEE0).toChar().lowercaseChar())
                ch.isWhitespace() -> Unit                     // 名称中间的 OCR 噪声空格
                ch == '*' || ch == '＊' -> Unit               // ST 标记：*STXX 与 STXX 视同名
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 按来源聚合一批「名称 → 代码」。
     *
     * @return Pair(唯一映射, 歧义映射)
     *
     * 同名却出现多个代码时做**多数表决**：条目多的那个代码胜出。
     * 这样历史流水里偶尔混入一条 OCR 噪声不会让本来清晰的映射失效；
     * 只有真正并列（或两倍及以上并列）时才判为歧义，交给用户选。
     */
    private fun aggregate(
        entries: List<NameSymbol>,
        source: Source
    ): Pair<Map<String, String>, Map<String, List<Candidate>>> {
        // key -> (symbol -> 出现次数 & 展示名)
        val grouped = LinkedHashMap<String, LinkedHashMap<String, Pair<Int, String>>>()
        for (e in entries) {
            val k = key(e.name)
            if (k.isBlank() || e.symbol.isBlank()) continue
            val bucket = grouped.getOrPut(k) { LinkedHashMap() }
            val prev = bucket[e.symbol]
            bucket[e.symbol] = if (prev == null) 1 to e.name else (prev.first + 1) to prev.second
        }

        val unique = LinkedHashMap<String, String>()
        val ambiguous = LinkedHashMap<String, List<Candidate>>()
        for ((k, bucket) in grouped) {
            val ranked = bucket.entries.sortedByDescending { it.value.first }
            if (ranked.size == 1 || ranked[0].value.first > ranked[1].value.first) {
                unique[k] = ranked[0].key
            } else {
                // 并列：不敢猜，交给用户点
                ambiguous[k] = ranked.map { Candidate(it.key, it.value.second, source) }
            }
        }
        return unique to ambiguous
    }

    /**
     * 构建索引。
     *
     * @param alias   记忆库（已是 Map，天然唯一）
     * @param history 历史流水，**要求按日期倒序传入**（虽然这里只看唯一性，顺序不影响结论）
     */
    fun buildIndex(
        alias: Map<String, String> = emptyMap(),
        holding: List<NameSymbol> = emptyList(),
        history: List<NameSymbol> = emptyList(),
        basic: List<NameSymbol> = emptyList(),
        preset: List<NameSymbol> = emptyList()
    ): Index {
        val aliasNormalized = LinkedHashMap<String, String>()
        for ((name, symbol) in alias) {
            val k = key(name)
            if (k.isNotBlank() && symbol.isNotBlank()) aliasNormalized[k] = symbol
        }

        val (h, hAmb) = aggregate(holding, Source.HOLDING)
        val (his, hisAmb) = aggregate(history, Source.HISTORY)
        val (b, bAmb) = aggregate(basic, Source.BASIC)
        val (p, pAmb) = aggregate(preset, Source.PRESET)

        val ambiguous = LinkedHashMap<String, List<Candidate>>()
        // 低优先级先放，高优先级同名覆盖（源码 raw 顺序：preset→basic→history→holding）
        for (m in listOf(pAmb, bAmb, hisAmb, hAmb)) ambiguous.putAll(m)

        return Index(
            alias = aliasNormalized,
            holding = h,
            history = his,
            basic = b,
            preset = p,
            ambiguous = ambiguous
        )
    }

    /**
     * 解析单个名称能自动回填的代码。
     *
     * @return 命中且唯一的候选；名称为空 / 无匹配 / 同名歧义时返回 null（不自动填）
     */
    fun resolveExact(name: String, index: Index): Candidate? {
        val k = key(name)
        if (k.isBlank()) return null
        // 记忆是用户亲手确认过的答案，权威高于一切：
        // 哪怕别的源认为这个名字有歧义（如沪深300ETF），用户选过的那只仍然先用它
        index.alias[k]?.let { return Candidate(it, name, Source.ALIAS) }
        // 其余来源：只要认定同名多代码，就绝不自动填
        if (index.ambiguous.containsKey(k)) return null

        val symbol = index.merged[k] ?: return null
        val source = when {
            index.alias.containsKey(k) -> Source.ALIAS
            index.holding.containsKey(k) -> Source.HOLDING
            index.history.containsKey(k) -> Source.HISTORY
            index.basic.containsKey(k) -> Source.BASIC
            else -> Source.PRESET
        }
        return Candidate(symbol, name, source)
    }

    /**
     * 给一个名称列出候选（用于预览页一键填充的 chips）。
     *
     * 包含三类：
     * 1. 精确命中（排最前，标 0 分）
     * 2. 模糊：归一化名互为前缀 / 相互包含（如「中证红利 」 vs 「中证红利ETF」）
     * 3. 歧义项：同名多代码，用户必须自己挑
     *
     * @param limit 最多返回几个（UI 上一行放得下为限）
     */
    fun suggest(name: String, index: Index, limit: Int = 4): List<Candidate> {
        val k = key(name)
        if (k.isBlank() || limit <= 0) return emptyList()

        data class Scored(val score: Int, val candidate: Candidate)

        val out = LinkedHashMap<String, Scored>() // symbol -> scored（去重）

        fun offer(symbol: String, candidateName: String, source: Source, score: Int) {
            val existed = out[symbol]
            if (existed == null || score < existed.score) out[symbol] = Scored(score, Candidate(symbol, candidateName, source))
        }

        fun fuzzyScore(otherKey: String): Int? {
            if (otherKey == k) return 0
            if (otherKey.length < 2 || k.length < 2) return null
            return when {
                otherKey.startsWith(k) || k.startsWith(otherKey) -> 1
                otherKey.contains(k) || k.contains(otherKey) -> 2
                else -> null
            }
        }

        resolveExact(name, index)?.let { offer(it.symbol, name, it.source, 0) }

        val pools = listOf(
            Source.ALIAS to index.alias.mapValues { it.value to it.key },
            Source.HOLDING to index.holding.mapValues { it.value to it.key },
            Source.HISTORY to index.history.mapValues { it.value to it.key },
            Source.BASIC to index.basic.mapValues { it.value to it.key },
            Source.PRESET to index.preset.mapValues { it.value to it.key }
        )
        for ((source, pool) in pools) {
            for ((otherKey, pair) in pool) {
                val score = fuzzyScore(otherKey) ?: continue
                offer(pair.first, pair.second, source, score)
            }
        }
        for ((_, candidates) in index.ambiguous) {
            for (c in candidates) {
                val score = fuzzyScore(key(c.name)) ?: continue
                offer(c.symbol, c.name, c.source, score)
            }
        }

        return out.values
            .sortedWith(compareBy<Scored> { it.score }.thenBy { it.candidate.symbol })
            .take(limit)
            .map { it.candidate }
    }
}
