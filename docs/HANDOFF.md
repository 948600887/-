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

## 当前状态快照（2026-11-09 15:12，⚠️ 历史快照、已过时；当前状态见文末「2026-11-15 交接补记」，一切以 `git log` 为准）

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

### 2026-11-11 · 小伍 (`workbuddy@local`) —「数据链路审查 P2 ①②③④」实施

只读全链路审查后，按用户选定范围只改四处，其余保持现状。**未动数据库/迁移（仍 v18，A7 锁未改）、未动取价/缓存 isStale 契约、未改日志脱敏、未改快照新建语义。**

- **① 交割单导入重复行**（`data/importer/BrokerageImportService.kt`）：`commit` 内按 `日期|标的|方向|数量|价格|手续费|账户` 维护本批次 `seenInFile`，同文件完全重复的两行只回放一次，第二行计入 `skippedDuplicate`（此前两行都回放，拼接导出会让持仓翻倍）。
  - **已知残余限制（用户确认接受）**：库内去重键仍不含账户（`transactions` 无 account 列，加列需升 Room v19 并改 A7 锁）。因此「跨文件」的两笔同价同量同日、不同账户真实成交，仍会被库内去重跳过；同一文件内的这类成交已正常入账。
- **② 交割单导入后刷新当日快照**（`ui/viewmodels/ImportBrokerageViewModel.kt`）：提交成功（`successCount > 0`）后调用 `refreshTodaySnapshotAfterImport`，与持仓截图/币安导入一致，消除「首页净资产已变、历史页今天还是旧值」。
- **③ 货基万份收益守卫**（`database/AssetDao.kt` + `data/repository/AssetRepository.kt`）：`updatePriceBySymbol` 的 mci 改 `COALESCE(:mci, millionCopiesIncome)`，Kotlin 侧 `millionCopiesIncomeToPersist` 只放行「有限且 > 0」；某天 0 值或解析降级成 null 不再覆盖上一次有效值，年化收入不再掉到 0。
- **④ 导出精度可反算**（`data/export/DataExporter.kt`）：列结构/顺序不变，只提高「数量/单价」列精度（股票数量 4 位、股票单价 4 位、基金/ETF 单价 6 位，加密货币沿用 10 位），并去掉尾随 0；非 0 值被舍成 0 时退回原值。目的：`数量 × 单价 ≈ 总价值` 在导出表内可直接复算（分数股 0.4 此前导成 0）。
- **测试**：新增 `MillionCopiesIncomeTest`、`ImportBrokerageSnapshotRefreshTest`，扩充 `BrokerageImportServiceTest`（同文件重复 / 跨账户同价同量）、`DataExporterCsvTest`（分数股反算 / 基金净值与低价股 4~6 位 / 微小数量不归零）。

### 2026-11-11 · 寇豆码 (`software-engineer`) — 全面审查结论的全量修复（P0/P1/P2 + 文档勘误）

- **背景**：基于 2026-11-11 架构师 + QA 双轮复核确认的问题清单实施，审查结论已定，不再重新论证问题是否存在。**本轮未 `git commit` / `git add` / `git push`**（主理人按逻辑分批提交）；**未跑全量门禁**（QA 负责 `tools/check_gate.sh`）。
- **范围**：只改清单相关文件；DB 仍 v18、无迁移、无加列、A7 锁未动；未改构建脚本 / APK 产物命名；未动 OkHttp 头、`DashboardPreferences` 写回方式、并发常量（`MAX_CONCURRENT_SYNC=6` / 轻量 4 / legacy 2）、熔断阈值、导入 `commit()` 不自动同步净值等硬约定。

**逐项改动**

- **【P0·R1】交割单导入刷当日快照缺前置行情同步 —— 最小方案（回退，不补 sync）**
  - `ui/viewmodels/ImportBrokerageViewModel.kt`：`commit()` 成功后**移除** `refreshTodaySnapshotAfterImport(repository, result.successCount, "ImportBrokerageVM")`；`941dbcb` 专为这次调用新增的 `repository: AssetRepository` 构造参数在 VM 内再无使用点，**一并回退**（含两行 import、factory 里的 `container.assetRepository` 实参），`ImportWizardCancellationTest` 的 `CancelableBrokerageVm` 构造同步改回两参。
  - 理由：`SyncSnapshotPolicy.refreshTodaySnapshotAfterImport` 的契约是「导入落库 **+ 导入后行情同步** 之后才刷新」，参数名是 `syncedCount`；截图导入 `HoldingSnapshotViewModel.kt:335-358`、币安导入 `BinanceImportViewModel.kt:253-267` 都先跑 `PriceSyncCoordinator.sync` 再刷并传同步 `successCount`，唯独交割单入口没有。`BrokerageImportService.applyBuy` 新建 Asset 不设 `currentPrice` → `Asset.kt:73` 按成交价 `purchasePrice` 估值 → `AssetRepository.replaceSnapshotForDate` 删当日全部旧快照并不可逆清 `SnapshotManualAdjustmentStore`。**刻意不改为补前置 sync**（会引入网络调用、改变导入耗时与失败分支语义），回到「等后台 Worker / 用户手动刷新补快照」原状。
  - `data/sync/SyncSnapshotPolicy.kt` KDoc 补**硬约束**（见下方勘误③）。
  - `ImportBrokerageSnapshotRefreshTest.kt`：原 2 例「成功刷新 exactly=1 / successCount=0 不刷」改为护栏 —— 导入成功（successCount=2）后 `coVerify(exactly = 0)`，零成功用例保留；去掉 `runCatching { coVerify(...) }.isSuccess` 吞异常（R8），断言真实失败可见；用 `mockkStatic(SyncSnapshotPolicyKt)` 验证顶层函数 0 调用 + VM 两参构造，防止后人把调用加回来。
