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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.formatDateLabel
import com.example.stockdiary.utils.formatYAxisLabel
import com.example.stockdiary.utils.profitColor
import com.example.stockdiary.utils.toLocalDate
import com.example.stockdiary.utils.toProfitDisplay
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** 折线图绘图区内边距（px）。手势映射与绘制必须共用，否则点按位置和画出来的点对不上 */
private const val TREND_PADDING_LEFT = 40f
private const val TREND_PADDING_RIGHT = 20f
private const val TREND_PADDING_TOP = 20f
private const val TREND_PADDING_BOTTOM = 64f
/** X 轴日期标签之间的最小间距（px），放不下就省略中间标签 */
private const val TREND_LABEL_GAP = 8f

/**
 * 按时间戳等距排布折线点的 x 坐标。
 *
 * 为什么不能按下标等距：快照有缺口时（fillMissingSnapshots 只补今天一条，不逐日回填），
 * 缺 20 天和连续 20 天按下标画出来一样宽，用户会误以为期间一直有数据。
 * 这里把 [最早时刻, 最晚时刻] 线性映射到 [left, left + width]。
 *
 * 所有快照同一时刻（同一天多份）时时间跨度为 0，退化为按下标等距，避免除零。
 */
internal fun trendXPositions(timestamps: List<Long>, left: Float, width: Float): List<Float> {
    if (timestamps.isEmpty()) return emptyList()
    if (timestamps.size == 1) return listOf(left)
    val minTs = timestamps.min()
    val maxTs = timestamps.max()
    val span = (maxTs - minTs).toFloat()
    if (span <= 0f) {
        return List(timestamps.size) { index -> left + width * index / (timestamps.size - 1) }
    }
    return timestamps.map { ts -> left + width * ((ts - minTs).toFloat() / span) }
}

/**
 * 给定 x 坐标返回最近的数据点下标；[positions] 为空时返回 -1（调用方需判）。
 * 两个点距离相等时取下标较小者（先到先得）。
 */
