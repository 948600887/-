# 金库 GoldVault —— AI 协作操作手册

给需要接手本仓库的 AI（或人）的**操作 + 阅读指南**。先读 `README.md` 了解项目概况，再按本手册定位与动手。

> ⚠️ **重要：`CODE_ARCHITECTURE.md` 部分内容已过时**，不要全信。已核实的过时点：
> - 导航类实际是 `ui/navigation/GoldVaultNavHost.kt`，不是文档里写的 `StockDiaryNavHost.kt`；
> - `common/` 目录现在只有 `APIConfig.kt`（内含 `APIEndpoints` 对象）和 `Result.kt`，文档里列的 `Constants.kt` / `ErrorMessages.kt` / `components/` 等已不存在。
>
> 有冲突时**以实际代码为准**（本手册的路径均已逐条核实过）。

### 根目录一堆 .md，该信哪些

| 文件 | 可信度 | 说明 |
|---|---|---|
| `README.md` | ✅ 可信 | 项目概况、技术栈、构建命令、目录导读。 |
| **`AI_GUIDE.md`（本文件）** | ✅ 最新 | 操作 + 阅读手册，**优先读这个**。 |
| `docs/PROJECT_MEMORY.md` | ✅ 最新 | 项目完整记忆：决策、不变量、开发流水。 |
| `docs/system_design.md` + 两个 `.mermaid` | 🟡 大致可用 | 设计图，细节以代码为准。 |
| `CODE_ARCHITECTURE.md` | ⚠️ 部分过时 | 见上方警告，路径已对不上。 |
| `BUILD_INSTRUCTIONS.md` | 🟡 不完整 | 步骤没错，但**没提新克隆会因缺 `local.properties` 直接构建失败**（见下章），照它做会踩坑。 |
| `体检报告-*.md` / `诊断报告-*.md` / `code_review_report.md` / `final_comprehensive_check_report.md` / `OCR导入优化说明-*.md` / `东方财富妙想Skill安装指南.md` | 📜 历史快照 | 当时的一次性产出，**不代表现状**，别拿它当规格书。 |

---

## ⚡ 从零构建 APK（新克隆必读，否则一定构建失败）

### 前置条件

| 项 | 要求 |
|---|---|
| JDK | **17**（`compileOptions` 与 `kotlinOptions.jvmTarget` 都是 17） |
| Android SDK | **compileSdk / targetSdk = 34**，需装 Android 14（API 34）平台 + 对应 build-tools |
| 网络 | 首次构建要下载 Gradle 8.13 和全部依赖（Compose / Room / OkHttp 等），几分钟 |

### ⚠️ 必须做的一步：告诉 Gradle SDK 在哪

`local.properties` **被 .gitignore 排除了**（它存本机 SDK 路径，本就不该入库），
所以新克隆**没有**这个文件。不处理会直接报 `SDK location not found`。二选一：

