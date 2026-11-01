package com.example.stockdiary.data

import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.importer.earnMissingHint
import com.example.stockdiary.data.importer.mergeEarnIntoBalances
import com.example.stockdiary.data.repository.AssetRepository
import com.example.stockdiary.network.BinanceBalance
import com.example.stockdiary.network.BinanceEarnPosition
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机 USDT 数量偏差的**决定性复现**。
 *
 * ## 复现的是什么
 *
 * 用户账户（币安官方 App「理财」页实测）USDT 活期理财 142.08565983，本 App 导入后
 * 只有 124.59510564 —— 而 124.59510564 恰好是 `/api/v3/account` 里 `LDUSDT` 活期壳的值。
 * [mergeEarnIntoBalances] 的合并规则是「现货 + max(账户活期壳, 理财接口活期) + 定期」，
 * 拿到理财接口数据时该给出 142.08565983。
 *
 * ## 这组用例要判明什么（二选一，不许靠猜）
 *
 * - **绿**：合并逻辑没问题，实得的 124.59 只能是「simple-earn 接口在真机没拉到数据」，
 *   根因在拉取侧（权限 / 接口 / 解析），与 [mergeEarnIntoBalances] 无关；
 * - **红**：合并阶段真的把理财接口的数据丢了，修复点在 [mergeEarnIntoBalances]。
 *
 * ## 数据形状刻意照抄真机
 *
 * `/api/v3/account` 会把用户历史上接触过的资产**全部**列出来（大量 free/locked 都是 0），
 * 真机响应里因此同时存在 `USDT`（0）与 `LDUSDT`（124.59510564）两行。早先的复现只喂
 * 一行 `LDUSDT`，掩盖了「同组内多成员」这条路径，这里按真实形状喂。
 */
class BinanceEarnUsdtReproTest {

    private val repo = mockk<AssetRepository>(relaxed = true)

    init {
        coEvery { repo.inTransaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>()
        }
    }

    private val service = BinanceImportService(repo)

    private fun balance(asset: String, free: Double = 0.0, locked: Double = 0.0) =
        BinanceBalance(asset = asset, free = free, locked = locked)

    private fun earn(asset: String, amount: Double, locked: Boolean = false) =
        BinanceEarnPosition(asset = asset, amount = amount, locked = locked)

    /**
     * 真机形状的 `/api/v3/account` 余额。
     *
     * 关键：`USDT` 现货行是 0（钱全在活期理财里）、`LDUSDT` 壳 124.59510564；
     * USDC / LDUSDC 都是 0（刚被押去做借币抵押），另有一批历史零头行。
     */
    private fun realAccountBalances(): List<BinanceBalance> = listOf(
        balance("BTC", free = 0.00000299),
        balance("USD1", free = 0.37502608),
        balance("BNB", free = 0.00060867),
        balance("USDT", free = 0.0),
        balance("LDUSDT", free = 124.59510564),
        balance("USDC", free = 0.0),
        balance("LDUSDC", free = 0.0),
        balance("ETH", free = 0.0)
    )

    /** simple-earn 接口返回：USDT 活期 142.08565983、USDC 活期 43.57300526、BNB 定期一份 */
    private fun realEarnPositions(): List<BinanceEarnPosition> = listOf(
        earn("USDT", amount = 142.08565983),
        earn("USDC", amount = 43.57300526),
        earn("BNB", amount = 0.01, locked = true)
    )

    /**
     * 借币链路追加的余额（与 [BinanceImportService.fetchAccount] 里的 buildLoanBalances 同口径）：
     * 借入 USDT 50.80033811 记负；抵押给币安的 USDC / BNB 仍记正。
     */
    private fun realLoanBalances(): List<BinanceBalance> = listOf(
        BinanceBalance(
            asset = "USDT",
            free = -50.80033811,
            locked = 0.0,
            kind = BinanceBalance.Kind.LOAN_DEBT
        ),
        BinanceBalance(
            asset = "USDC",
            free = 0.0,
            locked = 43.56582541,
            kind = BinanceBalance.Kind.LOAN_COLLATERAL
        ),
        BinanceBalance(
            asset = "BNB",
            free = 0.0,
            locked = 0.0025457,
            kind = BinanceBalance.Kind.LOAN_COLLATERAL
        )
    )

