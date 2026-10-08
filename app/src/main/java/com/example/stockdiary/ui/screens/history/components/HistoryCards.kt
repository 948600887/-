package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.example.stockdiary.data.AssetGrowthCalculator
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.ui.theme.AccentBlue
import com.example.stockdiary.ui.theme.AccentOrange
import com.example.stockdiary.ui.theme.LossRed
import com.example.stockdiary.ui.theme.OnSurface
import com.example.stockdiary.ui.theme.OnSurfaceVariant
import com.example.stockdiary.ui.theme.Primary
import com.example.stockdiary.ui.theme.PrimaryGreen
import com.example.stockdiary.ui.theme.ProfitGreen
import com.example.stockdiary.ui.theme.SurfaceContainerLow
import com.example.stockdiary.ui.viewmodels.MonthlyReturn
import com.example.stockdiary.ui.viewmodels.SnapshotStatistics
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
                color = if (isSelected) Primary else SurfaceContainerLow,
                contentColor = if (isSelected) Color.White else OnSurfaceVariant
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
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("资产总览", fontSize = 12.sp, color = OnSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "¥${latest.netAssets.format(2)}",
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OnSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OverviewMetric(
                        "本月新增",
                        monthDelta?.toProfitDisplay("¥") ?: "—",
                        monthDelta?.profitColor() ?: OnSurface
                    )
                    OverviewMetric(
                        "今年新增",
                        yearDelta?.toProfitDisplay("¥") ?: "—",
                        yearDelta?.profitColor() ?: OnSurface
                    )
                }
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
    val periodYield = if (earliest.netAssets > 0) (periodChange / earliest.netAssets) * 100 else 0.0
    val firstLabel = earliest.date.formatDateLabel()
    val lastLabel = latest.date.formatDateLabel()
    val periodLabel = if (firstLabel == lastLabel) firstLabel else "$firstLabel ~ $lastLabel"

    item {
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("期间增值", fontSize = 12.sp, color = OnSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
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
                        Text("净资产增加", fontSize = 11.sp, color = OnSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${if (periodYield >= 0) "+" else ""}${periodYield.format(2)}%",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            color = periodYield.profitColor()
                        )
                        Text("增幅", fontSize = 11.sp, color = OnSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(periodLabel, fontSize = 11.sp, color = OnSurfaceVariant)
            }
        }
    }
}

fun LazyListScope.historyRiskCard(stats: SnapshotStatistics) {
    item {
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("风险指标", fontSize = 12.sp, color = OnSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RiskMetric("最大回撤", "${stats.maxDrawdown.format(2)}%", ProfitGreen)
                    RiskMetric("波动率", "${stats.volatility.format(2)}%", OnSurface)
                    RiskMetric("日均收益率", "${stats.avgDailyReturnPercent.format(2)}%", stats.avgDailyReturnPercent.profitColor())
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RiskMetric("日均收益额", stats.avgDailyReturnAmount.toProfitDisplay("¥"), stats.avgDailyReturnAmount.profitColor())
                    RiskMetric("最高净资产", "¥${stats.maxValue.format(0)}", OnSurface)
                    RiskMetric("最低净资产", "¥${stats.minValue.format(0)}", OnSurface)
                }
            }
        }
    }
}

fun LazyListScope.historyMonthlyReturnChart(monthlyReturns: List<MonthlyReturn>) {
    if (monthlyReturns.isEmpty()) return
    item {
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("月度收益", fontSize = 12.sp, color = OnSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                MonthlyReturnChart(
                    monthlyReturns = monthlyReturns,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
            }
        }
    }
}

fun LazyListScope.historyAssetDistribution(latest: AssetSnapshot) {
    if (latest.totalAssets <= 0) return
    val totalForShare = if (latest.totalAssets > 0) latest.totalAssets else 1.0
    item {
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("资产分布", fontSize = 12.sp, color = OnSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
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
                            DistributionLegend("股票", latest.stockValue, latest.stockValue / totalForShare * 100, LossRed)
                        }
                        if (latest.fundValue > 0) {
                            DistributionLegend("基金", latest.fundValue, latest.fundValue / totalForShare * 100, AccentOrange)
                        }
                        if (latest.cryptoValue > 0) {
                            DistributionLegend("加密货币", latest.cryptoValue, latest.cryptoValue / totalForShare * 100, AccentBlue)
                        }
                        if (latest.bankDepositValue > 0) {
                            DistributionLegend("银行存款", latest.bankDepositValue, latest.bankDepositValue / totalForShare * 100, PrimaryGreen)
                        }
                    }
                }
            }
        }
    }
}
