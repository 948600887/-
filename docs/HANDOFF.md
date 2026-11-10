# 多 AI 交接板 / 交流集

> 用途：两个 AI 同时改进本仓库时，**声明在途工作 + 交接上下文**。
> 规则见根目录 [`AGENTS.md`](../AGENTS.md)（开工/收工三步、硬规则）。
>
> **本文件 append-only**：交接流水**只往末尾追加**，不要改写或删除别人的条目。
> 「当前认领」表只增删**自己那一行**。最新记录在**文件末尾**，从下往上看。
> 事实来源是 `git log`，本文件只写机器看不出来的：为什么、坑、待办、给对方的话。

---

## 当前认领（开工写一行，收工删自己那行）

| AI（git user.name） | 开始时间 | 正在改的文件 / 模块 | 备注 |
|---|---|---|---|
| _(空，无在途认领)_ | | | |

## 当前状态快照（2026-11-09 15:12，以 `git log` 为准）

- `HEAD` = `b8c532d`（协作约定落地），已推送到 `origin/main`。
- **最新 APK**：`releases/GoldVault-1.0.0-release-arm64-v8a-b8c532d.apk`（26MB，arm64-v8a，
  debug keystore 签名，`apksigner verify` 通过）。基于 `b8c532d` 构建；
  出包时曾因代理 502 拉不到远端，**之后网络恢复已 `git fetch` 复核**：远端无其他 AI 的新提交，
  本地与 `origin/main` 同步，此包即当前最新代码基线（`fc81e2c` 仅为文档提交）。
- 出包时已删旧包 `74ff03e`（11-08 23:32，明确早于本次），`releases/` 现只留上面一个。
- 最近一笔：`fix(持仓): 消除编辑态底部操作栏与 TabBar 间的大块空白`
  —— 关掉 `PortfolioScreen.kt` 编辑态 `BottomAppBar` 的默认 `windowInsets`（双层 Scaffold 重复避让），
  设中性灰底作视觉锚点，三个启用态按钮文字色改 `AppTheme.colors.primary`。
- 门禁：`check_gate.sh` 9 步全绿，单测 1582 例 / 0 失败 / 0 错误 / 0 跳过。
- **真机待验（UI 无自动化测试覆盖）**：竖屏操作栏是否贴住 TabBar；
  横屏三键导航 + 侧边栏 + 编辑态四个按钮是否被挤压；深/浅主题文字可读性；
  展开 125 项滚到底；编辑态切换时滚动锚点。
- **已知取舍**：空白 B（操作栏→TabBar）消失，但空白 A（卡片→操作栏）会因 `innerPadding.bottom` 少 48dp
  **反而增大约 48dp**，这是分组折叠时列表只有 4 个 item 的固有留白；
  若要彻底消除需把操作栏移进 content 区（交互会变，须先问用户）。
- **遗留项**：浅色主题下三个启用态按钮绿字对比度 3.88:1（`#2E7D32` 在 `#E0E0E0` 上），
  深色已修到 5.16:1；浅色属主题层既有问题，单开一笔处理，别夹带进 UI 修复。
- 出包约定：`releases/` 只留最新 1 个 APK，签名产物名带 commit hash，构建完清根目录 `*.log`。
- **最新 APK**：`releases/GoldVault-1.0.0-release-arm64-v8a-53390f9.apk`（18:46，26MB，arm64-v8a，含 R1~R6 修复）。旧包 `b8c532d` 已删（明确更早）。

## 给另一个 AI 的开工提示（可直接复制）

```
先读仓库根目录 AGENTS.md 和 docs/HANDOFF.md，按里面的「开工三步」做：
git pull --rebase → 看认领表与最近流水 → 在认领表加自己一行。
动手前再读 AI_GUIDE.md 的「已知约束与坑」。
注意：本仓库已有一套静态快照文档（docs/PROJECT_MEMORY.md 停在 2026-10-22、
CODE_ARCHITECTURE.md 部分过时），一律以实际代码 + git log 为准。
改完跑 bash tools/check_gate.sh 全绿，按主题立即 commit + push，
并在 docs/HANDOFF.md 末尾追加交接记录、删掉自己的认领行。
```

---

## 交接流水（按时间正序，最新在末尾，只追加）