1. **推荐**：仓库根目录新建 `local.properties`，写一行
   ```
   sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
   ```
   （Windows 的 `\` 要写成 `\\`；macOS/Linux 写 `sdk.dir=/Users/<你>/Library/Android/sdk`）
2. 或设置环境变量 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）指向 SDK 根目录。

> 补充：`local.properties` 还可放 `api_key` 与 `eastmoney_skill_api_key`，
> 但 `app/build.gradle.kts` 对两者都有默认值（`"demo"` / 空串），**不配也能构建**，只影响个别联网功能。

### 然后

```bash
./gradlew.bat assembleDebug      # Windows
./gradlew assembleDebug          # macOS / Linux
```

首次会先下载 Gradle 与依赖，耐心等（5~15 分钟）。成功后 APK 在
`app/build/outputs/apk/debug/app-debug.apk`（约 65 MB，debug 签名，可直接 `adb install -r` 安装）。

> 注意：`*.apk` 也在 .gitignore 里，**仓库里没有 APK，必须自己构建**——这是刻意的，避免大二进制入库。
> 同理 `gradle-8.13/` 与 `gradle-8.13-bin.zip` 也被排除，首次构建由 Gradle Wrapper 自动下载。

### ✅ 仓库完整性自检（拿到仓库先跑一遍）

```bash
git ls-files | wc -l                 # 应为 320（全部源码 + 资源，无 APK）
git status                           # 工作树应干净
git log --oneline -1                 # 最新应为 106657a（或更新）
```

想确认**源码一个不少**（比如怀疑对方给的 zip 不完整），用官方仓库比对：

```bash
git fetch origin && git log origin/main..main --oneline   # 空 = 与远端一致
```

只想导出纯净源码（不含 build 产物/gradle 缓存）：

```bash
git archive --format=zip --prefix=NewStockDiary/ -o NewStockDiary.zip HEAD   # 约 1.4 MB
```

> 别整仓打包：仓库含 350 个 jar、`gradle-8.13` 146 MB、build 目录，整仓 **767 MB**；
> 而 `app/src` 只有 **4.1 MB / 259 个 .kt**。

---

## 1. 30 秒定位

| 想知道 | 看哪里 |
|---|---|
| App 入口 | `MainActivity.kt`（包根目录） |
| 页面路由 | `ui/navigation/GoldVaultNavHost.kt` |
| 仪表盘（主界面） | `ui/screens/DashboardScreen.kt` + `ui/viewmodels/DashboardViewModel.kt` |
| 数据库表结构 | `database/Asset.kt`、`Dividend.kt`、`AssetSnapshot.kt`、`Transaction.kt` |
| 行情怎么抓的 | `network/UnifiedPriceService.kt`（总门面）、`data/price/StockDataManager.kt` |
| 分红怎么抓的 | `data/repository/DividendRepository.kt` |
| 收息进度怎么算的 | `data/dividend/DividendProgressCalculator.kt` |
| 仪表盘显示开关 | `data/prefs/DashboardPreferencesManager.kt` |
| 币安持仓导入 | `data/importer/BinanceImportService.kt` + `ui/viewmodels/BinanceImportViewModel.kt` |
| 依赖注入 | `di/AppContainer.kt`（手写单例容器，**没有 Hilt**） |

包名：`com.example.stockdiary`。所有路径若无特别说明，均相对于
`app/src/main/java/com/example/stockdiary/`。

## 2. 命令速查

```bash
# 编译（改 Kotlin 后最快反馈，约 1~3 分钟）
./gradlew.bat compileDebugKotlin

# 全量 JVM 单元测试（当前 956 个，约 2~3 分钟）
./gradlew.bat testDebugUnitTest

# 只跑某一个测试类
./gradlew.bat testDebugUnitTest --tests "com.example.stockdiary.data.dividend.*"

# 打 debug APK（约 40 秒）
./gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 用 `gradlew.bat`，macOS/Linux 用 `./gradlew`。

## 3. 目录地图

```
app/src/main/java/com/example/stockdiary/
├── MainActivity.kt              入口
├── database/                    Room：实体 + DAO（Asset / Dividend / Transaction / AssetSnapshot / Portfolio / StockBasic）
├── data/
│   ├── repository/              AssetRepository、DividendRepository
│   ├── sync/                    AssetSyncManager（价格同步编排 + 看门狗）
│   ├── price/                   StockDataManager（股票行情多源 + 数据源级熔断）、SourceCircuitBreaker
│   ├── dividend/                DividendProgressCalculator（收息进度）、DividendIncomeLoader、
│   │                            DividendYieldCalculator、DividendIncomeCalculator、DividendService
│   ├── importer/                BinanceImportService（币安持仓导入）
│   ├── calculator/              AssetGrowthCalculator（资产增值）
│   └── prefs/                   DashboardPreferencesManager（仪表盘偏好/开关）
├── network/                     网络层：UnifiedPriceService（门面）、NetworkClient（OkHttp 池）、
│                                BinanceAPI / BinanceAccountAPI / BinanceHostGate（币安 + 熔断）、
│                                SinaStockAPI / TencentStockAPI / FundAPI（行情源）、
│                                EastmoneyDividendApiService / FundDividendApiService（分红源）
├── ui/
│   ├── navigation/              GoldVaultNavHost
│   ├── screens/                 各页面（Dashboard / Portfolio / History / Calendar / Settings …）
│   ├── viewmodels/              各页面 ViewModel
│   ├── components/              可复用 Compose 组件（DashboardComponents、DividendProgressComponents …）
│   └── theme/                   Theme / Color / Typography
├── utils/                       StockCodeManager（代码分类）、ExchangeRateManager（汇率）、AppLogger
├── common/                      APIConfig.kt（APIEndpoints 端点/超时/缓存）、Result.kt
└── di/                          AppContainer（手写依赖容器）
```

