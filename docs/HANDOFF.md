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
