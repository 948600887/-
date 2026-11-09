# 多 AI 协作约定（动手前必读）

本仓库同时由两个 AI 改进。**先读本文件，再动手。** 配套交流集：[`docs/HANDOFF.md`](docs/HANDOFF.md)。

## 一句话原则

**`git log` 是唯一事实来源。** 本文件与 `docs/HANDOFF.md` 只负责两件 git 管不了的事：
**动手前声明「我在改什么」**，以及**交接「为什么这么改、给对方留了什么坑」**。

所有静态文档（`AI_GUIDE.md`、`docs/PROJECT_MEMORY.md`、`CODE_ARCHITECTURE.md`、`docs/system_design.md`）
都是某一时刻的快照，**可能过时**；有冲突一律以实际代码 + `git log` 为准，不要照着旧文档改代码。

## 开工三步

1. `git pull --rebase`，再 `git log --oneline -8` 看对方最近改了什么。
2. 读 `docs/HANDOFF.md` 的「当前认领」表和最近 3 条交接流水。
3. 在「当前认领」里追加一行，写明你要改的文件/模块。**若与对方重叠，先等或换模块**，别两边同时改同一文件。

## 收工三步

1. 跑 `bash tools/check_gate.sh`，全绿才推（gradle 任务需 `--rerun`，否则 UP-TO-DATE 假绿）。
2. **立即**按主题提交并推送：`git add <具体文件>` → `git commit`（中文信息）→ `git pull --rebase` → `git push`。
   **不要攒着一起提**，攒着必冲突。交接文件的更新也一并提交。
3. 在 `docs/HANDOFF.md` 追加一条交接记录，并删掉自己在「当前认领」里的那行。

## 硬规则

- **append-only**：`docs/HANDOFF.md` 的流水只追加、不改写历史条目；「当前认领」只增删自己的行。
- **禁止抹掉对方工作区**：不要 `git checkout -- .` / `git reset --hard` / `git clean -fd` / `git commit -a`。
  改文件前先 `git status` + `git diff` 看有无对方未提交的改动；`git add` 只列自己的文件，别 `git add .`。
- **偏好写入走 Manager**：`DashboardPreferences` 一律用 `DashboardPreferencesManager.updatePreferences { it.copy(...) }`，
  **禁止整块 `savePreferences(_preferences.value)` 回写**（会冲掉对方刚改的字段）。详见 `AI_GUIDE.md`。
- **不要并发跑构建**：两个进程同时 `gradlew` 会抢 `app/build/`，容易出 `Unable to delete ...kotlin-classes` 假失败。
- **出包（APK）**：产物落 `releases/`，文件名带 commit hash，用 debug keystore 签名，默认只留最新 1 个包。
  多 AI 环境下**先确认对方没在出包**；清理时只删「明确早于自己刚出的包」的旧包，**别误删对方刚出的包**。
- **提交信息**：中文，标题 `type(scope): 简述`，正文写清「为什么改 + 已知取舍 + 验证结果」。
- 用户偏好：改动做完**默认直接 commit + push**，不逐次询问；但 push 前门禁必须全绿、按主题分批提交。

## 身份标识

用 `git config user.name` 区分双方，这样 `git log --format='%h %an %s'` 一眼看出谁改了什么。
本机当前是 `小伍 <workbuddy@local>`。另一个 AI 请在本仓库设一个不同的名字（局部配置，不影响其它仓库）：

```bash
git config user.name "AI-B"
```

## 更推荐：各用一份独立工作区

如果两边都在**同一个文件夹**里干活，会真实抢这些东西：`app/build/`（并发 gradle）、`releases/`（默认清理会互删包）、
`.git/index.lock`（同时提交）、以及对方的未提交改动。最干净的避法是让另一个 AI **单独 clone 一份到别的目录**：

```bash
git clone https://github.com/948600887/-.git GoldVault-AI-B
```

各写各的，靠 push/pull + `docs/HANDOFF.md` 互通，物理冲突全部消失，只剩 git 合并（git 自己能处理）。
注意新克隆缺 `local.properties` 会报 `SDK location not found`，建法见 `AI_GUIDE.md` 开头。

## 更多背景

| 想找 | 看哪里 |
|---|---|
| 构建 / 目录地图 / 已知坑 | `AI_GUIDE.md`（入口手册） |
| 谁在改什么、交接留言 | `docs/HANDOFF.md` |
| 项目决策快照 | `docs/PROJECT_MEMORY.md`（**快照停在 2026-10-22，别当现状**） |
| 发版检查清单 | `docs/RELEASE_CHECKLIST.md` |
