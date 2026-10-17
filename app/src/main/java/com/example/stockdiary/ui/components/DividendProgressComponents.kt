package com.example.stockdiary.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.dividend.DividendProgressCalculator
import com.example.stockdiary.ui.theme.OnSurface
import com.example.stockdiary.ui.theme.OnSurfaceVariant
import com.example.stockdiary.ui.theme.Outline
import com.example.stockdiary.ui.theme.Primary
import com.example.stockdiary.ui.theme.SurfaceContainer
import com.example.stockdiary.utils.format
import kotlin.math.ceil

/**
 * 今年收息进度：已收 / 全年预计。
 *
 * 这是同类股息 App 的头号卖点 —— 用户打开 App 第一个想知道的就是
 * 「今年已经到手多少、全年大概能有多少」。进度条给出「还差多少」的直观感受。
 */
@Composable
fun DividendProgressCard(
    progress: DividendProgressCalculator.Progress,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("今年收息进度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (progress.receivedCount > 0) {
                    Text(
                        "已到账 ${progress.receivedCount} 笔",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (!progress.hasData) {
                Text(
                    "暂无分红数据，导入持仓并同步分红后可看到今年的收息进度",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text("今年已收", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                    Text(
                        "¥${progress.yearReceived.format(2)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("全年预计", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                    Text(
                        "¥${progress.yearProjected.format(2)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = OnSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (progress.yearProjected > 0) {
                LinearProgressIndicator(
                    progress = { progress.ratio.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Primary,
                    trackColor = Outline.copy(alpha = 0.25f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (progress.remaining > 0.005) {
                        "已完成 ${(progress.ratio * 100).format(1)}%，还剩 ¥${progress.remaining.format(2)}"
                    } else {
                        "全年预计已全部到账，超额 ¥${(progress.yearReceived - progress.yearProjected).format(2)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
            } else {
                Text(
                    "预计数据暂缺，已收金额按实际到账记录统计",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
            }
        }
    }
}

/**
 * 下一笔分红倒计时。
 * 把分红说成「下一笔什么时候到」比年化数字更有体感。
 */
@Composable
fun NextPayoutCard(
    next: DividendProgressCalculator.NextPayout,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("下一笔分红", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    next.assetName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    "${next.displayDate}${if (next.isEstimated) "（按历史同期推算）" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
                Text(
                    if (next.payDate != null) "到账日" else "除息日（数据源未提供到账日）",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${next.daysUntil}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )
                Text(
                    if (next.payDate != null) "天后到账" else "天后除息",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariant
                )
                if (next.amount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "约 ¥${next.amount.format(2)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = OnSurface
                    )
                }
            }
        }
    }
}

/**
 * 12 个月现金流柱图：看出一年里钱从哪几个月进来。
 * 深色 = 已到账，浅色 = 已排定未到账。没有记录的月份就是空着，不编造。
 */
@Composable
fun MonthlyCashFlowCard(
    progress: DividendProgressCalculator.Progress,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("全年收息节奏", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "按今年已排定的派息记录统计，没有记录的月份留空",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            MonthlyCashFlowChart(
                monthly = progress.monthly,
                modifier = Modifier.fillMaxWidth().height(130.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LegendItem(color = Primary, label = "已到账")
                LegendItem(color = Primary.copy(alpha = 0.35f), label = "待到账")
            }
        }
    }
}

/**
 * 柱子之间的间隙。
 *
 * **柱子（Canvas）和下方月份标签（Row）必须共用同一个值**，并且用同一套布局算法：
 * 每格宽度 =（总宽 − 间隙×11）÷ 12，文字在格内居中。
 * 之前标签用 `Arrangement.SpaceBetween`，它是「按文字实际宽度均分剩余空间」——
 * 而 "1"~"9" 与 "10"~"12" 字宽不同，格心就不等距，和柱子的等距格心对不上，
 * 看起来就是「柱子和月份没对齐」。
 */
private val CHART_BAR_GAP = 4.dp

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.width(10.dp).height(10.dp)) {
            drawRect(color = color, size = Size(size.width, size.height))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = OnSurfaceVariant)
    }
}

@Composable
private fun MonthlyCashFlowChart(
    monthly: List<DividendProgressCalculator.MonthCash>,
    modifier: Modifier = Modifier
) {
    val maxValue = monthly.maxOf { it.received + it.scheduled }
    val currentMonth = java.time.LocalDate.now().monthValue

    Box(modifier = modifier) {
        if (maxValue <= 0) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("今年还没有派息记录", fontSize = 12.sp, color = OnSurfaceVariant)
            }
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val count = monthly.size
            // 与下方月份标签共用同一个间隙值、同一套分格算法，否则两者会错位
            val gap = CHART_BAR_GAP.toPx()
            val barWidth = (size.width - gap * (count - 1)) / count
            // 顶部留一点余量，柱子顶到边会显得挤
            val usableHeight = size.height - 14.dp.toPx()
            val scale = if (maxValue > 0) (usableHeight / maxValue.toFloat()) else 0f

            monthly.forEachIndexed { index, month ->
                val left = index * (barWidth + gap)
                val total = month.received + month.scheduled
                if (total <= 0) return@forEachIndexed

                val totalHeight = (total * scale).toFloat()
                val receivedHeight = (month.received * scale).toFloat()

                // 已排定未到账：整根浅色
                drawRect(
                    color = Primary.copy(alpha = 0.35f),
                    topLeft = Offset(left, size.height - totalHeight),
                    size = Size(barWidth, totalHeight)
                )
                // 已到账：底部深色覆盖
                if (receivedHeight > 0) {
                    drawRect(
                        color = Primary,
                        topLeft = Offset(left, size.height - receivedHeight),
                        size = Size(barWidth, receivedHeight)
                    )
                }
            }
        }

        // 每格 weight(1f) + 固定 4.dp 间隙 = 与柱子完全相同的分格：
        // 格宽 (总宽 − 间隙×11) ÷ 12，文字在格内居中，格心与柱子中心重合。
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
        ) {
            monthly.forEachIndexed { index, month ->
                if (index > 0) Spacer(modifier = Modifier.width(CHART_BAR_GAP))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${month.month}",
                        fontSize = 10.sp,
                        color = if (month.month == currentMonth) Primary else OnSurfaceVariant,
                        fontWeight = if (month.month == currentMonth) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
