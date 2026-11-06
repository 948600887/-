# 发版前检查清单（Release Checklist）

> **为什么需要这份清单**：`tools/check_gate.sh` 与 `.github/workflows/gate.yml` 都是**无设备**的
> 静态 / JVM 校验：它们能编译 androidTest 源码、能离线比对迁移 SQL 与 schema JSON，
> 但**执行不了** instrumentation 测试，也**验不了** release 混淆后的真实行为。
> Room v18 迁移的 `IdentityHash` 运行时校验尤其如此——离线 Python 脚本替代不了。
> 所以发版前必须由人在**真机或模拟器**上补跑一次并留档。
> `check_gate.sh` 末尾只打印一行提醒、不自动执行本清单（避免拖慢日常门禁）。

每次发版（打 release 包 / 推 `main`）前，按顺序完成三项，逐项把命令输出留档。

---

## ① 质量门禁全绿

```bash
bash tools/check_gate.sh
```

必须打印 `GATE PASSED`，且 `verify_test_count` 一行里的失败 / 错误均为 0。

这一步包含：2 步毫秒级源码不变量静态检查（含校验器自身的负向反证）、
`compileDebugKotlin`、`compileDebugAndroidTestKotlin`、离线迁移校验（含 17→18 列断言）、
迁移数据保留校验、全量 JVM 单测。
**不要带 `--skip-unit-test`**：那个开关只是改文档 / 改脚本时的本地快速通道，发版不适用。

---

## ② 真机 / 模拟器跑 instrumentation 测试并留档输出 —— **本步需要设备**

```bash
# Windows
./gradlew.bat connectedDebugAndroidTest
# macOS / Linux
./gradlew connectedDebugAndroidTest
```

⚠️ **必须先接上真机或启动模拟器**（`adb devices` 能看到设备），否则任务会失败。

**为什么这一步不可省**：这是 Room v18 迁移 `IdentityHash` 的**唯一端到端验证**。
老用户跨 v1→v18 升级时，Room 打开真实数据库会校验 IdentityHash；若某条手写迁移
与导出的 `18.json` 不一致，会直接抛 `IllegalStateException` —— 用户**一开 App 就崩、
无法自愈、只能卸载重装 = 丢全部资产数据**。

- `tools/validate_migration.py` 是**离线**脚本：它按 JSON 建 in-memory 库、执行迁移 SQL、
  用 PRAGMA 双向比对，验的是「SQL 跑完的 schema」，**不会触发 Room 的 IdentityHash 校验**，
  因此替代不了第 ② 步。
- `check_gate.sh` 只做 `compileDebugAndroidTestKotlin`（编译，不执行），
  `MigrationTest.kt` 真跑起来要靠本步。
- CI（`.github/workflows/gate.yml`）跑在 `ubuntu-latest`、无设备、不配 emulator，
  **跑不了** `connectedAndroidTest`，只能靠人工这一遍。

把完整输出留档（例如 `docs/release-evidence/<日期>-connectedAndroidTest.log`），
发版记录里注明设备型号 / API 级别与执行结果。

---

## ③ release 包构建 + 签名 + 安装冒烟

```bash
# 只打 arm64-v8a（真机常用 ABI，体积约 31MB；不传该参数则出四 ABI 通用包）
./gradlew.bat assembleRelease -PabiFilter=arm64-v8a

# 用正式 keystore 签名并校验签名
apksigner sign --ks <你的 keystore> --ks-key-alias <别名> \
    --out GoldVault-release-signed.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose GoldVault-release-signed.apk

# 装到真机做冒烟
adb install -r GoldVault-release-signed.apk
```

冒烟至少覆盖：冷启动不崩、首页能出数、下拉刷新能跑完、从旧版本覆盖安装后资产数据仍在。

**为什么 release 必须单独验**：`release` 开了 `isMinifyEnabled` / `isShrinkResources`，
Gson / Room / Compose 在混淆后的行为与 debug 不同；`proguard-rules.pro` 的 `-keep` 规则
目前是刻意保留的（改动必须真机验证，收益不抵风险），但「规则没改」不等于「包没问题」，
所以每次发版仍要装一遍真机。

---

## 留档模板

| 项 | 命令 | 结果 | 输出位置 |
|---|---|---|---|
| ① 门禁 | `bash tools/check_gate.sh` | `GATE PASSED` / 失败数 0 | |
| ② 设备测试 | `connectedDebugAndroidTest` | PASS / 设备 / API | |
| ③ release | `assembleRelease` + `apksigner` + `adb install -r` | 冒烟结论 | |
