package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.FundAPI
import com.example.stockdiary.network.SinaStockAPI
import com.example.stockdiary.network.SinaStockData
import com.example.stockdiary.network.StockAPIManager
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.StockCodeManager.CodeCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 添加资产弹窗。
 *
 * 原先是一个 660 行的巨型 composable（状态 + 代码查询 + 11 个字段 + 提交全挤在一起）。
 * 现在这里只保留三件事：**表单状态**、**代码自动识别查询**、**整体布局组装**；
 * 具体字段构件与提交构造分别见 [AddAssetForm.kt]（构件）与 [buildAssetFromInput]（纯函数，可单测）。
 */
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
    var saveError by remember { mutableStateOf<String?>(null) }
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadError = "未找到该代码，请手动输入名称"
                }
            }
        } catch (e: CancellationException) {
            throw e
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
                } catch (e: CancellationException) {
                    throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "网络错误：${e.message}"
        } finally {
            isLoading = false
        }
    }

    // 需要行情价格的资产类型必须提供代码：没有 symbol 就永远同步不到价格，
    // 也不会被任何导入回放/持仓合并/分红匹配命中。
    val symbolRequired by remember(selectedType) {
        derivedStateOf {
            selectedType == AssetType.STOCK ||
                selectedType == AssetType.FUND ||
                selectedType == AssetType.CRYPTO
        }
    }

    // 「碰过」的字段才报错。否则一打开弹窗，空的必填项全红，
    // 用户还没输入就被判错，体验等于打了脸。
    var touched by remember { mutableStateOf(setOf<String>()) }
    fun markTouched(field: String) { touched = touched + field }

    val isValid by remember {
        derivedStateOf {
            val balanceValid = (balance.toDoubleOrNull() ?: -1.0) >= 0
            val interestRateValid = selectedType != AssetType.BANK_DEPOSIT || (interestRate.toDoubleOrNull() ?: -1.0) >= 0
            val purchasePriceValid = selectedType == AssetType.BANK_DEPOSIT || purchasePrice.isBlank() || (purchasePrice.toDoubleOrNull() ?: -1.0) >= 0
            val symbolValid = !symbolRequired || symbol.isNotBlank()
            name.isNotBlank() &&
            account.isNotBlank() &&
            balanceValid &&
            interestRateValid &&
            purchasePriceValid &&
            symbolValid
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
                AddAssetHeader(onDismiss = onDismiss)

                // 内容区域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp)
                ) {
                    // 资产类型
                    AssetTypePicker(
                        selectedType = selectedType,
                        onSelectType = { type ->
                            selectedType = type
                            fundInfo = null
                            stockInfo = null
                            loadError = null
                        }
                    )

                    // 基本信息
                    DialogSectionHeader("基本信息")

                    AssetNameField(
                        name = name,
                        onNameChange = { name = it; markTouched("name") },
                        showError = "name" in touched
                    )

                    if (selectedType == AssetType.STOCK ||
                        selectedType == AssetType.FUND ||
                        selectedType == AssetType.CRYPTO
                    ) {
                        AssetSymbolField(
                            symbol = symbol,
                            onSymbolChange = { symbol = it; markTouched("symbol") },
                            selectedType = selectedType,
                            isLoading = isLoading,
                            required = symbolRequired,
                            showError = "symbol" in touched
                        )

                        // 自动识别类型标签
                        val cat = detectedCategory
                        if (cat != null && cat != CodeCategory.UNKNOWN) {
                            DetectedCategoryLabel(category = cat)
                        }

                        // 显示查找结果
                        LookupResultCards(
                            stockInfo = stockInfo,
                            fundInfo = fundInfo,
                            loadError = loadError,
                            category = detectedCategory
                        )
                    }

                    val filteredAccounts = remember(account, existingAccounts) {
                        if (account.isBlank()) {
                            existingAccounts
                        } else {
                            existingAccounts.filter { it.contains(account, ignoreCase = true) }
                        }
                    }

                    AssetAccountField(
                        account = account,
                        onAccountChange = { account = it; markTouched("account") },
                        expanded = accountExpanded,
                        onExpandedChange = { accountExpanded = it },
                        filteredAccounts = filteredAccounts,
                        showError = "account" in touched
                    )

                    // 交易信息
                    DialogSectionHeader("交易信息")

                    AssetBalanceField(
                        balance = balance,
                        onBalanceChange = { balance = it; markTouched("balance") },
                        selectedType = selectedType,
                        showError = "balance" in touched
                    )

                    if (selectedType != AssetType.BANK_DEPOSIT) {
                        val autoPrice = when {
                            fundInfo?.price != null && fundInfo?.price ?: 0.0 > 0 -> fundInfo?.price
                            stockInfo?.price != null -> stockInfo?.price
                            else -> null
                        }
                        AssetPriceField(
                            purchasePrice = purchasePrice,
                            onPriceChange = { purchasePrice = it; markTouched("price") },
                            autoPrice = autoPrice,
                            showError = "price" in touched
                        )
                    }

                    if (selectedType == AssetType.BANK_DEPOSIT) {
                        AssetInterestRateField(
                            interestRate = interestRate,
                            onRateChange = { interestRate = it; markTouched("rate") },
                            showError = "rate" in touched
                        )
                    }

                    // 备注
                    DialogSectionHeader("备注")
                    AssetNotesField(
                        notes = notes,
                        onNotesChange = { notes = it }
                    )
                }

                // 底部按钮
                AddAssetActions(
                    onDismiss = onDismiss,
                    onConfirmClick = {
                        if (isSaving) return@AddAssetActions
                        // 点保存时把所有字段标记为"已碰过"：否则按钮直接灰着、
                        // 界面上却没有一处红字，用户不知道该改哪一项。
                        touched = setOf("name", "account", "balance", "symbol", "price", "rate")
                        if (!isValid) {
                            saveError = "请检查标红的必填项"
                            return@AddAssetActions
                        }
                        isSaving = true
                        saveError = null
                        // Asset 的 init 里有 require(...)，构造本身会抛异常；
                        // 这里运行在主线程且没有任何兜底，抛出去就是整 App 崩溃
                        val asset = try {
                            buildAssetFromInput(
                                selectedType = selectedType,
                                name = name,
                                symbol = symbol,
                                account = account,
                                balance = balance,
                                purchasePrice = purchasePrice,
                                interestRate = interestRate,
                                notes = notes,
                                fundPrice = fundInfo?.price,
                                stockPrice = stockInfo?.price
                            )
                        } catch (e: Exception) {
                            // 别把 loader 留在转圈状态——那会让"添加"按钮永久禁用且没有任何提示
                            isSaving = false
                            saveError = e.message ?: "资产数据不合法"
                            return@AddAssetActions
                        }
                        onConfirm(asset)
                    },
                    isSaving = isSaving,
                    saveError = saveError
                )
            }
        }
    }
}
