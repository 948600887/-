package com.example.stockdiary.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [BinanceHostGate] 三态熔断器状态机回归。
 *
 * 钉的契约：
 * - 单波抖动（3 连失败，=旧阈值=同步并发度）不再误熔；
 * - 累计 5 次才熔断、blockCount +1；
 * - 冷却期内 tryAcquireProbe 恒拒绝；
 * - 冷却结束只放行 1 个半开探测、并发第 2 个仍拒；
 * - 探测成功闭路、后续全放行、计数清零、blockCount 不涨；
 * - 探测失败立即重开冷却（不等计数到阈值）；
 * - OPEN 期迟到成功不解封；成功后计数清零；
 * - 并发下只熔断一次且计数不越界；
 * - 卡死的探测过期后自愈重新武装。
 *
 * 契约一旦退化，症状是两个极端：「币安连不上时每轮同步白烧 20s×N 个标的、还弹同步失败通知」，
 * 或「单波抖动就误熔半个组合 10 分钟」——正是本轮要消除的。
 *
 * BinanceHostGate 是进程内 object：`@Before`/`@After` 复位保证本类用例互不污染，
 * 也防止熔断状态漏给别的测试类。
 */
class BinanceHostGateTest {

    /** 固定基准时间（epoch ms），与真实时钟解耦，时间断言才稳定 */
    private val base = 1_700_000_000_000L

    /** 冷却结束的那一刻（now == blockedUntil 即已解封，可竞争探测） */
    private val afterCooldown: Long get() = base + BinanceHostGate.COOLDOWN_MS

    @Before
    fun resetGate() {
        BinanceHostGate.resetForTesting()
    }

    @After
    fun resetGateAfter() {
        // 与 @Before 对称：用例跑完留下的熔断状态不能漏给后续测试类
        BinanceHostGate.resetForTesting()
    }