### 2026-11-09 · 小伍 (`workbuddy@local`)

- **做了什么**：诊断并修复持仓管理编辑态底部大块空白（commit `38d0777`）。
  根因是双层 Scaffold：外层 `GoldVaultNavHost` 的 `NavigationBar` 已按 `WindowInsets.navigationBars` 避让，
  内层 `PortfolioScreen` 的 `BottomAppBar` 默认 `windowInsets` 又避让一次 `systemBars.bottom`，
  多算约 48dp。`MainActivity.kt:47-53` 当年修过一次两层分工，但只改了 Scaffold 参数，
  **漏了 M3 组件自带的 windowInsets**——这类坑以后换别的 M3 条形组件也会遇到，记得检查。
- **技术分歧结论（别再重复争论）**：曾考虑把 inset 收窄成 `navigationBars.only(Horizontal)` 以保横屏水平避让，
  经 foundation-layout 1.6.1 / material3 1.2.0 字节码复核后**否决**：
  `ModifierLocal` 的 provider 值只向下传播、无反向通道，`ConsumedInsetsModifier` 只读祖先，
  外层 `innerPadding.start/end` 无条件取自 `contentWindowInsets`（横屏 ≈48dp 不丢）；
  收窄反而造成外层 48dp + 栏内 48dp = 96dp 双重水平避让。最终保持 `WindowInsets(0,0,0,0)`。
- **验证**：`compileDebugKotlin --rerun` + `check_gate.sh` 9 步全绿，1582 例单测 0 失败
  （146 个结果 XML 均本轮新生成，非假绿）。**但项目有 Compose UI 测试依赖却零用例**，
  门禁 PASS 只代表无编译/数据层回归，**不代表布局已验证**——真机清单见上方快照。
- **给对方**：`tools/check_gate.sh` 现在是 **9 步**（含两个不变量检查 + 迁移三校验 + 三条 `--rerun` gradle 任务），
  别照旧文档里写的「6 步」理解；gradle 任务不加 `--rerun` 会 UP-TO-DATE 假绿。
  `releases/` 我出包时会清理只留最新一个，你出包前先看认领表，别互相删包。

### 2026-11-09 15:12 · 小伍 (`workbuddy@local`)

- **做了什么**：建立多 AI 协作约定（`AGENTS.md` / `docs/HANDOFF.md` / `AI_GUIDE.md` 顶部指针，commit `b8c532d`，已推）；
  随后出了 release 包 `GoldVault-1.0.0-release-arm64-v8a-b8c532d.apk`。
- **验证**：`assembleRelease -PabiFilter=arm64-v8a` BUILD SUCCESSFUL（6m43s）；签名 `sign`/`verify` 均 EXIT 0；
  ABI 仅 `arm64-v8a`；26MB。出包前的 `check_gate.sh` 9 步全绿、1582 例 0 失败。
- **给对方**：`releases/` 现在**只有 `b8c532d` 这一个包**，我删掉了旧的 `74ff03e`（时间明确更早）。
  你出包时别删 `b8c532d`，清理只删明确早于你自己刚出包的旧包。
  ⚠️ 本次 `git pull --rebase` 连续两次被代理 502 / TLS 瞬断挡住，所以**拉不到远端可能的新提交**；
  这个包基于本地 `b8c532d`。你那边若已推过新改动，装机前先确认基线，别拿这个包验收你的改动。

### 2026-11-09 18:40 · 小伍 (`workbuddy@local`)

- **做了什么**：修复首页「预计每日收入 / 年化收入率」**点刷新后大幅跳动**（用户真机诊断 11-09 16:55），R1~R6 全套落地，13 个源码/测试文件。
  根因：`AssetSyncManager.kt:581` 刷新时 `forceRefresh=true` 强制重抓分红，「HTML 有表头但解析 0 条」被当成「确实无分红」→ 删库 + 7 天负缓存 → 每轮分红集合洗牌 → 分子 `realAnnualIncome` 每轮不同；分母两轮只差 0.0089 元。