- **【P1·R2】去重文档结论错误 + 假绿测试桩 —— 维持限制，只修文档与桩（不做真修复）**
  - `BrokerageImportService.kt:519-522` 注释更正（见勘误②）；`BrokerageImportServiceTest.installStatefulAsset()` 的 `findDuplicate` 桩由恒 `returns null` 改成**状态化 `coAnswers`**（按本测试已插入的 Transaction 集合、只比 5 字段判定命中），复现真实 Room 行为；`:383-394` 用例改名为「当前限制：不同账户同价同量第二笔被库内去重跳过」，断言改为 `successCount=1 / skippedDuplicate=1 / balance=100`。其余既有用例（日期升序回放、异常/用户跳过计数、同文件完全重复行）在新桩下保持全绿。
  - **评估结论：不做「不升 schema 的低成本真修复」**。理由：① `transactions` 表无 account 列（严禁加列 / 升 v19 / 改 A7 锁），`commitTransaction` 写流水也不带 account，同文件仅账户不同的两笔合法成交落库后是**完全相同的记录**，DB 层无法区分「合法两笔」与「重复导入一笔」；② 任何批次内放行方案（如把本批次已插入 key 集合传下去）只能修「首次导入」，但「部分失败重试」时库里已有上次批次插入的同 key 记录，无法区分是本批次还是上次批次 → 会出现「首次能进、重试进不了」的不一致语义，比稳定跳过更难排查；③ 需改 `commitTransaction` 签名 / 新增 DAO 查询，动摇「事务内二次确认去重」语义，影响约 20 个既有用例，改动不小。按判定标准「只要有一处不确定即维持限制」，维持现状并钉死文档与测试桩。
- **【P2·R5】导出负微小值**：`data/export/DataExporter.kt` 的 `csvExportNumber` 旧判断 `trimmed == "-0"` 提前返回 `"0"`，使「非 0 值被舍成 0 时退回原值」对负数不可达且正负不对称。改为只有 `value == 0.0`（含 `-0.0`）或 `trimmed.isEmpty()` 才返回 `"0"`，`-0` 并入退回逻辑；`DataExporterCsvTest` 补 `-0.00001 → "-0.00001"`、`-0.4 → "-0.4"`、`0.0 → "0"`，保留原正数用例。
- **【P2·R9】搜索 trim**：`ui/components/PortfolioSearch.kt` 把 trim 提到 `derivedStateOf` / filter 外只做一次，改用 `trim { it.isWhitespace() }` 覆盖全角空格 U+3000；抽 `matchesTrimmedQuery` 供列表复用，`matchesSearchQuery` 保留为纯函数单测入口；匹配字段、大小写不敏感、null 安全、空查询返回全部不变。`PortfolioSearchTest` 补「　茅台　」等全角空格用例。
- **【P2·R7】mci 守卫诊断**：`data/repository/AssetRepository.kt` 给被过滤的无效 mci（0 / null / 负值 / NaN / Infinity）补诊断日志（含原始值与过滤原因），不再只有有效值才打；`millionCopiesIncomeToPersist` KDoc 声明保守取舍（真实 0 值会保留上一次有效值、可能使年化收入虚高、无时间戳/连续性判定）。**不改 DAO SQL、不改放行判据**。`AssetDao.kt:52` 的 `COALESCE` 语义见下方说明。
- **【P2·R8】快照测试脆弱**：`ImportBrokerageSnapshotRefreshTest` 去掉 `runCatching` 吞异常；`ImportWizardCancellationTest` 的 `waitUntil` 本来就没有吞异常，保留轮询写法。项目 KDoc 明确「IO 在真实线程、虚拟时钟推不动」，**未套用 `runTest` + 虚拟时间**、也未换成 `UiStateCollectExt.trackIn`（该辅助针对 `StateFlow` 合流订阅，与本处「验证顶层函数未被调用」目标不同，强换风险高）。
- **【P2·R6】导出容差与格式变更说明**：`DataExporterCsvTest` 补「数量 × 单价 与金额列（固定 2 位）误差 ≤ 0.005」断言，明确是**近似可反算**而非严格守恒；**不改导出逻辑**（NaN/Infinity 是既有行为，非本批引入）。格式变更见下方说明。
- **【P2·R3】已证伪，不改生产代码**：QA 已证明 `DividendRepository.kt:744-749` 写入的是从 DB 读回的完整 `mergedData`，A16 仍有效，「缓存守卫使主链路失效」不成立。`data/repository/DividendRepositoryCachePolicyTest` 中测 `shouldUpdateDividendCache` 的两个用例改名/注释标为「防御性契约，非 A16 主链路验证」；`DividendRepository` 无完整单测（仅纯函数策略测试），补主链路 cache 断言需 mock 大量 DAO，非低成本，**不补**。注意 `data/DividendRepositoryCachePolicyTest.kt`（`shouldReuseEmptyResult` / `DividendFailureBackoffTest`）与本项无关，未动。
- **【P2·R4】待真机，不改解析逻辑**：`network/FundDividendApiService.kt` 的 `parseDividendHtmlDetailed` KDoc 记录「分母改单表」的反向取舍与待验证项（多表拆分分红页首抓可能残缺放行并触发 `markFetched` 24h 停更），**不改解析器**。待真机/抓包确认真实多表页面是否存在。

