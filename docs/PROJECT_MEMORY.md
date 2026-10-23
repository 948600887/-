# 金库 GoldVault —— 项目记忆（PRO）

> 这是本项目的**长期记忆快照**，供接手的 AI（或人）在动手前阅读，避免推翻已拍板的决策、重复踩过的坑。
> 入口手册是根目录 `AI_GUIDE.md`；本文件是它的第 10 章「项目记忆」的完整版。
>
> 快照时间：2026-10-22（对应提交 `e79c758`）。上一版快照：2026-10-21（`106657a`）。
> 本版相对上一版的**新增/变更**集中在：资产币种与到期日、后台同步按类型分频、日历防抖与格子布局、仪表盘开关即时生效、币安密钥加密、死代码清理。详见第 1 节末尾「2026-10-22 批次」与第 3 节。

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

### 2026-10-22 批次（本版新增）

| 提交 | 决策 | 原因 / 影响 / 代价 |
|---|---|---|
| `f11cb90` | 资产表单按类型一一对应，暴露 **currency / dueDate** | 原先 `Asset` 有 `currency`(默认 CNY) 与 `dueDate` 却不让人填 → 加密(美元价)/港股(港币)市值被按 CNY 算错。现按类型显示字段：参考单价仅证券+加密；年利率仅存款(必填)+贷款(选填)；到期日仅存款/信用卡/贷款；数量标签按类型(信用卡=欠款金额、贷款=贷款余额)。**代价**：DB 无需迁移(列早已在)，但所有新增调用方要传 currency。 |
| `2e1f208` | `addOrUpdateStockAsset` 增 5 参，**新建写入 / 合并保留** | 修"加美股(USD)被静默重置成 CNY"。合并分支刻意不动 currency 等字段，因为 `adjustPosition`(加仓)不传币种。已加单测守住。 |
| `5c7410b` | **后台同步按资产类型分频**，不再用 A 股时钟一刀切 | 三类市场时钟本就不同(股票 9:30–15:00、场外基金 T1、加密 24/7)。股票/基金**仅盘后/非交易时段刷**(`stockIntradaySync` 默认关)；加密**默认不自动刷**(`cryptoAutoSync` 默认关，只手动刷)。判定收敛到 `SyncStrategy.shouldAutoSyncType` 纯函数。**代价**：加密默认不再自动更新价格，需用户开开关。 |
| `9400a59` | 两个同步子开关**收纳进主开关** | 上一版直接加两行让设置页太挤；现仅当"自动刷新价格与分红"开启时才渲染子开关。 |
| `3a10cc3` | 仪表盘开关**改完即时生效** | 原 VM 构造期只加载一次偏好，改完不刷新，且存年度收入时会把新开关覆盖回去("改了没反应+过会儿变回去")。加 `reloadPreferences()` + `ON_RESUME` 触发 + 存缓存前先重新 load。 |
| `ee6054e` | 日历按**真实依赖字段**去重，不按时间 debounce | 一次同步会重算日历 20+ 次(每个 currentPrice 更新都触发)。按 `(symbol, balance, currency)` 签名 `distinctUntilChanged`——比 debounce 更精准、零延迟。**注意**：日历不依赖实时价，加字段进签名前先确认它真会影响日历结果。 |
| `41e71a9` / `b17fe07` / `cee874b` | 日历格子**固定高度 + 三段固定槽位** | 修"日期格子参差不齐"与"'分'被切一半"。贴底槽位要留足余量(字号+默认字体 padding)，否则被 Surface 的 clip 切尾。**代价**：格子高度写死，字号再调要同步复核槽位。 |
| `8e0ff23` | 币安 API 密钥改 **EncryptedSharedPreferences** | 原明文存 SharedPreferences。现加密优先 + **任何异常回退明文**(绝不崩) + 旧明文读回校验后才清的迁移。用 security-crypto **1.0.0 的字符串别名 API**(1.0.0 没有 `MasterKey` 类)。Keystore 可用性**需真机验证**(本环境只能编译+JVM 单测)。 |
| `a446673` | 诊断增强 + 未知币种告警去重 | L3 日志裁剪后补换行；L4 onTrimMemory 措辞分级；新浪源失败补记 URL/响应体(只增强诊断，不摘除源)；未知币种 `getRate` 兜底 1.0 但每币种告警一次。 |
| `b47cee8` / `f5b44f5` | 清理确认零引用的死代码 | 删 `DashboardComponent` 枚举及三处方法、`AssetDao` 5 个零调用方法及 Repository 转发、`Extensions.DividendData.isNewest()`。**旧审查报告若让你改这些，说明它基于旧版本。** |
| `e79c758` | 「0 天后除息」改「**今日除息**」 | 文案分支：`daysUntil==0` 时大字"今日"+标签"除息"。`daysUntil` 已有 `>=0` 过滤，无负数场景。 |