- **六项内容**：R1 分红页三态判定（`classifyFundDividendPage`）+ 删库保护；R2 一次刷新只写一次（epoch 版本号 + 迟到结果丢弃）；R3 刷新期冻结收入率分母；R4 缓存分片 `@Synchronized` + 单飞 freshness 守卫；R5 三处指纹加按日汇率版本 + mci 日志；R6 日志打印 `I / D / N / dataHash / scopeKey`。
- **验证**：`check_gate.sh` **9 步全绿，1589 例 / 0 失败 / 0 错误 / 0 跳过**（基线 1582，新增 7 例），146 个 XML 均本轮新生成。
- **本轮两处额外修复（断线遗留）**：① 工程师 agent 因网络 502 中断，代码已落盘但留了个语法错误（测试函数名以数字开头 `41KB大页面...`，Kotlin 不允许），已改名；② 审查发现 R2 的「刷新后 30s 丢弃迟到重算」会误伤手动改分红（清指纹后切回首页不更新），已改为**只有缓存仍有效才跳过**。
- **给对方**：
  - ⚠️ 这批改动**改了分红抓取与收入计算链**，你若也在动 `FundDividendApiService.kt` / `DividendRepository.kt` / `DividendIncome*`，先看本次提交再动手。
  - `classifyFundDividendPage` 新增第四态 `SUSPICIOUS_EMPTY_PARSE` → 返回 `Result.failure` → 走「全源未应答保留旧值」。**别把它改回 `success(emptyList)`**，否则跳动 bug 复活。
  - R5 的 `exchangeRateVersion()` 返回**当天日期**而非真汇率版本号：只把陈旧窗口从跨年缩到跨日，日内 10 次汇率刷新不会触发重算（影响 ≤0.07%，刻意取舍）。别误以为「汇率漏项已彻底修好」。
  - 日志现在会打 `I=...`，**下一份真机诊断可直接读出刷新前后数值**，此前 279 条「预计收入」全是文案无数字。
  - 真机必验：点一次刷新，看 D/Y 是否还大幅来回跳、`SUSPICIOUS_EMPTY_PARSE` WARN 后 DB 分红记录是否保留。


### 2026-11-09 18:46 · 小伍 (`workbuddy@local`)

- **做了什么**：R1~R6 全套修复后出包 `GoldVault-1.0.0-release-arm64-v8a-53390f9.apk`；
  代码提交 `53390f9`、交接板提交 `80fc95f`，均已推到 `origin/main`。
- **验证**：`assembleRelease -PabiFilter=arm64-v8a` BUILD SUCCESSFUL（5m31s）；签名/verify EXIT 0；
  ABI 仅 arm64-v8a；26MB。出包前门禁 9 步全绿、1589 例 0 失败。
- **给对方**：`releases/` 现在**只有 `53390f9` 这一个包**，你出包时别删它。
  这个包**是修复版**，装机点一次刷新即可验收「预计每日收入 / 年化收入率」是否还大幅来回跳；
  日志现在会打 `I=...` / `分红页有表头但解析到 0 条，按解析可疑处理`，可直接抓诊断报告比对。

### 2026-11-10 全面只读体检（团队 software-audit-goldvault-full）

- 用户要求「全面检查一遍 app」。按部分工作流建 3 人团队（工程师=代码质量/回归、QA=独立门禁+边界、架构师=数据流/并发/状态分层），lead 自己跑门禁+真机日志+不变量。**全程只读，0 行代码改动**。
- 仓库：HEAD `f195a39`（本地领先远端 1 笔未推，代理 502/Empty reply 多次失败）；`releases/` 仅 `53390f9` 包；根目录 0 日志；干净门禁 **9/9 绿、1589 例 0 失败**（146 XML 本轮新生成，非假绿）。

#### 最终分级（lead 拍板）：3 阻断 / 8 重要·高 / 9 观察
> 53390f9（R1~R6）未引入新坑，但留有 3 条阻断级**残留路径**，恰恰对应「刷新后跳动」的同类症状。

