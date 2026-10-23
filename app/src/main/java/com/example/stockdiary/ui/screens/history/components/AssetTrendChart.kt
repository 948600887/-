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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
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

/**
 * 折线图绘图区内边距（px）。手势映射与绘制必须共用，否则点按位置和画出来的点对不上。
 * 左侧 88px：Y 轴标签在数据范围极窄时会退到原值显示（最长 7 位数字约 84px），
 * 按最宽情况留，避免标签压到绘图区
 */
private const val TREND_PADDING_LEFT = 88f
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

/**
 * 快照的「日期时刻」：date 字段当天的纪元日 × 86400000ms（无时区、无 DST、无时刻抖动）。
 *
 * X 轴必须按它排布，不能按创建时刻（timestamp）：用户看到的刻度是**日期**，间距就得与
 * **日期差**成正比。用创建时刻的话，「15 号 23:50 打开 App 建的快照」和「19 号 09:00
 * 建的」之间只隔 3.4 天而不是 4 天——同样差 1 天的两段（如 17→18 与 18→19）会画出
 * 不同宽度，用户直接读成「比例不对」。同一天多份快照已在 ViewModel 按日期去重，
 * 不会塌到同一个 x；日期脏数据退回创建时刻。
 */
internal fun List<AssetSnapshot>.dayTimestamps(): List<Long> =
    map { snapshot ->
        snapshot.date.toLocalDate()?.toEpochDay()?.times(86_400_000L) ?: snapshot.timestamp
    }

private data class TrendLabelCache(
    val texts: List<String>,
    val widths: List<Float>
)

private data class TrendGridLine(
    val y: Float,
    val label: String
)

private data class TrendSegment(
    val path: Path,
    val crossesMissingDays: Boolean
)

private data class TrendChartGeometry(
    val width: Float,
    val paddingTop: Float,
    val paddingBottomY: Float,
    val paddingLeft: Float,
    val paddingRightX: Float,
    val positions: List<Float>,
    val points: List<Offset>,
    val dates: List<java.time.LocalDate?>,
    val gridLines: List<TrendGridLine>,
    val labelY: Float,
    val labelIndices: List<Int>,
    val fillPath: Path?,
    val segments: List<TrendSegment>
)

/**
 * 把只随快照和画布尺寸变化的计算集中到 draw 之外。
 * 拖动选中点时这份几何不会重建，主图层只执行 drawLine/drawPath/drawText。
 */
private fun buildTrendChartGeometry(
    snapshots: List<AssetSnapshot>,
    canvasSize: IntSize,
    labelWidths: List<Float>
): TrendChartGeometry? {
    val width = canvasSize.width.toFloat()
    val height = canvasSize.height.toFloat()
    val chartWidth = width - TREND_PADDING_LEFT - TREND_PADDING_RIGHT
    val chartHeight = height - TREND_PADDING_TOP - TREND_PADDING_BOTTOM
    if (snapshots.isEmpty() || chartWidth <= 0f || chartHeight <= 0f) return null

    val minValue = snapshots.minOf { it.netAssets }
    val maxValue = snapshots.maxOf { it.netAssets }
    val valueRange = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val dates = snapshots.map { it.date.toLocalDate() }
    val dayTimestamps = snapshots.mapIndexed { index, snapshot ->
        dates[index]?.toEpochDay()?.times(86_400_000L) ?: snapshot.timestamp
    }
    val positions = trendXPositions(
        timestamps = dayTimestamps,
        left = TREND_PADDING_LEFT,
        width = chartWidth
    )
    val points = snapshots.mapIndexed { index, snapshot ->
        val y = TREND_PADDING_TOP + chartHeight -
            ((snapshot.netAssets - minValue) / valueRange * chartHeight).toFloat()
        Offset(positions[index], y)
    }

    val gridCount = 4
    val stepY = chartHeight / gridCount
    val yStep = valueRange / gridCount
    val gridLines = (0..gridCount).map { index ->
        TrendGridLine(
            y = TREND_PADDING_TOP + index * stepY,
            label = (maxValue - index * valueRange / gridCount).formatYAxisLabel(step = yStep)
        )
    }
    val labelIndices = trendXLabelIndices(
        positions = positions,
        labelWidths = labelWidths,
        minGap = TREND_LABEL_GAP
    )

    val fillPath = if (points.size >= 2) {
        Path().apply {
            moveTo(points.first().x, TREND_PADDING_TOP + chartHeight)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, TREND_PADDING_TOP + chartHeight)
            close()
        }
    } else null
    val segments = if (points.size >= 2) {
        (1 until points.size).map { index ->
            val previousDate = dates[index - 1]
            val currentDate = dates[index]
            val gapDays = if (previousDate != null && currentDate != null) {
                ChronoUnit.DAYS.between(previousDate, currentDate)
            } else 1L
            TrendSegment(
                path = Path().apply {
                    moveTo(points[index - 1].x, points[index - 1].y)
                    lineTo(points[index].x, points[index].y)
                },
                crossesMissingDays = gapDays > 1L
            )
        }
    } else emptyList()

    return TrendChartGeometry(
        width = width,
        paddingTop = TREND_PADDING_TOP,
        paddingBottomY = TREND_PADDING_TOP + chartHeight,
        paddingLeft = TREND_PADDING_LEFT,
        paddingRightX = width - TREND_PADDING_RIGHT,
        positions = positions,
        points = points,
        dates = dates,
        gridLines = gridLines,
        labelY = TREND_PADDING_TOP + chartHeight + 24f,
        labelIndices = labelIndices,
        fillPath = fillPath,
        segments = segments
    )
}