---

## 2. 不变量（改代码必须守住）
1. **已收 ≤ 今年预计** 必须恒成立（除息日口径的推论）。若界面再出现超额提示，说明口径被改坏了。
2. **两个头条数字同源**：`DividendIncomeLoader.totalAnnualIncome` 与 `DividendProgressCalculator.yearProjected` 必须同源同值。
3. **股息率不受影响**：`dividendsBySymbol` 走 TTM，改年度收入口径时**不要碰它**。
4. **日历不改口径**：见决策表第 2 条。
5. **`addOrUpdateStockAsset` 新建/合并分工不可颠倒**（2026-10-22 新增）：新建分支写入 `currency`/`dueDate`/`notes`/`interestRate`/`currentPrice`；**合并（加仓）分支必须只更新 `balance`/`purchasePrice`/`lastUpdateTime`**，靠 `existingAsset.copy` 保留既有 currency 等。颠倒会让加仓把既有外币仓重置成 CNY（`AssetDetailViewModel.adjustPosition` 走合并分支且不传币种）。新增调用方**必须传 `currency`**。
   > 附：`DashboardComponent` 枚举及 `updateComponentVisibility`/`isComponentVisible`/`getVisibleComponents` **已于 `b47cee8` 删除**（原第 5 条不变量说的"死代码别动"已失效）；开关只在 `DashboardPreferences` data class 一处。
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

### 2026-10-22 批次

| 提交 | 主题 | 要点 |
|---|---|---|
| `94bcbd5` | 货基不再双重计入 | 守「已收 ≤ 预计」：`calculate` 在 `isMoneyFund` 命中后 `return@assetLoop` 跳过现金循环；加回归测试(修复前 1060>365，修复后 60≤365)。 |
| `3a10cc3` | 仪表盘开关即时生效 | `reloadPreferences()` + `ON_RESUME` 触发；存年度收入缓存前先 `loadPreferences()` 再只覆盖两个缓存字段。 |
| `a446673` | 诊断/可观测性四项 | L3 裁剪后补换行、L4 onTrimMemory 措辞分级、新浪源补记 URL/响应体、未知币种告警去重。 |
| `b47cee8` | 删 `DashboardComponent` 死代码 | 测试未整删：保留 7 条真实 `DashboardPreferences` 持久化契约用例，删 4 条依赖死代码的。 |
| `8e0ff23` | 币安密钥加密 | EncryptedSharedPreferences 优先 + 异常回退明文 + 旧明文迁移。 |
| `1c3fbb7` | 文档 / schema 说明 | `CODE_ARCHITECTURE.md` 加「已过时」横幅；纠正"DataManager"误判(该文件不存在)；澄清 schema 5~15.json 从未导出过。 |
| `91cd966` | 自定义仪表盘面板可下滑 | 内容 `Column` 加 `verticalScroll` + `imePadding`；周期 chip 改 `FlowRow`。 |
| `5c7410b` | 同步按类型分频 | `SyncStrategy.shouldAutoSyncType` 纯函数 + 两偏好(默认关)；新增 `SyncStrategyTypePolicyTest` 7 条。 |
| `9400a59` | 同步子开关收纳 | 仅主开关开启时才渲染两个子开关。 |
| `ee6054e` | 日历按依赖字段去重 | 加回归测试(三次发射只重算 2 次)。 |
| `41e71a9` | 日历格子固定槽位 | 修日期格子参差不齐。 |
| `f11cb90` | 资产表单按类型精细化 | 币种/到期日/年利率/数量标签按类型；单测 +7(→968)。 |
| `b17fe07` / `cee874b` | 「分」被切一半 / 再上移 | 槽位放饱；改 `Arrangement.Top` 抬起约 4dp。 |
| `2e1f208` | **修复币种与到期日丢失** | `addOrUpdateStockAsset` 5 参透传(新建写入/合并保留)；资产卡/详情页显「到期日」；CSV 加到期日列；新增单测(→969)。 |
| `f5b44f5` | 清死代码 | `AssetDao` 5 方法 + Repository 转发 + `Extensions.DividendData.isNewest()`。 |
| `e79c758` | 「今日除息」文案 | `daysUntil==0` 时大字「今日」+ 标签「除息」。 |

