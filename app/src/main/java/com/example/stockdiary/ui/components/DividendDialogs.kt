package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.stockdiary.ui.theme.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Savings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.Dividend
import com.example.stockdiary.database.amountFor
import com.example.stockdiary.database.perTenSharesToAmount
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.shareUnitWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DividendDetailDialog(
    asset: Asset,
    onDismiss: () -> Unit
) {
    val container = LocalAppContainer.current
    val symbol = remember(asset) { StockCodeManager.normalizeSymbol(asset.symbol ?: "") }
    val coroutineScope = rememberCoroutineScope()
    // 股票写「股」，基金/ETF 写「份」——ETF 是场内基金，之前被写成「股」是错的
    val unitWord = asset.shareUnitWord

    var isLoading by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<Dividend>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }

    var editingRecord by remember { mutableStateOf<Dividend?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    // 删除是撤不回的操作，手滑点到就永久丢一条分红记录 —— 必须先确认
    var pendingDeleteRecord by remember { mutableStateOf<Dividend?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

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
                    val totalAmount = perTenSharesToAmount(totalPerShare, asset.balance)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PrimaryContainer.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                            Spacer(modifier = Modifier.height(8.dp))
                            // 分红是按「除息日当天持有的份额」发的，App 只存了当前份额，
                            // 所以这里只能拿当前份额去折算历史分红 —— 必须讲清楚它不是真实到账金额。
                            Text(
                                text = "按当前 ${asset.balance.format(0)} $unitWord 估算，非实际到账",
                                fontSize = 10.sp,
                                color = OnSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn {
                        items(
                            items = records,
                            key = { it.id }
                        ) { record ->
                            val amount = record.amountFor(asset.balance)
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
                                        Text(
                                            // 除息日只说明「有没有资格拿」，钱到账通常还要等 1~4 周，
                                            // 有发放日就一并标出来，免得被当成到账日
                                            record.date + (record.payDate?.let { " · 到账 ${it.take(5)}" } ?: ""),
                                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurface
                                        )
                                        Text(
                                            "每10$unitWord ¥${record.amount.format(2)}",
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
                                                onClick = { pendingDeleteRecord = record },
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
            unitWord = unitWord,
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

    pendingDeleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRecord = null; deleteError = null },
            title = { Text("删除分红记录", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将删除 ${record.date} 的分红记录（每10$unitWord ¥${record.amount.format(2)}），删除后无法恢复。"
                )
                deleteError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "删除失败：$it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            // 没兜底的话一次 DB 异常就会让确认框永远不消失，
                            // 而 rememberCoroutineScope 不带 CoroutineExceptionHandler —— 直接崩 App。
                            val error = runCatching {
                                withContext(Dispatchers.IO) {
                                    container.dividendRepository.deleteManualDividend(record.id, symbol)
                                }
                            }.onFailure {
                                AppLogger.e("删除分红失败: ${it.message}", it, "DividendDetail")
                            }.exceptionOrNull()
                            if (error == null) {
                                pendingDeleteRecord = null
                                loadRecords()
                            } else {
                                deleteError = error.message ?: "删除失败"
                            }
                        }
                    }
                ) { Text("删除", color = LossRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecord = null; deleteError = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DividendEditDialog(
    symbol: String,
    assetName: String,
    unitWord: String = "股",
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
                    label = { Text("每10${unitWord}分红 (元)") },
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
                        parsedAmount > 100 -> error = "每10${unitWord}分红超过100元，请检查单位"
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