- **阻断① A1（含 A1a=I5 跨页两个数 + A1b=B2 30s 窗口误伤）**：根因 = 单一共享 SP 槽位 + `dividendInputFingerprint` 不含分红记录版本 + 写入点散落（首页进场 `DashboardViewModel.kt:334-340`、持仓进场 `:270-276`、持仓刷新 `:370-377` 写，**首页刷新 phase-2 `:459-488` 不写**）。后果：刷新后首页停在旧值、持仓页长期旧值、重进「新→旧→新」闪跳；手动改分红切回首页 30s 内 stale。
- **阻断② A16（=B3）**：R1 只保护「空结果」分支，`data.isNotEmpty()` 时 `DividendRepository.kt:1244-1248` 全量刷新无条件 `deleteNonManualBySymbol` 再只插回本次 `validRecords`；`classifyFundDividendPage:661-665` 对 recordCount>0 一律 HAS_RECORDS、无完整性校验 → **残缺解析洗库**，经 `:740 markFetched`(24h) + `DividendIncomeLoader.kt:97` 同日短路，偏小 I 复用一整天。**用户原始「刷新跳动」症状的残留根因**。
- **阻断③ A19（=B1）**：场内基金（ETF/REIT/LOF）东财批量 `eastmoneyAnswered` 预置 `answered=true`，天天基金 SUSPICIOUS failure 被吞后无法翻转 → `:740 markFetched` + `:742 clearFailure` 把「解析可疑」误记为「源已应答无分红」，库里无旧自动记录时固化 7 天负缓存。R1 核心保证对整类资产被绕过（既有缺陷，53390f9 未改 `:950`）。

- **重要·高**：A2（R3 冻结分母在 Composable `DashboardScreen.kt:97` 旋屏二次冻结，仅刷新中 5~19s 且恰旋屏）、A3（同屏净资产 vs 冻结分母口径）、A4（三处签名字段集不一致、均缺 records 摘要）、A5（三 VM 三套 scopeKey）、A7/A18（freshness 契约只约束 UseCase 层，Loader `:97` 不看 cacheOnly，刷新可被 SP 缓存短路成零请求；并发写同一 SP 分片无版本保护）、A8（`AssetSyncManager.kt:581` forceRefresh 根因触发点未移除）、A9（净资产 4 套公式/日志口径重复）。
- **观察**：A6（UseCase `:138-148` finally 取消路径不可靠，`Mutex.lock` tryLock 清洁路径争用时跳过清理；`:102/:141` 注释缺陷）、A10~A15、A17（`FundDividendApiService.kt:105-108` 引用不存在的 `FUND_MAX_CONCURRENT_SYNC`）、`DividendRepository.kt:1169 saveDividends()` public 默认 destructive 但零调用方（未来接线风险）。

#### A1 分级分歧裁决（lead）
工程师按「可见影响+可自愈」判重要（冷启动重算写盘纠正，约 30s+一次重算）；架构师按「单一来源/状态所有权」判阻断（本轮唯一结构性破坏单一数据源）。**lead 裁决：维持阻断**——匹配用户需求书第 1 优先级「realAnnualIncome 是否真同源」，且 A1 是 A1/B2/A4「一根多症」的根，统一修法（唯一 `IncomeInputSignature` 含分红记录版本 + 唯一写入点 + `invalidateIncome()` 门面）一处可同时关闭三条、缓解 A7/A18。

#### 下一版验收断言规格（本轮均未落地，符合只读）
- **A** `DashboardViewModelTest` 复用 `:289` 夹具，刷新写 200.0 后 `coVerify(exactly=1){prefsMgr.updatePreferences(any())}`（当前 0 次，钉死 A1a）+ 再 `loadDividendIncome` 采 uiState 序列断言 `realAnnualIncome` 不回落到 100.0。
- **B** 两个子用例必选——① 有争用清理跳过：`inFlight`/`inFlightKey` 最终置空 + `lastSuccess` 不被污染；② 无争用取消发起方：不产生第二次 loader 调用 + `lastSuccess` 不被污染（现状基线断言；若修法下沉写入则改「首个结果被写入且值正确」，二选一写死）。可选 2b 取消纯复用方：重复抓取但 `lastSuccess` 仍写入。
- **C**（对应 A16）：「首次 10 条 / 二次仅 2 条」两次 `forceRefresh=true` 全量刷新，断言 DB 仍保留 10 条、`totalAnnualIncome` 不掉。

