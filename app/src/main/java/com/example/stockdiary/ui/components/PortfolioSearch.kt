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
import com.example.stockdiary.database.AssetType

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
        // trim 提到 filter / derivedStateOf 之外，只做一次（原来每个资产都 trim 一遍）；
        // 用 isWhitespace 覆盖全角空格 U+3000 等 Unicode 空白，纯 String.trim() 只去 ≤ U+0020。
        val normalizedQuery = searchQuery.trim { it.isWhitespace() }
        derivedStateOf { assets.filter { matchesTrimmedQuery(it, normalizedQuery) } }
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
                    placeholder = { Text("输入名称、代码或账户") },
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

/**
 * 持仓搜索的匹配判定，抽成纯函数便于单测。
 *
 * 匹配范围（子串 + 大小写不敏感）：
 * - [Asset.name] 名称；
 * - [Asset.symbol] 代码（可为 null，跳过）；
 * - [Asset.account] 账户/平台；
 * - [Asset.notes] 备注（可为 null，跳过）；
 * - [AssetType.displayName] 类别中文名（如「基金」「虚拟货币」）。
 *
 * 入参先做一次 `trim { it.isWhitespace() }`：既去掉用户手滑带的首尾空格，也覆盖全角空格
 * U+3000 等 Unicode 空白（默认 `trim()` 只去 ≤ U+0020）。空查询（trim 后空白）视为匹配全部，
 * 等价于搜索框为空时展示完整列表。
 *
 * 列表场景请走 [matchesTrimmedQuery] 并复用同一份已 trim 的查询，避免逐资产重复 trim。
 */
internal fun matchesSearchQuery(asset: Asset, rawQuery: String): Boolean =
    matchesTrimmedQuery(asset, rawQuery.trim { it.isWhitespace() })

/**
 * [matchesSearchQuery] 的免 trim 版本：入参 [q] 已由调用方统一 trim 过一次。
 *
 * [PortfolioSearchDialog] 在 `derivedStateOf` / filter 外算好 `normalizedQuery` 后传进来，
 * 保持匹配字段、大小写不敏感、null 安全、空查询返回全部这些既有语义不变。
 */
internal fun matchesTrimmedQuery(asset: Asset, q: String): Boolean {
    if (q.isBlank()) return true
    return asset.name.contains(q, ignoreCase = true)
        || (asset.symbol?.contains(q, ignoreCase = true) == true)
        || asset.account.contains(q, ignoreCase = true)
        || (asset.notes?.contains(q, ignoreCase = true) == true)
        || asset.type.displayName.contains(q, ignoreCase = true)
}
