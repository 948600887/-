package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.calculator.MonthlyReturn
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.profitColor
import com.example.stockdiary.utils.toProfitDisplay
import kotlin.math.abs

@Composable
fun MonthlyReturnChart(
    monthlyReturns: List<MonthlyReturn>,
    modifier: Modifier = Modifier
) {
    if (monthlyReturns.isEmpty()) return

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val maxAbs = monthlyReturns.maxOf { maxOf(abs(it.returnAmount), 1.0) }
    val showYear = monthlyReturns.map { it.month.year }.distinct().size > 1

    // 设计规范 4.4：颜色在 @Composable 组体内读出并捕获为局部 val 后再进 draw lambda
    val colors = AppTheme.colors
    val barUp = colors.lossRed
    val barDown = colors.profitGreen
    val selOverlay = colors.onSurface.copy(alpha = 0.3f)
    val axisText = colors.chartAxisText.toArgb()

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                modifier = Modifier.fillMaxSize().pointerInput(monthlyReturns) {
                    detectTapGestures { tapOffset ->
                        val width = size.width
                        val count = monthlyReturns.size
                        val gap = width / count
                        val index = (tapOffset.x / gap).toInt().coerceIn(0, count - 1)
                        selectedIndex = if (selectedIndex == index) null else index
                    }
                }
            ) {
                val width = size.width
                val height = size.height
                val paddingBottom = 48f
                val paddingTop = 8f
                val cHeight = height - paddingTop - paddingBottom
                val barWidth = (width / monthlyReturns.size) * 0.6f
                val gap = width / monthlyReturns.size
                val zeroY = paddingTop + cHeight / 2f

                // 零线（OnSurfaceVariant as Outline 别名已废除，语义 = onSurfaceVariant，勿配成 AppColors.outline）
                drawLine(
                    colors.onSurfaceVariant.copy(alpha = 0.3f), Offset(0f, zeroY), Offset(width, zeroY), 1f
                )

                monthlyReturns.forEachIndexed { index, mr ->
                    val centerX = gap * index + gap / 2f
                    val barHeight = (cHeight / 2f * (abs(mr.returnAmount) / maxAbs).toFloat())
                    val barColor = if (mr.returnAmount >= 0) barUp else barDown

                    val topY = if (mr.returnAmount >= 0) zeroY - barHeight else zeroY
                    val bottomY = if (mr.returnAmount >= 0) zeroY else zeroY + barHeight

                    drawRect(
                        color = if (selectedIndex == index) barColor.copy(alpha = 1f) else barColor.copy(alpha = 0.85f),
                        topLeft = Offset(centerX - barWidth / 2f, topY),
                        size = Size(barWidth, abs(bottomY - topY)),
                    )

                    if (selectedIndex == index) {
                        drawRect(
                            color = selOverlay,
                            topLeft = Offset(centerX - barWidth / 2f, topY),
                            size = Size(barWidth, abs(bottomY - topY))
                        )
                    }

                    // 月份标签
                    val label = if (showYear) {
                        "${mr.month.year % 100}年${mr.month.monthValue}月"
                    } else {
                        "${mr.month.monthValue}月"
                    }
                    val paint = android.graphics.Paint().apply {
                        color = axisText; textSize = 18f; isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        label, centerX, height - 16f, paint
                    )
                }
            }
        }

        selectedIndex?.let { index ->
            if (index in monthlyReturns.indices) {
                val mr = monthlyReturns[index]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${mr.month.year}年${mr.month.monthValue}月",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface
                        )
                        Text(
                            text = "增值: ${mr.returnAmount.toProfitDisplay("¥")}  (${if (mr.returnRate >= 0) "+" else ""}${mr.returnRate.format(2)}%)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = mr.returnAmount.profitColor()
                        )
                    }
                }
            }
        }
    }
}