#### 真机必验清单（装机 53390f9 包）
1. 点一次刷新，首页「预计每日收入/年化收入率」是否仍大幅来回跳（重点看 A16 残缺洗库 / A19 场内基金）。
2. 逐标的对比刷新前后 `records=` 合计与 `dataHash`（`DividendIncomeLoader.kt:190-195` 已打，无需埋点），抽查单只 `recordsBySymbol[symbol].size` 是否异常下降。
3. 手动改一只分红后切回首页，收入是否在 30s 内 stale（A1b/B2）。
4. 持仓页 vs 首页「年度分红/预计每日收入」是否一致（A1a/I5）。
5. R6 日志 `I / D / N` 数值 vs 屏上显示是否对应。

#### 已知坑核对
未复燃：OkHttp 手动 Accept-Encoding、日期脏数据双层防御、DashboardPreferences 整块回写、两 451 口径、收息进度按除息日/日历按发放日、隐藏低价值币只影响列表、DB v18 迁移、`--rerun` 假绿。部分复燃：AI_GUIDE §7-7「源未应答不写负缓存」（A19）。注释失真：`FundDividendApiService.kt:105-108`。

#### 结论
上轮出的 `53390f9` 包**未完全修复**用户原始「刷新后跳动」——A16/A19 是同一症状的两类残留路径，A1/B2 是跨页一致性缺陷；门禁全绿不覆盖这些运行时/数据流路径（0 覆盖）。建议下一轮按「一根多症」统一修 A1/A16/A19 三条阻断 + A4/A7/A18，并补 A/B/C 回归测试。本轮只读未提交任何代码改动；`f195a39` 文档提交仍待网络恢复补推。

### 2026-11-09 20:37 ~ 22:50 · 小伍 (`workbuddy@local`) —「按重要度逐步全部优化」实施

- **做了什么**：按 2026-11-10 体检分级**逐优先级**实施。工程师子代理（software-engineer）先落地了 Round1 全部代码（被接口频率限制 429 在 91 分钟时中断，未提交），由主会话接手复核、编译、跑测、提交。
- **Round1（3 阻断 + A4/A7/A18）—— 已完成并提交 `433c145`**：
  - A1：年度分红缓存收口到 `DashboardPreferencesManager.persistAnnualDividendIncome` **唯一写入点**（首页进场/首页刷新 phase-2/持仓页三处共用同一口径）；新增 `annualDividendRecordsVersion` 记录摘要 + `invalidateAnnualDividendIncome` 门面，缓存命中判据改为「指纹匹配 且 记录版本非空」——关闭跨页两个数、重进闪跳、手动改分红 30s 内 stale（A1a/A1b）。
  - A16：① `classifyFundDividendPage(html, recordCount, countDividendDataRows(html))` 新增残缺判定（页面数据行 > 解析条数×2 → `SUSPICIOUS_EMPTY_PARSE`）；② 全量刷新删除改走纯函数 `shouldReplaceRecordsOnFullRefresh`（解析数>=库里自动记录数才删插），残缺/可疑一律保留旧值。两道防护。
  - A19：`answered` 初值改为 `eastmoneyAnswered && !isExchangeFund`，场内基金(ETF/REIT/LOF)不再被东财批量空结果预置，交由天天基金真抓后才定。
  - A4/A7/A18 随 A1 一并收口。新增 A/B/C 回归（A=刷新落盘且不回落、B=单飞清理、C=残缺刷新不洗库）。
  - 验证：`bash tools/check_gate.sh` 全绿；全量单测 **1600 例 0 失败 0 错误**（基线 1589 + 新增 11）；androidTest 编译通过。
- **Round2（重要级）—— 部分完成**：
  - **A2（已修、编译通过，待门禁）**：`DashboardScreen.kt:97` 冻结分母的宿主从 `remember` 改为 `rememberSaveable(String 存值)`，刷新途中旋屏不再丢失冻结值导致「年化收入率」跳一下。
  - **A17（已修）**：`FundDividendApiService.kt:105` 过时注释引用已废弃常量 `AssetSyncManager.FUND_MAX_CONCURRENT_SYNC`，改为描述实际两档闸门（健康 2 / 降级串行）与现分档（轻量 4 / legacy 2）。
  - **A3**：判为**设计取舍**——同屏实时净资产（顶部大数字）vs 冻结分母（年化率用）本是 R3 故意为之（避免进度条抖动），非 bug，不改。
  - **A5**：经核查 `scopeKey` 已是 `DividendIncomeLoader.kt:281` **单一函数**，三 VM 经 `useCase.load` 统一走它，无「三套」问题，无需改。
  - **A8**：`AssetSyncManager.kt:581` 的 `forceRefresh=true` 触发点是手动刷新**确实重抓**所必需的；原始「洗库」已由 A16/A19 守卫缓解，硬删会破坏手动刷新取新分红，故保留（已被 A16/A19 闭环）。
  - **A9**：净资产在两处写法等价（`DashboardScreen` 的 `totalAssets-totalLiabilities` 与 `DashboardViewModel.computeNetAssetsForLog` 的 `sumOf{totalValueCny}-sumOf{abs}`），属装饰性重复；统一有回归风险，延后（如需统一再开一笔）。
