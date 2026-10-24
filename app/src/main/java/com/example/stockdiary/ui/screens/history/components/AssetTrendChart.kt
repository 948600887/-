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
import kotlin.math.ceil

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
 * X 轴日期标签之间的额外留白（px，除标签自身宽度外）。
 * 只有「放得下」判定用得到：标签密到影响阅读时不出，而不是排到刚好不重叠。
 */
private const val TREND_LABEL_SPACING = 16f
/** X 轴日期标签的挑选预算上限：再宽的屏也不多画（同花顺 6 个月只标 4 个月初） */
internal const val MAX_X_LABELS = 6
/** 稀疏化步长档位（天）：先小后大，取第一个能让标签数落进预算的 */
private val DAY_STEP_LADDER = listOf(1L, 2L, 3L, 7L, 14L)

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
 * 为 X 轴日期标签挑选该画哪些快照的下标。**同花顺规则：按可见跨度选自然日历步长**，
 * 不是「能塞下多少就塞多少」——6 个月只标 4 个月初，一个月每周一个，绝不排满。
 *
 * 每份快照的日期都尽量标在**它自己的点上**：缺口在哪用户直接看得出来
 * （与跨缺口虚线段互相印证）。首末下标永远保留（最起码知道起止）。
 *
 * 稀疏档位（按首末快照的日期跨度）：
 * - 跨度 ≤ 14 天：步长 1/2/3 天（一周视图逐日，全部画在各自的点上）
 * - 跨度 ≤ 62 天：步长 7/14 天（一周一个 / 双周一个）
 * - 更长：步长 N 个自然月，**锚定每月 1 号**（N 随跨度与屏宽涨，半年/一年/全部依次变疏）
 *
 * [labelWidths] 与 [positions] 同序同长；最后仍有一道像素级防重叠兜底（窄屏 /
 * 标签异常宽时再削），保证物理上不叠字。
 */
internal fun trendXLabelIndices(
    dates: List<java.time.LocalDate?>,
    positions: List<Float>,
    labelWidths: List<Float>,
    minGap: Float
): List<Int> {
    if (positions.isEmpty()) return emptyList()
    if (positions.size == 1) return listOf(0)
    val n = positions.size

    // 放得下就全画：一周 8 个点、稀疏缺口都走这条（不掺日历步长）
    val widest = labelWidths.maxOrNull() ?: 0f
    val availableWidth = (positions.last() - positions.first()).coerceAtLeast(0f)
    val fitsAll = n <= ((availableWidth + minGap) / (widest + TREND_LABEL_SPACING)).toInt()
    if (fitsAll) return (0 until n).toList()

    val first = dates.firstOrNull { it != null }
    val last = dates.lastOrNull { it != null }
    if (first == null || last == null) {
        // 日期全是脏数据：退化成等距稀疏，保住「有刻度、不叠字」
        return thinEvenly(n)
    }
    val spanDays = ChronoUnit.DAYS.between(first, last)
    // 首末各占一个名额，剩下的是步长挑选的预算；屏越窄预算越小
    val budget = (((availableWidth + minGap) / (widest + TREND_LABEL_SPACING)).toInt())
        .coerceIn(2, MAX_X_LABELS) - 2

    val ticks: List<java.time.LocalDate> = if (spanDays <= 62) {
        val step = DAY_STEP_LADDER.firstOrNull { spanDays / it <= budget } ?: DAY_STEP_LADDER.last()
        generateSequence(first) { it.plusDays(step) }.takeWhile { !it.isAfter(last) }.toList()
    } else {
        // 月口径：锚定每月 1 号，步长按月数随跨度涨（半年起每 2 个月、一年起每 3 个月…）
        val monthStep = maxOf(1, ceil(spanDays / 30.44 / budget.coerceAtLeast(1)).toInt())
        val anchor = first.withDayOfMonth(1)
        generateSequence(anchor) { it.plusMonths(monthStep.toLong()) }
            .takeWhile { !it.isAfter(last) }
            .toList()
    }

    // 每个 tick 取「日期不早于它的第一个快照」；tick 递增，cursor 单调前进即可
    val kept = sortedSetOf(0, n - 1)
    var cursor = 0
    for (tick in ticks) {
        while (cursor < n && dates[cursor]?.isBefore(tick) == true) cursor++
        if (cursor < n) kept.add(cursor)
    }
    return kept.sorted().filterWithOverlapGuard(positions, labelWidths, minGap)
}

/** 日期全脏时的退化稀疏：等距挑，首末保留 */
private fun thinEvenly(count: Int): List<Int> {
    if (count <= 2) return (0 until count).toList()
    val step = ceil(count / (MAX_X_LABELS - 2.0)).toInt().coerceAtLeast(1)
    return (0 until count).filter { it % step == 0 || it == count - 1 }
}

/**
 * 像素级防重叠兜底：与上一个已画标签间隔不足 [minGap] 就跳过中间的。
 * 首末下标永远保留（最起码知道起止）。internal 供单测直接钉边界。
 */
internal fun List<Int>.filterWithOverlapGuard(
    positions: List<Float>,
    labelWidths: List<Float>,
    minGap: Float
): List<Int> {
    val kept = mutableListOf<Int>()
    var lastEnd = Float.NEGATIVE_INFINITY
    for (index in this) {
        val isEdge = index == first() || index == last()
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
        dates = dates,
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