## 4. 四条核心链路

**① 行情同步**：`AssetSyncManager` 编排（有并发信号量 + 总预算看门狗）→ `UnifiedPriceService`
→ 按类型分派：股票走 `StockDataManager`（新浪/腾讯，带 `SourceCircuitBreaker` 数据源级熔断）、
基金走 `FundAPI`、加密货币走 `BinanceAPI`（受 `BinanceHostGate` 主机级熔断保护）。

**② 分红抓取**：`DividendRepository.prefetchDividends`（东财批量，分块并发）→ 逐条
`fetchDividendData`：场外基金 → 天天基金；**场内基金（ETF/REIT）→ 天天基金**；
普通股票 → 东财 → 新浪兜底。
判断依据在 `StockCodeManager`（`isOTCFund` / `isETF` / `classifyCode`）。

**③ 收息进度**：`DividendProgressCalculator.calculate`
- 分子「今年已收」= 除息日在今年且 ≤ 今天的记录 × 当前持仓（**按除息日**）
- 分母「今年预计」= `naturalYearAnnualIncome`，与 `DividendIncomeLoader` 的年度收入**同源同值**
- 货币基金特殊：无现金分红，按「每万份收益 × 已过天数/当年天数」估算

**④ 币安导入**：`BinanceImportViewModel` → `BinanceAccountAPI.getAccount`（签名 `/account`）
+ `getPrices`（公开 `/ticker/price`）→ `BinanceImportService.buildPreview` → 用户确认 → `commit` 落库。

## 5. 改功能该动哪里

| 需求 | 主要文件 |
|---|---|
| 加/改仪表盘卡片显示开关 | 真正生效的是 `data/prefs/DashboardPreferencesManager.kt` 里的 **`DashboardPreferences` data class**（字段 + KEY + `load/savePreferences`）；UI 在 `ui/screens/settings/components/DashboardConfigSheet.kt`（手写 `SwitchPreference`）+ `ui/screens/DashboardScreen.kt`（条件渲染）。⚠️ 同文件里还有个 `DashboardComponent` 枚举 + 两处 `when`（`updateComponentVisibility` / `isComponentVisible`），那是**死代码**，生产零消费，改它界面不会变，别动。 |
| 改收息进度口径 | `data/dividend/DividendProgressCalculator.kt` |
| 改年度收入/预计收入 | `data/dividend/DividendIncomeLoader.kt` + `DividendProgressCalculator.naturalYearAnnualIncome`（两者必须同源） |
| 加分红数据源 | `network/` 新建 API 类 → `data/repository/DividendRepository.kt` 接路由 → `data/sync/AssetSyncManager.kt` |
| 加行情数据源 | `network/` 新建 → `data/price/StockDataManager.kt` 注册 → 配 `SourceCircuitBreaker` |
| 改代码分类规则（哪些算 ETF/REIT/场外） | `utils/StockCodeManager.kt` |
| 加页面 | `ui/navigation/GoldVaultNavHost.kt` + `ui/screens/` + `ui/viewmodels/` |
| 改主题配色 | `ui/theme/Color.kt` / `Theme.kt` |