- **Round3（观察项）—— 评估**：A6（UseCase 取消清理）已由本轮 B 回归测试覆盖、确认可靠；A10~A15 / `saveDividends()` 默认 destructive 零调用方 均属低优先观察，未改动，保持现状。
- **给对方**：本轮提交 `433c145` 已本地落盘，`git push` 因代理 502 / schannel 中断**暂未推**（网络恢复即补推）；`releases/` 仍只留 `53390f9`，本次未出包（用户未要求）；A2 是 Composable 改动、0 单测覆盖，需真机验证「刷新中旋屏年化率不跳」。
- **下一步**：A2/A17 走完门禁即可提交；A3/A5/A8/A9 及 Round3 观察项按上述评估维持现状（除非用户要求硬统一/硬删）。

### 2026-11-10 16:29 · 小伍 (`workbuddy@local`) — 收工出包（本轮全部结束）

- **Round2 完成并提交 `641e59d`**：A2（`DashboardScreen` 冻结分母 `remember`→`rememberSaveable`）走完 `check_gate.sh` 全绿、1600 例 0 失败；A17（`FundDividendApiService.kt:105` 过时注释）一并修。A3/A5/A8/A9 及 Round3 观察项维持前述评估、未改。
- **推送**：`3e18b85`（体检文档）+ `433c145` + `641e59d` 全部推到 origin/main（此前代理 502 已恢复）。
- **出包**：`assembleRelease -PabiFilter=arm64-v8a` → 签名（debug.keystore，36.1.0 apksigner，verify 通过）→ `releases/GoldVault-1.0.0-release-arm64-v8a-641e59d.apk`（26M，v1.0.0/code11，基于 641e59d）。按约定清理 `releases/`：删除旧 `53390f9` 与 `*.idsig`，仅留此最新包。
- **装机**：`adb install -r` 覆盖（debug keystore 同签名）。
- **真机验证建议（尚未做）**：重点验 A16/A19 残留路径（点刷新看年化率是否还跳）、A2 刷新中旋屏年化率不跳。
- **认领行已删除**（无在途认领）。

### 2026-11-10 17:42 · 小伍 (`workbuddy@local`) — 补充全面检查（非分红模块）

- **重跑门禁**：`check_gate.sh` 全绿（4m18s），确认 Round1/Round2 修复无回归。
- **补充审查范围**：上一轮只读体检聚焦分红链路，本轮对未覆盖模块做只读审查：①资产同步链路 ②网络层 ③数据库 ④其他 UI·ViewModel ⑤协程与内存。
- **结论**：整体健康度高。🔴 崩溃/数据损坏风险 **0 项**；🟡 仅 1 项——`AssetCards.kt:356` 用 `collectAsState()` 而非全仓统一的 `collectAsStateWithLifecycle()`（后台 onStop 后仍在订阅、徒增 CPU/内存、违反约定）；🟢 代码异味 3 项（`AssetSyncManager.kt` 单文件 ~91KB 职责过重、`FundDividendApiService.kt:222`+`EastmoneyDividendApiService.kt:25` 的 `client` 暴露为 `internal var`、`SettingsViewModel.kt:77-124` 4 条 Room 计数流可合并为 `combine`）。
- **修复**：`AssetCards.kt:356` 改 `collectAsStateWithLifecycle()`，已提交 `8f97fa6`，门禁全绿（4m6s）。🟢 三项非紧急，维持现状（除非用户要求）。
- **推送**：`4c29d44`(HANDOFF收工) + `8f97fa6`(AssetCards) 本地领先，因代理 502/schannel 间歇未推，网络恢复即补推。