    /**
     * **决定性判定**：理财接口给了 142.08565983 时，USDT 合并结果必须是 142.08565983。
     *
     * 账户侧：现货 0 + 活期壳 124.59510564；理财侧：活期全量 142.08565983。
     * max(124.59510564, 142.08565983) = 142.08565983，故 USDT 应为 142.08565983。
     * 若这条红了，就是合并阶段把理财数据丢了。
     */
    @Test
    fun 理财接口有数据时_USDT合并结果是官方全量() {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = realEarnPositions()
        )
        val usdt = merged.single {
            it.total != 0.0 && (it.asset == "USDT" || it.asset == "LDUSDT")
        }
        assertEquals(142.08565983, usdt.total, 1e-8)
    }

    /**
     * 合并结果经 buildPreview 走到预览行后，USDT 数量仍是 142.08565983。
     *
     * 这一条同时守住「合并之后、对齐内核之前」这段：对齐内核按 symbol 合并同代码行，
     * 折叠掉的零行不能再把数量改回去。
     */
    @Test
    fun 理财接口有数据时_预览行USDT数量是官方全量() = runBlocking {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = realEarnPositions()
        )
        val preview = service.buildPreview(merged + realLoanBalances(), existingAssets = emptyList())

        val usdt = preview.items.single { it.symbol == "USDT" }
        assertEquals(142.08565983, usdt.quantity, 1e-8)
    }

    /**
     * 借币负债是**独立追加**的负余额行，不并进 USDT 持仓、也不把 USDT 数量改小。
     *
     * 负债若在合并里被吞掉，净资产就会虚高 50.8 USDT（只记借来的钱、不记欠的钱）。
     */
    @Test
    fun 借币负债独立成行_不并入USDT持仓() = runBlocking {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = realEarnPositions()
        )
        val preview = service.buildPreview(merged + realLoanBalances(), existingAssets = emptyList())

        val debt = preview.items.single { it.symbol == "USDT#loan" }
        assertEquals(-50.80033811, debt.quantity, 1e-8)
        // 负债拉低的是净资产，不是 USDT 持仓数量
        assertEquals(142.08565983, preview.items.single { it.symbol == "USDT" }.quantity, 1e-8)
    }

    /**
     * 对照：理财接口一条都没拉到时，USDT 退化成账户壳值 124.59510564。
     *
     * 这正是真机现在的样子 —— 所以「预览显示 124.59」等价于「理财接口没给数据」，
     * 只要上一条是绿的，根因就锁定在拉取侧。
     */
    @Test
    fun 理财接口为空时_USDT退化为账户壳值() = runBlocking {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = emptyList()
        )
        val preview = service.buildPreview(merged + realLoanBalances(), existingAssets = emptyList())

        val usdt = preview.items.single { it.symbol == "USDT" }
        assertEquals(124.59510564, usdt.quantity, 1e-8)
    }

    /**
     * 理财接口只给回 USDT 时（USDC/BNB 一条都没有），USDT 仍要是 142.08565983。
     *
     * 隔离出「只缺 USDT 这一条」的最小场景，排除其它资产的干扰。
     */
    @Test
    fun 只有USDT一条理财持仓时也能补全() {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = listOf(earn("USDT", amount = 142.08565983))
        )
        val usdt = merged.single {
            it.total != 0.0 && (it.asset == "USDT" || it.asset == "LDUSDT")
        }
        assertEquals(142.08565983, usdt.total, 1e-8)
    }

    /**
     * 理财接口按 productId 拆成多行返回时（币安对同一资产的不同产品各自一行），
     * 同资产多行要先合计、再与账户壳取较大值。
     *
     * 真机 142.08565983 里有一块（17.49）是被押进抵押金库的份额，
     * 币安会把它作为独立 productId 返回。
     */
    @Test
    fun 同资产多行理财持仓先合计再比较() {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = listOf(
                earn("USDT", amount = 124.59510564),
                earn("USDT", amount = 17.49055419)
            )
        )
        val usdt = merged.single {
            it.total != 0.0 && (it.asset == "USDT" || it.asset == "LDUSDT")
        }
        assertEquals(142.08565983, usdt.total, 1e-8)
    }

    /**
     * 反向护栏：理财接口给的比账户壳小时（壳是全量、接口只报了部分），
     * 取较大值 → 仍是壳的 124.59510564，绝不能被接口的小值改小。
     */
    @Test
    fun 理财接口数据比壳小时不被改小() {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = listOf(earn("USDT", amount = 100.0))
        )
        val usdt = merged.single {
            it.total != 0.0 && (it.asset == "USDT" || it.asset == "LDUSDT")
        }
        assertEquals(124.59510564, usdt.total, 1e-8)
    }

    // ---------- 「接口一条都没返回」必须与「真的没有理财」区分开 ----------

    @Test
    fun 账户有理财壳但接口空手而归时要给出提示() {
        // 真机现状：/api/v3/account 里 LDUSDT 壳 124.59510564（理财持仓的铁证），
        // 而 simple-earn 接口一条都没返回。HTTP 是 200，所以 failures 为空、旧代码完全静默，
        // 用户只看到 124.59 却不知道为什么少了 17.49。这种自相矛盾必须说出来。
        val hint = earnMissingHint(
            balances = realAccountBalances(),
            positions = emptyList()
        )
        assertNotNull("有壳无接口数据时必须提示，不能静默", hint)
        val text = hint ?: ""
        assertTrue("提示要说到点子上（Simple Earn 权限）", text.contains("simple-earn"))
        assertTrue("提示要点名是哪个资产（USDT）", text.contains("USDT"))
        assertFalse("提示不该抛 LD 裸码给用户（LD 是壳内部代码）", text.contains("LDUSDT"))
    }

    @Test
    fun 接口给回数据时不再提示() {
        val hint = earnMissingHint(
            balances = realAccountBalances(),
            positions = realEarnPositions()
        )
        assertEquals(null, hint)
    }

    @Test
    fun 账户里只有零壳时不算矛盾() {
        // 币安会把历史接触过的资产全列成 0 余额：0 壳不代表用户真的持有理财。
        // 账户无理财 + 接口也无数据 → 是真的没有理财持仓，不该给用户假警报。
        val zeroShells = listOf(
            BinanceBalance(asset = "USDT", free = 0.0, locked = 0.0),
            BinanceBalance(asset = "LDUSDT", free = 0.0, locked = 0.0),
            BinanceBalance(asset = "BTC", free = 0.5, locked = 0.0)
        )
        assertEquals(null, earnMissingHint(balances = zeroShells, positions = emptyList()))
    }

    @Test
    fun 没有LD壳的账户不误报() {
        // 纯现货账户（钱不在理财里）→ 接口返回空是正常的，不提示
        val spotOnly = listOf(
            BinanceBalance(asset = "USDT", free = 100.0, locked = 0.0),
            BinanceBalance(asset = "BTC", free = 0.5, locked = 0.0),
            // LDO 只是恰好以 LD 开头的真实现货，不算理财壳
            BinanceBalance(asset = "LDO", free = 3.0, locked = 0.0)
        )
        assertEquals(null, earnMissingHint(balances = spotOnly, positions = emptyList()))
    }

    /**
     * 归并后 USDT 只留一行：被折叠掉的那行必须归零，
     * 否则同一笔钱会在列表里留两行（124.59 与 142.08 并存）。
     */
    @Test
    fun USDT归并后只剩一行有钱() {
        val merged = mergeEarnIntoBalances(
            balances = realAccountBalances(),
            earn = realEarnPositions()
        )
        val usdtRows = merged.filter { it.asset == "USDT" || it.asset == "LDUSDT" }
        assertEquals(2, usdtRows.size)
        assertEquals(1, usdtRows.count { it.total != 0.0 })
        assertNotNull(usdtRows.single { it.total != 0.0 })
    }
}
