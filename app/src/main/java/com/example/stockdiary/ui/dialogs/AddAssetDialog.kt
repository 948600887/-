package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.SinaStockData
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.StockAPIManager
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.components.DialogInfoItem
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.ui.components.DialogTypeChip
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.StockCodeManager.CodeCategory
import com.example.stockdiary.utils.format
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAssetDialog(
    onDismiss: () -> Unit,
    onConfirm: (Asset) -> Unit,
    existingAccounts: List<String> = emptyList()
) {
    var selectedType by remember { mutableStateOf(AssetType.STOCK) }
    var name by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var accountExpanded by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var purchasePrice by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var interestRate by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var fundInfo by remember { mutableStateOf<FundAPI.FundInfo?>(null) }
    var stockInfo by remember { mutableStateOf<SinaStockData?>(null) }

    // 自动识别代码类别
    val detectedCategory by remember {
        derivedStateOf {
            if (symbol.length >= 4) StockCodeManager.classifyCode(symbol) else null
        }
    }

    // 天天基金API查询（必须先定义，因为 LaunchedEffect 中会调用）
    // 优先用 FundAPI（无 Referer 头，不会被天天基金反爬拦截），EnhancedFundAPI 兜底
    suspend fun tryFundAPI() {
        try {
            // 先用 FundAPI（与 AddStockDialog 一致，已确认可用）
            val fundApiResult = FundAPI.getFundInfo(symbol)
            if (fundApiResult != null && fundApiResult.name.isNotEmpty()) {
                fundInfo = FundAPI.FundInfo(
                    code = fundApiResult.code.ifEmpty { symbol },
                    name = fundApiResult.name,
                    price = fundApiResult.price,
                    netWorth = fundApiResult.price,
                    changePercent = fundApiResult.changePercent,
                    date = fundApiResult.date
                )
                stockInfo = null
                loadError = null
                if (name.isBlank()) name = fundApiResult.name
                if (purchasePrice.isBlank() && fundApiResult.price > 0) {
                    purchasePrice = fundApiResult.price.toString()
                }
                return
            }

            // FundAPI 失败，尝试带缓存的版本
            val result = FundAPI.getFundInfoWithCache(symbol)
            if (result.isSuccess) {
                val info = result.getOrNull()
                fundInfo = info
                stockInfo = null
                loadError = null
                if (info != null) {
                    if (name.isBlank()) name = info.name
                    if (purchasePrice.isBlank() && info.price > 0) {
                        purchasePrice = info.price.toString()
                    }
                }
            } else {
                // 两个基金API都失败，尝试股票API兜底
                try {
                    val formattedCode = SinaStockAPI.formatStockCode(symbol)
                    val stockResult = StockAPIManager.instance.getStockData(formattedCode)
                    if (stockResult.isSuccess) {
                        val info = stockResult.getOrNull()
                        stockInfo = info
                        fundInfo = null
                        loadError = null
                        if (info != null) {
                            if (name.isBlank()) name = info.name
                            if (purchasePrice.isBlank()) purchasePrice = info.price.toString()
                        }
                    } else {
                        loadError = "未找到该代码，请手动输入名称"
                    }
                } catch (e: Exception) {
                    loadError = "未找到该代码，请手动输入名称"
                }
            }
        } catch (e: Exception) {
            loadError = "网络错误：${e.message}"
        }
    }

    // 自动识别并查询
    LaunchedEffect(symbol) {
        fundInfo = null
        stockInfo = null
        loadError = null

        val cat = detectedCategory
        if (cat == null || cat == CodeCategory.UNKNOWN) {
            if (symbol.length >= 4 && selectedType == AssetType.STOCK) {
                // 无法识别但用户选了股票，尝试用股票API兜底
                isLoading = true
                delay(500)
                try {
                    val formattedCode = SinaStockAPI.formatStockCode(symbol)
                    val result = StockAPIManager.instance.getStockData(formattedCode)
                    if (result.isSuccess) {
                        stockInfo = result.getOrNull()
                        stockInfo?.let { info ->
                            if (name.isBlank()) name = info.name
                            if (purchasePrice.isBlank()) purchasePrice = info.price.toString()
                        }
                    } else {
                        loadError = "未找到该代码，请手动输入名称"
                    }
                } catch (e: Exception) {
                    loadError = "网络错误：${e.message}"
                } finally {
                    isLoading = false
                }
            }
            return@LaunchedEffect
        }

        // 自动设置资产类型
        when (cat) {
            CodeCategory.STOCK -> selectedType = AssetType.STOCK
            CodeCategory.ETF, CodeCategory.LOF, CodeCategory.EXCHANGE_FUND, CodeCategory.OTC_FUND -> selectedType = AssetType.FUND
            else -> {}
        }

        delay(500)
        isLoading = true
        try {
            when (cat) {
                CodeCategory.STOCK, CodeCategory.ETF, CodeCategory.LOF, CodeCategory.EXCHANGE_FUND -> {
                    // 股票/场内基金 → 用股票API
                    val formattedCode = SinaStockAPI.formatStockCode(symbol)
                    val result = StockAPIManager.instance.getStockData(formattedCode)
                    if (result.isSuccess) {
                        val info = result.getOrNull()
                        stockInfo = info
                        fundInfo = null
                        if (info != null) {
                            if (name.isBlank()) name = info.name
                            if (purchasePrice.isBlank()) purchasePrice = info.price.toString()
                        }
                    } else {
                        // 场内基金API失败，尝试天天基金（可能是被误判的场外基金）
                        if (cat == CodeCategory.EXCHANGE_FUND) {
                            tryFundAPI()
                        } else {
                            loadError = "未找到该代码，请手动输入名称"
                        }
                    }
                }
                CodeCategory.OTC_FUND -> {
                    // 场外基金 → 天天基金API
                    tryFundAPI()
                }
                else -> {}
            }
        } catch (e: Exception) {
            loadError = "网络错误：${e.message}"
        } finally {
            isLoading = false
        }
    }

    val isValid by remember {
        derivedStateOf {
            val balanceValid = (balance.toDoubleOrNull() ?: -1.0) >= 0
            val interestRateValid = selectedType != AssetType.BANK_DEPOSIT || (interestRate.toDoubleOrNull() ?: -1.0) >= 0
            val purchasePriceValid = selectedType == AssetType.BANK_DEPOSIT || purchasePrice.isBlank() || (purchasePrice.toDoubleOrNull() ?: -1.0) >= 0
            name.isNotBlank() &&
            account.isNotBlank() &&
            balanceValid &&
            interestRateValid &&
            purchasePriceValid
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 头部
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "添加资产",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 内容区域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 资产类型
                    SectionHeader("资产类型")

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 证券：合并股票+基金，输入代码后自动识别
                            val isSecuritiesSelected = selectedType == AssetType.STOCK || selectedType == AssetType.FUND
                            TypeChip(
                                type = AssetType.STOCK,
                                selected = isSecuritiesSelected,
                                onClick = {
                                    if (!isSecuritiesSelected) {
                                        selectedType = AssetType.STOCK
                                    }
                                    fundInfo = null
                                    stockInfo = null
                                    loadError = null
                                },
                                customLabel = "证券",
                                modifier = Modifier.weight(1f)
                            )
                            TypeChip(
                                type = AssetType.BANK_DEPOSIT,
                                selected = selectedType == AssetType.BANK_DEPOSIT,
                                onClick = {
                                    selectedType = AssetType.BANK_DEPOSIT
                                    fundInfo = null
                                    stockInfo = null
                                    loadError = null
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(AssetType.CRYPTO, AssetType.CREDIT_CARD, AssetType.LOAN).forEach { type ->
                                TypeChip(
                                    type = type,
                                    selected = selectedType == type,
                                    onClick = {
                                        selectedType = type
                                        fundInfo = null
                                        stockInfo = null
                                        loadError = null
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // 基本信息
                    SectionHeader("基本信息")

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("资产名称") },
                        placeholder = { Text("例如：贵州茅台、易方达消费精选") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = name.isBlank(),
                        supportingText = if (name.isBlank()) { { Text("必填") } } else null
                    )

                    if (selectedType == AssetType.STOCK || selectedType == AssetType.FUND || selectedType == AssetType.CRYPTO) {
                        OutlinedTextField(
                            value = symbol,
                            onValueChange = { symbol = it },
                            label = {
                                when (selectedType) {
                                    AssetType.CRYPTO -> Text("交易对")
                                    else -> Text("代码")
                                }
                            },
                            placeholder = {
                                when (selectedType) {
                                    AssetType.CRYPTO -> Text("例如：BTC/USDT")
                                    else -> Text("输入代码自动识别：600519/510050/007467...")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        )

                        // 自动识别类型标签
                        if (detectedCategory != null && detectedCategory != CodeCategory.UNKNOWN) {
                            val cat = detectedCategory
                            if (cat != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "识别为 ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(cat.color.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = cat.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = cat.color
                                    )
                                }
                            }
                            }
                        }

                        // 显示查找结果
                        stockInfo?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            StockInfoCard(info = it, category = detectedCategory)
                        }
                        fundInfo?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            FundInfoCard(info = it)
                        }
                        loadError?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            ErrorCard(message = it)
                        }
                    }

                    val filteredAccounts = remember(account, existingAccounts) {
                        if (account.isBlank()) {
                            existingAccounts
                        } else {
                            existingAccounts.filter { it.contains(account, ignoreCase = true) }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = accountExpanded && filteredAccounts.isNotEmpty(),
                        onExpandedChange = { accountExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = account,
                            onValueChange = {
                                account = it
                                accountExpanded = true
                            },
                            label = { Text("账户/平台") },
                            placeholder = { Text("选择或输入：招商证券、支付宝等") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            singleLine = true,
                            isError = account.isBlank(),
                            supportingText = if (account.isBlank()) { { Text("必填") } } else null,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountExpanded && filteredAccounts.isNotEmpty()) }
                        )

                        ExposedDropdownMenu(
                            expanded = accountExpanded && filteredAccounts.isNotEmpty(),
                            onDismissRequest = { accountExpanded = false }
                        ) {
                            filteredAccounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text(acc) },
                                    onClick = {
                                        account = acc
                                        accountExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }

                    // 交易信息
                    SectionHeader("交易信息")

                    OutlinedTextField(
                        value = balance,
                        onValueChange = { balance = it },
                        label = {
                            when (selectedType) {
                                AssetType.STOCK -> Text("持股数量")
                                AssetType.FUND -> Text("持有份额")
                                AssetType.CRYPTO -> Text("持有数量")
                                AssetType.BANK_DEPOSIT -> Text("存款金额")
                                else -> Text("数量/金额")
                            }
                        },
                        placeholder = { Text("请输入数字") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = (balance.toDoubleOrNull() ?: -1.0) < 0,
                        supportingText = {
                            when {
                                balance.toDoubleOrNull() == null -> Text("请输入有效数字")
                                (balance.toDoubleOrNull() ?: -1.0) < 0 -> Text("数量不能为负数")
                                else -> {}
                            }
                        }
                    )

                    if (selectedType != AssetType.BANK_DEPOSIT) {
                        val autoPrice = when {
                            fundInfo?.price != null && fundInfo?.price ?: 0.0 > 0 -> fundInfo?.price
                            stockInfo?.price != null -> stockInfo?.price
                            else -> null
                        }
                        OutlinedTextField(
                            value = purchasePrice,
                            onValueChange = { purchasePrice = it },
                            label = { Text("买入单价") },
                            placeholder = {
                                if (autoPrice != null) {
                                    Text("自动填充：${autoPrice}")
                                } else {
                                    Text("可选，留空则不计算盈亏")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = purchasePrice.isNotBlank() && (purchasePrice.toDoubleOrNull() ?: -1.0) < 0,
                            supportingText = {
                                if (purchasePrice.isNotBlank() && (purchasePrice.toDoubleOrNull() ?: -1.0) < 0) {
                                    Text("价格不能为负数")
                                }
                            }
                        )
                    }

                    if (selectedType == AssetType.BANK_DEPOSIT) {
                        OutlinedTextField(
                            value = interestRate,
                            onValueChange = { interestRate = it },
                            label = { Text("年利率 (%)") },
                            placeholder = { Text("例如：3.5") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = (interestRate.toDoubleOrNull() ?: -1.0) < 0,
                            supportingText = {
                                when {
                                    interestRate.toDoubleOrNull() == null -> Text("必填，请输入有效数字")
                                    (interestRate.toDoubleOrNull() ?: -1.0) < 0 -> Text("利率不能为负数")
                                    else -> {}
                                }
                            }
                        )
                    }

                    // 备注
                    SectionHeader("备注")
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("备注（可选）") },
                        placeholder = { Text("添加备注信息，如买入日期、用途等") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }

                // 底部按钮
                HorizontalDivider()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                if (isSaving) return@Button
                                isSaving = true
                                val normalizedSymbol = if (selectedType == AssetType.STOCK || selectedType == AssetType.FUND) {
                                    StockCodeManager.normalizeSymbol(symbol)
                                } else {
                                    symbol
                                }
                                val asset = Asset(
                                    name = name,
                                    type = selectedType,
                                    symbol = normalizedSymbol.takeIf { it.isNotBlank() },
                                    account = account,
                                    balance = balance.toDoubleOrNull() ?: 0.0,
                                    purchasePrice = if (selectedType == AssetType.BANK_DEPOSIT) {
                                        1.0
                                    } else {
                                        purchasePrice.toDoubleOrNull() ?: fundInfo?.price ?: stockInfo?.price ?: 0.0
                                    },
                                    currentPrice = if (selectedType == AssetType.BANK_DEPOSIT) {
                                        1.0
                                    } else {
                                        fundInfo?.price ?: stockInfo?.price ?: purchasePrice.toDoubleOrNull()
                                    },
                                    interestRate = if (selectedType == AssetType.BANK_DEPOSIT) {
                                        interestRate.toDoubleOrNull()
                                    } else {
                                        null
                                    },
                                    notes = notes.takeIf { it.isNotBlank() }
                                )
                                onConfirm(asset)
                            },
                            enabled = isValid && !isSaving,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("添加")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) = DialogSectionHeader(text)

@Composable
private fun TypeChip(
    type: AssetType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customLabel: String? = null
) = DialogTypeChip(type, selected, onClick, modifier, customLabel)

@Composable
private fun FundInfoCard(info: FundAPI.FundInfo) {
    // 场外基金净值显示到4位小数（如 1.2345）
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("净值", "${info.price.format(4)}")
                if (info.changePercent != null) {
                    InfoItem(
                        label = "涨跌",
                        value = "${info.changePercent.format(2)}%",
                        color = if (info.changePercent >= 0) LossRed else ProfitGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun StockInfoCard(info: SinaStockData, category: CodeCategory? = null) {
    // ETF/LOF/场内基金价格显示到厘（3位小数），股票显示到分（2位小数）
    val priceDigits = if (category == CodeCategory.ETF || category == CodeCategory.LOF || category == CodeCategory.EXCHANGE_FUND) 3 else 2
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("现价", "${info.price.format(priceDigits)}")
                InfoItem(
                    label = "涨跌",
                    value = "${info.changePercent.format(2)}%",
                    color = if (info.changePercent >= 0) LossRed else ProfitGreen
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    color: Color? = null
) = DialogInfoItem(label, value, color)