# 新股日记 App 代码架构文档

## 📁 项目结构

```
com.example.stockdiary/
├── MainActivity.kt                 # 应用入口
│
├── navigation/
│   └── Screen.kt                   # 导航路由定义
│
├── database/                       # 📦 数据库层
│   ├── StockDatabase.kt           # Room 数据库主类
│   ├── DatabaseBuilder.kt         # 数据库单例构造器
│   ├── Converters.kt               # 类型转换器
│   ├── Asset.kt                    # 资产实体 (Entity)
│   ├── AssetDao.kt                 # 资产数据访问对象
│   ├── AssetType.kt                # 资产类型枚举
│   ├── AssetSnapshot.kt            # 资产快照实体
│   ├── SnapshotDao.kt              # 快照数据访问对象
│   ├── Portfolio.kt                # 投资组合实体
│   ├── PortfolioDao.kt             # 组合数据访问对象
│   ├── Dividend.kt                 # 分红实体
│   ├── DividendDao.kt              # 分红数据访问对象
│   ├── Transaction.kt              # 交易记录实体
│   ├── TransactionDao.kt           # 交易数据访问对象
│   ├── StockBasic.kt               # 股票基本信息实体
│   └── StockBasicDao.kt            # 股票基本信息访问对象
│
├── data/                           # 📊 数据层
│   ├── AssetRepository.kt          # 资产数据仓库 (新版)
│   ├── AssetSyncManager.kt         # 资产同步管理器
│   ├── DashboardPreferencesManager.kt # 仪表盘偏好管理
│   ├── SnapshotScheduler.kt        # 快照调度器
│   ├── SnapshotWorker.kt           # 快照后台任务
│   ├── DataExporter.kt             # 数据导出器
│   ├── AssetRepository.kt          # 资产数据仓库
│   ├── DataManager.kt              # 数据管理器 (旧版，全局状态)
│   ├── StockDataManager.kt         # 股票数据管理器
│   ├── ApiManager.kt               # API 管理器
│   ├── StockAPIManager.kt         # 股票 API 管理
│   ├── StockData.kt               # 股票数据模型
│   ├── StockBasicInfo.kt          # 股票基本信息
│   ├── ApiError.kt                # API 错误模型
│   # --- 分红相关 ---
│   ├── DividendApi.kt             # 分红 API 接口
│   ├── DividendApiServiceBase.kt  # 分红 API 服务基类
│   ├── DividendService.kt         # 分红服务
│   ├── DividendRepository.kt      # 分红数据仓库
│   ├── DividendCacheManager.kt    # 分红缓存管理
│   ├── DividendData.kt             # 分红数据模型
│   ├── DividendYieldCalculator.kt # 分红收益率计算器
│   ├── EastmoneyDividendApiService.kt # 东方财富分红 API
│   ├── SinaDividendApiService.kt  # 新浪分红 API
│   └── TencentDividendApiService.kt  # 腾讯分红 API
│
├── network/                       # 🌐 网络层
│   ├── SinaStockAPI.kt           # 新浪股票 API
│   ├── FundAPI.kt                # 天天基金 API
│   └── BinanceAPI.kt              # 币安虚拟货币 API
│
├── ui/                            # 🎨 UI 层
│   ├── screens/                  # 页面/屏幕
│   │   ├── DashboardScreen.kt    # 资产仪表盘 (新功能)
│   │   ├── SettingsScreen.kt     # 设置页面
│   │   ├── HistoryScreen.kt      # 历史曲线图
│   │   ├── AddAssetScreen.kt     # 添加资产
│   │   ├── PortfolioScreen.kt    # 投资组合
│   │   ├── MarketScreen.kt       # 行情页面
│   │   ├── StockDetailScreen.kt  # 股票详情
│   │   ├── DividendScreen.kt     # 分红页面
│   │   ├── CalendarScreen.kt     # 日历页面
│   │   ├── ReportScreen.kt       # 报告页面
│   │   └── ProfileScreen.kt      # 个人资料
│   ├── dialogs/                  # 对话框
│   │   ├── AddAssetDialog.kt     # 添加资产对话框
│   │   ├── AddStockDialog.kt     # 添加股票对话框
│   │   ├── StockDetailEditDialog.kt # 股票详情编辑
│   │   └── StockDetailTradeDialog.kt # 股票交易对话框
│   ├── components/               # 可复用组件
│   │   ├── SwipeableAssetCard.kt # 可滑动资产卡片
│   │   ├── KLineChart.kt         # K 线图组件
│   │   ├── DataVisualization.kt  # 数据可视化组件
│   │   ├── ErrorDialog.kt        # 错误对话框
│   │   └── ErrorHandlerComposable.kt # 错误处理组件
│   ├── navigation/
│   │   └── StockDiaryNavHost.kt  # 导航主机
│   └── theme/                    # 主题相关
│       ├── Theme.kt              # 主题定义
│       ├── Color.kt              # 颜色定义
│       └── Typography.kt         # 字体样式
│
├── utils/                        # 🛠️ 工具类
│   ├── Extensions.kt             # 扩展函数
│   ├── ErrorHandler.kt           # 错误处理器
│   ├── ErrorState.kt             # 错误状态
│   ├── LogManager.kt             # 日志管理器
│   ├── NotificationHelper.kt     # 通知助手
│   ├── PerformanceMonitor.kt     # 性能监控
│   └── StockCodeManager.kt       # 股票代码管理
│
└── common/                       # 🔧 通用组件
    ├── Constants.kt              # 基础常量
    ├── APIConfig.kt             # API 配置（端点、超时、缓存）
    ├── AppConstants.kt          # 应用配置（主题、快照、通知）
    ├── ErrorMessages.kt         # 错误消息和代码定义
    ├── Result.kt                # Result 类型封装
    └── components/
        ├── ErrorMessage.kt        # 错误消息组件
        └── LoadingIndicator.kt   # 加载指示器
```