@Composable
fun AssetTrendChart(
    snapshots: List<AssetSnapshot>,
    modifier: Modifier = Modifier
) {
    val selectedPointIndex = remember { mutableStateOf<Int?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 设计规范 4.4：颜色在 @Composable 组体内读出并捕获为局部 val 后再进 draw lambda
    val colors = AppTheme.colors
    val lineColor = colors.primary
    val gridColor = colors.outline.copy(alpha = 0.2f)
    val areaColor = colors.primary.copy(alpha = 0.12f)
    val pointFill = colors.chartPointFill
    val crosshairColor = colors.onSurfaceVariant.copy(alpha = 0.4f)
    val axisText = colors.chartAxisText.toArgb()

    if (snapshots.isEmpty()) return

    // Paint 和日期格式化/测量都不应在 draw 或循环中反复分配；文本只在快照变化时重算。
    val axisPaint = remember(axisText) {
        android.graphics.Paint().apply {
            color = axisText
            textSize = 22f
            isAntiAlias = true
        }
    }
    val labelCache = remember(snapshots) {
        val texts = snapshots.map { it.date.formatDateLabel() }
        TrendLabelCache(texts = texts, widths = texts.map(axisPaint::measureText))
    }
    val geometry = remember(snapshots, canvasSize, labelCache.widths) {
        buildTrendChartGeometry(snapshots, canvasSize, labelCache.widths)
    }
    val areaBrush = remember(areaColor, lineColor, geometry) {
        geometry?.let {
            Brush.verticalGradient(
                colors = listOf(areaColor, lineColor.copy(alpha = 0f)),
                startY = it.paddingTop,
                endY = it.paddingBottomY
            )
        }
    }
    val solidStroke = remember { Stroke(width = 2.5f) }
    val dashedStroke = remember {
        Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f))
    }

    Box(modifier = modifier) {
        // 主图层不读取 selectedPointIndex：拖动时只重绘下面的选中态图层，避免整图重跑。
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
        ) {
            val chart = geometry ?: return@Canvas

            chart.gridLines.forEach { gridLine ->
                drawLine(
                    gridColor,
                    Offset(chart.paddingLeft, gridLine.y),
                    Offset(chart.paddingRightX, gridLine.y),
                    1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    gridLine.label,
                    2f,
                    gridLine.y + 4f,
                    axisPaint
                )
            }

            // 标签文本、宽度和需要显示的下标均已缓存，draw 期不再整表 format/measure。
            chart.labelIndices.forEach { index ->
                val labelX = when (index) {
                    0 -> chart.paddingLeft
                    snapshots.lastIndex -> chart.paddingRightX - labelCache.widths[index]
                    else -> chart.points[index].x - labelCache.widths[index] / 2f
                }
                drawContext.canvas.nativeCanvas.drawText(
                    labelCache.texts[index],
                    labelX,
                    chart.labelY,
                    axisPaint
                )
            }

            chart.fillPath?.let { path ->
                areaBrush?.let { brush -> drawPath(path = path, brush = brush) }
            }
            // 日期解析、缺口判断和 Path 构建都在 geometry 中完成；这里仅按实/虚线绘制。
            chart.segments.forEach { segment ->
                drawPath(
                    path = segment.path,
                    color = lineColor,
                    style = if (segment.crossesMissingDays) dashedStroke else solidStroke
                )
            }
        }

        // 选中态独立图层同时承接手势；状态变化不会让主 draw 块依赖失效。
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(geometry?.positions) {
                    detectTapGestures { tapOffset ->
                        val positions = geometry?.positions.orEmpty()
                        if (positions.size < 2) return@detectTapGestures
                        val index = nearestPointIndex(tapOffset.x, positions)
                        if (index >= 0) {
                            selectedPointIndex.value = if (selectedPointIndex.value == index) null else index
                        }
                    }
                }
                .pointerInput(geometry?.positions) {
                    detectHorizontalDragGestures(
                        onDragEnd = {},
                        onDragCancel = {},
                        onHorizontalDrag = { _, dragAmount ->
                            val positions = geometry?.positions.orEmpty()
                            if (positions.size < 2) return@detectHorizontalDragGestures
                            // 拖动仍按原交互从当前吸附点平移，但位置表不再每个手势事件重算。
                            val currentIndex = selectedPointIndex.value ?: 0
                            val anchorX = positions.getOrNull(currentIndex) ?: positions.first()
                            val newIndex = nearestPointIndex(anchorX + dragAmount, positions)
                            if (newIndex >= 0) selectedPointIndex.value = newIndex
                        }
                    )
                }
        ) {
            val chart = geometry ?: return@Canvas
            selectedPointIndex.value?.let { index ->
                if (index in chart.points.indices) {
                    val point = chart.points[index]
                    drawLine(
                        crosshairColor,
                        Offset(point.x, chart.paddingTop),
                        Offset(point.x, chart.paddingBottomY),
                        1f
                    )
                    drawLine(
                        crosshairColor,
                        Offset(chart.paddingLeft, point.y),
                        Offset(chart.paddingRightX, point.y),
                        1f
                    )
                    drawCircle(color = pointFill, radius = 6f, center = point)
                    drawCircle(color = lineColor, radius = 6f, center = point, style = Stroke(width = 2f))
                }
            }
        }

        TrendSelectionCard(
            selectedPointIndex = selectedPointIndex,
            snapshots = snapshots,
            geometry = geometry,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
        )
    }
}