**三处勘误（append-only，不改写历史条目）**

1. `641e59d` / HANDOFF:158、170（A2 冻结分母 `remember`→`rememberSaveable` 时期的论证）：当时「LaunchedEffect 不重启动」的论断**已被 `a077cdf`（`fix(首页): A2 旋屏重建后保持年化率冻结分母`）证伪** —— Composable 重建后 `LaunchedEffect` 会重启，不能靠「它不重启」来保住状态；`a077cdf` 改以「重建后保持冻结分母」收口并补 `DashboardYieldFreezeTest`。
2. `ba2e195` / HANDOFF:234 的「同一文件内的这类成交已正常入账」**与 `TransactionDao.kt:74-75` 不符**：`findDuplicate` 只有 `symbol|type|quantity|price|date` 5 字段、无 account/fee；`seenInFile` 只挡同文件内**完全重复**的行。因此「同日同价同量、不同账户或不同手续费的合法成交」**无论同文件还是跨文件，都会被库内去重跳过**。
3. `SyncSnapshotPolicy.refreshTodaySnapshotAfterImport` 的 `syncedCount` 契约**硬约束**：调用方必须先完成 `PriceSyncCoordinator.sync` 并把其 `successCount` 作为 `syncedCount` 传入；**未完成行情同步的导入入口不得调用本函数**。已在 KDoc 落地。

**另需留意的语义 / 格式变化**

- `AssetDao.kt:52` 的 `COALESCE(:millionCopiesIncome, millionCopiesIncome)` 使「不传 mci」的语义从「覆盖为 null」变为「**保留上一次旧值**」；配合 `millionCopiesIncomeToPersist` 的保守放行，真实 0 值不会被写入。
- 导出列字符串由**固定小数位**改为**去尾零**（`100.00`→`100`、`0.40`→`0.4`），数值等价但**文本形态变化**，可能影响外部 CSV 消费方 / Excel 模板（按字符串比对或固定列宽解析的场景）。

**已知未修 / 维持现状项**

- 交割单去重「无 account 列」的残余限制（见 P1 评估），不升 schema、不加列、不改 A7 锁。
- 交割单入口不补前置行情同步 → 当日快照由后台 Worker / 手动刷新补齐（P0 最小方案）。
- `FundDividendApiService` 多表拆分分红页待真机确认（R4）；`DividendRepository` 主链路 cache 断言未补（R3）。
- 快照/取消测试保留实时轮询（`Thread.sleep(5)`，4s 上限），仅去掉吞异常（R8）。
- 导出 NaN/Infinity 行为为既有行为，未改。

- **门禁结果**：2026-11-11 QA（`software-qa-engineer`）独立全量门禁 `bash tools/check_gate.sh` → **GATE PASSED**（EXIT=0，总耗时 245s / 4m05s）：8 个 run_step + 1 个 `verify_test_count` 共 **9/9 步 EXIT=0**，三条 gradle 任务均带 `--rerun`。结果 **151 个结果 XML / 1632 例 / 失败 0 / 错误 0 / 跳过 0**；STALE=0（全部 XML mtime 16:47:04，均属本轮），无 UP-TO-DATE/FROM-CACHE 假绿。较修复前基线（151 XML / 1627 例）为 **XML +0、用例 +5**（快照护栏 +1、导出 +2、搜索 +2），与 `git diff` 新增 5 个 `@Test`、删除 0 个一致。

---

## 2026-11-15 00:40 交接补记（补 11-12~11-15；此前流水停在 11-11）

> 本板头部「当前状态快照」写于 11-09（`b8c532d` / 1589 例）、末尾流水停在 11-11（1632 例），11-12 之后的工作此前只落在 `.workbuddy/memory/*.md` 与 git log，没同步进交接板（违反「关键状态必须落盘 / 每步同步更新 handoff」）。此段 append-only 补齐，不改写历史条目。

