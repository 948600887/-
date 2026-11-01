package com.example.stockdiary.data.importer

import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.BinanceBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归真机 P0：**主行被自己的「清零行」覆盖**。
 *
 * ## 故障链（逐环都能在本文件里单独钉住）
 *
 * 1. [mergeEarnIntoBalances] 把同一底层的多行折叠成一行，被折掉的行以
 *    `BinanceBalance(asset = member.asset, free = 0.0, locked = 0.0)` 返回；
 * 2. 那条折叠行保留**原始资产名**（理财壳 `LDBNB`），而 [symbolForBalance] 会把 LD 剥成
 *    底层交易对 `BNBUSDT` —— 与主行（现货 `BNB`）**映射到同一个 symbol**；
 * 3. [findSupersededAssets] 用 `underlyingKeyOf(symbolForBalance(...))` 求折叠键，得到 `{BNB}`，
 *    于是「库里同底层」的 `BNBUSDT` 主行也被选成「要清零的旧行」；
 * 4. commit 先写 items 的主行数量、再写同一 id 的 0 —— 后写覆盖前写，资产被清空。
 *
 * 真机日志实锤：连续两次导入都是「共 145 条，新增 0，覆盖 136，未变 9」，两次一模一样，
 * 就是因为上一轮写对的数量被自己清掉了；下一轮预览自然满屏「覆盖 0 → 0.00000299」。
 *
 * ## 修法
 *
 * [findSupersededAssets] 增加 `importedSymbols`（本次要落库的 symbol 集合），
 * 过滤时排除它们：只清零「同底层、但本次不作为任何 item 落库」的历史行。
 */
class BinanceSupersededZeroTest {

    /** 造一条库里已有的加密资产（symbol 用交易对形态，与落库/取价一致） */
    private fun cryptoAsset(id: Long, symbol: String, balance: Double) = Asset(
        id = id,
        name = symbol.removeSuffix("USDT"),
        type = AssetType.CRYPTO,
        symbol = symbol,
        account = "币安",
        balance = balance,
        purchasePrice = 0.0
    )

    private fun balance(
        asset: String,
        free: Double,
        locked: Double = 0.0,
        kind: BinanceBalance.Kind = BinanceBalance.Kind.SPOT
    ) = BinanceBalance(asset = asset, free = free, locked = locked, kind = kind)

    /**
     * 核心回归：折叠行（LDBNB 归零行）与主行（BNB）同 symbol，**主行绝不能被清零**。
     *
     * 真机形状：现货 BNB 0.0006 + 活期理财壳 LDBNB 被折叠 → 主行 `BNBUSDT`。
     * 库里已有一条 `BNBUSDT`（上一轮导入写进去的数量，理论上已被清零过）。
     * 修复前 `findSupersededAssets` 会把它当成「被折掉的旧行」返回，commit 随即写 0。
     */
    @Test
    fun 折叠行不得把同symbol的主行当成旧行清零() {
        // 与 mergeEarnIntoBalances 的输出形状一致：主行有数量、被折掉的那行数量为 0
        val mergedBalances = listOf(
            balance("BNB", free = 0.0006),
            balance("LDBNB", free = 0.0, kind = BinanceBalance.Kind.EARN)
        )
        val existing = listOf(
            cryptoAsset(id = 1L, symbol = "BNBUSDT", balance = 1.0)
        )

        val superseded = findSupersededAssets(
            balances = mergedBalances,
            existingAssets = existing,
            importedSymbols = setOf("BNBUSDT")
        )

        assertTrue(
            "主行 BNBUSDT 本次要落库，绝不能出现在清零名单里（否则导入后数量变 0）",
            superseded.none { it.symbol == "BNBUSDT" }
        )
        assertTrue("没有其它历史旧行时应清零 0 条", superseded.isEmpty())
    }

    /**
     * 历史的 LD 裸码行仍然必须被清零：它已经并进主行了，留着就是同一笔钱记两遍。
     *
     * 这是「排除 importedSymbols」必须**不能**误伤的既有能力——LDBNB 不在本次落库的
     * symbol 集合里（本次落的是 BNBUSDT），所以照旧被选中。
     */
    @Test
    fun 历史LD裸码行仍被清零() {
        val mergedBalances = listOf(
            balance("BNB", free = 0.5),
            balance("LDBNB", free = 0.0, kind = BinanceBalance.Kind.EARN)
        )
        val existing = listOf(
            cryptoAsset(id = 1L, symbol = "BNBUSDT", balance = 1.0),
            // 历史版本按 LD 裸码落过一行：必须清掉（钱已并入 BNBUSDT 主行）
            cryptoAsset(id = 2L, symbol = "LDBNB", balance = 1.0)
        )

        val superseded = findSupersededAssets(
            balances = mergedBalances,
            existingAssets = existing,
            importedSymbols = setOf("BNBUSDT")
        )

        assertTrue(
            "历史 LD 裸码行必须继续被清零（否则同一笔钱记两遍）",
            superseded.any { it.symbol == "LDBNB" }
        )
        assertTrue(
            "主行仍不应被清零",
            superseded.none { it.symbol == "BNBUSDT" }
        )
    }

