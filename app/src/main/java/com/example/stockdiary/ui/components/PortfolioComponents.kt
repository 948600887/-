package com.example.stockdiary.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.DividendRecord
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.costCny
import com.example.stockdiary.utils.profitCny
import com.example.stockdiary.utils.profitColor
import com.example.stockdiary.utils.toProfitDisplay
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PortfolioOverviewCard(
    totalValue: Double,
    totalProfit: Double,
    totalProfitPercent: Double,
    estimatedAnnualDividend: Double,
    isDividendLoading: Boolean,
    dividendStatus: String?,
    stockCount: Int
) {
    val monthlyDividend = if (estimatedAnnualDividend > 0) estimatedAnnualDividend / 12 else 0.0

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
                Text("$stockCount 只", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("¥${totalValue.format(2)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OnSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${totalProfit.toProfitDisplay("¥")} (${totalProfitPercent.format(2)}%)",
                style = MaterialTheme.typography.bodyMedium,
                color = totalProfit.profitColor()
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PortfolioMetricColumn("年度分红", if (isDividendLoading) "计算中" else "¥${estimatedAnnualDividend.format(0)}", isDividendLoading)
                PortfolioMetricColumn("月均分红", if (isDividendLoading) "-" else "¥${monthlyDividend.format(0)}", false)
                PortfolioMetricColumn(dividendStatus ?: "持有", if (dividendStatus != null) "" else "$stockCount", isDividendLoading && dividendStatus != null)
            }
        }
    }
}

