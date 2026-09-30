package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.ui.theme.OnSurface
import com.example.stockdiary.ui.theme.OnSurfaceVariant
import com.example.stockdiary.ui.theme.Outline
import com.example.stockdiary.ui.theme.Primary
import com.example.stockdiary.ui.theme.SurfaceContainerLow
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.formatDateLabel
import com.example.stockdiary.utils.formatYAxisLabel
import com.example.stockdiary.utils.profitColor
import com.example.stockdiary.utils.toProfitDisplay
import kotlin.math.roundToInt

@Composable
fun AssetTrendChart(
    snapshots: List<AssetSnapshot>,
    modifier: Modifier = Modifier
) {
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    val currentSnapshots by rememberUpdatedState(snapshots)

    val lineColor = Primary
    val gridColor = Outline.copy(alpha = 0.2f)
    val areaColor = Primary.copy(alpha = 0.12f)

    if (snapshots.isEmpty()) return

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(snapshots) {
                    detectTapGestures { tapOffset ->
                        val w = size.width
                        val padding = 40f
                        val snaps = currentSnapshots
                        if (snaps.size < 2) return@detectTapGestures
                        val index = ((tapOffset.x - padding) / (w - 2 * padding) * (snaps.size - 1))
                            .roundToInt().coerceIn(0, snaps.size - 1)
                        selectedPointIndex = if (selectedPointIndex == index) null else index
                    }
                }
                .pointerInput(snapshots) {
                    detectHorizontalDragGestures(
                        onDragEnd = {},
                        onDragCancel = {},
                        onHorizontalDrag = { _, dragAmount ->
                            val w = size.width
                            val padding = 40f
                            val snaps = currentSnapshots
                            if (snaps.size < 2) return@detectHorizontalDragGestures
                            val step = (w - 2 * padding) / (snaps.size - 1)
                            val currentIdx = selectedPointIndex ?: 0
                            val newIdx = (currentIdx + (dragAmount / step).roundToInt())
                                .coerceIn(0, snaps.size - 1)
                            selectedPointIndex = newIdx
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height
            val paddingTop = 20f
            val paddingBottom = 64f
            val paddingLeft = 40f
            val paddingRight = 20f

            val cWidth = width - paddingLeft - paddingRight
            val cHeight = height - paddingTop - paddingBottom

            val minValue = snapshots.minOf { it.netAssets }
            val maxValue = snapshots.maxOf { it.netAssets }
            val valueRange = if (maxValue - minValue > 0) maxValue - minValue else 1.0

            val points = snapshots.mapIndexed { index, snapshot ->
                val x = paddingLeft + cWidth * index / (snapshots.size - 1).coerceAtLeast(1)
                val y = (paddingTop + cHeight - ((snapshot.netAssets - minValue) / valueRange * cHeight)).toFloat()
                Offset(x, y)
            }

            // Y轴网格线
            val gridCount = 4
            val stepY = cHeight / gridCount
            for (i in 0..gridCount) {
                val yPos = paddingTop + i * stepY
                drawLine(gridColor, Offset(paddingLeft, yPos), Offset(width - paddingRight, yPos), 1f)
                val yValue = maxValue - (i * valueRange / gridCount)
                drawContext.canvas.nativeCanvas.drawText(
                    yValue.formatYAxisLabel(), 2f, yPos + 4f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY; textSize = 22f; isAntiAlias = true
                    }
                )
            }

            // X轴日期标签
            if (snapshots.isNotEmpty()) {
                val firstDate = snapshots.first().date.formatDateLabel()
                val lastDate = snapshots.last().date.formatDateLabel()
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY; textSize = 22f; isAntiAlias = true
                }
                val labelY = paddingTop + cHeight + 24f
                drawContext.canvas.nativeCanvas.drawText(firstDate, paddingLeft, labelY, paint)
                drawContext.canvas.nativeCanvas.drawText(
                    lastDate, width - paddingRight - paint.measureText(lastDate), labelY, paint
                )
            }

            // 渐变填充区域
            if (points.size >= 2) {
                val fillPath = Path().apply {
                    moveTo(points.first().x, paddingTop + cHeight)
                    for (point in points) lineTo(point.x, point.y)
                    lineTo(points.last().x, paddingTop + cHeight)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(areaColor, Primary.copy(alpha = 0.0f)),
                        startY = paddingTop, endY = paddingTop + cHeight
                    )
                )

                // 折线
                val linePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(path = linePath, color = lineColor, style = Stroke(width = 2.5f))
            }

            // 十字线
            selectedPointIndex?.let { index ->
                if (index in points.indices) {
                    val point = points[index]
                    drawLine(OnSurfaceVariant.copy(alpha = 0.4f), Offset(point.x, paddingTop), Offset(point.x, paddingTop + cHeight), 1f)
                    drawLine(OnSurfaceVariant.copy(alpha = 0.4f), Offset(paddingLeft, point.y), Offset(width - paddingRight, point.y), 1f)
                    drawCircle(color = Color.White, radius = 6f, center = point)
                    drawCircle(color = lineColor, radius = 6f, center = point, style = Stroke(width = 2f))
                }
            }
        }

        // 选中的数据卡片
        val idx = selectedPointIndex
        if (idx != null && idx in snapshots.indices) {
            val snapshot = snapshots[idx]
            Card(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(snapshot.date, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurface)
                        Text(
                            "收益: ${snapshot.totalProfit.toProfitDisplay("¥")}",
                            fontSize = 11.sp, color = snapshot.totalProfit.profitColor()
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("¥${snapshot.netAssets.format(2)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                        Text(
                            "${if (snapshot.yieldRate >= 0) "+" else ""}${snapshot.yieldRate.format(2)}%",
                            fontSize = 12.sp, color = snapshot.yieldRate.profitColor()
                        )
                    }
                }
            }
        }
    }
}