## 6. 架构与代码约定

- **MVVM + Repository**：`Screen → ViewModel → Repository → DAO / API`。
- **依赖注入**：手写 `AppContainer`（`di/AppContainer.kt`），`by lazy` 单例，**没用 Hilt**（别引入，会与现有构造冲突）。
- **命名**：`XxxScreen` / `XxxDialog` / `XxxData` / `XxxManager` / `XxxAPI` / `XxxRepository` / `XxxViewModel`。
- **日志**：一律用 `AppLogger`，它会对 OkHttp 日志做**脱敏**（币安签名/密钥不会落盘）。**禁止**把 API Key/Secret 写进日志或异常 message。
- **币种**：金额一律经 `ExchangeRateManager.toCny(...)` 折成人民币后再汇总。
- **KDoc**：本仓库注释写得重，尤其"为什么这么做"。**改代码前先读注释**，很多坑都写在里面了。

## 7. 已知约束与坑（必读）

1. **Compose 版本必须严格对齐**：`compose-bom:2024.02.00` 与 `lifecycle 2.7.0` / `activity-compose 1.8.2` 绑死。随意升级会把 `compose-runtime` 拉高，导致启动即崩 `LocalLifecycleOwner not present`。见 `app/build.gradle.kts` 注释。
2. **项目路径含非 ASCII 字符**：构建已统一加 `-Dfile.encoding=UTF-8`，别删（`app/build.gradle.kts` 的 `tasks.withType<Test>`）。
3. **币安在中国大陆被地区封锁**：行情返 **HTTP 451**、账户常 `UnknownHostException`。**这是网络/合规限制，不是代码 Bug**，换币安自家域名同样 451。已用 `network/BinanceHostGate.kt`（三态断路器：CLOSED/OPEN/HALF_OPEN + 半开探测自愈）做主机级短路；**行情路径与账户/资产路径共用同一闸门**。
4. **两套日期口径，不要混**：`收息进度` 按**除息日**；`日历页`按**发放日**（`DividendData.cashDateOrNull`）。日历用发放日是专门修"钱没到却显示分红"这个 bug 换的，**改回除息日会让它复活**。
5. **`DashboardComponent` 枚举 + 两处穷举 `when`（`updateComponentVisibility` / `isComponentVisible`）是死代码**：生产代码零消费，真正生效的开关在 `DashboardPreferences` data class（见第 5 节对照表）。保留它只为兼容 SharedPreferences KEY，**别在它上面加新开关**——改了界面也不会变。若要做大扫除，连同 `DashboardPreferencesCardVisibilityTest` 一起删。
6. **场内基金/REIT（508xxx、500/501xxx、16xxxx）分红不在东财股票分红报告**（返回 9201 空），必须走天天基金源。见 `DividendRepository.fetchDividendData` 的 `isExchangeFund`。
7. **分红负缓存**：数据源"未应答"（网络失败）时 `answered` 不置位、末尾 `throw DividendSourceUnavailableException`，**不写负缓存**；只有真正应答过才缓存"无分红"。
8. **货币基金**不发现金分红，走"每万份收益"估算，与现金分红口径不同（见 `moneyFundIncome`）。
9. 代码里**没有** `DataManager.kt`：早期架构文档曾称其为「旧版全局状态容器」，实际不存在。活跃的行情抓取层是 `StockDataManager`（多源聚合 + `SourceCircuitBreaker` 数据源级熔断），本身被 Repository 调用，是现行代码不是遗留物。**不要**为了「消灭 DataManager」做大重构。

## 8. 测试怎么加