**当前状态（以 `git log` 为准）**：HEAD `90fa4bd`（本地已提交、**未推**），`origin/main = 6481e73`，本地领先 1 笔；门禁 **1701 例 / 155 XML / 0 失败 / STALE=0**、`check_invariants` 9/9。`releases/` 仍只有 `a0f9cd6` 旧包；11-15 构建的 `app-release-unsigned.apk` 在 `app/build/outputs/apk/release/`，**按梦瑶指示停止出包，未签名未交付**。工作区 8 个 `M` 是 `core.autocrlf=true` 的 CRLF 假阳性（`git diff --numstat` 为空），别误提交。

**11-12~11-14 脉络**：11-12「全面优化」评估结论是硬约束下安全空间极小、Lint 32 warning 只消除 3 个、其余 29 刻意不动（别顺手泛改）；11-13 真机诊断证明 `5c660fe` 的 514 方案 A/B 未达预期，根因修正为「单轮请求总量/突发频率」，已上 P1 匀速闸门 + P2 未取到优先（`14f2e69`）与诊断「排障摘要」（`a0f9cd6`）；11-14 完成数据保存层评审（写入侧可靠、恢复侧真空）并按梦瑶「可以在导出数据那里加」实现备份恢复闭环。

**备份恢复闭环（本轮唯一功能改动）**：设置页「导出数据」→「3. 备份与恢复（可回灌）」，导出结构化 JSON 覆盖 Room 7 张表、导入走 DAO + `withTransaction` 整库替换，SAF 由用户选目录、恢复前二次确认，不含显示偏好与币安 API 密钥。**拍板不导出 `.db` 文件**：运行时替换 Room 文件需 close/重建单例 + 处理 WAL/SHM，本项目手动 DI 多处持有 `StockDatabase.getInstance()` 与 DAO 引用，会造成「磁盘新的、内存旧的」甚至崩；JSON 方案不改 schema，不撞 A2/A7 锁 v18。三笔中文提交 `d3825c4`(数据层) / `6481e73`(UI 层) 已推、`90fa4bd`(修 B1~B8) 未推。

**QA 两轮**：第 1 轮 **IS_PASS NO** —— 门禁 1681 例全绿仍抓到 2 个 P2：B1 恢复成功后 `clearDerivedCaches()` → `FundNavCacheDao.clear()` 删光刚写回的第 7 张表（实际只恢复 6 张）；B2 `pendingRestoreUri` 用 `remember` 保存、转屏后确认按钮静默无操作。根因是新增 15 例只测纯 JVM codec + mock 事务，**UI/VM 层零测试**。第 2 轮 **IS_PASS YES**：B1~B8 逐条读码确认真修复、+20 例真断言（VM 状态机用 MockK relaxed Uri + `ByteArrayInputStream` 真跑通）。**别改回去的修法**：清派生缓存**必须在 `restore()` 之前**；`PendingRestore(uri, summary)` 原子进 ViewModel、UI 侧不存 Uri；`restoreBackup()` 二次读流并用 `hasSameIdentity` 比对 `exportedAt + formatVersion + 7 个 count`，不一致零写库；`buildPayload()` 包只读事务；Double `isFinite` 全量校验 + 主键必填 + `snapshotId` 引用校验。

**已知未修（QA 标注遗留）**：5 项 P3 —— L1 持久 Uri 授权不释放会累积；L2 摘要是身份启发式非内容指纹；L3 **孤儿 `snapshotId` 的备份不可恢复**（与刻意不校验的 `assetId` 口径不一致，影响最大，动备份格式时优先处理）；L4 恢复失败/取消时事务外 `DELETE FROM fund_nav_cache` 不可回滚且取消分支无提示；L5 `clearFetchState()` 的 `commit()` 跑在 Room 事务线程内 + WAL 未显式启用。评审期裸奔项 R02/R03/R04/R05/R07 仍未修；真实 Room 事务回滚 / 7 表内容相等、真机 SAF 全链路仍 0 覆盖（`androidTest` 门禁只编译不执行）。

**给接手的人（待办按优先级）**
1. **push 阻塞**：本机 `127.0.0.1:57055` 代理隧道故障，累计 5 次 push 失败（schannel ×3 / 502 ×2），按「同一手段最多 2 次」已停手。网络恢复后 `git -C "D:/主人的任务/NewStockDiary" push origin main`（必要时 `-c http.version=HTTP/1.1`），`git status -sb` 确认无 ahead。
2. **真机验证需梦瑶本人**（本机 adb 无设备）：装新包 → 导出全量备份存网盘/电脑 → 卸载重装 → 从备份恢复 → 核对各表条数；**特别验 B2 真实场景：确认框弹出后旋转屏幕，点确认能真正恢复**。
3. 出包：梦瑶 11-15 明确「不用出包」。若改主意，按硬约定只出 arm64-v8a、debug keystore 手动签名、完整路径告诉她、清理 `releases/` 只留最新包 + 删 `*.idsig`。
4. `a0f9cd6` 的 514 修复与排障摘要**真机验收仍未完成**（11-14 诊断仍是旧包）；判别只需看诊断头部有无 `--- 排障摘要 ---`。

---

## 2026-11-15 12:50 交接补记（全面体检后「优化你所有能优化的」）

