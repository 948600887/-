package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.calculator.MonthlyReturn
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.toProfitDisplay
import kotlin.math.abs

private data class MonthlyBarGeometry(
    val topLeft: Offset,
    val size: Size,
    val isPositive: Boolean,
    val centerX: Float
)

private data class MonthlyChartGeometry(
    val width: Float,
    val height: Float,
    val zeroY: Float,
    val bars: List<MonthlyBarGeometry>
)

/** 只在数据或尺寸变化时计算柱体矩形，点按选中时直接复用。 */
private fun buildMonthlyChartGeometry(
    monthlyReturns: List<MonthlyReturn>,
    canvasSize: IntSize,
    maxAbs: Double
): MonthlyChartGeometry? {
    val width = canvasSize.width.toFloat()
    val height = canvasSize.height.toFloat()
    val paddingBottom = 48f
    val paddingTop = 8f
    val chartHeight = height - paddingTop - paddingBottom
    if (monthlyReturns.isEmpty() || width <= 0f || chartHeight <= 0f) return null

    val gap = width / monthlyReturns.size
    val barWidth = gap * 0.6f
    val zeroY = paddingTop + chartHeight / 2f
    val bars = monthlyReturns.mapIndexed { index, monthlyReturn ->
        val centerX = gap * index + gap / 2f
        val barHeight = chartHeight / 2f * (abs(monthlyReturn.returnAmount) / maxAbs).toFloat()
        val isPositive = monthlyReturn.returnAmount >= 0
        val topY = if (isPositive) zeroY - barHeight else zeroY
        val bottomY = if (isPositive) zeroY else zeroY + barHeight
        MonthlyBarGeometry(
            topLeft = Offset(centerX - barWidth / 2f, topY),
            size = Size(barWidth, abs(bottomY - topY)),
            isPositive = isPositive,
            centerX = centerX
        )
    }
    return MonthlyChartGeometry(width = width, height = height, zeroY = zeroY, bars = bars)
}

@Composable
fun MonthlyReturnChart(
    monthlyReturns: List<MonthlyReturn>,
    modifier: Modifier = Modifier
) {
    if (monthlyReturns.isEmpty()) return

    val selectedIndex = remember { mutableStateOf<Int?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 数据聚合只随月度列表变化，选中柱子引发的重组不应再全表 max/distinct。
    val maxAbs = remember(monthlyReturns) {
        monthlyReturns.maxOf { maxOf(abs(it.returnAmount), 1.0) }
    }
    val showYear = remember(monthlyReturns) {
        monthlyReturns.asSequence().map { it.month.year }.distinct().take(2).count() > 1
    }
    val labels = remember(monthlyReturns, showYear) {
        monthlyReturns.map { monthlyReturn ->
            if (showYear) {
                "${monthlyReturn.month.year % 100}年${monthlyReturn.month.monthValue}月"
            } else {
                "${monthlyReturn.month.monthValue}月"
            }
        }
    }

    // 设计规范 4.4：颜色在 @Composable 组体内读出并捕获为局部 val 后再进 draw lambda
    val colors = AppTheme.colors
    val barUp = colors.lossRed
    val barDown = colors.profitGreen
    val selOverlay = colors.onSurface.copy(alpha = 0.3f)
    val zeroLineColor = colors.onSurfaceVariant.copy(alpha = 0.3f)
    val axisText = colors.chartAxisText.toArgb()
    // Paint 提到循环和 draw 之外复用，避免每根柱子都 new 一次。
    val labelPaint = remember(axisText) {
        android.graphics.Paint().apply {
            color = axisText
            textSize = 18f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val geometry = remember(monthlyReturns, canvasSize, maxAbs) {
        buildMonthlyChartGeometry(monthlyReturns, canvasSize, maxAbs)
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 基础柱图不读取 selectedIndex；选中变化只让上面的交互图层失效。
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
            ) {
                val chart = geometry ?: return@Canvas
                drawLine(
                    zeroLineColor,
                    Offset(0f, chart.zeroY),
                    Offset(chart.width, chart.zeroY),
                    1f
                )
                chart.bars.forEachIndexed { index, bar ->
                    val barColor = if (bar.isPositive) barUp else barDown
                    drawRect(
                        color = barColor.copy(alpha = 0.85f),
                        topLeft = bar.topLeft,
                        size = bar.size
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        labels[index],
                        bar.centerX,
                        chart.height - 16f,
                        labelPaint
                    )
                }
            }

            // 点按和高亮单独一层；高亮时不再重画零线、所有柱子和月份标签。
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(geometry?.width, geometry?.bars?.size) {
                        detectTapGestures { tapOffset ->
                            val chart = geometry ?: return@detectTapGestures
                            if (chart.bars.isEmpty()) return@detectTapGestures
                            val gap = chart.width / chart.bars.size
                            val index = (tapOffset.x / gap).toInt().coerceIn(0, chart.bars.lastIndex)
                            selectedIndex.value = if (selectedIndex.value == index) null else index
                        }
                    }
            ) {
                val chart = geometry ?: return@Canvas
                selectedIndex.value?.let { index ->
                    chart.bars.getOrNull(index)?.let { bar ->
                        val barColor = if (bar.isPositive) barUp else barDown
                        // 先补成完全不透明，再覆一层选择色，视觉与原实现一致。
                        drawRect(color = barColor, topLeft = bar.topLeft, size = bar.size)
                        drawRect(color = selOverlay, topLeft = bar.topLeft, size = bar.size)
                    }
                }
            }
        }

        MonthlySelectionDetail(
            monthlyReturns = monthlyReturns,
            selectedIndex = selectedIndex,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

/** 详情文本单独读取选中状态，避免其重组向上传播到基础柱图。 */
@Composable
private fun MonthlySelectionDetail(
    monthlyReturns: List<MonthlyReturn>,
    selectedIndex: State<Int?>,
    modifier: Modifier = Modifier
) {
    // 跨月缺快照时 monthsSpanned > 1，必须说出来，否则用户会把多个月变化当成单月收益。
    val selected = selectedIndex.value
    val detailText = if (selected != null && selected in monthlyReturns.indices) {
        val monthlyReturn = monthlyReturns[selected]
        val sign = if (monthlyReturn.returnRate >= 0) "+" else ""
        buildString {
            append("${monthlyReturn.month.year}年${monthlyReturn.month.monthValue}月")
            append(" · 收益率 $sign${monthlyReturn.returnRate.format(2)}%")
            append(" · 金额 ${monthlyReturn.returnAmount.toProfitDisplay("¥")}")
            if (monthlyReturn.monthsSpanned > 1) append("（含 ${monthlyReturn.monthsSpanned} 个月）")
        }
    } else {
        "点按柱子看详情"
    }
    Text(
        text = detailText,
        fontSize = 11.sp,
        color = AppTheme.colors.onSurfaceVariant,
        modifier = modifier
    )
}