## 🔑 核心架构模式

### 1. MVVM 架构
```
View (Screen) → ViewModel → Model (Repository) → Data Source (DAO/API)
```

### 2. Repository 模式
```
Screen → Repository → DAO (本地) / API (网络)
```

### 3. 单例模式
```kotlin
// 数据库单例
StockDatabase.getInstance(context)

// 主题管理
ThemeManager.getInstance(context)

// 数据管理器
DataManager.getInstance(context)
```

## 📋 快速修改指南

### 添加新的资产类型
1. 编辑 `database/AssetType.kt` 添加新枚举
2. 在 `data/AssetRepository.kt` 添加同步逻辑（如需 API）
3. 在 `ui/screens/DashboardScreen.kt` 添加显示逻辑

### 添加新的 API 数据源
1. 在 `network/` 目录创建新的 API 类
2. 在 `data/AssetSyncManager.kt` 添加同步调用
3. 测试 API 响应和错误处理

### 修改主题颜色
1. 编辑 `ui/theme/Color.kt`
2. 编辑 `ui/theme/Theme.kt` 中的颜色映射

### 添加新的页面
1. 在 `navigation/Screen.kt` 添加路由
2. 在 `ui/screens/` 创建新 Screen
3. 在 `StockDiaryNavHost.kt` 添加导航逻辑
4. 在底部导航栏添加入口

## 🔗 依赖关系图

```
MainActivity
    └── StockDiaryNavHost
            ├── DashboardScreen ──── AssetRepository
            │                         ├── AssetSyncManager
            │                         └── DashboardPreferencesManager
            ├── SettingsScreen ───── SnapshotScheduler
            ├── HistoryScreen ────── SnapshotDao
            ├── PortfolioScreen ──── DataManager
            │                         └── StockAPIManager
            ├── MarketScreen ──────── DataManager
            ├── StockDetailScreen ─── AssetDetailViewModel
            ├── DividendScreen ────── DividendService
            │                         ├── DividendRepository
            │                         └── EastmoneyDividendApiService
            └── [其他Screen] ─────── DataManager

DataManager (全局状态)
    ├── StockAPIManager ──────── SinaStockAPI
    └── DividendService ──────── 各种 DividendApiService
```

## ⚙️ 关键配置文件

| 配置项 | 文件位置 |
|--------|----------|
| 数据库名称 | `database/StockDatabase.kt` |
| 主题模式 | `ui/theme/ThemeManager.kt` |
| 快照频率 | `data/SnapshotScheduler.kt` |
| API 端点 | `network/*.kt` |
| 应用图标 | `res/mipmap-*` |
| 权限配置 | `AndroidManifest.xml` |

## 🐛 调试技巧

### 查看日志
```kotlin
LogManager.d("tag", "message")
```

### 性能监控
```kotlin
PerformanceMonitor.start("operation")
// ... 执行操作
PerformanceMonitor.end("operation")
```

### 错误追踪
```kotlin
ErrorHandler.handleException(e, context)
```

## 📝 命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| 屏幕 | `XxxScreen` | `DashboardScreen` |
| 对话框 | `XxxDialog` | `AddAssetDialog` |
| 数据模型 | `XxxData` | `StockData` |
| 实体类 | `Xxx` | `Asset`, `Portfolio` |
| 管理器 | `XxxManager` | `DataManager` |
| API | `XxxAPI` | `SinaStockAPI` |
| Repository | `XxxRepository` | `AssetRepository` |

## 🚀 下一步建议

1. **统一数据层**: 将 `DataManager` 逐步迁移到 `Repository` 模式
2. **添加单元测试**: 为核心逻辑添加测试用例
3. **使用 Hilt**: 引入依赖注入框架
4. **添加日志框架**: 如 Timber 替代 LogManager

---

*最后更新: 2026-05-10*
