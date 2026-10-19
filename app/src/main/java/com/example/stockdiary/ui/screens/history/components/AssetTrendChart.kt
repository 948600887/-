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
import androidx.compose.ui.graphics.PathEffect
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
 * 为 X 轴日期标签挑选该画哪些快照的下标。
 *
 * 每份快照的日期都尽量标在**它自己的点上**：快照稀疏时全部可见——用户能直接看出
 * 哪些天有数据、缺口在哪（与跨缺口虚线段互相印证）；标签会互相重叠时跳过中间的、
 * 保留上一个，首末下标永远保留（最起码知道起止）。[labelWidths] 与 [positions] 同序同长。
 */
internal fun trendXLabelIndices(
    positions: List<Float>,
    labelWidths: List<Float>,
    minGap: Float
): List<Int> {
    if (positions.isEmpty()) return emptyList()
    val kept = mutableListOf<Int>()
    var lastEnd = Float.NEGATIVE_INFINITY
    for (index in positions.indices) {
        val isEdge = index == 0 || index == positions.lastIndex
        val start = positions[index] - labelWidths[index] / 2f
        val end = positions[index] + labelWidths[index] / 2f
        // 首末必须画；中间的与上一个已画标签间隔不足 minGap 就跳过
        if (isEdge || start - lastEnd >= minGap) {
            kept.add(index)
            if (end > lastEnd) lastEnd = end
        }
    }
    return kept
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

            // X轴日期标签：**每份快照的日期都标在它自己的点上**，与上一个标签放不下
            // （间隔不足 TREND_LABEL_GAP）才跳过，首末永远保留。快照稀疏时全部可见——
            // 用户能直接看出哪些天有数据、缺口在哪（与虚线段互相印证）；密集时自动稀疏化。
            // 曾经只标首/中/末三个，中间的点在哪天全靠猜，缺口被读成「间距画错了」
            if (snapshots.isNotEmpty()) {
                val paint = android.graphics.Paint().apply {
                    color = axisText; textSize = 22f; isAntiAlias = true
                }
                val labelY = paddingTop + cHeight + 24f
                val labelWidths = snapshots.map { paint.measureText(it.date.formatDateLabel()) }
                val labelIndices = trendXLabelIndices(
                    positions = positions,
                    labelWidths = labelWidths,
                    minGap = TREND_LABEL_GAP
                )
                labelIndices.forEach { index ->
                    val label = snapshots[index].date.formatDateLabel()
                    // 首个左对齐到绘图区左沿、末个右对齐到右沿，其余居中于各自的点
                    val labelX = when (index) {
                        0 -> paddingLeft
                        snapshots.lastIndex -> width - paddingRight - labelWidths[index]
                        else -> points[index].x - labelWidths[index] / 2f
                    }
                    drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, paint)
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

                // 折线按段画：相邻快照日期连续（相差 1 天）画实线；跨缺口
                // （相差 >1 天，中间没有快照）画虚线。X 轴按真实日期排布，缺数据的
                // 日子横向占位，所以不同日期的间隔不一样——虚线就是把这件事画出来：
                // 这一段是跨无数据期的连线，不是逐日实测
                for (i in 1 until points.size) {
                    val prevDate = snapshots[i - 1].date.toLocalDate()
                    val currDate = snapshots[i].date.toLocalDate()
                    val gapDays = if (prevDate != null && currDate != null) {
                        ChronoUnit.DAYS.between(prevDate, currDate)
                    } else 1L
                    val segPath = Path().apply {
                        moveTo(points[i - 1].x, points[i - 1].y)
                        lineTo(points[i].x, points[i].y)
                    }
                    drawPath(
                        path = segPath,
                        color = lineColor,
                        style = Stroke(
                            width = 2.5f,
                            pathEffect = if (gapDays > 1L) PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f) else null
                        )
                    )
                }
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
                        // 不要再在这里显示 totalAssets（总资产，含负债）：本页所有卡片
                        // （资产总览/期间增值/风险指标）与本图画的都是净资产 netAssets，
                        // 混进一个含负债的总资产，数值可能差数倍，用户会以为图本身画错了
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
