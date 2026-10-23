package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.stockdiary.ui.theme.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset

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

/**
 * 搜索结果行。
 *
 * 以前这里和 `AssetCards.StockAssetItem` 是两份几乎一样的实现，已经漂移：这边少了到期日、
 * 没有批量选择态，价格的几种状态（退市 / 币安不可达 / 待更新）又抄了一遍。
 * 现在直接委托给 `StockAssetItem`——顺带把触摸目标（32dp→40dp）、资产名省略号、
 * 到期日显示三处差异一并抹平，以后改价格口径只改一处。
 */
@Composable
fun SearchAssetItem(asset: Asset, onClick: () -> Unit, onDividendClick: () -> Unit) {
    StockAssetItem(
        asset = asset,
        onClick = { onClick() },
        onDividendClick = { onDividendClick() }
    )
}