### 2026-11-10 18:15 · 小伍 (`workbuddy@local`) —「全部优化」收尾复核

- **背景**：前序「按重要度逐步全部优化」已完成 Round1（3 阻断+A4/A7/A18，433c145）、Round2（A2/A17，641e59d）、补充检查（AssetCards 8f97fa6）。本轮用户要求「全部优化」，对剩余「维持/延后/观察」项做最终复核，确认是否还有可安全收尾的。
- **复核结论：剩余项经二次核查，均不应改动（含两处更正此前乐观评估）**：
  - 🟢② `client` 暴露为 `internal var`：**不应封装为 private val**。全仓 8 个测试文件（DividendServiceTest / DividendOtcRateLimitTest / EastmoneyDividendApiServiceTest / DividendServiceGzipDecompressionTest）直接 `service.client = ...` 注入替换 client，是既有测试注入点。改 private val 会破坏全部相关单测。维持。
  - 🟢③ `SettingsViewModel` 4 条计数流：**不应合并为 combine**。注释明确是刻意的容错隔离（"Room 在数据库异常时会向收集者抛错，不兜住会让 viewModelScope 崩溃；只收集计数"），4 个独立流各自 try-catch 保证单表异常不拖垮其他三个计数。combine 任一子流抛错会整体取消，引入回归。维持。
  - A3：同屏实时净资产 vs 冻结分母是 R3 故意为之（防进度条抖），维持。A5：scopeKey 已是 Loader 单一函数，前轮已澄清，维持。A8：forceRefresh 是手动刷新重抓必需、洗库已被 A16/A19 守卫缓解，维持。
  - A9：净资产两处写法（`DashboardScreen` 的 `totalAssets-totalLiabilities` 与 VM 日志的 `sumOf{totalValueCny}-sumOf{abs}`）看似可统一但口径可能不完全一致（负债项一个用 `sumDebtValueCny()`、一个用 `abs(totalValueCny)`），改错口径会引入真 bug；统一收益极低。维持（如需统一须先核对 `excludingDebts/debts/sumTotalValueCny/sumDebtValueCny` 四大扩展函数口径，并加单测钉死）。
  - 🟢① `AssetSyncManager` 单文件 ~91KB：大重构，无功能收益、回归风险高，维持现状（除非用户明确要拆）。A10~A15 / `saveDividends()` 默认 destructive 零调用方：低优先观察、未展开定义，维持。
- **实质产出**：本轮 **0 行代码改动**——这是审慎结论，避免为「全部」而改引入回归。可安全优化的高/中优先级项已于前两轮做完（1600 例门禁全绿、0 失败）。
- **待补推**：本地领先 4c29d44(收工) / 8f97fa6(AssetCards) / 54880b2(补充检查登记) + 本笔，网络恢复即补推。

### 2026-11-10 18:31 · 小伍 (`workbuddy@local`) — 全面深入检查（带运行时验证）

- **背景**：前序已有两轮只读体检（分红链路 + 非分红模块）。本轮用户要求「全面深入的检查一遍」，在静态走查基础上增加**运行时验证**与**关键层面的深度交叉确认**。
- **运行时验证**：`check_gate.sh` 全绿（5m22s），147 个 XML、**1600 例单测 0 失败 0 错误 0 跳过**（确认非假绿），当前 HEAD 无回归。
- **三层关键架构深度确认（此前未如此细）**：
  1. **网络层**：`NetworkClient` 单例 `baseClient` + `newBuilder()` 派生（共享连接池/Dispatcher）；`callTimeout` 全局兜住「54 分钟挂死」、`Dispatcher.maxRequestsPerHost=16` 修「刷新卡死二号根因」；拦截器仅日志+脱敏且不吞结果；**全仓零手动 `Accept-Encoding`**（grep 命中全是「绝不能加」警告注释），唯一 `addHeader("Accept",text/html...)` 是天天基金 fhsp 内容协商、不涉及压缩，安全。
  2. **同步层**：并发总闸 `MAX_CONCURRENT_SYNC` + 基金分档 `LIGHTWEIGHT=4`/`LEGACY=2`；`forceRefresh` 仍在 `AssetSyncManager.kt:581` 作手动刷新重抓触发点（A8 保留有理）；`syncSingleAsset` 取消守卫保留（1054-1056 `failureRecorded` 语义 + 批次7 的 `isActive` 物理守卫），退后台/看门狗截断不再误标「上次更新失败」。
  3. **数据库**：`@Database(version=18)`，迁移链 1→18 全在、`fallbackToDestructiveMigration` 未启用；`MIGRATION_16_17` 故意用 `ABORT`（非 `OR IGNORE`）防静默丢数据（注释论证 + QA 实测构造脏行验证）；`MIGRATION_17_18` 安全 `ADD COLUMN isEarnLocked`；`MigrationTest`(androidTest 唯一文件) 端到端校验。