/** 详情卡在自己的重组作用域读取选中状态，避免状态变化连带重组主 Canvas。 */
@Composable
private fun TrendSelectionCard(
    selectedPointIndex: State<Int?>,
    snapshots: List<AssetSnapshot>,
    geometry: TrendChartGeometry?,
    modifier: Modifier = Modifier
) {
    val idx = selectedPointIndex.value
    if (idx == null || idx !in snapshots.indices) return

    val colors = AppTheme.colors
    val snapshot = snapshots[idx]
    // 资产增值口径：与上一期快照比净资产的增量，不再展示基于成本的盈亏
    val previous = if (idx > 0) snapshots[idx - 1] else null
    val delta = previous?.let { snapshot.netAssets - it.netAssets }
    // 时间跨度：复用几何缓存中的日期解析结果，避免每次拖动再次 toLocalDate。
    val spanText = previous?.let { prevSnapshot ->
        val previousDate = geometry?.dates?.getOrNull(idx - 1)
        val currentDate = geometry?.dates?.getOrNull(idx)
        if (previousDate != null && currentDate != null) {
            val days = ChronoUnit.DAYS.between(previousDate, currentDate).toInt()
            if (days > 1) "较 ${prevSnapshot.date} · 共 $days 天" else "较 ${prevSnapshot.date}"
        } else null
    }
    Card(
        modifier = modifier,
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
