# 金库 GoldVault

个人资产记账 Android App：持仓与账户管理、行情同步、分红日历与收息进度、资产快照与历史曲线、币安持仓导入、OCR 导入。

- 应用名：金库（`android:label="金库"`）
- 包名：`com.example.stockdiary`（早期项目名「新股日记」，部分文档仍沿用旧称）
- 当前版本：`1.0.0`（versionCode 11）

## 技术栈

| 领域 | 选型 |
|---|---|
| UI | Kotlin + Jetpack Compose（Material 3），Compose BOM `2024.02.00` |
| 本地库 | Room `2.6.1`（KSP，schema 导出到 `app/schemas`） |
| 网络 | OkHttp `4.12.0`（全裸 OkHttp，未用 Retrofit） |
| 后台任务 | WorkManager `2.9.0`（资产快照） |
| OCR | ML Kit 中文文字识别 `16.0.1`（模型内置，无需联网） |
| HTML 解析 | Jsoup `1.17.2`（天天基金分红页） |
| 环境 | `compileSdk`/`targetSdk` 35，`minSdk` 26，Java 17 |

## 快速开始

**新克隆的机器必须先告诉 Gradle SDK 在哪**，否则直接报 `SDK location not found`：
`local.properties` 被 `.gitignore` 排除（它存本机路径，本就不入库），需自己建一行
`sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk`（Windows 的 `\` 写成 `\\`），
或设置环境变量 `ANDROID_HOME`。

```bash
# 构建 debug APK
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows

# 跑 JVM 单元测试（当前用例数以 tools/check_gate.sh 的 verify_test_count 输出为准）
./gradlew testDebugUnitTest
gradlew.bat testDebugUnitTest
```

构建产物：`app/build/outputs/apk/debug/app-debug.apk`（约 65 MB）。
仓库里**不含 APK**（`*.apk` 已排除），必须自行构建。

### 两个构建坑（改依赖前务必看）

1. **Compose 版本必须严格对齐**：`compose-bom:2024.02.00` 与 `lifecycle 2.7.0` / `activity-compose 1.8.2` 是绑死的。随意升级会把 `compose-runtime` 拉高到与 `compose-ui` 不一致，导致启动即崩 `CompositionLocal LocalLifecycleOwner not present`。详见 `app/build.gradle.kts` 里的注释。
2. **项目路径含非 ASCII 字符时**，构建脚本已统一加 `-Dfile.encoding=UTF-8`（否则 Gradle Test Worker 找不到 `GradleWorkerMain`）。

## 目录导读

```
app/src/main/java/com/example/stockdiary/
├── database/   Room 实体与 DAO（Asset / Dividend / Transaction / Snapshot / Portfolio）
├── data/       Repository、AssetSyncManager 同步、分红服务、快照调度
├── network/    各数据源客户端（含 BinanceHostGate 主机级熔断器）
├── ui/         Compose 页面（screens/）、组件（components/）、主题（theme/）
├── utils/      工具类（StockCodeManager 代码分类、AppLogger、汇率）
└── common/     常量、APIEndpoints、Timeouts、Result 封装
```

配套文档：

- **`AI_GUIDE.md` —— 首选入口**：操作 + 阅读手册（30 秒定位、命令速查、四条核心链路、改功能对照表、已知坑、怎么加测试、提交约定、从零构建 APK）。
- **`docs/PROJECT_MEMORY.md` —— 项目记忆**：关键决策表、7 条不变量、开发流水（含提交号）、未解决限制。**动手前先看，别推翻已拍板的决策。**
- `docs/system_design.md` —— 系统设计（附 `.mermaid` 图）
- ⚠️ `CODE_ARCHITECTURE.md` —— **部分过时**：导航实际是 `ui/navigation/GoldVaultNavHost.kt`（不是 `StockDiaryNavHost.kt`）；`common/` 现在只有 `APIConfig.kt` 和 `Result.kt`。以实际代码为准。
- 🟡 `BUILD_INSTRUCTIONS.md` —— 步骤没错但**没提上面的 `local.properties` 坑**，照它做新克隆会失败。
- 📜 根目录其余 `*.md`（体检/诊断/review 报告等）均为**历史快照**，不代表现状。

## 数据源

| 用途 | 来源 |
|---|---|
| 股票行情 | 新浪 `hq.sinajs.cn`、腾讯 `qt.gtimg.cn` |
| 分红（股票） | 东方财富 `datacenter-web` |
| 分红（基金 / ETF / REIT） | 天天基金 `fundf10`（`fhsp_` 页面）、新浪 `vip.stock` |
| 基金净值 | 天天基金 / 东方财富 |
| 加密货币 | 币安 `api.binance.com` |

## 已知约束（避免重复踩坑）

1. **币安公开行情与签名账户已解耦，不再共用闸门**：公开行情（ticker/24hr、ticker/price、klines）一律走 `data-api.binance.vision` 镜像——它与 `api.binance.com` 同套 `/api/v3` 路径、同返回结构，但**不做地区封锁**（实测 200 + lastPrice），无需代理即可取价；`api.binance.com` 在部分地区回 **HTTP 451**（合规层拒绝，换 IP 无效，**不是代码 Bug**）。签名 `/account` 导入仍只能走 `api.binance.com`，封锁地区会 451 / `UnknownHostException`。`network/BinanceHostGate.kt`（三态断路器：CLOSED / OPEN / HALF_OPEN + 半开探测自愈）**只保护公开行情镜像**；账户 / 资产路径刻意**不喂**它——否则封锁地区账户必失败，会把能用的镜像行情一并熔断短路。
2. **场内基金 / REIT（508xxx、500/501xxx、16xxxx）的分红不在东财股票分红报告**（`RPT_SHAREBONUS_DET` 返回 9201 空），必须走天天基金分红源——见 `DividendRepository.fetchDividendData` 的 `isExchangeFund` 分支。

## 给协作者（含 AI）的建议

1. **先读 `AI_GUIDE.md`**（最新、路径均已核实），再看 `docs/PROJECT_MEMORY.md`（别推翻既有决策）。
2. 然后看 `app/src`，从 `MainActivity` → `GoldVaultNavHost` → 各 `Screen` / `ViewModel` / `Repository` 顺下来。
3. 改完请跑 `bash tools/check_gate.sh`，保证全量单测不回归（当前用例数以 `tools/check_gate.sh` 的 `verify_test_count` 输出为准）；新增逻辑请配套单测（网络路径可用注入假 `OkHttpClient` 的方式测试，仓库已有先例）。
4. 改动做完默认直接 commit + push，但**按主题分批**，且 push 前必须编译 + 全量测试通过。
