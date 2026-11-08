package com.example.stockdiary.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 盈亏色（红涨绿跌，A 股惯例）：>=0 红、<0 绿。
 *
 * 放在 ui.theme 是因为它依赖 [AppTheme.colors]：此前定义在 utils/Extensions.kt，
 * 让一个通用工具包反向 import 了 UI 层主题（utils → ui），破坏分层方向。
 * 依赖主题的展示逻辑本就属于 UI 层，调用点也都在 @Composable 内，迁到这里即可。
 */
@Composable
fun Double.profitColor(): Color =
    if (this >= 0) AppTheme.colors.lossRed else AppTheme.colors.profitGreen
