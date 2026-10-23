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
git ls-files | wc -l                 # 应为 323（全部源码 + 资源，无 APK）
git status                           # 工作树应干净
git log --oneline -1                 # 最新应为 e79c758（或更新）
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
> 而 `app/src` 只有 **4.1 MB / 261 个 .kt**。

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
| 后台同步按类型分频 | `data/sync/SyncStrategy.kt` + `data/sync/PriceSyncWorker.kt` |
| 资产币种/到期日 | `database/Asset.kt`（`currency`/`dueDate`）+ `utils/ExchangeRateManager.kt` |
| 币安持仓导入 | `data/importer/BinanceImportService.kt` + `ui/viewmodels/BinanceImportViewModel.kt` |
| 依赖注入 | `di/AppContainer.kt`（手写单例容器，**没有 Hilt**） |

包名：`com.example.stockdiary`。所有路径若无特别说明，均相对于
`app/src/main/java/com/example/stockdiary/`。

## 2. 命令速查

```bash
# 编译（改 Kotlin 后最快反馈，约 1~3 分钟）
./gradlew.bat compileDebugKotlin

# 全量 JVM 单元测试（当前 981 个，约 2~4 分钟）
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
│   ├── sync/                    AssetSyncManager（价格同步编排 + 看门狗）、PriceSyncWorker（后台周期任务）、
│   │                            SyncStrategy（按资产类型分频判定）、PriceSyncPreferencesManager（同步偏好）
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
→ 按类型分派：股票走 `StockDataManager`（**数据源全自动**：妙想Skill(配置了 API Key 才参与) > 新浪 > 腾讯，
带 `SourceCircuitBreaker` 数据源级熔断，某个源连续失败会自动短期禁用并降级；「我的 → 行情数据源」手动选择入口已去除）、
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
| 加/改仪表盘卡片显示开关 | 真正生效的是 `data/prefs/DashboardPreferencesManager.kt` 里的 **`DashboardPreferences` data class**（字段 + KEY + `load/savePreferences`）；UI 在 `ui/screens/settings/components/DashboardConfigSheet.kt`（手写 `SwitchPreference`）+ `ui/screens/DashboardScreen.kt`（条件渲染）。⚠️ 开关改完要**即时生效**：`DashboardViewModel` 有 `reloadPreferences()`、`DashboardScreen` 用 `DisposableEffect(ON_RESUME)` 回前台触发；存年度收入缓存前必须先 `loadPreferences()` 再只覆盖 `annualDividendCache`/`annualDividendSymbols` 两个字段，否则会把用户刚改的开关回滚（见 `3a10cc3`）。 |
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
- **币种**：金额一律经 `ExchangeRateManager.toCny(...)` 折成人民币后再汇总。`Asset.currency`（默认 `"CNY"`）已在添加/编辑表单暴露（CNY/USD/HKD，切换类型时给默认值）；未知币种 `getRate` 兜底 1.0 且**每币种只告警一次**。⚠️ 调用 `AssetRepository.addOrUpdateStockAsset` 时**必须传 `currency`**——它是 12 参数函数，新建分支才写入 currency/dueDate 等；**合并分支刻意不改这些字段**（靠 `existingAsset.copy` 保留既有），否则加仓会把既有外币仓重置成 CNY。详见第 7 节第 10 条。
- **KDoc**：本仓库注释写得重，尤其"为什么这么做"。**改代码前先读注释**，很多坑都写在里面了。

## 7. 已知约束与坑（必读）

1. **Compose 版本必须严格对齐**：`compose-bom:2024.02.00` 与 `lifecycle 2.7.0` / `activity-compose 1.8.2` 绑死。随意升级会把 `compose-runtime` 拉高，导致启动即崩 `LocalLifecycleOwner not present`。见 `app/build.gradle.kts` 注释。
2. **项目路径含非 ASCII 字符**：构建已统一加 `-Dfile.encoding=UTF-8`，别删（`app/build.gradle.kts` 的 `tasks.withType<Test>`）。
3. **币安公开行情已绕开中国大陆地区封锁**：公开行情镜像 `data-api.binance.vision` 与 `api.binance.com` 同套 `/api/v3` 路径、**不做地区封锁**（实测 200 + lastPrice），故**行情/查价/市值估算无需代理即可联网**。但**签名 `/account` 导入仍只能走 `api.binance.com`**，封锁地区会 451 / `UnknownHostException`，仍需挂代理。`network/BinanceHostGate.kt`（三态：CLOSED/OPEN/HALF_OPEN + 半开探测自愈）**只保护公开行情镜像**；账户路径刻意不喂它——否则封锁地区账户失败会把能用的镜像行情一并熔断短路。
4. **两套日期口径，不要混**：`收息进度` 按**除息日**；`日历页`按**发放日**（`DividendData.cashDateOrNull`）。日历用发放日是专门修"钱没到却显示分红"这个 bug 换的，**改回除息日会让它复活**。
5. ~~`DashboardComponent` 枚举死代码~~ **已于 `b47cee8` 删除**（连同 `updateComponentVisibility` / `isComponentVisible` / `getVisibleComponents`）。开关现在只在 `DashboardPreferences` data class 一处，不再有两套实现。旧资料（含某些审查报告）若让你去改那个枚举——**忽略，它已不存在**。
6. **场内基金/REIT（508xxx、500/501xxx、16xxxx）分红不在东财股票分红报告**（返回 9201 空），必须走天天基金源。见 `DividendRepository.fetchDividendData` 的 `isExchangeFund`。
7. **分红负缓存**：数据源"未应答"（网络失败）时 `answered` 不置位、末尾 `throw DividendSourceUnavailableException`，**不写负缓存**；只有真正应答过才缓存"无分红"。
8. **货币基金**不发现金分红，走"每万份收益"估算，与现金分红口径不同（见 `moneyFundIncome`）。
9. 代码里**没有** `DataManager.kt`：早期架构文档曾称其为「旧版全局状态容器」，实际不存在。活跃的行情抓取层是 `StockDataManager`（多源聚合 + `SourceCircuitBreaker` 数据源级熔断），本身被 Repository 调用，是现行代码不是遗留物。**不要**为了「消灭 DataManager」做大重构。
10. **`addOrUpdateStockAsset` 的参数契约（2026-10-22 新增，最易踩）**：签名现为 **12 参**（原 symbol/name/balance/price/account/assetType/fee + `currency`/`dueDate`/`notes`/`interestRate`/`currentPrice`）。**新建持仓分支**把这些字段落库；**合并（加仓）分支刻意只更新 `balance`/`purchasePrice`/`lastUpdateTime`**，currency/dueDate 等靠 `existingAsset.copy` 默认保留。原因是 `AssetDetailViewModel.adjustPosition` 走合并分支且不传币种——若把合并分支写成"用传入值覆盖"，加仓会把既有 USD 仓重置成 CNY。**新增调用方必须传 `currency`**，否则外币资产被静默按 CNY 折算（`2e1f208`）。
11. **后台同步按资产类型分频，不再一刀切**（`5c7410b`）：判定集中在一个纯函数 `data/sync/SyncStrategy.kt` 的 `shouldAutoSyncType(type, inTradingHours, stockIntradaySync, cryptoAutoSync)`——股票/基金 = `stockIntradaySync || !inTradingHours`（**仅盘后/非交易时段刷**）、加密 = `cryptoAutoSync`（**默认关，只手动刷**）。两偏好在 `data/sync/PriceSyncPreferencesManager.kt`，默认均为 **false**；`PriceSyncWorker` 每轮据此过滤资产，全被过滤掉时跳过本轮但仍重排下次。要改同步时机先看这个函数，别直接改 `AssetSyncManager` 的时钟。
12. **日历有两处专门修过的坑，别改回去**：① `CalendarViewModel` 对 assets 流按 `(symbol, balance, currency)` 签名做 `distinctUntilChanged`（`ee6054e`）——日历不依赖实时价，去掉它一次同步会重算日历 20+ 次；② 日期格子是**固定高度 + 三段固定槽位**（`41e71a9` / `b17fe07` / `cee874b`），贴底的「分」槽位收太紧会被 Surface 的 `clip` 切掉半截。动日历布局前先读这三笔注释。
13. **以下死代码已删除，旧资料别再引用**（`f5b44f5`、`b47cee8`）：`AssetDao` 的 `getAssetsByAccount(Flow)` / `getTotalValueByCurrency` / `getAccountsByType` / `getAssetsPaged` / `deleteAssetsByType` 及其 Repository 转发、`Extensions.DividendData.isNewest()`、`DashboardComponent` 枚举及三处方法。若某份审查报告让你用它们，说明那份报告基于旧版本。
14. **行情数据源没有手动切换入口了**（2026-10-23 应用户要求去除）：顺序**全自动** = 妙想Skill(仅配置了 API Key 时参与) > 新浪 > 腾讯，某个源连续失败由 `SourceCircuitBreaker` 自动短期禁用并降级。`ApiManager` 现在只保留三个源名字符串常量，**不再有 SharedPreferences 偏好读写**；`ApiSelectionDialog.kt` 已删除。设置页保留「**数据源状态**」入口（`DataSourceStatusDialog`）用来看每个源通不通、熔断状态。批量预取 `StockDataManager.getBatchStockDataThroughSina` 仍写死走新浪（只有新浪有批量接口，N 个代码 ≈ ⌈N/20⌉ 次请求），某个代码没被预取到时才走上面的自动链。**别再去加"让用户选数据源"的 UI**——那是被明确去掉的。

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
| 3 | **公开行情改走 data-api.binance.vision 镜像（绕开地区封锁）；串行重试仍不做** | 451 是合规层拒绝、换 IP 无效；但币安**公开行情镜像** `data-api.binance.vision` 与 `api.binance.com` 同套路径、不做地区封锁，实测可稳定取价。故公开行情（ticker/24hr、ticker/price、旧 BinanceAPI）一律走镜像、**无需代理**。签名 `/account` 导入只能走 api.binance.com，封锁地区仍需代理。串行重试仍不做（20s→80s 是负优化）。⚠️ 账户路径**不再喂** BinanceHostGate——否则封锁地区账户失败会把能用的镜像行情也熔断短路。 |
| 4 | **REIT 分红走天天基金** | 东财股票分红报告不含 REIT 收益分配（返 9201 空），此前静默显示 0 并被 7 天负缓存固化。 |

### 长期约定

- 改动做完**默认直接 commit + push**，无需逐次确认。
- 但：**按主题分批提交**，且 **push 前必须编译 + 全量测试通过**，不带红灯推。

### 仍未解决 / 外部限制（别再试图"修复"）

- **币安签名 `/account` 导入在中国大陆仍受地区封锁**：HTTP 451 + DNS 屏蔽，代码层面无解，唯一办法是挂代理。公开行情已改走 `data-api.binance.vision` 镜像绕开封锁，无需代理即可取价。
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
