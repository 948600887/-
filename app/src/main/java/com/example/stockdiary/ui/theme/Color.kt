package com.example.stockdiary.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 主题定义源（设计令牌的浅色基值）。
 *
 * 这些是主题令牌的浅色基值，深色变体见 AppColors（ui/theme/AppColors.kt 的 DarkAppColors）；
 * 页面请改用 AppTheme.colors.xxx（Composable 内唯一读取入口），不要直接引用本文件的语义 val。
 * 本文件 val 保留的原因：被 LightAppColors / DarkAppColors / Light(Dark)ColorScheme 引用，
 * 以及 StockCodeManager.CodeCategory.color 等非 Composable 上下文仍会使用。
 */

val WhiteSurface = Color(0xFFFFFFFF)
val WhiteBackground = Color(0xFFF5F5F5)
val LightGray = Color(0xFFF0F0F0)
val MidGray = Color(0xFFE0E0E0)
val DarkGray = Color(0xFF9E9E9E)
val TextPrimary = Color(0xFF1A1A1A)
val TextSecondary = Color(0xFF666666)

val BlackSurface = Color(0xFF121212)
val BlackBackground = Color(0xFF0A0A0A)
val DarkCard = Color(0xFF1E1E1E)
val DarkBorder = Color(0xFF2A2A2A)
val DarkTextPrimary = Color(0xFFEAEAEA)
val DarkTextSecondary = Color(0xFFAAAAAA)

val PrimaryGreen = Color(0xFF2E7D32)
val PrimaryGreenLight = Color(0xFF4CAF50)
val PrimaryGreenDark = Color(0xFF1B5E20)
val PrimaryGreenContainer = Color(0xFFE8F5E9)
val PrimaryGreenContainerDark = Color(0xFF1A2E1A)

val AccentGreen = Color(0xFF66BB6A)
val AccentGreenLight = Color(0xFFC8E6C9)
val AccentGreenDark = Color(0xFF388E3C)

val ProfitGreen = Color(0xFF43A047)
val LossRed = Color(0xFFE53935)

val AccentOrange = Color(0xFFFF9800)
val AccentBlue = Color(0xFF1976D2)

// 深色模式微调强调色（无 *Dark 变体的共享强调色提亮，供 DarkAppColors / DarkColorScheme 共用）。
val LossRedBright = Color(0xFFFF8A80)
val ProfitGreenBright = Color(0xFF81C784)
val WarningBright = Color(0xFFFFB74D)

val DividerLight = Color(0xFFE0E0E0)
val DividerDark = Color(0xFF333333)
val BorderLight = Color(0xFFEEEEEE)
val BorderDark = Color(0xFF2A2A2A)

val Primary = PrimaryGreen
val PrimaryLight = PrimaryGreenLight
val PrimaryDark = PrimaryGreenDark
val PrimaryContainer = PrimaryGreenContainer
val PrimaryContainerDark = PrimaryGreenContainerDark

val Secondary = AccentGreen
val SecondaryLight = AccentGreenLight
val SecondaryDark = AccentGreenDark
val SecondaryContainer = Color(0x1A4CAF50)
val SecondaryContainerDark = Color(0xFF1A1E1A)

val Tertiary = AccentBlue
val TertiaryLight = Color(0xFF64B5F6)
val TertiaryDark = Color(0xFF1565C0)
val TertiaryContainer = Color(0x1A1976D2)
val TertiaryContainerDark = Color(0xFF1A1E20)

val Accent = ProfitGreen
val AccentLight = ProfitGreen
val AccentDark = LossRed

val Error = LossRed
val ErrorLight = LossRed
val ErrorDark = Color(0xFFC62828)
val ErrorContainer = Color(0xFFFFEBEE)
val ErrorContainerDark = Color(0xFF1E1212)

val Surface = WhiteSurface
val SurfaceDark = Color(0xFF121212)
val SurfaceContainerLowest = WhiteBackground
val SurfaceContainerLowestDark = Color(0xFF0E0E0E)
val SurfaceContainerLow = LightGray
val SurfaceContainerLowDark = Color(0xFF181818)
val SurfaceContainer = WhiteSurface
val SurfaceContainerDark = Color(0xFF1E1E1E)
val SurfaceContainerHigh = LightGray
val SurfaceContainerHighDark = Color(0xFF242424)
val SurfaceContainerHighest = MidGray
val SurfaceContainerHighestDark = Color(0xFF2A2A2A)

val Background = WhiteBackground
val BackgroundDark = Color(0xFF0E0E0E)

val OnPrimary = Color(0xFFFFFFFF)
val OnPrimaryDark = Color(0xFF0E0E0E)
val OnSecondary = Color(0xFF0A0A0A)
val OnSecondaryDark = Color(0xFF0E0E0E)
val OnBackground = TextPrimary
val OnBackgroundDark = Color(0xFFE8E8E8)
val OnSurface = TextPrimary
val OnSurfaceDark = Color(0xFFE8E8E8)
val OnSurfaceVariant = TextSecondary
val OnSurfaceVariantDark = Color(0xFFAAAAAA)
val OnError = Color(0xFFFFFFFF)
val OnErrorDark = Color(0xFFFFFFFF)

val Outline = BorderLight
val OutlineDark = Color(0xFF333333)
val OutlineVariant = DividerLight
val OutlineVariantDark = Color(0xFF2A2A2A)

val InverseSurface = TextPrimary
val InverseSurfaceDark = Color(0xFFE8E8E8)
val InverseOnSurface = WhiteSurface
val InverseOnSurfaceDark = Color(0xFF121212)
val InversePrimary = PrimaryGreenLight
val InversePrimaryDark = PrimaryGreen

val Scrim = Color(0xFF000000)
val ScrimDark = Color(0xE6000000)

val Gray = DarkGray
val GrayLight = MidGray
val GrayDark = DarkGray

val Success = ProfitGreen
val SuccessDark = ProfitGreen
val Warning = AccentOrange
val WarningDark = Color(0xFFF57C00)
val Info = AccentBlue
val InfoDark = Color(0xFF1565C0)
val Disabled = Color(0xFFBDBDBD)
val DisabledDark = Color(0xFF424242)

val GlassSurface = Color(0x40FFFFFF)
val GlassBorder = Color(0x202E7D32)
val GlowEffect = Color(0x204CAF50)
