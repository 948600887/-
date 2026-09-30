# 金库 / NewStockDiary 全面检查报告

生成时间：2026-07-23  
检查范围：全部源码、资源、清单文件、Lint 静态检查、Debug 编译与 APK 打包

---

## 1. 检查结论

| 项目 | 结果 |
|------|------|
| Lint（Debug） | ✅ 通过，0 errors |
| 编译 | ✅ 通过 |
| Debug APK 打包 | ✅ 通过 |
| 关键安全项 | ✅ 已落实 |
| 代码规范项 | ✅ 已修复 |

---

## 2. 本次修复的 Lint 警告与问题

### 2.1 Composable 命名规范
- **文件**：`GlobalExceptionHandler.kt`
- **问题**：`safeLet` 被标记为 `@Composable` 但以小写字母开头，违反 ComposableNaming。
- **修复**：移除不必要的 `@Composable` 注解（该函数未使用任何 Compose API）。

### 2.2 Modifier 参数顺序
- **文件**：`AssetDetailScreen.kt`、`DashboardScreen.kt`、`PortfolioScreen.kt`、`DataVisualization.kt`、`CalendarCard.kt`
- **问题**：`modifier: Modifier = Modifier` 不是第一个可选参数。
- **修复**：将 `modifier` 移至第一个可选参数位置，所有调用处均使用命名参数，无需额外修改。

### 2.3 冗余 SDK 版本判断
- **文件**：`NotificationHelper.kt`
- **问题**：`minSdk = 26`，多处仍判断 `>= O`（26）与 `>= LOLLIPOP`（21）。
- **修复**：删除冗余判断，简化通道创建与闹钟设置逻辑。

### 2.4 精确闹钟权限误报
- **文件**：`NotificationHelper.kt`、`AndroidManifest.xml`
- **问题**：Lint 将 `SCHEDULE_EXACT_ALARM` 误标为 ProtectedPermissions；`setAlarmClock`/`setExact` 报 MissingPermission。
- **修复**：
  - 在 `AndroidManifest.xml` 中为该权限声明添加 `tools:ignore="ProtectedPermissions"`。
  - 在调用精确闹钟 API 处添加 `@Suppress("MissingPermission")`，并保留运行时 `canScheduleExactAlarms()` 检查。

### 2.5 静态 Context 泄漏警告
- **文件**：`AssetRepository.kt`、`DataExporter.kt`、`DataImportExportService.kt`、`DividendRepository.kt`、`LocalDataSource.kt`、`StockDataManager.kt`
- **问题**：单例持有 `Context` 字段被 Lint 报 StaticFieldLeak。
- **修复**：确认所有单例均使用 `context.applicationContext`，并添加 `@SuppressLint("StaticFieldLeak")` 与注释说明。

### 2.6 云备份与数据传输安全
- **文件**：`AndroidManifest.xml`、`res/xml/data_extraction_rules.xml`、`res/xml/full_backup_content.xml`
- **问题**：`android:allowBackup="false"` 在 Android 12+ 仅影响云备份，不覆盖设备间传输；且 minSdk 26 需要 `fullBackupContent` 才能在 Android 8-11 生效。
- **修复**：
  - 新增 `data_extraction_rules.xml`，对 `cloud-backup` 与 `device-transfer` 均排除所有域。
  - 新增 `full_backup_content.xml`，对所有域排除备份。
  - 在 `AndroidManifest.xml` 同时声明 `android:fullBackupContent` 与 `android:dataExtractionRules`。

### 2.7 冗余 mipmap 版本限定符
- **文件**：`res/mipmap-anydpi-v26/`
- **问题**：minSdk 为 26，`-v26` 限定符无意义。
- **修复**：将 `ic_launcher.xml` 移至 `res/mipmap-anydpi/`，删除 `mipmap-anydpi-v26` 目录。

### 2.8 未使用资源
- **文件**：`res/values/colors.xml`
- **问题**：`R.color.white` 未被使用。
- **修复**：清空 `colors.xml` 中的未使用颜色。

---

## 3. 剩余未修复的 Lint 警告（经评估可接受）

| 警告 | 位置 | 说明 | 处理建议 |
|------|------|------|----------|
| OldTargetApi | `build.gradle.kts:16` | `targetSdk = 34` | 升级到 targetSdk 35 需全面回归测试，建议作为独立迭代处理。 |
| GradleDependency | `build.gradle.kts:99` | `compose.foundation:1.6.0` | 非安全或功能问题；升级需验证 Compose 兼容性。 |
| KaptUsageInsteadOfKsp | `build.gradle.kts:86` | Room 使用 kapt | KSP 迁移可提升构建性能，但涉及构建脚本改造，建议独立迭代。 |

---

## 4. 关键安全与质量项复核

| 检查项 | 状态 | 依据 |
|--------|------|------|
| 仅 HTTPS 明文流量 | ✅ | `android:usesCleartextTraffic="false"` + `network_security_config.xml` |
| 文件分享最小暴露 | ✅ | `file_paths.xml` 仅暴露 `cache/exports` |
| 云备份/传输关闭 | ✅ | `allowBackup="false"` + `fullBackupContent` + `dataExtraction_rules` |
| 通知渠道拆分 | ✅ | 5 个独立渠道 |
| Room 迁移异常透传 | ✅ | 迁移代码未捕获异常 |
| 单例共享 OkHttp | ✅ | `NetworkClient` 单例 |
| WebView 安全加固 | ✅ | 禁用 file/content access、禁止混合内容 |
| 通知权限运行时申请 | ✅ | `SettingsScreen` + `SettingsViewModel` |
| 自动价格刷新失败阈值 | ✅ | `PriceSyncWorker` 连续 3 次失败通知 |
| 当日快照事务替换 | ✅ | `AssetRepository.replaceSnapshotForDate` 使用 `withTransaction` |

---

## 5. 构建产物

- **APK 路径**：`D:\主人的任务\NewStockDiary\app\build\outputs\apk\debug\app-debug.apk`
- **APK 大小**：约 23.3 MB（24,436,422 字节）
- **生成时间**：2026-07-23 23:15:25
- **Lint 报告**：`D:\主人的任务\NewStockDiary\app\build\reports\lint-results-debug.html`

---

## 6. 建议后续事项

1. **targetSdk 升级**：在独立迭代中升级到 35，并针对权限、通知、窗口行为做回归测试。
2. **KSP 迁移**：将 Room 的 kapt 替换为 KSP，缩短构建时间。
3. **Compose 版本升级**：评估 `compose.foundation` 升级到最新稳定版，验证输入与手势兼容性。
4. **自动化测试**：为核心流程（价格同步、快照生成、数据导入导出、通知权限流）补充单元/集成测试。