- 全在 `app/src/test/`，纯 JVM（`unitTests.isReturnDefaultValues = true`），用 **JUnit4 + mockk + kotlinx-coroutines-test**。
- **网络路径**：给 API 加 `internal` 重载注入假 `OkHttpClient`（用自定义 `Interceptor` 造响应/异常），参考 `BinanceAccountAPI.getPrices(pairs, httpClient)` 与 `BinanceAccountAPIGateTest`。
- **SharedPreferences**：参考 `DashboardPreferencesCardVisibilityTest` 里的内存版 FakePrefs（仓库原有的 `DividendIncomeCacheVerifyTest.FakePrefs` 只实现了 getString，跑不动 `savePreferences`）。
- **进程内 object**（如 `BinanceHostGate`、`ExchangeRateManager`）测试必须 `@Before`/`@After` 复位，防全局状态污染其他测试类。
- 加完跑全量：`./gradlew.bat testDebugUnitTest`，**必须 0 失败**。

## 9. 提交约定

- 改动做完**默认直接 commit + push**，无需逐次确认。
- **按主题分批提交**（"修 Bug"与"加功能"分开），一笔一个主题。
- **push 前必须编译 + 全量测试通过**，不带红灯推。
- 提交信息用中文：标题 `type(scope): 简述`，正文写清**为什么改**、已知取舍、验证结果。
- 远端：`https://github.com/948600887/-`（公开，分支 `main`）。

## 10. 项目记忆（接手必读，动手前先看，别推翻已拍板的决策）

**完整版见 [`docs/PROJECT_MEMORY.md`](docs/PROJECT_MEMORY.md)**——长期约定、关键决策表、7 条不变量、
开发流水（含提交号）、未解决限制，都在里面。下面只列**最容易踩的 4 条**：

| # | 决策 | 一句话 |
|---|---|---|
| 1 | **收息进度按除息日** | 原先分子按到账日、分母按除息日导致「已收 > 预计」。改后已收 ≤ 预计恒成立。**代价**：「今年已收」不再等同"钱真到账"。 |
| 2 | **日历页仍按发放日** | 日历用发放日是专门修「钱没到却显示分红」这个 bug 换的，**改回除息日会让 bug 复活**。两套口径并存是有意为之。 |
| 3 | **明确不做币安备用域名/重试** | 451 是按条款拒绝，换币安自家域名照样 451；串行重试把 20s 放大到 80s，是负优化。币安拿不到**只能挂代理**。 |
| 4 | **REIT 分红走天天基金** | 东财股票分红报告不含 REIT 收益分配（返 9201 空），此前静默显示 0 并被 7 天负缓存固化。 |

### 长期约定

- 改动做完**默认直接 commit + push**，无需逐次确认。
- 但：**按主题分批提交**，且 **push 前必须编译 + 全量测试通过**，不带红灯推。

### 仍未解决 / 外部限制（别再试图"修复"）

- **币安资产在中国大陆拿不到**：地区封锁（HTTP 451 + DNS 屏蔽），代码层面无解，唯一办法是挂代理。
- 代码里**没有** `DataManager.kt`：早期文档误称其为旧版全局状态容器，实际不存在；活跃行情层是 `StockDataManager`（多源 + 熔断），被 Repository 调用，是现行代码。无需为「消灭 DataManager」做重构。
- `CODE_ARCHITECTURE.md` 部分过时，以实际代码与本手册为准。

## 11. 记忆在哪（三层）

本项目的"记忆"分三处，**只有最后一份在仓库里**，前两份在开发机本地、不入 git：

| 层 | 位置 | 说明 |
|---|---|---|
| 用户级长期偏好 | `~/.workbuddy/user-*-personal/MEMORY.md` | 跨项目通用偏好（如"默认提交并推"）。不入库。 |
| 项目工作日志 | `<工作区>/.workbuddy/memory/YYYY-MM-DD.md` | 逐日 append-only 原始流水。不入库。 |
| **项目记忆（本仓库）** | **`docs/PROJECT_MEMORY.md`** | ✅ 上面两层的**可读快照**，已随代码入库，另一个 AI clone 后可直接读到。 |

> 所以：把仓库（或 `AI_GUIDE.md` + `docs/PROJECT_MEMORY.md`）给另一个 AI，
> 它就同时拿到了**全部代码 + 项目记忆 + 构建 APK 的方法**。