@Composable
fun AccountGroup(
    account: String,
    assets: List<Asset>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onAssetClick: (Asset) -> Unit,
    onDividendClick: (Asset) -> Unit,
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelection: (Asset) -> Unit = {}
) {
    val totalValue = assets.sumOf { it.totalValueCny }
    val totalProfit = assets.sumOf { it.profitCny }
    val totalCost = assets.sumOf { it.costCny }
    val profitPercentage = if (totalCost > 0) (totalProfit / totalCost) * 100 else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(account, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
                    Text("${assets.size} 只", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("¥${totalValue.format(2)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
                    Text(
                        "${totalProfit.toProfitDisplay("¥")} (${profitPercentage.format(2)}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = totalProfit.profitColor()
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = OnSurfaceVariant
                )
            }
            if (isExpanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.fillMaxWidth(), color = Outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))
                    assets.forEachIndexed { index, asset ->
                        StockAssetItem(
                            asset = asset,
                            onClick = { if (selectionMode) onToggleSelection(asset) else onAssetClick(asset) },
                            onDividendClick = { onDividendClick(asset) },
                            selectionMode = selectionMode,
                            isSelected = selectedIds.contains(asset.id),
                            onToggleSelection = { onToggleSelection(asset) }
                        )
                        if (index < assets.size - 1) Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun StockAssetItem(
    asset: Asset,
    onClick: () -> Unit,
    onDividendClick: () -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {}
) {
    val profit = asset.profitCny
    val costCny = asset.costCny
    val profitPercent = if (costCny > 0) (profit / costCny) * 100 else 0.0
    val currentPrice = asset.currentPrice ?: asset.purchasePrice
    val change = currentPrice - asset.purchasePrice
    val changePercentage = if (asset.purchasePrice > 0) (change / asset.purchasePrice) * 100 else 0.0
    val changeColor = if (change >= 0) LossRed else ProfitGreen
    val pricePrecision = if (asset.type == AssetType.FUND || StockCodeManager.isETF(asset.symbol)) 3 else 2

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnSurface, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("¥${currentPrice.format(pricePrecision)}", style = MaterialTheme.typography.bodySmall, color = OnSurface)
                    Text(
                        "${if (change >= 0) "+" else ""}${changePercentage.format(2)}%",
                        style = MaterialTheme.typography.bodySmall, color = changeColor, fontWeight = FontWeight.Medium
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("¥${asset.totalValueCny.format(2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text(
                    "${profit.toProfitDisplay("¥")} (${profitPercent.format(1)}%)",
                    style = MaterialTheme.typography.bodySmall, color = profit.profitColor(), fontWeight = FontWeight.Medium
                )
            }
            if (!selectionMode) {
                IconButton(onClick = onDividendClick, modifier = Modifier.size(32.dp)) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioSearchDialog(
    assets: List<Asset>,
    onDismiss: () -> Unit,
    onAssetSelected: (Asset) -> Unit,
    onDividendSelected: (Asset) -> Unit
) {
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
                Text("搜索持仓", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OnSurface)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入名称或代码") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清除", tint = OnSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, unfocusedBorderColor = Outline)
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
                            Text("没有找到匹配的持仓", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
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
    val profit = asset.profit
    val profitPercent = if (asset.balance > 0 && asset.purchasePrice > 0)
        (profit / (asset.balance * asset.purchasePrice)) * 100 else 0.0
    val profitColor = if (profit >= 0) LossRed else ProfitGreen

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnSurface, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(asset.symbol ?: "", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    Text("•", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    Text("${asset.balance.format(0)}${if (asset.type == AssetType.STOCK) "股" else "份"}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("¥${asset.totalValue.format(2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text(
                    "${if (profit >= 0) "+" else ""}¥${profit.format(2)} (${profitPercent.format(1)}%)",
                    style = MaterialTheme.typography.bodySmall, color = profitColor, fontWeight = FontWeight.Medium
                )
            }
            IconButton(onClick = onDividendClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Savings, contentDescription = "分红", tint = Primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun DividendDetailDialog(
    asset: Asset,
    onDismiss: () -> Unit
) {
    val container = LocalAppContainer.current
    val symbol = remember(asset) { StockCodeManager.normalizeSymbol(asset.symbol ?: "") }
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<Dividend>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }

    var editingRecord by remember { mutableStateOf<Dividend?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    suspend fun loadRecords() {
        if (symbol.isBlank()) {
            isLoading = false
            loadError = "无有效代码"
            return
        }
        isLoading = true
        loadError = null
        try {
            val result = withContext(Dispatchers.IO) {
                container.dividendRepository.getDbDividendsBySymbol(symbol)
            }
            records = result.sortedByDescending { it.date }
        } catch (e: Exception) {
            AppLogger.e("加载分红详情失败: ${e.message}", e, "DividendDetail")
            loadError = "加载失败"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(symbol) {
        loadRecords()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(asset.name, fontWeight = FontWeight.Bold)
                    Text(symbol, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                }
                TextButton(onClick = { isEditMode = !isEditMode }) {
                    Text(if (isEditMode) "完成" else "管理")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (loadError != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(loadError ?: "", color = OnSurfaceVariant)
                    }
                } else if (records.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Savings, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("暂无分红记录", color = OnSurfaceVariant)
                        }
                    }
                } else {
                    val totalPerShare = records.sumOf { it.amount }
                    val totalAmount = totalPerShare / 10.0 * asset.balance
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PrimaryContainer.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${records.size}次", fontWeight = FontWeight.Bold, color = OnSurface)
                                Text("分红次数", fontSize = 11.sp, color = OnSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("¥${totalAmount.format(2)}", fontWeight = FontWeight.Bold, color = LossRed)
                                Text("累计分红", fontSize = 11.sp, color = OnSurfaceVariant)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn {
                        items(
                            items = records,
                            key = { it.id }
                        ) { record ->
                            val amount = record.amount / 10.0 * asset.balance
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(record.date, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurface)
                                        Text(
                                            "每10股 ¥${record.amount.format(2)}",
                                            fontSize = 11.sp, color = OnSurfaceVariant
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "¥${amount.format(2)}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LossRed
                                        )
                                        if (isEditMode) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            IconButton(
                                                onClick = {
                                                    editingRecord = record
                                                    showEditDialog = true
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = Primary, modifier = Modifier.size(16.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            container.dividendRepository.deleteManualDividend(record.id, symbol)
                                                        }
                                                        loadRecords()
                                                    }
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = LossRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isEditMode) {
                    TextButton(
                        onClick = {
                            editingRecord = null
                            showEditDialog = true
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("新增")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )

    if (showEditDialog) {
        DividendEditDialog(
            symbol = symbol,
            assetName = asset.name,
            existing = editingRecord,
            onDismiss = { showEditDialog = false },
            onSave = {
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        container.dividendRepository.saveManualDividend(it)
                    }
                    showEditDialog = false
                    loadRecords()
                }
            }
        )
    }
}

@Composable
private fun DividendEditDialog(
    symbol: String,
    assetName: String,
    existing: Dividend?,
    onDismiss: () -> Unit,
    onSave: (Dividend) -> Unit
) {
    var date by remember { mutableStateOf(existing?.date ?: "") }
    var amountText by remember { mutableStateOf(existing?.amount?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增分红" else "编辑分红", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(assetName, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("除息日 (yyyy-MM-dd)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("每10股分红 (元)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error ?: "", color = LossRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedAmount = amountText.toDoubleOrNull()
                    val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
                    when {
                        !datePattern.matches(date) -> error = "日期格式错误"
                        parsedAmount == null || parsedAmount < 0 -> error = "分红金额必须大于等于0"
                        parsedAmount > 100 -> error = "每10股分红超过100元，请检查单位"
                        else -> {
                            error = null
                            onSave(
                                Dividend(
                                    id = existing?.id ?: 0,
                                    symbol = symbol,
                                    name = assetName,
                                    amount = parsedAmount,
                                    date = date
                                )
                            )
                        }
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}