    /** 连打 FAIL_THRESHOLD 次失败，把闸门打到 OPEN */
    private fun tripOpen(at: Long = base) {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(at) }
    }

    // ---------- 阈值：误伤修复 ----------

    @Test
    fun `连续阈值减一次失败未达阈值_不熔断`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD - 1) { BinanceHostGate.onNetworkFailure(base) }
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        // CLOSED 仍放行
        assertTrue(BinanceHostGate.tryAcquireProbe(base))
        assertEquals(BinanceHostGate.FAIL_THRESHOLD - 1, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `单波 3 连失败不熔断_钉死误伤修复`() {
        // 3 = 旧阈值 = 同步并发度：单波抖动（3 个在途同时挂满 20s）旧实现会误熔半个组合。
        // 新阈值 5，3 连失败必须仍在 CLOSED、后续请求照常放行、不熔断。
        repeat(3) { BinanceHostGate.onNetworkFailure(base) }
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.tryAcquireProbe(base))
        assertFalse(BinanceHostGate.isUnavailable())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
    }

    @Test
    fun `累计到阈值那次失败触发熔断且blockCount加一`() {
        tripOpen()
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertFalse(BinanceHostGate.tryAcquireProbe(base))
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 触发熔断时计数清零：冷却期内的迟来失败重新累计
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(base + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
    }

    // ---------- OPEN 冷却期 ----------

    @Test
    fun `冷却期内 tryAcquireProbe 恒拒绝`() {
        tripOpen()
        assertFalse(BinanceHostGate.tryAcquireProbe(base))
        assertFalse(BinanceHostGate.tryAcquireProbe(base + BinanceHostGate.COOLDOWN_MS - 1))
        // 冷却期一直是「暂不可达」，UI 该显示提示
        assertTrue(BinanceHostGate.isUnavailable())
    }

    @Test
    fun `OPEN 期间迟到成功不解封，仅清零计数`() {
        tripOpen()
        // 冷却期内又来了两次失败（并发迟来）：计数被压到阈值以下，不为 0
        BinanceHostGate.onNetworkFailure(base + 1_000)
        BinanceHostGate.onNetworkFailure(base + 2_000)
        // 一次成功把计数清零
        BinanceHostGate.onSuccess()
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        // 但熔断不因此提前解封：成功不清 blockedUntil，避免抖动解封
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.tryAcquireProbe(base + 3_000))
        assertFalse(BinanceHostGate.tryAcquireProbe(base + BinanceHostGate.COOLDOWN_MS - 1))
    }

    /**
     * 阈值无关写法：先攒 2 次 → 成功清零 → 再攒到「阈值-1」仍不熔断 → 再 1 次才熔断。
     * 用 [BinanceHostGate.FAIL_THRESHOLD] 推导，改阈值时本用例自动适配（曾因阈值硬编码 5 而假绿）。
     */
    @Test
    fun `onSuccess 单独调用能把计数清零_需重新累计到阈值才熔断`() {
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onNetworkFailure(base)
        BinanceHostGate.onSuccess()
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        // 若计数没被清零，这里 (阈值-1)+2 早已越过阈值；清零后只到 (阈值-1)，不熔断
        repeat(BinanceHostGate.FAIL_THRESHOLD - 1) { BinanceHostGate.onNetworkFailure(base) }
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.tryAcquireProbe(base))
        // 再失败一次才重新熔断
        BinanceHostGate.onNetworkFailure(base)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.tryAcquireProbe(base))
    }

    /**
     * **本轮最重要的回归**：一整波并发超时（= 同步并发度 6）必须**不熔断**。
     *
     * 真机诊断 20261026_230142 实锤：10:33 与 20:07 各有一波 6 个在途请求同时
     * `InterruptedIOException: timeout`，而旧阈值 5 ≤ 并发度 6 ⇒ **一波就打满阈值**，
     * 10:33 那轮直接 跳过 130 个（币安熔断 129）、耗时 32s（正常轮 5~11s）。
     * 铁律：阈值必须严格大于同步并发度，否则单波抖动必然误熔整个加密货币组合。
     */
    @Test
    fun `单波6连失败不熔断_阈值必须高于同步并发度`() {
        val concurrency = 6 // AssetSyncManager.MAX_CONCURRENT_SYNC
        assertTrue(
            "阈值(${BinanceHostGate.FAIL_THRESHOLD}) 必须严格大于同步并发度($concurrency)，否则单波抖动必然误熔",
            BinanceHostGate.FAIL_THRESHOLD > concurrency
        )
        repeat(concurrency) { BinanceHostGate.onNetworkFailure(base) }
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(concurrency, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
        assertTrue("一整波并发超时后，后续请求仍应正常放行", BinanceHostGate.tryAcquireProbe(base))
        assertFalse(BinanceHostGate.isUnavailable())
    }

    // ---------- HALF_OPEN 半开探测 ----------

    @Test
    fun `冷却结束恰好 1 个探测放行_并发第 2 个仍拒`() {
        tripOpen()
        // 冷却已过：第一个 tryAcquireProbe 竞争成为唯一的半开探测
        assertTrue("冷却结束后应放行恰好一个探测", BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertEquals(Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())
        // 半开期探测已在进行：其余请求全部拒绝，不烧时间
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + 1))
        // 半开期对 UI 仍是「暂不可达」
        assertTrue(BinanceHostGate.isUnavailable())
    }

    @Test
    fun `探测成功闭路_后续全放行且 blockCount 不涨`() {
        tripOpen()
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown)) // 成为探测
        BinanceHostGate.onSuccess() // 探测成功
        // 闭路：全部清零，blockCount 不涨（探测成功是恢复，不是又一次判死）
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.consecutiveFailuresForTesting())
        assertEquals(0L, BinanceHostGate.blockedUntilForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 后续请求全放行、UI 提示消失
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown + 1))
        assertFalse(BinanceHostGate.isUnavailable())
    }

    @Test
    fun `探测失败立即重开冷却_不等计数到阈值`() {
        tripOpen()
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown)) // 成为探测
        // 探测失败：主机仍不可达的确定证据，立即重开冷却，不等累计到阈值
        BinanceHostGate.onNetworkFailure(afterCooldown)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        // 截止时间按探测失败的 now 顺延一整轮冷却
        assertEquals(afterCooldown + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
        // 重开不计入 blockCount（是同一次熔断 episode 的延续，不是新的判死）
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 新一轮冷却内继续拒绝
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.COOLDOWN_MS - 1))
    }

    @Test
    fun `探测卡死自愈_超过 PROBE_TIMEOUT 重新武装`() {
        tripOpen()
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown)) // 成为探测，probeUntil = afterCooldown + PROBE_TIMEOUT
        // 探测窗口内：仍是 HALF_OPEN、拒绝新请求
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS - 1))
        // 探测卡死（被取消、未 resolve）：超过 PROBE_TIMEOUT 后自愈，重新武装一个新探测并放行
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS))
        assertEquals(Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())
        // 重新武装后立刻又是「只有一个探测」：同一 now 再来一个仍被拒
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.PROBE_TIMEOUT_MS))
    }

    // ---------- 并发：只熔断一次 ----------

    @Test
    fun `并发 onNetworkFailure 只熔断一次且计数不越界`() {
        val threads = 8
        val perThread = 5 // 共 40 次失败，远超阈值
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    startGate.await()
                    try {
                        repeat(perThread) { BinanceHostGate.onNetworkFailure(base) }
                    } finally {
                        doneGate.countDown()
                    }
                }
            }
            // 统一放闸，把并发压到最紧
            startGate.countDown()
            assertTrue("并发任务未在 10s 内完成", doneGate.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }

        // 只熔断一次：blockedUntil 只被 CAS 设置一次，且就是 base + COOLDOWN_MS
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
        assertEquals(base + BinanceHostGate.COOLDOWN_MS, BinanceHostGate.blockedUntilForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())
        // 计数不越界：并发越界的线程只把计数压回阈值以下，不会虚涨
        assertTrue(
            "计数越界: ${BinanceHostGate.consecutiveFailuresForTesting()}",
            BinanceHostGate.consecutiveFailuresForTesting() in 0 until BinanceHostGate.FAIL_THRESHOLD
        )
    }

    // ---------- 诊断文案：statusText 三态映射 ----------

    @Test
    fun `statusText 三态映射正确_供诊断报告头部快照`() {
        // CLOSED → 正常
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals("正常", BinanceHostGate.statusText())

        // OPEN → 暂不可达(熔断中)
        tripOpen()
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue("熔断中", BinanceHostGate.statusText().contains("熔断中"))

        // HALF_OPEN → 暂不可达(探测中)：冷却结束后首个探测进入半开
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown))
        assertEquals(Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())
        assertTrue("探测中", BinanceHostGate.statusText().contains("探测中"))
        assertFalse("HALF_OPEN 也属暂不可达", BinanceHostGate.statusText().contains("正常"))
    }

    // ---------- 地区封锁（REGION_BLOCKED）：粘死，不再无限空探 ----------

    /**
     * 单发地区封锁仍只计数、不立即粘死——与既有契约 `getCryptoPrice 收到451…` 一致：
     * 单发 451 不熔断、UI 不显示暂不可达、regionBlocked 仍为 false（要凑满阈值才粘死）。
     */
    @Test
    fun `单发地区封锁仍只计数不粘死_与既有契约一致`() {
        BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(1, BinanceHostGate.consecutiveFailuresForTesting())
        assertFalse("单发 451 未达阈值，不应粘死", BinanceHostGate.regionBlockedForTesting())
        assertFalse(BinanceHostGate.isUnavailable())
    }

    /**
     * 地区封锁达阈值熔断后**粘死**：regionBlocked 置位，冷却过期后 [tryAcquireProbe]
     * 仍永久拒绝探测（不再每 5 分钟白探一次 451）。这正是要灭掉的「无限空探循环」。
     */
    @Test
    fun `地区封锁达阈值熔断并粘死_永久拒绝探测`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD) {
            BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED)
        }
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.regionBlockedForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())

        // 冷却早已过期（now 远超 blockedUntil），普通 TRANSIENT 此刻会进 HALF_OPEN 探测；
        // 但地区封锁已粘死，必须永远拒探测。
        val longAfter = base + BinanceHostGate.COOLDOWN_MS * 100
        assertFalse("地区封锁粘死后，冷却过期也不该再探测", BinanceHostGate.tryAcquireProbe(longAfter))
        // 相位仍 OPEN（粘死期间不会因探测而被改写）
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.isUnavailable())
    }

    /**
     * 瞬断先熔断（OPEN），冷却期内又确认是地区封锁：即便最初是瞬断触发的熔断，
     * 一旦期间撞到地区封锁也打粘性标记，冷却过期后不再空探。覆盖「先瞬断后确认封锁」的真实序列。
     */
    @Test
    fun `瞬断熔断后期间确认地区封锁也粘死`() {
        // 先用瞬断（TRANSIENT）凑满阈值熔断
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(base, FailureType.TRANSIENT) }
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertFalse("瞬断熔断时不应粘死", BinanceHostGate.regionBlockedForTesting())

        // 冷却期内又撞到一次地区封锁（如半开探测 451）
        BinanceHostGate.onNetworkFailure(base + 1_000, FailureType.REGION_BLOCKED)
        assertTrue("期间确认地区封锁后必须粘死", BinanceHostGate.regionBlockedForTesting())

        // 冷却过期后永久拒探测
        val longAfter = base + BinanceHostGate.COOLDOWN_MS * 100
        assertFalse(BinanceHostGate.tryAcquireProbe(longAfter))
    }

    /**
     * 半开探测本身撞到地区封锁（451）：立即重开冷却并粘死，不再每轮空探。
     */
    @Test
    fun `半开探测撞地区封锁_重开冷却并粘死`() {
        // 瞬断熔断 → 冷却结束 → 成为探测
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(base, FailureType.TRANSIENT) }
        assertTrue(BinanceHostGate.tryAcquireProbe(afterCooldown)) // 成为探测，进入 HALF_OPEN
        assertEquals(Phase.HALF_OPEN, BinanceHostGate.phaseForTesting())

        // 探测以 451 失败：重开冷却 + 粘死
        BinanceHostGate.onNetworkFailure(afterCooldown, FailureType.REGION_BLOCKED)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.regionBlockedForTesting())
        // blockCount 不应因这次「同一次熔断 episode 的延续」而再涨
        assertEquals(1, BinanceHostGate.blockCountForTesting())

        // 永久拒探测
        assertFalse(BinanceHostGate.tryAcquireProbe(afterCooldown + BinanceHostGate.COOLDOWN_MS * 100))
    }

    /**
     * 地区封锁走**独立的更小阈值** [BinanceHostGate.REGION_BLOCKED_FAIL_THRESHOLD]=2，
     * 远快于瞬断的 [BinanceHostGate.FAIL_THRESHOLD]=8。
     *
     * 451/403 是确定性故障（换 IP 前不会自愈），让它攒够 8 次才停手 = 白打 8 轮请求。
     * 但仍**不是 1 次**：[regionBlocked] 是进程内粘死的（要重启 App 才重评），
     * 一条 451 就永久冻结全部加密价格的误判代价太大。
     */
    @Test
    fun `连续2次地区封锁即熔断粘死_远快于瞬断阈值`() {
        val regionThreshold = BinanceHostGate.REGION_BLOCKED_FAIL_THRESHOLD
        assertTrue(
            "地区封锁阈值必须小于瞬断阈值，否则确定性故障要白打太多轮",
            regionThreshold < BinanceHostGate.FAIL_THRESHOLD
        )
        assertTrue("地区封锁阈值不能是 1（粘死代价太大）", regionThreshold > 1)

        // 第 1 次：只计数
        BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED)
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.regionBlockedForTesting())

        // 第 2 次：熔断 + 粘死
        BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.regionBlockedForTesting())
        assertEquals(1, BinanceHostGate.blockCountForTesting())

        // 对照：同样的 2 次**瞬断**还远不到阈值，不该熔断
        BinanceHostGate.resetForTesting()
        repeat(regionThreshold) { BinanceHostGate.onNetworkFailure(base, FailureType.TRANSIENT) }
        assertEquals("同等次数的瞬断不该熔断（阈值更高）", Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertFalse(BinanceHostGate.regionBlockedForTesting())
    }

    /**
     * 瞬断与地区封锁**各用各的计数**：1 次瞬断 + 1 次 451 不能凑满地区阈值 2。
     *
     * 共用计数时这一序列会让 `failures` 从 1 直接到 2（≥ 地区阈值），
     * 于是「单发 451」也变成熔断 + 粘死——正是本次修复的回归点。
     */
    @Test
    fun `瞬断与地区封锁各用各的计数_一次瞬断加一次451不熔断`() {
        BinanceHostGate.onNetworkFailure(base, FailureType.TRANSIENT)
        BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED)

        assertEquals("1 次瞬断 + 1 次 451 远未到瞬断阈值，不应熔断", Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertFalse("地区封锁计数只有 1，不应粘死", BinanceHostGate.regionBlockedForTesting())
        assertEquals(1, BinanceHostGate.consecutiveRegionFailuresForTesting())
        assertEquals(2, BinanceHostGate.consecutiveFailuresForTesting())

        // 第二次 451 才该熔断并粘死
        BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED)
        assertEquals(Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertTrue(BinanceHostGate.regionBlockedForTesting())
    }

    /** 瞬断累计到阈值时照常熔断，但**不能**因为中间夹过 451 而被误粘死 */
    @Test
    fun `瞬断累计到阈值熔断_不因夹过一次451而粘死`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD - 1) {
            BinanceHostGate.onNetworkFailure(base, FailureType.TRANSIENT)
        }
        // 中间夹一次地区封锁：总数凑满 8，但地区计数只有 1
        BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED)

        assertEquals("总失败数达到瞬断阈值，应正常熔断", Phase.OPEN, BinanceHostGate.phaseForTesting())
        assertFalse("地区封锁只出现 1 次，不该打粘性标记", BinanceHostGate.regionBlockedForTesting())
    }

    /** [resetForTesting] 必须清除 regionBlocked，否则会漏给别的测试类造成误判 */
    @Test
    fun `resetForTesting 清除 regionBlocked 标记`() {
        repeat(BinanceHostGate.FAIL_THRESHOLD) { BinanceHostGate.onNetworkFailure(base, FailureType.REGION_BLOCKED) }
        assertTrue(BinanceHostGate.regionBlockedForTesting())
        BinanceHostGate.resetForTesting()
        assertFalse(BinanceHostGate.regionBlockedForTesting())
        assertEquals(Phase.CLOSED, BinanceHostGate.phaseForTesting())
        assertEquals(0, BinanceHostGate.blockCountForTesting())
    }
}