**当前状态（以 `git log` 为准）**：`origin/main = 6481e73` **一直未变**；本地已提交未推的确定含 `90fa4bd`(B1~B8) / `17108ae`(交接板补记) / `5097893`(历史文档归档注记) / `cd6123c`(本段所在提交)。**HEAD 与「领先几笔」不写死**——交接板自己也占一笔提交，写死数字必然自相矛盾；一律实时 `git log -1 --oneline` + `git status -sb` 查看，push 成功后应无 ahead。`check_invariants` 9/9 复跑通过；门禁 1701 例 / 155 XML / 0 失败基线未变（本轮纯文档改动，未跑 gradle 全量门禁）；工作区干净、暂存区零改动。`releases/` 仍只有 `a0f9cd6` 旧包，**继续不出包**。

**本轮已落地的三件事（都是「我自己的工具/记忆/工作区」，不是 app 源码）**
1. **A（上一轮）**：项目 `MEMORY.md` 刷新状态基线、`HANDOFF.md` 头部标历史 + 文末补 11-12~11-15，提交 `17108ae`。
2. **B（本轮）**：`docs/` 下 7 份老文档统一加「历史归档、不代表当前状态」注记 —— `PROJECT_MEMORY.md`(10-22 快照，并声明第 0 节长期约定仍有效)、`架构评审-20261106.md`(并点名其第 15 行「时效性注记」自身已过时：8 步门禁 → 实为 9 步)、`真机探测清单-加源前可达性验证.md`、`RELEASE_CHECKLIST.md`(并写明当前「不用出包」)、`system_design.md`(2026-09 深色模式) 与两张 `.mermaid`(用 `%%` 注释避免破坏图)。**纯新增 22 行、零删改、不删文件**，统一指向 `git log` / 本文文末补记 / 项目 `MEMORY.md`。提交 `5097893`。
3. **CRLF 假阳性根治**：8 个 `.kt` 长期显示 M 但 `git diff --numstat` 为空，判因 `git ls-files --eol` = `i/lf w/crlf`（`core.autocrlf=true` 来自 PortableGit 系统配置）。用 `git add --renormalize -- <8 路径>` 刷新索引 stat 缓存后 `git status --porcelain` 为空、`git diff --cached --numstat` 为空 —— **不动源码、不需提交**；以后再见假 M 先判因，别误提交。

**本轮没做、且不打算擅自做的**
- **C 清理项复查后判定多数不该删**：5 个 CAD 技能包（约 682KB / 54 文件，`skill_management list` 确认 0 个启用）与 `mcp.json` 里 `disabled: true` 的 `TZZB` 都是禁用态、零开销，删了不可逆还可能丢梦瑶以后要用的能力 → **建议保留**；唯一真垃圾是 `D:\主人的任务\nul`(106B，一次 `cd: too many arguments` 报错重定向)，已 scan-only 列清单，等梦瑶明确确认 + 二次确认才删，本轮**未删任何文件**。
- **push 仍阻塞**：代理已从 `57055` 换到 `127.0.0.1:52184`，但 `curl github` = 000、`git ls-remote origin HEAD` = `CONNECT tunnel failed, response 502`；另试「`--noproxy '*'` 直连」同样 000。两种手段各一轮失败，按「同一手段最多 2 次」停手，未再重试 push。**需梦瑶本人恢复代理/换网络**，之后 `git -C "D:/主人的任务/NewStockDiary" push origin main` 并 `git status -sb` 确认无 ahead。
- 真机验证（备份恢复全链路、旋转屏幕确认恢复、514 修复装包）仍需梦瑶本人，本机 `adb` 无设备；QA 的 5 项 P3 与 R02~R07 裸奔项维持不动，未纳入本轮「优化」以免引入回归。

---

## 2026-11-15 app 层三路只读审查最终结论（梦瑶「继续检查app」→「继续」）

**边界**：三路审查全程只读 —— 未改任何 app 源码/测试、未出包、未 push、未做真机。路线：架构体检（备份恢复闭环、L1~L5、A1~A9）+ 独立 QA（实跑门禁、找假绿、评 0 覆盖）+ 既有链路（导出/诊断/写入侧、R02~R07、诊断摘要是否全而准）。

**门禁实跑**：`tools/check_gate.sh` EXIT=0 / GATE PASSED / 279s；**1701 例 / 155 XML / 0 失败 / 0 错误 / 0 跳过 / STALE=0**；`check_invariants` 9/9。架构侧确认 A1~A9 零违规、`BackupManager.restore()` 的 Room v18 事务边界本身正确、7 表覆盖完整、B2 状态机有效、DAO 与内存单例一致。

**交付判定：不可交付**。限定措辞：唯一的 **P0 级功能缺陷阻断 = L3**；另有独立的「验证未完成」理由。修完 L3 只清掉 P0，仍须补测 + 真机验收。QA 第 2 轮 `IS_PASS: YES` 已收窄为「B1~B8 静态读码验收通过 + 门禁全绿」，**不代表运行时端到端已验证，更不代表可交付**。

