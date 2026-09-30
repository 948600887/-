# 金库（New Stock Diary）最终代码审查报告

**审查时间：** 2026-07-23  
**审查范围：** 主工程 `NewStockDiary/app/src/main` 全量代码  
**构建结果：** 通过（`./gradlew clean :app:assembleDebug` BUILD SUCCESSFUL）  
**APK 输出：** `app/build/outputs/apk/debug/app-debug.apk`（约 21.8 MB，生成于 2026/7/23 21:45）

---

## 1. 关键约束项检查结论

| 约束项 | 状态 | 说明 |
|--------|------|------|
| 数据库迁移不捕获异常 | ✅ 已修复 | `StockDatabase.kt` 所有 Migration 均未使用 try/catch，失败会自然上抛 |
| Room DAO 提供分页方法 | ✅ 已修复 | `AssetDao`/`SnapshotDao`/`TransactionDao` 等均已新增 `LIMIT :limit OFFSET :offset` 方法 |
| NetworkClient 共享连接池 | ✅ 已修复 | `NetworkClient.kt` 通过单一 `baseClient` + `newBuilder()` 派生业务客户端，共享 ConnectionPool/Dispatcher |
| WebView 禁用文件/混合内容 | ✅ 已修复 | `DataVisualization.kt` 已设置 `allowFileAccess=false`、`allowContentAccess=false`、`MIXED_CONTENT_NEVER_ALLOW` |
| FileProvider 最小暴露 | ✅ 已修复 | `file_paths.xml` 仅暴露 `cache/exports/`；`DataExporter` 与 `DataImportExportService` 均写入该目录 |
| 强制 HTTPS | ✅ 已修复 | `network_security_config.xml` 设置 `cleartextTrafficPermitted="false"`；`AndroidManifest.xml` 设置 `android:usesCleartextTraffic="false"`；所有网络请求 URL 均为 HTTPS |
| 通知渠道拆分 | ✅ 已修复 | `NotificationHelper.kt` 已拆分为 price_updates / dividend_updates / sync_status / daily_reminder / general 5 个渠道 |
| 云备份关闭 | ✅ 已修复 | `AndroidManifest.xml` 设置 `android:allowBackup="false"`，冗余的 `data_extraction_rules.xml`/`backup_rules.xml` 已删除 |
| Release 启用 R8/ProGuard | ✅ 已修复 | `build.gradle.kts` 中 `isMinifyEnabled=true`、`isShrinkResources=true`，并配置 `proguard-rules.pro`（含 Room/Compose/ViewModel） |
| 全局异常处理器 | ✅ 已修复 | `GlobalExceptionHandler.kt` 记录崩溃后调用系统默认 `UncaughtExceptionHandler`，不掩盖崩溃信号 |
| LazyColumn key | ✅ 已修复 | 所有动态列表（资产、分红记录、设置项等）均已提供稳定 key |
| derivedStateOf 包裹 remember | ✅ 已修复 | 所有 `derivedStateOf` 均位于 `remember { }` 内 |
| 数据导入校验 | ✅ 已修复 | `DataImportExportService.kt` 校验版本、必填字段、枚举、ID 重复、外键引用、负数等 |
| 存储路径规范 | ✅ 已修复 | Android 10+ 使用 `getExternalFilesDir()`（崩溃日志目录），未使用已废弃的 `getExternalStoragePublicDirectory` |

---

## 2. 本轮审查新增发现

经最终静态审查与成功构建验证，**未发现新的阻塞性（Critical）或重要（Important）缺陷**。所有关键约束项与此前识别的风险点均已落地修复。

### 轻微观察（不影响当前合并）

1. **价格同步 Worker 内部快照异常被吞掉**  
   `PriceSyncWorker.createOrReplaceTodaySnapshot()` 内部捕获了 `Exception` 并仅记录日志，即使快照刷新失败，Worker 仍返回成功。当前行为可接受（价格同步已成功，快照失败不致命），但如希望严格联动，可将该异常抛出，由外层 `doWork()` 统一走 `handleFailure()`。

2. **调试网络日志仍为 BASIC 级别**  
   `NetworkClient` 在 `DEBUG` 模式下使用 `HttpLoggingInterceptor.Level.BASIC`，会输出完整 URL 与 Header。虽然仅 Debug 构建生效，但若 Header 中未来携带 API Key，仍存在敏感信息泄露风险。建议后续根据项目约定调整为仅输出截断 URL 的自定义 Interceptor。

---

## 3. 构建与产物验证

```text
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 28s
39 actionable tasks: 39 executed
```

APK 信息：
- 路径：`D:\主人的任务\NewStockDiary\app\build\outputs\apk\debug\app-debug.apk`
- 大小：22,827,081 字节（约 21.8 MB）
- 生成时间：2026/7/23 21:45:16

---

## 4. 结论

代码健康度较审查前有显著提升，关键安全风险、崩溃风险、状态丢失风险与线程安全问题均已解决。当前 Debug APK 可直接安装测试。建议后续按优先级补充：

- 自动化单元测试覆盖价格同步、快照生成、数据导入导出核心路径；
- 在 Android 13+ 真机上验证通知权限流程与后台同步触发；
- 如有发布计划，使用正式签名配置验证 Release 构建与 ProGuard 规则。
