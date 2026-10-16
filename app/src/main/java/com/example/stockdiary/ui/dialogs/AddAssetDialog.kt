package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
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

/**
 * 添加资产弹窗。
 *
 * 原先是一个 660 行的巨型 composable（状态 + 代码查询 + 11 个字段 + 提交全挤在一起）。
 * 现在这里只负责四件事：**表单状态**、**代码自动识别查询**、**焦点链**、**布局组装**；
 * 具体字段构件见 [AddAssetForm.kt]，构造与校验分别在
 * [buildAssetFromInput] / [validateAssetInput]（都是纯函数，可单测）。
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

    // 焦点链：让键盘「下一步」能一路走到最后一个字段，而不是每填一项都要手点屏幕。
    val nameFocus = remember { FocusRequester() }
    val symbolFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    val balanceFocus = remember { FocusRequester() }
    val priceFocus = remember { FocusRequester() }
    val rateFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val symbolVisible = selectedType == AssetType.STOCK ||
        selectedType == AssetType.FUND ||
        selectedType == AssetType.CRYPTO
    val isDeposit = selectedType == AssetType.BANK_DEPOSIT

    /** 下一个该聚焦的字段：跳过当前类型下不可见的那些 */
    fun focusAfter(field: String) {
        when (field) {
            "name" -> if (symbolVisible) symbolFocus else accountFocus
            "symbol" -> accountFocus
            "account" -> balanceFocus
            "balance" -> if (isDeposit) rateFocus else priceFocus
            "price", "rate" -> notesFocus
            else -> return
        }.requestFocus()
    }

    /** 聚焦到指定字段本身（校验失败时用来定位到出错的那一项） */
    fun focusField(field: String) {
        when (field) {
            "name" -> nameFocus
            "symbol" -> symbolFocus
            "account" -> accountFocus
            "balance" -> balanceFocus
            "price" -> priceFocus
            "rate" -> rateFocus
            else -> return
        }.requestFocus()
    }

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

    // 自动识别并查询。
    // key 里带上 selectedType：否则切类型时只会把行情卡片清空却不重查 ——
    // 用户会看到「输完代码、行情没了」，以为没查到。
    LaunchedEffect(symbol, selectedType) {
        fundInfo = null
        stockInfo = null
        loadError = null

        // 存款/负债没有行情可查，切到这些类型时直接跳过，别白发一次网络请求
        if (!symbolVisible || symbol.isBlank()) {
            return@LaunchedEffect
        }

        val cat = detectedCategory
        if (cat == null || cat == CodeCategory.UNKNOWN) {
            if (symbol.length >= 4 && selectedType == AssetType.STOCK) {
                // 无法识别但用户选了股票，尝试用股票API兜底
                isLoading = true
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

    // 校验结果由纯函数统一出，字段红字与「能否提交」读同一份，不可能互相打架
    val errors by remember {
        derivedStateOf {
            validateAssetInput(
                selectedType = selectedType,
                name = name,
                symbol = symbol,
                account = account,
                balance = balance,
                purchasePrice = purchasePrice,
                interestRate = interestRate
            )
        }
    }

    // 「碰过」的字段才报错。否则一打开弹窗，空的必填项全红，
    // 用户还没输入就被判错，体验等于打了脸。
    var touched by remember { mutableStateOf(setOf<String>()) }
    fun markTouched(field: String) { touched = touched + field }

    val allFields = setOf("name", "account", "balance", "symbol", "price", "rate")
    /** 只在该字段已经被碰过时显示红字 */
    fun errorFor(field: String, error: FieldError?): String? =
        if (field in touched) error?.message else null

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
                AssetFormHeader(title = "添加资产", onDismiss = onDismiss)

                // 内容区域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
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
                        error = errorFor("name", errors.name),
                        focusRequester = nameFocus,
                        onNext = { focusAfter("name") }
                    )

                    if (symbolVisible) {
                        AssetSymbolField(
                            symbol = symbol,
                            onSymbolChange = { symbol = it; markTouched("symbol") },
                            selectedType = selectedType,
                            isLoading = isLoading,
                            error = errorFor("symbol", errors.symbol),
                            focusRequester = symbolFocus,
                            onNext = { focusAfter("symbol") }
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
                        error = errorFor("account", errors.account),
                        focusRequester = accountFocus,
                        onNext = { focusAfter("account") }
                    )

                    // 交易信息
                    DialogSectionHeader("交易信息")

                    AssetBalanceField(
                        balance = balance,
                        onBalanceChange = {
                            balance = sanitizeDecimalInput(it)
                            markTouched("balance")
                        },
                        selectedType = selectedType,
                        error = errorFor("balance", errors.balance),
                        focusRequester = balanceFocus,
                        onNext = { focusAfter("balance") }
                    )

                    if (!isDeposit) {
                        val autoPrice = when {
                            fundInfo?.price != null && fundInfo?.price ?: 0.0 > 0 -> fundInfo?.price
                            stockInfo?.price != null -> stockInfo?.price
                            else -> null
                        }
                        AssetPriceField(
                            purchasePrice = purchasePrice,
                            onPriceChange = {
                                purchasePrice = sanitizeDecimalInput(it)
                                markTouched("price")
                            },
                            autoPrice = autoPrice,
                            error = errorFor("price", errors.price),
                            focusRequester = priceFocus,
                            onNext = { focusAfter("price") }
                        )
                    }

                    if (isDeposit) {
                        AssetInterestRateField(
                            interestRate = interestRate,
                            onRateChange = {
                                interestRate = sanitizeDecimalInput(it)
                                markTouched("rate")
                            },
                            error = errorFor("rate", errors.rate),
                            focusRequester = rateFocus,
                            onNext = { focusAfter("rate") }
                        )
                    }

                    // 备注
                    DialogSectionHeader("备注")
                    AssetNotesField(
                        notes = notes,
                        onNotesChange = { notes = it },
                        focusRequester = notesFocus,
                        onDone = { focusManager.clearFocus() }
                    )
                }

                // 底部按钮
                AssetFormActions(
                    onDismiss = onDismiss,
                    onConfirmClick = {
                        if (isSaving) return@AssetFormActions
                        // 点保存时把所有字段标记为"已碰过"：否则按钮直接灰着、
                        // 界面上却没有一处红字，用户不知道该改哪一项。
                        touched = allFields
                        if (!errors.isValid) {
                            saveError = errors.summary
                            // 滚到第一个出错的字段：requestFocus 会让 TextField 进入可视区，
                            // 否则标红的那一项很可能还在滚动区外，用户压根看不见。
                            errors.firstField?.let { focusField(it) }
                            return@AssetFormActions
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
                            return@AssetFormActions
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