**L3（P0，唯一功能缺陷阻断）**：`BackupCodec.kt:233-238` 的 `validatePayload()` 对孤儿 `snapshotId` 强制抛异常，而 `assetId` 被显式豁免；`encode()` 不调 `validatePayload()`、`BackupCodec.summary()` 只数数组长度 → 导出成功、确认框正常，**点确认恢复才失败、零写库**，用户误判备份文件损坏。修法 A（唯一推荐）：`decode()` 遇孤儿 `snapshotId` **不抛异常、不过滤、原行原值保留，禁止 `filter`**（filter 会让 count 变 N-k，撞 B3 身份校验）；`validatePayload()` 其余校验与 `requireFinitePayload`/`requiredLong`/`strictInt`/`assertUniqueKeys` 全部保留；`BackupCodecTest.kt:691-698` 由「断言抛异常」改正向护栏 `assertEquals(orphan, decode(encode(orphan)))` + `assertTrue(hasSameIdentity(decoded, summary(json)))`。**别用方案 B（导出端过滤）**：违反 `BackupPayload`「Room 7 表全部行」规格、确认框条数与库内不符、救不了存量已导出备份。

**三处断言真空 + 6 个假绿反例（独立「验证未完成」）**：把 `BackupManager.restore()` 的 `db.withTransaction {}` 换成 `run {}`、`exportBackup()` 写出 `"{}"`、VM 传给 `BackupManager` 的 `BackupPayload` 实参多处 `any()` —— 以上改动 1701 例仍全绿。另有调用日志断言自我镜像、B3 身份比对只钉 3/7 count、门禁无用例数下限、清库实现被整体 mock。须补：真 Room 事务回滚 / 7 表内容相等、真机 SAF 全链路、旋转屏确认恢复、`exportBackup` 写出字节。（本轮只记录，未动测试代码。）

**其余遗留口径（多轮交叉复核已收敛）**：L1 持久 Uri 授权只 take 不 release、L2 摘要是身份启发式非内容指纹、L4 恢复失败时事务外 `DELETE FROM fund_nav_cache` 不可回滚且取消无提示、L5 `clearFetchState()` 的 `commit()` 在 `deleteAllData()` 的 Room 事务线程内 + WAL 未显式启用（**QA 原文无误，别写「QA 写错」**）—— 四项维持 P3。R04 `stock_basic` 清库未清降 **P2 待定口径，不要给 `deleteAllData()` 补 `clearAll()`**（真正待定的是备份是否该含可重建参考表）。R02(诊断被 CSV 挤掉)/R03(非原子写)/R05(凭据降级明文)/R07(`apply()` 竞态) 遗留。诊断「排障摘要」结论：准 > 全 —— 514/退避/预算顺延一屏可见，但 ERROR/崩溃类型/失败原因/构建标识不足、进程内存态快照会静默缺失、链路覆盖把下界 0 误报「未覆盖」。

**待梦瑶决策**：是否授权 ①修 L3 + 改护栏测试（仅 `BackupCodec.kt` + `BackupCodecTest.kt` 两个文件、零新依赖）②补三处断言真空相关测试 ③修 R02/R03/R05/R07。任何一项授权后须补跑 9 步门禁（1701 例为基线，只增不减）。push 与真机两项阻塞不变，见上节。

**当前状态（以 `git log` 为准）**：本段追加会产生一笔新的本地文档提交；`origin/main` 与 ahead 笔数实时以 `git status -sb` 为准，不在此写死。

---

## 2026-11-17 出包 + 遗留项收口（梦瑶「全部优化」→「我修改了，你出包」）

**梦瑶 11-15 曾选「先不动源码」，11-17 改口「全部优化」并授权实施；随后两路 worker（app-arch-check/app-legacy-check）因 429 限频中断，梦瑶自己接续修了很多并让我出包。** 现场核正：她自 11-15 起已自行提交多笔（`856e7d2` L3 修复 / `c50e712` 断言真空补测 + L1 Uri 释放 / 及 history/sync/failure/dividend/network 等 10 笔），已 push 到 `f218d9d`。工作区残留两路 worker 中断前的导出/凭据/诊断半成品，本轮收口。

**本轮落地（均已提交，中文信息写清为什么改）**
- `33154f9` 导出/诊断：R02 文件清理按用途分组保底，诊断报告不被 CSV 挤掉；R03 导出写出改 tmp+rename 原子落盘（覆盖已存在目标先删后 rename，Windows 兼容）；诊断 null 补说明行、链路覆盖措辞改「本份日志未见记录（可能未导入或已轮转）」与「未解析到≠0」一致。新增 AtomicWriteTest 3 例 / PruneTest 6 例。
- `60b3145` 凭据/VM：R07 凭据 save/clear/迁移统一 commit 同步落盘，迁移先写密文读回校验成功再清旧明文；L4 恢复取消补缓存清理提示；修 `AssetSyncManagerPrefetchNonBlockingTest` 的 JUnit4「should be void」初始化错误（表达式体末尾 `?.join()` 推断成 `Unit?`，补非空 `Unit`）。新增凭据测试 6 例真钉 commit/apply 调用数。
- **授权外我做的 3 处纯编译修复**：① 撤销误加的 `import io.mockk.capture`（`capture` 是 MockK `MockKMatcherScope` 成员，`coEvery{}` 内可直接解析，顶层 import 反而 Unresolved）；② `FakePrefs` 加 `open` 允许匿名继承；③ 上述 JUnit4 void 修复。都不是改被测逻辑。