- **测试覆盖盲区量化（真实风险地图）**：核心类基本覆盖（AssetSyncManager/FundDividendApiService/BinanceAccountAPI/UnifiedPriceService/BinanceHostGate/SourceCircuitBreaker/DividendIncome*/PortfolioVM/DashboardVM/SettingsVM 均有单测）；**弱点**：`DividendRepository` 无完整单测（仅 cache policy 纯函数测试）、`SinaStockAPI` 无单测；**整个 Compose UI 层零自动化测试**（androidTest 仅 MigrationTest 1 个）——UI 行为（刷新跳动、旋屏冻结、布局）只能靠真机验证，是明确风险但非代码 bug。
- **整体结论**：代码健康度高；网络/同步/数据库三大「数据安全 + 静默失效」层面经深度核查均专业可靠。唯一实质性风险是 **UI 层零自动化测试**（已知盲区）。本轮 0 行代码改动。
- **待补推**：无（本地与 origin/main 同步，1a6404a 已推）。

### 2026-11-10 18:55 · 小伍 (`workbuddy@local`) — 修复导入向导「账户/平台」选择

- **用户反馈**：导入资产时选择账户/平台的功能有问题（导入向导核对页的「导入到账户」）。
- **定位**：`ui/components/import/ImportAccountField.kt` 的候选账户 chip 有两个缺陷：
  1. `knownAccounts.take(4)` **只展示库里前 4 个账户**，第 5 个起无任何入口、只能手敲精确名字——账户多时选不到想要的；
  2. `HoldingSnapshotViewModel.init` 预填第一个账户后，点「已选中」的那个 chip 值不变、**无视觉反馈**，造成「选了没反应」的错觉。
- **修复（提交 `ced0848`）**：去掉 `take(4)`，改用 `FlowRow` 展示**全部**已知账户并自动换行（账户多也能点选）；用 `FilterChip` 替换 `SuggestionChip` 并加 `selected` 高亮（带勾选），当前选中项清晰可见。
- **波及**：持仓导入（`HoldingSnapshotDialog`）与币安导入（`BinanceImportDialog`）共用 `ImportAccountField`，两处同时受益。
- **验证**：`check_gate.sh` 全绿（4m10s），全量单测 1600 例 0 失败 0 错误 0 跳过。

### 2026-11-10 19:20 · 小伍 (`workbuddy@local`) — 持仓搜索优化（扩字段 + trim）

- **用户问询**：搜索持仓功能有没有可优化点。审计 `PortfolioSearchDialog` 后落地其中一项（用户勾选「输入 trim + 扩字段」）。
- **改动（提交 `4097566`）**：`ui/components/PortfolioSearch.kt`
  1. 匹配范围从 `name / symbol` 扩展到 `account`(账户/平台)、`notes`(备注)、`type.displayName`(类别中文名)——与持仓页「按账户分组」习惯对齐；
  2. 入参先 `trim()`，修「首尾空格导致明明有却搜不到」的隐性漏匹配；
  3. 匹配逻辑抽纯函数 `matchesSearchQuery`（null 安全、大小写不敏感），组件委托调用；placeholder 改「名称、代码或账户」。
- **测试**：新增 `PortfolioSearchTest.kt`（trim / 多字段 / null 安全 / 大小写不敏感），`testDebugUnitTest` 全量 0 失败。
- **未做（用户未选）**：搜索范围与主列表隐藏规则统一、结果计数+相关度排序、拼音/首字母搜索——均为可选增强，按需再开。
