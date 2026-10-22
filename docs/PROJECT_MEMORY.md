# 金库 GoldVault —— 项目记忆（PRO）

> 这是本项目的**长期记忆快照**，供接手的 AI（或人）在动手前阅读，避免推翻已拍板的决策、重复踩过的坑。
> 入口手册是根目录 `AI_GUIDE.md`；本文件是它的第 10 章「项目记忆」的完整版。
>
> 快照时间：2026-10-21（对应提交 `106657a`）。

---

## 0. 长期约定（用户明确说过，必须遵守）

| 约定 | 内容 |
|---|---|
| **默认直接提交并推送** | 改动做完**默认 commit + push**，不再逐次询问确认。 |
| 按主题分批提交 | "修 Bug" 与 "加功能" 分开，一笔一个主题，不混成一堆。 |
| push 前必须绿灯 | `compileDebugKotlin` + `testDebugUnitTest` 全绿才能推，**不带红灯推**。 |
| 提交信息用中文 | 标题 `type(scope): 简述`，正文写清**为什么改**、已知取舍、验证结果。 |
| 金额口径 | 一律经 `ExchangeRateManager.toCny(...)` 折人民币后再汇总。 |

---

## 1. 关键决策表（改之前先看，别推翻）

| 时间 | 决策 | 原因 / 影响 / 代价 |
|---|---|---|
| 2026-10-21 | **收息进度一律按除息日**，不再按发放日 | 原先分子（已收）按到账日、分母（今年预计）按除息日，窗口错位 → 界面出现「已收 > 预计」并弹「请检查分红数据」。改后分子恒为分母子集，已收 ≤ 预计恒成立。**代价**：「今年已收」不再等同"钱真到账"，已除息未发放的记录也计入。 |
| 2026-10-21 | **日历页仍按发放日**（`DividendData.cashDateOrNull`），不跟随改除息日 | 日历用发放日是当初专门修「按除息日打「分」，用户看到"明明没到日期却显示分红了"」这个实测 bug 换来的，改回去 bug 会复活。**两套日期口径并存是有意为之，不是疏漏。** |
| 2026-10-21 | 「预计年度收入」改**自然年口径**，与「今年预计」同源 | 让 Dashboard / Portfolio / Calendar 三个头条数字严格一致。唯一数据源抽成 `DividendProgressCalculator.naturalYearAnnualIncome`。**`dividendsBySymbol`(TTM) 不动**，股息率/FIRE 收入率不受影响。 |
| 2026-10-21 | 自然年口径下**保留存款利息** | 用户拍板"保留利息"（如 10 万 × 3% = 3000 计入今年预计）。注意存款利息只进预计、不进已收。 |
| 2026-10-21 | 币安**账户/资产路径**接入 `BinanceHostGate` | 原先只有行情路径接了熔断，账户路径从没喂过闸门 → "行情知道、资产不知道"、UI 提示不统一。现在两条路径**共用同一闸门**。 |
| 2026-10-21 | **明确不做**币安备用域名 / 串行重试 | 451 是服务器按条款拒绝，换币安自家任何域名（含 `data-api.binance.vision`）同样 451；而串行重试在 `callTimeout=20s` 下把单请求放大到 80s，是**负优化**。 |
| 2026-10-21 | REIT / 非 ETF 场内基金（508xxx、500/501xxx）分红**改走天天基金** | 东财 `RPT_SHAREBONUS_DET` 对这类标的返回 `code:9201 返回数据为空`，此前静默显示 0 并被 7 天负缓存固化。天天基金 `fundf10.eastmoney.com/fhsp_<code>.html` 实抓 200 且有完整数据。 |
| 2026-10-21 | 历史页风险指标六项改 `Modifier.weight(1f)` 等宽 | 原先 `SpaceEvenly` + 宽度随内容变化 → 上下两行三列不对齐。 |
| 2026-10-21 | 自定义仪表盘新增 4 个卡片开关（默认全 true） | 用户要「资产增值可以不用显示」，并授权扩展。面板现共 12 项：摘要组件 5 / 资产类别 5 / 收入组件 3 / 隐藏低价值币 2。默认 true 保证老用户升级不丢卡。 |
| 较早 | 三态断路器 `shouldAllow` 改名 `tryAcquireProbe` | 显式化 CAS 副作用（消耗唯一半开探测槽），防止 UI 误调。行为未变。涉及 `BinanceHostGate` / `SourceCircuitBreaker`。 |

---

## 2. 不变量（改代码必须守住）

