# APK 构建说明

## 环境要求

1. **Android Studio** 最新版
2. **JDK 17+**
3. **Android SDK** (API 34 或更高)

## 构建步骤

### 方法 1: Android Studio 图形界面

1. 打开 Android Studio
2. 选择 "Open an existing project"
3. 选择 `D:\主人的任务\NewStockDiary` 文件夹
4. 等待 Gradle 同步完成
5. 点击菜单: `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
6. APK 生成位置: `app/build/outputs/apk/debug/app-debug.apk`

### 方法 2: 命令行构建

```bash
# 进入项目目录
cd "D:\主人的任务\NewStockDiary"

# 清理并构建 Debug APK
./gradlew clean assembleDebug

# 或使用离线模式（如果已下载依赖）
./gradlew assembleDebug --offline
```

### 方法 3: 如果遇到 Kotlin 守护进程问题

```bash
# 停止所有 Gradle 守护进程
./gradlew --stop

# 清理 Gradle 缓存
rm -rf ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin

# 使用 --no-daemon 模式
./gradlew assembleDebug --no-daemon
```

## 生成的文件位置

```
NewStockDiary/
└── app/
    └── build/
        └── outputs/
            └── apk/
                └── debug/
                    └── app-debug.apk  ← 这是安装文件
```

## 安装 APK

1. 将 `app-debug.apk` 复制到手机
2. 在手机上打开并安装
3. 如果提示 "未知来源"，需要在设置中允许安装

## 故障排除

### 问题 1: Kotlin daemon 连接失败
```
java.nio.file.AccessDeniedException
```
解决: 删除 `C:\Users\林\AppData\Local\kotlin` 文件夹，然后重试

### 问题 2: Android SDK 平台缺失
```
Failed to stat file 'android.jar': No such file or directory
```
解决: 在 Android Studio 中安装 SDK Platform 36，或在 build.gradle.kts 中改为 `compileSdk = 35`

### 问题 3: Gradle 版本问题
解决: 使用项目自带的 Gradle Wrapper

## 后续优化建议

构建成功后，可以考虑：
- 签名发布版本 (release)
- 混淆代码 (ProGuard/R8)
- 添加单元测试覆盖
