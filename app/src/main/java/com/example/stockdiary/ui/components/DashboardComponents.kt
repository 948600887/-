package com.example.stockdiary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.AssetGrowthCalculator
import com.example.stockdiary.data.prefs.SortOrder
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.profitColor
import com.example.stockdiary.utils.toProfitDisplay
import com.example.stockdiary.utils.totalValueCny
import kotlin.math.absoluteValue

@Composable
fun TotalAssetCard(
    showTotalAssets: Boolean,
    showNetAssets: Boolean,
    showProfit: Boolean,
    showLiabilities: Boolean = true,
    totalAssets: Double,
    totalLiabilities: Double,
    netAssets: Double,
    realAnnualIncome: Double,
    dividendLoading: Boolean,
    incomeCalculationStatus: String? = null
) {
    val estimatedDailyDividend = realAnnualIncome / 365
    val realYield = if (totalAssets > 0) (realAnnualIncome / totalAssets) * 100 else 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showNetAssets) {
                MetricLabel("净资产", "¥${netAssets.format(2)}", MaterialTheme.typography.headlineMedium)
                if (showTotalAssets) {
                    Spacer(modifier = Modifier.height(8.dp))
                    MetricLabel("总资产", "¥${totalAssets.format(2)}", MaterialTheme.typography.headlineSmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Outline.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))
            } else if (showTotalAssets) {
                MetricLabel("总资产", "¥${totalAssets.format(2)}", MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Outline.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (showTotalAssets || showNetAssets) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricColumn(
                        label = "预计每日收入",
                        value = if (dividendLoading) null else "¥${estimatedDailyDividend.coerceAtLeast(0.0).format(2)}",
                        loading = dividendLoading
                    )
                    MetricColumn(
                        label = "年化收入率",
                        value = if (dividendLoading) null else "${realYield.format(2)}%",
                        loading = dividendLoading
                    )
                    if (showLiabilities) {
                        MetricColumn(label = "总负债", value = "¥${totalLiabilities.format(2)}")
                    }
                }

                if (incomeCalculationStatus != null && realAnnualIncome == 0.0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    StatusHint(text = incomeCalculationStatus)
                }
            }

            if (showProfit) {
                if (showTotalAssets || showNetAssets) Spacer(modifier = Modifier.height(12.dp))
                IncomeDisplay(
                    annualIncome = realAnnualIncome,
                    dividendLoading = dividendLoading
                )
            }
        }
    }
}

/**
 * 资产增值卡片：本月新增 / 今年新增。
 *
 * 口径是「净资产相对历史快照的增加额」——因为已经没有成本数据，
 * 无法剔除期间新投入的本金，所以它表达的是净资产的增长，不是纯投资收益。
 */
@Composable
fun AssetGrowthCard(
    growth: AssetGrowthCalculator.Growth,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "资产增值",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GrowthMetric("本月新增", growth.monthDelta)
                GrowthMetric("今年新增", growth.yearDelta)
            }
            if (!growth.hasBaseline) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "在「历史」页保存快照后，这里就能对比出本月 / 今年的增值",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GrowthMetric(label: String, delta: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = delta?.toProfitDisplay("¥") ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = delta?.profitColor() ?: OnSurface
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
    }
}

@Composable
fun AssetDistributionCard(
    assetsByType: Map<AssetType, List<Asset>>,
    totalAssets: Double
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "资产分布",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            val entries = assetsByType.toList().sortedByDescending { it.second.sumOf { asset -> asset.totalValueCny } }
            if (entries.isEmpty() || totalAssets <= 0) {
                Text(
                    text = "暂无资产分布数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    entries.forEach { (type, typeAssets) ->
                        val typeValue = typeAssets.sumOf { it.totalValueCny }
                        val typePercent = (typeValue / totalAssets * 100)
                        DistributionItem(label = type.displayName, value = typeValue, percent = typePercent)
                    }
                }
            }
        }
    }
}

@Composable
fun DistributionItem(label: String, value: Double, percent: Double) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(70.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "¥${if (value >= 10000) "${(value / 10000).format(1)}万" else value.format(0)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Text(text = "${percent.format(1)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun EmptyAssetCard(onAddAsset: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(64.dp), tint = OnSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "还没有添加任何资产", style = MaterialTheme.typography.titleMedium, color = OnSurface)
            Text(text = "点击下方按钮添加你的第一笔资产", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAddAsset) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加资产")
            }
        }
    }
}

@Composable
fun SortChip(currentSort: SortOrder, onSortChange: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    text = when (currentSort) {
                        SortOrder.BY_TYPE -> "按类型"
                        SortOrder.BY_VALUE_DESC -> "金额↓"
                        SortOrder.BY_VALUE_ASC -> "金额↑"
                        SortOrder.BY_NAME -> "名称"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = when (sort) {
                                SortOrder.BY_TYPE -> "按类型"
                                SortOrder.BY_VALUE_DESC -> "金额从高到低"
                                SortOrder.BY_VALUE_ASC -> "金额从低到高"
                                SortOrder.BY_NAME -> "按名称"
                            }
                        )
                    },
                    onClick = { onSortChange(sort); expanded = false },
                    leadingIcon = {
                        if (currentSort == sort) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricLabel(label: String, value: String, valueStyle: androidx.compose.ui.text.TextStyle) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = value, style = valueStyle, fontWeight = FontWeight.Bold, color = OnSurface)
}

@Composable
private fun MetricColumn(label: String, value: String? = null, loading: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
        if (loading) {
            Spacer(modifier = Modifier.height(4.dp))
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
        } else if (value != null) {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = OnSurface)
        }
    }
}

@Composable
private fun StatusHint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .background(Warning.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Warning, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, fontSize = 11.sp, color = OnSurfaceVariant)
    }
}

@Composable
private fun IncomeDisplay(annualIncome: Double, dividendLoading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (dividendLoading) {
                "预计年度收入：计算中"
            } else {
                "预计年度收入 ¥${annualIncome.format(2)} · 月均 ¥${(annualIncome / 12).format(2)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Primary,
            fontWeight = FontWeight.Medium
        )
    }
}