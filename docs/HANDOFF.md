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
  debug keystore 签名，`apksigner verify` 通过）。基于本地 `b8c532d` 构建；
  本次 `git pull` 被代理 502 挡住，若远端有更新的提交，此包不含那些改动。
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

