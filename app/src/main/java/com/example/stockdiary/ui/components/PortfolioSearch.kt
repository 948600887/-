package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.stockdiary.ui.theme.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Savings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.totalValueCny

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioSearchDialog(
    assets: List<Asset>,
    onDismiss: () -> Unit,
    onAssetSelected: (Asset) -> Unit,
    onDividendSelected: (Asset) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredAssets by remember(assets, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) assets
            else assets.filter { it.name.contains(searchQuery, ignoreCase = true) || (it.symbol?.contains(searchQuery, ignoreCase = true) == true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("搜索持仓", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入名称或代码") },
                    // 结果是边打边筛的，搜索键的作用就是把键盘收起来让结果露出来
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清除", tint = AppTheme.colors.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppTheme.colors.primary, unfocusedBorderColor = AppTheme.colors.outline)
                )
            }
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(items = filteredAssets, key = { it.id }) { asset ->
                    SearchAssetItem(asset = asset, onClick = { onAssetSelected(asset) }, onDividendClick = { onDividendSelected(asset) })
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (filteredAssets.isEmpty() && searchQuery.isNotBlank()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("没有找到匹配的持仓", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SearchAssetItem(asset: Asset, onClick: () -> Unit, onDividendClick: () -> Unit) {
    val pricePrecision = if (asset.type == AssetType.FUND || StockCodeManager.isETF(asset.symbol)) 3 else 2

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.onSurface, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(asset.symbol ?: "", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                    Text("•", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                    Text("${asset.balance.format(0)}${if (asset.type == AssetType.STOCK) "股" else "份"}", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("¥${asset.totalValueCny.format(2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.onSurface)
                Text(
                    asset.currentPrice?.let { "¥${it.format(pricePrecision)}" } ?: if (StockCodeManager.isDelistedForSync(asset.symbol)) "退市" else "价格待更新",
                    style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Medium
                )
                if (asset.millionCopiesIncome != null) {
                    Text("万份收益 ${asset.millionCopiesIncome.format(4)}",
                        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDividendClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Savings, contentDescription = "分红", tint = AppTheme.colors.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