**明确没做并已向梦瑶说明**：R05 凭据明文兜底维持（改「拒绝保存」会让 Keystore 不可用设备彻底无法导入币安，属破坏功能+大重构）；R04 `stock_basic` 清库未清仍 P2 待定（别补 clearAll）；L5 WAL/commit 位置风险高收益低；所有硬约定与「行为差异别顺手统一」。诊断「准 > 全」：构建标识已有（`environmentInfo()` 已带 versionName(versionCode)），ERROR/崩溃类型入头部属「全」不动。

**验证**：9 步门禁 EXIT=0 / GATE PASSED，**1756 例 / 164 XML / 0 失败 / 0 错误 / 0 跳过 / STALE=0**；`check_invariants` 9/9、迁移双向校验全过。门禁前工作区残留的 2 个编译错误 + 1 个 JUnit4 初始化错误均已修复后才跑出全绿。

**出包（按硬约定）**：只出 arm64-v8a；release unsigned 用 debug keystore 手动签名；`releases/` 已按授权清理只留最新 + 删 `*.idsig`。产物 `releases/GoldVault-1.0.0-release-arm64-v8a-60b3145.apk`（v1.0.0/code11，26,812,826 B，SHA-256 `ff54a857423ddb342943b732f267270c875f0e1a6d2070bf3655fa78afdd4284`，签名证书 Android Debug）。apksigner verify 通过、native-code 仅 arm64-v8a。

**阻塞中（需梦瑶本人）**：① push —— 本地 ahead 以 `git status -sb` 实时为准，网络/代理恢复后 `git -C "D:/主人的任务/NewStockDiary" push origin main` 并确认无 ahead；② 真机验收 —— 本机 adb 无设备，装新包后验备份恢复全链路、旋转屏确认恢复、514 修复与诊断排障摘要（判别装对没有看诊断头部有无 `--- 排障摘要 ---`）。**门禁全绿 ≠ 真机通过；本包未做真机冒烟。**

---

## 2026-11-18 再出包（基于 HEAD `0de53b8`）

梦瑶 11-18 再次要求出包。距上次出包（`60b3145`）新增 3 笔提交：`0de53b8` 深色 Snackbar 改用 App 深色表面、`ca3aaca` 深色弹窗/浮层改中性深色表面、`bce406a` 分红补齐改由系统周期任务推进（新增 DividendPrefetchScheduler/Worker + Application 注册 + Repository 适配 + `docs/PROJECT_MEMORY.md` 注记）。逐笔确认**未碰硬约定**（无 Accept-Encoding / 并发常量 / 熔断 / WAL / deleteAllData / 版本号改动），版本号仍 1.0.0/code11。工作区仅 `tools/__pycache__` 缓存垃圾，已清；提交后工作区干净。

**验证**：9 步门禁 EXIT=0 / GATE PASSED，**1756 例 / 164 XML / 0 失败 / 0 错误 / 0 跳过 / STALE=0**。

**产物**：`releases/GoldVault-1.0.0-release-arm64-v8a-0de53b8.apk`（v1.0.0/code11，26,829,210 B，SHA-256 `21a263a07b0c4bd579aec31fe6055ea56303b61951d4d9cb3f7968cecf2e154b`，Android Debug 签名，apksigner verify 通过，native-code 仅 arm64-v8a）。`releases/` 已清理只留此包，旧 `60b3145` 包已删。

**阻塞不变**：push（本地 ahead 以 `git status -sb` 实时为准）+ 真机验收（adb 无设备，需梦瑶本人）。本包未做真机冒烟。

---

## 2026-11-19 再出包（基于 HEAD `71ea207`）

梦瑶 11-19 再次要求出包。**push 已解除阻塞**：`git status -sb` = `## main...origin/main`（无 ahead），此前积压的本地提交已全部推送到远端。距上次出包（`0de53b8`）新增 2 笔且已推送：`71ea207` 备份缺表拒绝恢复（防缺表备份静默清库，BackupCodec + 测试 + VM 测试）、`cd1f59e` 分红周期任务未执行的诊断日志（新增 WorkDiagnostics + Scheduler/Worker 适配）。确认未碰硬约定（测试构造 payload 用 `dbVersion = 18` 是正确用法）、版本号仍 1.0.0/code11。工作区仅 `tools/__pycache__` 缓存垃圾，已清。

**验证**：9 步门禁 EXIT=0 / GATE PASSED，**1760 例 / 164 XML / 0 失败 / 0 错误 / 0 跳过 / STALE=0**（较 11-18 的 1756 多 4 例，来自备份缺表新测试）。

**产物**：`releases/GoldVault-1.0.0-release-arm64-v8a-71ea207.apk`（v1.0.0/code11，26,829,210 B，SHA-256 `282760a17d2ca3a70c5e7793ca1b626e0f19b970f53fb38284b7b9b196a8e9fd`，Android Debug 签名，apksigner verify 通过，native-code 仅 arm64-v8a）。`releases/` 已清理只留此包，旧 `0de53b8` 包已删。

