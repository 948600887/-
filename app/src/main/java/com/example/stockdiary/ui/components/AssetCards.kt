package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.stockdiary.ui.theme.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Savings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.totalValueCny

@Composable
fun PortfolioOverviewCard(
    totalAssets: Double,
    totalLiabilities: Double,
    netAssets: Double,
    estimatedAnnualDividend: Double,
    isDividendLoading: Boolean,
    dividendStatus: String?,
    assetCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PrimaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("总资产", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text("$assetCount 项", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("¥${totalAssets.format(2)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OnSurface)
            // 有负债时净资产比总资产更值得看，别让用户自己心算
            if (totalLiabilities > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("净资产 ¥${netAssets.format(2)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = OnSurface)
                    Text("负债 ¥${totalLiabilities.format(2)}", style = MaterialTheme.typography.bodyMedium, color = ProfitGreen)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))
            // 抓取期间照常显示旧值，不用「计算中」把屏幕占住——
            // 缓存命中时根本不抓，冷启动那一下也会有旧值可看
            val dividendText = when {
                isDividendLoading && estimatedAnnualDividend <= 0.0 -> "计算中"
                estimatedAnnualDividend > 0.0 -> "¥${estimatedAnnualDividend.format(0)}"
                else -> "-"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PortfolioMetricColumn("年度分红", dividendText, isDividendLoading && estimatedAnnualDividend <= 0.0)
                PortfolioMetricColumn("月均分红", if (estimatedAnnualDividend > 0) "¥${(estimatedAnnualDividend / 12).format(0)}" else "-", false)
                PortfolioMetricColumn(dividendStatus ?: "持有", if (dividendStatus != null) "" else "$assetCount", isDividendLoading && dividendStatus != null)
            }
        }
    }
}

/**
 * 市场大区头（场内 / 场外 / 其他）。比账户头更醒目，让两批来源一眼分开。
 *
 * 分组依据是 symbol 形态（`fund_` 前缀 = 场外份额，`sh`/`sz`/`bj` = 场内），
 * 不是账户名——账户名是用户自己填的，两次导入填同一个名字就会混在一起。
 */
@Composable
fun MarketHeaderCard(
    label: String,
    assetCount: Int,
    totalValue: Double,
    isExpanded: Boolean,
    expandKey: String,
    onToggleExpand: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PrimaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = { onToggleExpand(expandKey) }).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnSurface
                )
                Text("$assetCount 项", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "¥${totalValue.format(2)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = OnSurfaceVariant
                )
            }
        }
    }
}

/**
 * 账户分组头。**只渲染头部**，资产行由外层 LazyColumn 直接渲染。
 *
 * 这里曾经把该账户下的资产也用 Column + forEach 一起渲染，结果 LazyColumn 的懒加载
 * 完全失效——它只对自己的直接子项生效。持仓上百只时，一个账户就是上百个 Card
 * 一次性全部组合，首屏必然卡死。现在拍扁后每一项都是 LazyColumn 的直接子项。
 */
@Composable
fun AccountHeaderCard(
    account: String,
    assetCount: Int,
    totalValue: Double,
    isExpanded: Boolean,
    expandKey: String,
    onToggleExpand: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = { onToggleExpand(expandKey) }).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(account, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text("$assetCount 项", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("¥${totalValue.format(2)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
            }
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = OnSurfaceVariant
            )
        }
    }
}

@Composable
fun StockAssetItem(
    asset: Asset,
    onClick: (Long) -> Unit,
    onDividendClick: (Long) -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (Long) -> Unit
) {
    val isLiability = asset.type.isLiability
    val currentPrice = asset.currentPrice
    val pricePrecision = if (asset.type == AssetType.FUND || StockCodeManager.isETF(asset.symbol)) 3 else 2
    val unitLabel = if (asset.type == AssetType.STOCK) "股" else "份"

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = { onClick(asset.id) }),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) PrimaryContainer.copy(alpha = 0.4f) else SurfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection(asset.id) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnSurface, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    asset.symbol?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                        Text("•", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    // 负债没有「持仓数量」的概念，balance 就是欠多少钱
                    Text(
                        if (isLiability) "负债" else "${asset.balance.format(0)}$unitLabel",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("¥${asset.totalValueCny.format(2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text(
                    when {
                        isLiability -> "待偿还"
                        currentPrice != null -> "¥${currentPrice.format(pricePrecision)}"
                        else -> "价格待更新"
                    },
                    style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, fontWeight = FontWeight.Medium
                )
            }
            // 分红入口只对能分红的类型有意义，负债点了也是空列表
            if (!selectionMode && !isLiability) {
                IconButton(onClick = { onDividendClick(asset.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Savings, contentDescription = "分红", tint = Primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun PortfolioMetricColumn(label: String, value: String, isLoading: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
        } else {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = OnSurface)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
    }
}