1. **已收 ≤ 今年预计** 必须恒成立（除息日口径的推论）。若界面再出现超额提示，说明口径被改坏了。
2. **两个头条数字同源**：`DividendIncomeLoader.totalAnnualIncome` 与 `DividendProgressCalculator.yearProjected` 必须同源同值。
3. **股息率不受影响**：`dividendsBySymbol` 走 TTM，改年度收入口径时**不要碰它**。
4. **日历不改口径**：见决策表第 2 条。
5. **`DashboardComponent` 两处穷举 `when` 是死代码**（`updateComponentVisibility` / `isComponentVisible` 生产零消费）；真正生效的开关在 `DashboardPreferences` data class。别在死代码上改开关，改了界面不变。
6. **不写"假负缓存"**：数据源未应答（网络失败）时**不得**缓存"无分红"，否则一次断网把数据锁死 7 天。
7. **密钥不落盘**：日志一律走 `AppLogger`（对 OkHttp 日志脱敏），禁止把 API Key/Secret 写进日志或异常 message。

---

## 3. 开发流水（按主题，含提交号）

| 提交 | 主题 | 要点 |
|---|---|---|
| `6e65832` | advisory 改名 | `shouldAllow` → `tryAcquireProbe`，行为未变，三套测试全绿。 |
| `f3454bb` | 年度收入改自然年口径 | 抽出 `naturalYearAnnualIncome` 共享函数；测试 931 绿。 |
| `52869b5` | **REIT 分红路由修复** | `isExchangeFund = isEtf \|\| classifyCode==EXCHANGE_FUND`；两处分支门槛改用它；新浪兜底门槛同步收窄；新增 `ReitDividendRoutingTest`（断言 REIT 走天天基金、新浪调用 0 次，并有普通股走新浪的对照）。 |
| `e0e31bf` | 币安账户/资产接入 `BinanceHostGate` | `BinanceAccountAPI.kt` +43/−1，4 个记账点；新增 `BinanceAccountAPIGateTest`（10 条）；经**变异测试**验证 10/10 均有杀手变异，零空转绿灯。QA 自曝并修复了自己两条弱用例（缺前置哨兵）。 |
| `e2585c4` | 历史页风险指标等宽 | `HistoryMetrics.kt` + `HistoryCards.kt`，2 文件 8+/7−。 |
| `6f94007` | 新增 README | 此前仓库无 README、GitHub 描述为空；补门面 + 两个构建坑。 |
| `a6c50fd` | 收息进度改除息日 | 4 文件 +151/−89；删除死代码 `paymentLagDays()`；新增两条核心契约用例（旧口径 500>300 / 新口径 300≤300）。 |
| `106657a` | 仪表盘 4 个显示开关 | 4 文件 +389/−9；新增 `DashboardPreferencesCardVisibilityTest`（11 条，含"互不串 key"）。 |

测试规模演进：925 → 931 → 933 → 943 → 945 → **956**（0 failures / 0 errors / 0 skipped，86–87 suites）。

---

## 4. 仍未解决 / 外部限制（别再试图"修复"）

- **币安资产在中国大陆拿不到**：HTTP **451**（地区合规拒绝）+ `UnknownHostException`（DNS 屏蔽）双层封锁。
  **代码层面无解**，唯一办法是挂代理到允许地区的节点。App 已能优雅降级并给出明确中文提示。
- **代码里没有 `DataManager.kt`**：早期架构文档（CODE_ARCHITECTURE.md）曾写「DataManager 旧版全局状态容器」，但仓库中**不存在**该文件（已核实）。当前行情抓取层是 `StockDataManager`（多源聚合 + `SourceCircuitBreaker` 数据源级熔断），它本身**被 Repository 调用**，是活跃代码而非历史遗留。**不要**按「消灭 DataManager」去做大规模重构——它不存在，`StockDataManager` 也无需大改。新增功能仍尽量走 Repository 边界。
- **`CODE_ARCHITECTURE.md` 部分过时**：导航实际是 `ui/navigation/GoldVaultNavHost.kt`（不是 `StockDiaryNavHost.kt`）；`common/` 现在只有 `APIConfig.kt` 和 `Result.kt`。**以实际代码为准**。
- **设备端网络不稳会放大问题**：诊断日志曾出现东财/新浪/天天基金多域名同时 DNS 失败、价格同步 0/102。这是当时的网络状况，不是逻辑 Bug。

## 5. 提过但**未派工**的想法（不是欠债，是有意搁置）

- 「超额明细」诊断功能：列出"进了已收但没进预计"的记录（标的/除息日/到账日/金额），把「请检查分红数据」变成可执行清单。
  > 注：改除息日口径后超额已不再触发，此需求基本失去意义，**除非**将来再改口径。
- release 签名包：需要 keystore，用户未确认，目前只有 debug 包。
- GitHub 仓库描述为空、仓库名是 `-`：需用户在网页 Settings 自行改（`gh` CLI 未安装）。
