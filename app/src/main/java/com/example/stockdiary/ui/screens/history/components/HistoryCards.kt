package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.calculator.AssetGrowthCalculator
import com.example.stockdiary.data.calculator.MonthlyReturn
import com.example.stockdiary.data.calculator.SnapshotStats
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.formatDateLabel
import com.example.stockdiary.utils.profitColor
import com.example.stockdiary.utils.toProfitDisplay

private val periodOptions = listOf(
    "1W" to "1周", "1M" to "1月", "3M" to "3月",
    "6M" to "6月", "1Y" to "1年", "ALL" to "全部"
)

/**
 * 周期选择器。
 *
 * 必须是普通 @Composable 而非 LazyListScope 扩展：历史页在「当前周期内没有快照」时走空态分支，
 * 选择器若只挂在 LazyColumn 里就会被一起隐藏，导致用户无法切回「全部」——形成死路。
 */
@Composable
fun HistoryPeriodSelector(
    selectedPeriod: String,
    onPeriodSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        periodOptions.forEach { (key, label) ->
            val isSelected = selectedPeriod == key
            Surface(
                modifier = Modifier.clickable { onPeriodSelected(key) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.surfaceContainerLow,
                contentColor = if (isSelected) AppTheme.colors.onPrimary else AppTheme.colors.onSurfaceVariant
            ) {
                Text(
                    text = label, fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * 历史页小节卡片的统一骨架。
 *
 * 原先「资产总览 / 期间增值 / 风险指标 / 月度增值 / 资产分布」5 张卡各抄一遍
 * Card + SurfaceContainerLow + 12.dp 圆角 + 16.dp 内边距，改成一处定义后，
 * 以后调卡片样式只动这里。
 *
 * @param titleGap 标题与内容之间的间距：大数字卡片用 4.dp，图表/指标网格用 12.dp
 * @param spacerBefore 小节之间的 12.dp 留白；列表第一张卡不需要
 */
@Composable
private fun SectionCard(
    title: String,
    titleGap: Dp = 12.dp,
    spacerBefore: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (spacerBefore) {
        Spacer(modifier = Modifier.height(12.dp))
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceContainerLow),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 12.sp, color = AppTheme.colors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(titleGap))
            content()
        }
    }
}

fun LazyListScope.historyOverviewCard(
    latest: AssetSnapshot,
    snapshots: List<AssetSnapshot>
) {
    // 「资产增值」口径：净资产相对历史基准的增加额，与资产页用同一个计算器，
    // 基准取「本月 1 号之前」/「今年 1 月 1 号之前」的最后一条快照。
    val growth = AssetGrowthCalculator.fromSnapshots(
        snapshots = snapshots,
        currentNetAssets = latest.netAssets,
        today = latest.date
    )
    val monthDelta = growth.monthDelta
    val yearDelta = growth.yearDelta

    item {
        SectionCard(title = "资产总览", titleGap = 4.dp, spacerBefore = false) {
            Text(
                text = "¥${latest.netAssets.format(2)}",
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OverviewMetric(
                    "本月新增",
                    monthDelta?.toProfitDisplay("¥") ?: "—",
                    monthDelta?.profitColor() ?: AppTheme.colors.onSurface
                )
                OverviewMetric(
                    "今年新增",
                    yearDelta?.toProfitDisplay("¥") ?: "—",
                    yearDelta?.profitColor() ?: AppTheme.colors.onSurface
                )
            }
        }
    }
}

fun LazyListScope.historyTrendChart(
    snapshots: List<AssetSnapshot>,
    selectedPeriod: String,
    height: Dp
) {
    item {
        Spacer(modifier = Modifier.height(12.dp))
        Crossfade(
            targetState = selectedPeriod,
            label = "historyTrendChart"
        ) { period ->
            key(period) {
                AssetTrendChart(
                    snapshots = snapshots,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}

fun LazyListScope.historyPeriodReturnCard(
    earliest: AssetSnapshot,
    latest: AssetSnapshot
) {
    val periodChange = latest.netAssets - earliest.netAssets
    // 基期净资产 ≤ 0（期间从「一无所有」或脏数据开始）时增幅数学上无定义：
    // 显示 "+0.00%" 会误导——看着像零增长，实际可能是一整段新增。与本月/今年
    // 新增的「—」口径保持一致
    val periodYield = if (earliest.netAssets > 0) (periodChange / earliest.netAssets) * 100 else null
    val firstLabel = earliest.date.formatDateLabel()
    val lastLabel = latest.date.formatDateLabel()
    val periodLabel = if (firstLabel == lastLabel) firstLabel else "$firstLabel ~ $lastLabel"

    item {
        SectionCard(title = "期间增值", titleGap = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = periodChange.toProfitDisplay("¥"),
                        fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = periodChange.profitColor()
                    )
                    Text("净资产增加", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = periodYield?.let { "${if (it >= 0) "+" else ""}${it.format(2)}%" } ?: "—",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = periodYield?.profitColor() ?: AppTheme.colors.onSurfaceVariant
                    )
                    Text("增幅", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(periodLabel, fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
        }
    }
}

fun LazyListScope.historyRiskCard(stats: SnapshotStats) {
    item {
        SectionCard(title = "风险指标") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 最大回撤是「下跌」的度量，按国内惯例绿=跌，用 profitGreen 语义正确，勿改
                RiskMetric("最大回撤", "${stats.maxDrawdown.format(2)}%", AppTheme.colors.profitGreen)
                RiskMetric("年化波动率", "${stats.volatility.format(2)}%", AppTheme.colors.onSurface)
                // 首期净资产 ≤0 时增长率为 null，显示「—」：0.00% 会被读成零增长，
                // 与旁边的正日均增值额自相矛盾（与期间增值卡同一处理）
                RiskMetric(
                    "日均复合增长",
                    stats.avgDailyReturnPercent?.let { "${it.format(2)}%" } ?: "—",
                    stats.avgDailyReturnPercent?.profitColor() ?: AppTheme.colors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RiskMetric("日均增值额", stats.avgDailyReturnAmount.toProfitDisplay("¥"), stats.avgDailyReturnAmount.profitColor())
                RiskMetric("最高净资产", "¥${stats.maxValue.format(0)}", AppTheme.colors.onSurface)
                RiskMetric("最低净资产", "¥${stats.minValue.format(0)}", AppTheme.colors.onSurface)
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 快照有缺口时，日均/年化指标的分母是覆盖天数而非快照份数；
            // 这行小字让缺口自己说话，用户能判断数字可不可信
            Text(
                text = "覆盖 ${stats.coveredDays} 天 · ${stats.totalSnapshots} 份快照",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
    }
}

fun LazyListScope.historyMonthlyReturnChart(monthlyReturns: List<MonthlyReturn>) {
    if (monthlyReturns.isEmpty()) return
    item {
        SectionCard(title = "月度增值") {
            MonthlyReturnChart(
                monthlyReturns = monthlyReturns,
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
        }
    }
}

fun LazyListScope.historyAssetDistribution(latest: AssetSnapshot) {
    if (latest.totalAssets <= 0) return
    val totalForShare = if (latest.totalAssets > 0) latest.totalAssets else 1.0
    item {
        SectionCard(title = "资产分布") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssetDonutChart(
                    latest = latest,
                    modifier = Modifier.size(120.dp)
                )
                Spacer(modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (latest.stockValue > 0) {
                        DistributionLegend("股票", latest.stockValue, latest.stockValue / totalForShare * 100, AppTheme.colors.lossRed)
                    }
                    if (latest.fundValue > 0) {
                        DistributionLegend("基金", latest.fundValue, latest.fundValue / totalForShare * 100, AppTheme.colors.accentOrange)
                    }
                    if (latest.cryptoValue > 0) {
                        DistributionLegend("加密货币", latest.cryptoValue, latest.cryptoValue / totalForShare * 100, AppTheme.colors.accentBlue)
                    }
                    if (latest.bankDepositValue > 0) {
                        DistributionLegend("银行存款", latest.bankDepositValue, latest.bankDepositValue / totalForShare * 100, AppTheme.colors.primaryGreen)
                    }
                }
            }
        }
    }
}
