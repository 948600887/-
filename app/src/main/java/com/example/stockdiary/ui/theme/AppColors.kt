package com.example.stockdiary.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全 App 设计令牌（design tokens）。
 *
 * 浅色值 = Color.kt 现值（保证浅色模式视觉零变化）；
 * 深色值见系统设计文档第 2 节（现有 *Dark 值 + 三个 *Bright 微调值）。
 * 页面请统一通过 [AppTheme.colors] 读取，不要直接引用 Color.kt 顶层语义 val。
 */
data class AppColors(
    val isDark: Boolean,
    // 文本
    val onSurface: Color, val onSurfaceVariant: Color, val onBackground: Color,
    val onPrimary: Color, val onError: Color,
    // 表面/容器
    val surface: Color, val surfaceContainerLowest: Color, val surfaceContainerLow: Color,
    val surfaceContainer: Color, val surfaceContainerHigh: Color, val surfaceContainerHighest: Color,
    val background: Color, val primaryContainer: Color, val secondaryContainer: Color,
    val tertiaryContainer: Color, val errorContainer: Color, val warningContainer: Color,
    // 导入预览专用文字/底色令牌：浅色值 = 该页面原硬编码字面量（保证浅色零变化），
    // 不复用 error/success/warning 通用令牌——那些的浅色值与此处原值有差，深色亦需提亮
    val errorText: Color, val successText: Color, val warningText: Color, val errorRowBackground: Color,
    // 描边
    val outline: Color, val outlineVariant: Color,
    // 品牌/强调
    val primary: Color, val primaryGreen: Color, val lossRed: Color, val profitGreen: Color,
    val error: Color, val success: Color, val warning: Color, val info: Color,
    val accentOrange: Color, val accentBlue: Color, val disabled: Color,
    // 图表专用
    val chartAxisText: Color, val chartPointFill: Color,
)

/**
 * 主题令牌提供源。
 *
 * 使用 static：主题切换频率极低、读取点极多，避免逐读追踪开销；
 * 主题切换时由 GoldVaultTheme 整树重供，子树整体重组。
 */
val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** Composable 内唯一读取入口：`AppTheme.colors.xxx`（对齐 MaterialTheme.colorScheme 习惯）。 */
object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}

internal val LightAppColors = AppColors(
    isDark = false,
    onSurface = TextPrimary,            // #1A1A1A
    onSurfaceVariant = TextSecondary,   // #666666
    surface = WhiteSurface,             // #FFFFFF
    surfaceContainerLowest = WhiteBackground,
    surfaceContainerLow = LightGray,
    surfaceContainer = WhiteSurface,
    surfaceContainerHigh = LightGray,
    surfaceContainerHighest = MidGray,
    background = WhiteBackground,
    onBackground = TextPrimary,
    onPrimary = OnPrimary,
    onError = OnError,
    primaryContainer = PrimaryContainer,
    secondaryContainer = SecondaryContainer,
    tertiaryContainer = TertiaryContainer,
    errorContainer = ErrorContainer,
    warningContainer = Color(0xFFFFF59D),
    // 导入预览专用令牌：浅色值逐字等于该页面原硬编码（零变化），见 AppColorsTest 钉死
    errorText = Color(0xFFC62828),
    successText = Color(0xFF2E7D32),
    warningText = Color(0xFFF57F17),
    errorRowBackground = Color(0xFFFFCDD2),
    outline = Outline,
    outlineVariant = OutlineVariant,
    primary = Primary,
    primaryGreen = PrimaryGreen,
    lossRed = LossRed,
    profitGreen = ProfitGreen,
    error = Error,
    success = Success,
    warning = Warning,
    info = Info,
    accentOrange = AccentOrange,
    accentBlue = AccentBlue,
    disabled = Disabled,
    chartAxisText = Color(0xFF888888),
    chartPointFill = WhiteSurface,
)

internal val DarkAppColors = AppColors(
    isDark = true,
    onSurface = OnSurfaceDark,          // #E8E8E8
    onSurfaceVariant = OnSurfaceVariantDark, // #AAAAAA
    surface = SurfaceDark,              // #121212
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    onPrimary = OnPrimaryDark,          // #0E0E0E
    onError = OnPrimaryDark,            // 深色 error 提亮后改深字，对比充足
    primaryContainer = PrimaryContainerDark,
    secondaryContainer = SecondaryContainerDark,
    tertiaryContainer = TertiaryContainerDark,
    errorContainer = ErrorContainerDark,
    warningContainer = Color(0xFF3A2E00),
    // 导入预览专用令牌：深色用 Color.kt 已有亮色变体，保证深底下对比度
    errorText = LossRedBright,
    successText = ProfitGreenBright,
    warningText = WarningBright,
    errorRowBackground = ErrorContainerDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    primary = PrimaryGreenLight,        // #4CAF50 微调提亮
    primaryGreen = PrimaryGreenLight,
    lossRed = LossRedBright,            // #FF8A80
    profitGreen = ProfitGreenBright,    // #81C784
    error = LossRedBright,
    success = ProfitGreenBright,
    warning = WarningBright,            // #FFB74D
    info = TertiaryLight,               // #64B5F6
    accentOrange = AccentOrange,        // 复用 #FF9800
    accentBlue = TertiaryLight,         // 微亮
    disabled = DisabledDark,
    chartAxisText = OnSurfaceVariantDark,
    chartPointFill = SurfaceDark,
)