### 2026-10-23 批次

| 提交 | 主题 | 要点 |
|---|---|---|
| `d2ac6d5` | 全面优化页面性能与体验 | 落价攒批单事务、进度 200ms 节流、资产流按字段签名去重、图表绘制计算提到 remember、全 App 补省略号/触摸目标放到 40dp/金额精度统一、日历 Snackbar 移出滚动区等。970→ 后陆续加测试。 |
| `7e8dde9` | 日历「分」再抬 2dp | 金额槽与分红槽间加 `DIVIDEND_LIFT = 2dp`。 |
| `d86d5b1` | 日历「分」消遮挡 + 收紧间隙 | 上一版把三段之和凑成正好等于内容区（底部零余量），而分红标记已从 8sp 提到 10sp、16dp 槽位装不下 → 又被 clip 切。**修法：格高 56→60（内容区 58，三段+间距=56 留 2dp 余量）、分红槽 16→18dp；对齐 `Center→TopCenter`**（原来字浮在槽位中间，与上方金额空出约 8dp，用户反馈"间隙太大"）。**教训：抬比分只能加间距或加高格子，绝不能把总和凑到等于内容区。** |
| `1987f51` / `534e88d` | 修落价批量化引入的真 bug | `SyncPriceWriter` 收尾**从未调用 `flushAll()`** → 每轮同步尾部最多 7 个**已抓到**的价格被静默丢弃；标的处理顺序基本固定 ⇒ 被丢的总是同一批持仓，现价长期停在旧值、日志无痕。修法：flush 放进块内已有的 `finally` + `withContext(NonCancellable)`，一个插入点覆盖正常收尾与取消两条路径；加 2 条回归测试（不足一批也落库 / 取消时也落库）。 |
| 本次 | **去掉「我的 → 行情数据源」，数据源全自动** | 用户发现该设置形同虚设：批量预取写死走新浪（只有新浪有批量接口），偏好只在兜底路径生效，新浪正常时选腾讯/妙想毫无效果。**已去除**：设置页入口改为「数据源状态」（保留体检）、删 `ApiSelectionDialog.kt`、`ApiManager` 去掉 SharedPreferences 读写只留常量。顺序**全自动** = 妙想Skill(仅配置了 API Key 时参与) > 新浪 > 腾讯，某源连续失败由 `SourceCircuitBreaker` 自动短期禁用并降级。 |

测试规模演进：925 → 931 → 933 → 943 → 945 → 956 → 969 → 971 → 973 → 975 → **976**（0 failures / 0 errors / 0 skipped）。

---

## 4. 仍未解决 / 外部限制（别再试图"修复"）

### 4.1 已排查但**不是 Bug**，别去改代码

| 现象 | 结论 | 说明 |
|---|---|---|
| **历史页（资产分析）点「1周/1月/3月/6月/1年/全部」图表不变** | **数据问题，非 bug**（已用诊断日志坐实） | 全链路(`HistoryPeriodSelector` → `HistoryViewModel.selectPeriod` → `filterSnapshots` → `AssetTrendChart` 的 `Crossfade`+`key(period)`)逻辑正确。真实原因：**用户快照少且全在最近**(诊断日志"快照8天")，各周期滤出同一批 → 图表当然不变。等快照攒过一周后再看。**别去改 `HistoryViewModel` 的 `rebuildState` 或图表的 `Crossfade`/`key`**——那是正确代码。若届时要改善体感，可考虑在周期按钮下加「N 个快照(起~止)」反馈行。 |

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
