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
import com.example.stockdiary.data.SortOrder
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
    profit: Double,
    profitPercent: Double,
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
                        label = "综合收益率",
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
                ProfitDisplay(profit = profit, profitPercent = profitPercent)
            }
        }
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
fun AssetTypeSection(
    type: AssetType,
    assets: List<Asset>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val totalValue = assets.sumOf { it.totalValue }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = type.icon, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = type.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
                    Text(text = "${assets.size} 个账户", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "¥${totalValue.format(2)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AssetItem(asset: Asset, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = OnSurface)
                Text(text = asset.symbol ?: asset.account, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "¥${asset.totalValue.format(2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnSurface)
                if (asset.currentPrice != null) {
                    val changePercent = asset.changePercent
                    val isProfit = changePercent >= 0
                    Text(
                        text = "${if (isProfit) "↑" else "↓"} ${changePercent.absoluteValue.format(2)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isProfit) LossRed else ProfitGreen
                    )
                }
            }
        }
    }
}

@Composable
fun LiabilityItem(asset: Asset, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = ErrorContainer.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = OnSurface)
                Text(text = asset.account, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "-¥${asset.totalValue.format(2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = LossRed)
                asset.notes?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
            }
        }
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
private fun ProfitDisplay(profit: Double, profitPercent: Double) {
    val isProfit = profit >= 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isProfit) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
            contentDescription = null,
            tint = profit.profitColor(),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${profit.toProfitDisplay("¥")} (${profitPercent.format(2)}%)",
            style = MaterialTheme.typography.bodyMedium,
            color = profit.profitColor(),
            fontWeight = FontWeight.Medium
        )
    }
}