**唯一剩余阻塞**：真机验收（adb 无设备，需梦瑶本人）——装新包验备份恢复全链路（含新的缺表拒绝路径）、旋转屏确认恢复、分红周期任务与诊断排障摘要。门禁全绿 ≠ 真机通过，本包未做真机冒烟。

---

## 2026-11-19 全面体检（a9d804b → 53a3723，6 笔修复）

梦瑶要求「全面检查一下 app」。走完整 SOP：QA 跑门禁 + 审测试有效性，架构师逐函数比对 5 笔未推送改动 + 核 7 条硬约束，工程师修，QA 复验。

**体检结论（a9d804b 上）**：9 步门禁全绿 1783 例 / 165 XML / 0 失败 / STALE=0；`d732a69` 把 `DividendRepository` 1690→819 行、拆出 7 个组件，**逐函数归一化 diff 为机械委托，行为等价**，19 个公开成员与 8 处 `client` 测试注入点全部保留，无循环依赖、无反向依赖（`data|network|database → ui` 的 import 为 0）；7 条硬约束逐条未违反。`d732a69` 的收益是**可测性**而非覆盖度——拆出的 6 个类 847 行在测试树里引用数为 0。

**找到并修掉的缺陷（6 笔）**
1. `9275f32` **P1-1** `storageMode` 声明在 `prefs` 之后 → 初始化器把 `buildPrefs()` 里写的 `PLAINTEXT_*` 覆盖回 `ENCRYPTED`（`javap` 字节码实证：构造器 offset 24 无条件 `putfield`）。后果：`isPlaintextFallback` / `needsCredentialReentry` 恒 false，R05「明文存储」告警（**71ea207 起就已失效的旧洞**）与 463fb87 新增的「请重新填写」提示双双击穿。修：声明前移（KDoc 写死「声明顺序是行为的一部分」）+ `encryptedPrefsProvider` 测试注入点 + 3 例测试，红态实证 `expected:<PLAINTEXT_EMPTY> but was:<ENCRYPTED>`。
2. `341bc06` 检查点改纯 `apply()`——口径过宽（会把 a9d804b 的持久性让掉），保留为历史记录、不 amend。
3. `2dd65a9` **P1-2** 检查点**按调用线程分流**：非主线程 `commit()`（预取轮全走这条，持久性不变）、主线程 `apply()`（消掉主线程 fsync）；`endBatch` 为字面常量 `true`；`isMainThread` 构造注入（默认 `Looper.getMainLooper()?.thread === Thread.currentThread()` + fail-safe false，**不能用 `myLooper() == getMainLooper()`**——`isReturnDefaultValues=true` 下 null==null 会误判成主线程）。
4. `22a9f4f` A16 删库守卫的 writer 级集成测试 2 例（残缺 ⇒ `deleteNonManualBySymbol` 零调用；正常 ⇒ 一次），变异验证：把 `replaceAllowed` 改 `true` 该例立刻红——这是原来「改一行 1783 例仍全绿」那个洞的直接证据。为落地批准了 `DividendWriter` 加 `transactionRunner` 默认参数（lambda 体内才取 `StockDatabase`，现有调用点零改动）。
5. `a809b42` 修正 `DividendRepositoryCachePolicyTest` 的行号引用（`744-749` → `344-371`）。
6. `53a3723` 主线程下 `endBatch` 仍强制同步 commit 的独立护栏例。

**验收（53a3723）**：9 步门禁全绿，**1791 例 / 166 XML / 0 失败 / 0 错误 / 0 跳过 / STALE=0**，323s。4 条验收口径全通过（fake 可区分 apply/commit、三例钉住分流、原 13 例原样绿、「commit 失败」假绿已修）。红态证据分级：P1-1、分流持久性、A16 为实证；「主线程走 apply」为 diff 推理。

**已知风险（不阻断）**：① 主线程 `apply()` 排队到系统 flush 之间被杀，该区间结论内存/磁盘都不在（既定代价，毫秒级）；② `freshness()` 读侧仍可能与 IO commit 的锁耦合（P2）；③ `verify_test_count` 无用例数下限，建议加 `MIN_TESTS` 断言；④ P2 待排：`DividendCacheReader` 单标的/批量负缓存时间戳口径不一致（单标的写 `now` 会让空结论在本会话内永不按 7 天失效）、`DividendSourceFetcher` 重复 `fiscalYear` 映射（`substring(0,4)` vs `take(4)`）、`DataExporter` 分组保留使上限涨到约 40 份、`prefetchGate.unlock()` 在 `withContext` 之后；⑤ androidTest 仍只有 `MigrationTest.kt`，Compose UI 零测试。

**流程教训**：门禁失败先确认快照干净（HEAD 未变 + 工作区无未提交改动）再判红——本轮 QA 首轮撞上工程师「写到一半的测试文件」，2 例红属快照问题，隔离实验（单类 16/16、子集 320 全绿）后才定性。

