package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.stockdiary.ui.dialogs.sanitizeDecimalInput
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
                    // 「累计分红」原来的口径是「基金成立以来全部分红 × 当前份额」。
                    // 那等于假设用户从基金成立第一天起就持有当前这么多份额——
                    // 历史越久数字越离谱，而用户实际可能一分钱都没收到。
                    //
                    // 改成近 12 个月：与持仓页「年度分红」同口径，有实际参考意义。
                    // yyyy-MM-dd 的字典序就是时间序，字符串直接比即可
                    val oneYearAgo = remember {
                        val calendar = java.util.Calendar.getInstance()
                        calendar.add(java.util.Calendar.DAY_OF_YEAR, -365)
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(calendar.time)
                    }
                    val recentRecords = records.filter { it.date >= oneYearAgo }
                    val recentAmount = perTenSharesToAmount(
                        recentRecords.sumOf { it.amount },
                        asset.balance
                    )
                    // 历史累计只按「每10份」给：那是基金自身的属性，与用户持仓无关，
                    // 数字绝对正确，也能看出这基金分红勤不勤
                    val historicalPerTen = records.sumOf { it.amount }

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
                                    Text("¥${recentAmount.format(2)}", fontWeight = FontWeight.Bold, color = LossRed)
                                    Text("近12个月分红", fontSize = 11.sp, color = OnSurfaceVariant)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // 分红是按「除息日当天持有的份额」发的，App 只存了当前份额，
                            // 所以这里只能拿当前份额去折算 —— 必须讲清楚它不是真实到账金额。
                            Text(
                                text = "按当前 ${asset.balance.format(0)} $unitWord 估算，非实际到账",
                                fontSize = 10.sp,
                                color = OnSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (historicalPerTen > 0) {
                                Text(
                                    // 必须写 ${unitWord} 而不是 $unitWord：后面紧跟中文「派」，
                                    // Kotlin 会把整个「unitWord派」当成一个标识符去解析
                                    text = "该基金历史累计每10${unitWord}派 ¥${historicalPerTen.format(2)}",
                                    fontSize = 10.sp,
                                    color = OnSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (recentRecords.isEmpty() && records.isNotEmpty()) {
                                Text(
                                    text = "近12个月无分红，历史累计仅供参考",
                                    fontSize = 10.sp,
                                    color = OnSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
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
                                    // remember(record) 缓存 onClick：这段行是 items{} 里的 inline lambda，
                                    // 弹窗自身状态（isEditMode / editingRecord 等）一变就会让所有可见行重排，
                                    // 若不缓存，每个 IconButton 都拿到新 lambda 实例被迫重排。
                                    // record 是 data class，同一条记录实例不变时 lambda 复用。
                                    val editClick = remember(record) {
                                        { editingRecord = record; showEditDialog = true }
                                    }
                                    val deleteClick = remember(record) {
                                        { pendingDeleteRecord = record }
                                    }
                                    IconButton(
                                        onClick = editClick,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = Primary, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = deleteClick,
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
    // 编辑已有记录时，原值本来就是合法的，不该一打开就飘红
    var dateTouched by remember { mutableStateOf(existing != null) }
    var amountTouched by remember { mutableStateOf(existing != null) }
    var submitAttempted by remember { mutableStateOf(false) }

    val errors by remember(date, amountText, unitWord) {
        derivedStateOf { validateDividendInput(date, amountText, unitWord) }
    }
    val dateError = if (dateTouched || submitAttempted) errors.date?.message else null
    val amountError = if (amountTouched || submitAttempted) errors.amount?.message else null
    // 金额偏大只提醒、不拦：茅台这类高价股每10股派 300 多元是真实存在的
    val amountHint = if (amountError == null) dividendAmountHint(amountText, unitWord) else null

    val dateFocus = remember { FocusRequester() }
    val amountFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增分红" else "编辑分红", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(assetName, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it; dateTouched = true },
                    label = { Text("除息日 (yyyy-MM-dd)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(dateFocus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { amountFocus.requestFocus() }),
                    isError = dateError != null,
                    supportingText = { dateError?.let { Text(it) } }
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = sanitizeDecimalInput(it)
                        amountTouched = true
                    },
                    label = { Text("每10${unitWord}分红 (元)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            submitAttempted = true
                            if (errors.isValid) {
                                onSave(buildDividend(existing, symbol, assetName, amountText, date))
                            } else {
                                when (errors.firstField) {
                                    "date" -> dateFocus.requestFocus()
                                    "amount" -> amountFocus.requestFocus()
                                }
                            }
                        }
                    ),
                    isError = amountError != null,
                    supportingText = {
                        if (amountError != null) {
                            Text(amountError)
                        } else if (amountHint != null) {
                            Text(amountHint, color = OnSurfaceVariant)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitAttempted = true
                    if (!errors.isValid) {
                        when (errors.firstField) {
                            "date" -> dateFocus.requestFocus()
                            "amount" -> amountFocus.requestFocus()
                        }
                        return@TextButton
                    }
                    onSave(buildDividend(existing, symbol, assetName, amountText, date))
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 组装待保存的 Dividend。调用方必须先确认 [validateDividendInput] 通过。 */
private fun buildDividend(
    existing: Dividend?,
    symbol: String,
    assetName: String,
    amountText: String,
    date: String
): Dividend = Dividend(
    id = existing?.id ?: 0,
    symbol = symbol,
    name = assetName,
    amount = amountText.toDouble(),
    date = date
)