    /**
     * 借币负债 / 抵押行既有保护不能丢：它们带 `#loan` / `#collateral` 后缀，
     * 归组时会被剥掉后缀与现货同键，但**从来不是**「被折进主行的旧行」——
     * 一旦被清零，欠币安的钱凭空消失、净资产虚高。
     */
    @Test
    fun 借币负债与抵押行不被清零() {
        val mergedBalances = listOf(
            balance("USDT", free = 0.0),
            balance("LDUSDT", free = 124.59510564, kind = BinanceBalance.Kind.EARN),
            balance(
                asset = "USDT",
                free = -50.80033811,
                kind = BinanceBalance.Kind.LOAN_DEBT
            ),
            balance(
                asset = "USDC",
                free = 0.0,
                locked = 43.56582541,
                kind = BinanceBalance.Kind.LOAN_COLLATERAL
            )
        )
        val existing = listOf(
            cryptoAsset(id = 1L, symbol = "USDT", balance = 142.08565983),
            cryptoAsset(id = 2L, symbol = "USDT#loan", balance = -50.80033811),
            cryptoAsset(id = 3L, symbol = "USDCUSDT#collateral", balance = 43.56582541)
        )

        val superseded = findSupersededAssets(
            balances = mergedBalances,
            existingAssets = existing,
            importedSymbols = setOf("USDT", "USDT#loan", "USDCUSDT#collateral")
        )

        assertFalse("负债行绝不能被清零", superseded.any { it.symbol == "USDT#loan" })
        assertFalse("抵押行绝不能被清零", superseded.any { it.symbol == "USDCUSDT#collateral" })
        assertTrue("现货主行同样不该被清零", superseded.none { it.symbol == "USDT" })
    }

    /**
     * 稳定币（USDT）形状：主行 symbol 是**裸码 `USDT`**（稳定币不补 `USDT` 后缀，
     * 见 `symbolForBalance` 的「稳定币回落 baseAssetToSymbol」分支），与 BNB 那类
     * `BNBUSDT` 交易对形态不同。
     *
     * 这条单独钉住是因为真机上最痛的一笔就是 USDT（144.99 那条）：它的 symbol 形态
     * 与 BNB 不同，`importedSymbols` 的排除用的是**精确字符串匹配**，两种形态都得对。
     * 顺带钉住「历史 LDUSDT 裸码行照旧清零」——这是该函数不能被顺手废掉的原始用途。
     */
    @Test
    fun 稳定币主行不被清零_历史LDUSDT裸码行仍被清零() {
        val mergedBalances = listOf(
            balance("USDT", free = 142.08565983),
            balance("LDUSDT", free = 0.0, kind = BinanceBalance.Kind.EARN)
        )
        val existing = listOf(
            cryptoAsset(id = 1L, symbol = "USDT", balance = 1.0e-7),
            cryptoAsset(id = 2L, symbol = "LDUSDT", balance = 124.59510564)
        )

        val superseded = findSupersededAssets(
            balances = mergedBalances,
            existingAssets = existing,
            importedSymbols = setOf("USDT")
        )

        assertEquals(
            "只该清零历史 LDUSDT 裸码行（钱已并入 USDT 主行），主行必须留下",
            listOf(2L),
            superseded.map { it.id }
        )
    }

    /**
     * 反空跑守卫：上面几条「主行不被清零」的断言**必须**是被 `importedSymbols` 这条过滤
     * 挡住的，而不是恰好因为别的条件不成立才通过。
     *
     * 做法：同一组输入，只改第三个参数。若把 `importedSymbols` 传空，主行**就会**被选中
     * ——那正是 P0 的机制。所以：
     *
     * - 传了 → 不清零（修复生效）；
     * - 不传 → 被选中（证明 `foldedUnderlying` 确实命中了这个 symbol，前面的用例不是空跑）。
     *
     * 将来谁删掉 `if (symbol in importedSymbols) return@filter false`，第一条断言立刻变红。
     */
    @Test
    fun 主行是否被清零完全取决于importedSymbols_证明过滤是唯一防线() {
        val mergedBalances = listOf(
            balance("BNB", free = 0.0006),
            balance("LDBNB", free = 0.0, kind = BinanceBalance.Kind.EARN)
        )
        val existing = listOf(cryptoAsset(id = 1L, symbol = "BNBUSDT", balance = 1.0))

        val declared = findSupersededAssets(mergedBalances, existing, setOf("BNBUSDT"))
        val notDeclared = findSupersededAssets(mergedBalances, existing, emptySet())

        assertTrue("声明了主行 → 绝不清零：实际=$declared", declared.isEmpty())
        assertEquals(
            "没声明主行时它确实会被选中（P0 的机制），说明前面的通过不是空跑",
            listOf(1L),
            notDeclared.map { it.id }
        )
    }
}