internal fun nearestPointIndex(x: Float, positions: List<Float>): Int {
    if (positions.isEmpty()) return -1
    var bestIndex = 0
    var bestDistance = abs(x - positions[0])
    for (index in 1 until positions.size) {
        val distance = abs(x - positions[index])
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

/**
 * 时间跨度中点对应的快照下标，用于在首尾日期标签之间补一个中间参照。
 * 快照数 < 3 时补了也和首尾重复，返回 null。中点用 min + (max-min)/2 防溢出。
 */
internal fun midTimestampIndex(timestamps: List<Long>): Int? {
    if (timestamps.size < 3) return null
    val minTs = timestamps.min()
    val maxTs = timestamps.max()
    val midTs = minTs + (maxTs - minTs) / 2
    var bestIndex = 0
    var bestDistance = Long.MAX_VALUE
    timestamps.forEachIndexed { index, ts ->
        val distance = abs(ts - midTs)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

@Composable
fun AssetTrendChart(
    snapshots: List<AssetSnapshot>,
    modifier: Modifier = Modifier
) {
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    val currentSnapshots by rememberUpdatedState(snapshots)

    // 设计规范 4.4：颜色在 @Composable 组体内读出并捕获为局部 val 后再进 draw lambda
    val colors = AppTheme.colors
    val lineColor = colors.primary
    val gridColor = colors.outline.copy(alpha = 0.2f)
    val areaColor = colors.primary.copy(alpha = 0.12f)
    val pointFill = colors.chartPointFill
    val axisText = colors.chartAxisText.toArgb()

    if (snapshots.isEmpty()) return

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(snapshots) {
                    detectTapGestures { tapOffset ->
                        val snaps = currentSnapshots
                        if (snaps.size < 2) return@detectTapGestures
                        val positions = trendXPositions(
                            timestamps = snaps.map { it.timestamp },
                            left = TREND_PADDING_LEFT,
                            width = size.width - TREND_PADDING_LEFT - TREND_PADDING_RIGHT
                        )
                        val index = nearestPointIndex(tapOffset.x, positions)
                        if (index >= 0) {
                            selectedPointIndex = if (selectedPointIndex == index) null else index
                        }
                    }
                }
                .pointerInput(snapshots) {
                    detectHorizontalDragGestures(
                        onDragEnd = {},
                        onDragCancel = {},
                        onHorizontalDrag = { _, dragAmount ->
                            val snaps = currentSnapshots
                            if (snaps.size < 2) return@detectHorizontalDragGestures
                            val positions = trendXPositions(
                                timestamps = snaps.map { it.timestamp },
                                left = TREND_PADDING_LEFT,
                                width = size.width - TREND_PADDING_LEFT - TREND_PADDING_RIGHT
                            )
                            // 拖动 = 沿时间轴平移手指位置，再吸附到最近的点；
                            // 缺口处点更稀，拖同样的像素数会自然跨过更多数据
                            val currentIdx = selectedPointIndex ?: 0
                            val anchorX = positions.getOrNull(currentIdx) ?: positions.first()
                            val newIdx = nearestPointIndex(anchorX + dragAmount, positions)
                            if (newIdx >= 0) {
                                selectedPointIndex = newIdx
                            }
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height
            val paddingTop = TREND_PADDING_TOP
            val paddingBottom = TREND_PADDING_BOTTOM
            val paddingLeft = TREND_PADDING_LEFT
            val paddingRight = TREND_PADDING_RIGHT

            val cWidth = width - paddingLeft - paddingRight
            val cHeight = height - paddingTop - paddingBottom

            val minValue = snapshots.minOf { it.netAssets }
            val maxValue = snapshots.maxOf { it.netAssets }
            val valueRange = if (maxValue - minValue > 0) maxValue - minValue else 1.0

            // x 按时间戳排布：缺口在横向上被拉开，不再伪装成连续数据
            val positions = trendXPositions(
                timestamps = snapshots.map { it.timestamp },
                left = paddingLeft,
                width = cWidth
            )
            val points = snapshots.mapIndexed { index, snapshot ->
                val x = positions[index]
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
                        color = axisText; textSize = 22f; isAntiAlias = true
                    }
                )
            }

            // X轴日期标签：首尾保留，中间按时间中点补一个做参照；三者放不下就只画首尾
            if (snapshots.isNotEmpty()) {
                val firstDate = snapshots.first().date.formatDateLabel()
                val lastDate = snapshots.last().date.formatDateLabel()
                val paint = android.graphics.Paint().apply {
                    color = axisText; textSize = 22f; isAntiAlias = true
                }
                val labelY = paddingTop + cHeight + 24f
                drawContext.canvas.nativeCanvas.drawText(firstDate, paddingLeft, labelY, paint)
                val lastX = width - paddingRight - paint.measureText(lastDate)
                drawContext.canvas.nativeCanvas.drawText(lastDate, lastX, labelY, paint)

                val timestamps = snapshots.map { it.timestamp }
                val midIndex = midTimestampIndex(timestamps)
                if (midIndex != null && midIndex != 0 && midIndex != snapshots.lastIndex) {
                    val midDate = snapshots[midIndex].date.formatDateLabel()
                    val midWidth = paint.measureText(midDate)
                    val midX = points[midIndex].x - midWidth / 2f
                    val minMidX = paddingLeft + paint.measureText(firstDate) + TREND_LABEL_GAP
                    val maxMidX = lastX - TREND_LABEL_GAP - midWidth
                    if (midX in minMidX..maxMidX) {
                        drawContext.canvas.nativeCanvas.drawText(midDate, midX, labelY, paint)
                    }
                }
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
                        colors = listOf(areaColor, lineColor.copy(alpha = 0.0f)),
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
                    drawLine(colors.onSurfaceVariant.copy(alpha = 0.4f), Offset(point.x, paddingTop), Offset(point.x, paddingTop + cHeight), 1f)
                    drawLine(colors.onSurfaceVariant.copy(alpha = 0.4f), Offset(paddingLeft, point.y), Offset(width - paddingRight, point.y), 1f)
                    drawCircle(color = pointFill, radius = 6f, center = point)
                    drawCircle(color = lineColor, radius = 6f, center = point, style = Stroke(width = 2f))
                }
            }
        }

        // 选中的数据卡片
        val idx = selectedPointIndex
        if (idx != null && idx in snapshots.indices) {
            val snapshot = snapshots[idx]
            // 资产增值口径：与上一期快照比净资产的增量，不再展示基于成本的盈亏
            val previous = if (idx > 0) snapshots[idx - 1] else null
            val delta = previous?.let { snapshot.netAssets - it.netAssets }
            // 时间跨度：中间隔了 N 天，这个「增减额」就是 N 天的变化，必须说明白，
            // 否则用户会把跨缺口增量当成单日增量
            val spanText = previous?.let { prevSnapshot ->
                val prevDate = prevSnapshot.date.toLocalDate()
                val currDate = snapshot.date.toLocalDate()
                if (prevDate != null && currDate != null) {
                    val days = ChronoUnit.DAYS.between(prevDate, currDate).toInt()
                    if (days > 1) "较 ${prevSnapshot.date} · 共 $days 天" else "较 ${prevSnapshot.date}"
                } else null
            }
            Card(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(snapshot.date, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                        if (spanText != null) {
                            Text(
                                spanText,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Text(
                            "总资产: ¥${snapshot.totalAssets.format(2)}",
                            fontSize = 11.sp, color = colors.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("¥${snapshot.netAssets.format(2)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        Text(
                            delta?.toProfitDisplay("¥") ?: "起始",
                            fontSize = 12.sp, color = delta?.profitColor() ?: colors.onSurface
                        )
                    }
                }
            }
        }
    }
}
