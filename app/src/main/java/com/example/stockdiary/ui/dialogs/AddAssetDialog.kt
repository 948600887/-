package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.stockdiary.data.repository.AssetQuoteRepository
import com.example.stockdiary.data.repository.FundQuote
import com.example.stockdiary.data.repository.StockQuote
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.components.DialogSectionHeader
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.StockCodeManager.CodeCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
    existingAccounts: List<String> = emptyList(),
    /**
     * 现有资产列表，用于检测「该账户已有同名持仓」并提示累加/覆盖语义（#2）。
     * 不阻断保存，只给一句提醒，避免用户无意识地重复建仓。
     */
    existingAssets: List<Asset> = emptyList()
) {
    // 行情/基金查询收口层（审计 H1：UI 不再直接调 network 层）
    val quotes = LocalAppContainer.current.assetQuoteRepository

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
    // 币种 / 到期日：一一对应改造新增。初始类型是证券 → 默认 CNY
    var currency by remember { mutableStateOf(defaultCurrencyForType(AssetType.STOCK)) }
    var dueDate by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var fundInfo by remember { mutableStateOf<FundQuote?>(null) }
    var stockInfo by remember { mutableStateOf<StockQuote?>(null) }
    // 用户是否手动选过类型：一旦手动选过，就不再用代码自动识别去覆盖他的选择——
    // 否则选了「股票」输入 ETF 代码会被自动改回「基金」，用户明明想手动指定却总被改（#21）。
    var userPickedType by remember { mutableStateOf(false) }
    // 用户是否手动改过币种：切类型时只在「没手动改过」时给币种默认值，否则保留用户选择（#14）。
    var currencyTouched by remember { mutableStateOf(false) }
    // 行情自动回填标记：记录最近一次行情填进 name/purchasePrice 的值。
    // 代码变化时只清「仍等于自动填值、用户没手动改过」的那一项，保留用户手填/手改的（#1/#10）。
    var autoFilledName by remember { mutableStateOf<String?>(null) }
    var autoFilledPrice by remember { mutableStateOf<String?>(null) }
    // 退出确认：表单非空时误触关闭要先确认，避免十几项白填（#8）。
    var showExitConfirm by remember { mutableStateOf(false) }

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
    val isLiability = selectedType.isLiability
    // 按类型精细化的字段可见性：
    // - 参考单价：仅证券 + 加密货币（存款恒 1、负债按 balance 计，不适用）
    // - 年利率：存款（必填）+ 贷款（可选）
    // - 到期日：存款 + 负债（信用卡/贷款）
    val showPrice = selectedType == AssetType.STOCK ||
        selectedType == AssetType.FUND ||
        selectedType == AssetType.CRYPTO
    val showRate = isDeposit || selectedType == AssetType.LOAN
    val showDueDate = isDeposit || isLiability

    /** 下一个该聚焦的字段：跳过当前类型下不可见的那些 */
    fun focusAfter(field: String) {
        when (field) {
            "name" -> if (symbolVisible) symbolFocus else accountFocus
            "symbol" -> accountFocus
            "account" -> balanceFocus
            "balance" -> when {
                showPrice -> priceFocus
                showRate -> rateFocus
                else -> notesFocus
            }
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

    /** 表单是否已填过内容：决定是否在关闭时弹「放弃填写」确认（#8） */
    fun isFormDirty(): Boolean =
        name.isNotBlank() || symbol.isNotBlank() || account.isNotBlank() ||
            balance.isNotBlank() || notes.isNotBlank() || interestRate.isNotBlank() ||
            dueDate != null

    /** 关闭请求：非空则先确认，否则直接关（#8） */
    fun requestDismiss() {
        if (isFormDirty()) showExitConfirm = true else onDismiss()
    }

    // 自动识别代码类别
    val detectedCategory by remember {
        derivedStateOf {
            if (symbol.length >= 4) StockCodeManager.classifyCode(symbol) else null
        }
    }

    /** 把行情结果回填到 name/price，仅在「字段空 或 仍等于上次自动填值」时覆盖，并记录自动填值标记（#1/#10）。 */
    fun applyAutoFill(fetchedName: String?, fetchedPrice: Double?) {
        if (fetchedName != null && fetchedName.isNotBlank()) {
            if (name.isBlank() || name == autoFilledName) {
                name = fetchedName
                autoFilledName = fetchedName
            }
        }
        if (fetchedPrice != null && fetchedPrice > 0) {
            val priceStr = fetchedPrice.toString()
            if (purchasePrice.isBlank() || purchasePrice == autoFilledPrice) {
                purchasePrice = priceStr
                autoFilledPrice = priceStr
            }
        }
    }

    // 股票/场内基金代码 → 行情回显：统一走行情门面（经 [AssetQuoteRepository]，
    // 60s TTL 内存缓存 + 单飞去重 + 多源容错），不再直连任何单个 API。
    // 返回 false 表示没取到，由调用方决定提示文案或降级路径。
    suspend fun tryStockAPI(formattedCode: String): Boolean {
        val result = quotes.getStockQuote(formattedCode)
        if (result.isSuccess) {
            val info = result.getOrNull()
            stockInfo = info
            fundInfo = null
            loadError = null
            applyAutoFill(info?.name, info?.price)
            return true
        }
        return false
    }

    // 天天基金API查询（必须先定义，因为 LaunchedEffect 中会调用）
    // 优先用 FundAPI（无 Referer 头，不会被天天基金反爬拦截），EnhancedFundAPI 兜底
    suspend fun tryFundAPI() {
        try {
            // 先用 FundAPI（与 AddStockDialog 一致，已确认可用），结果经仓库映射成 FundQuote
            val fundQuote = quotes.getFundInfo(symbol)
            if (fundQuote != null && fundQuote.name.isNotEmpty()) {
                fundInfo = fundQuote
                stockInfo = null
                loadError = null
                applyAutoFill(fundQuote.name, fundQuote.price)
                return
            }

            // FundAPI 失败，尝试带缓存的版本
            val result = quotes.getFundInfoWithCache(symbol)
            if (result.isSuccess) {
                val info = result.getOrNull()
                fundInfo = info
                stockInfo = null
                loadError = null
                if (info != null) applyAutoFill(info.name, info.price)
            } else {
                // 两个基金API都失败，走行情门面兜底（股票/场内基金行情）
                try {
                    if (!tryStockAPI(quotes.formatStockCode(symbol))) {
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

    // 自动识别并查询，带防抖（#6）：连续输入只在最后一次停下 500ms 后才真正发请求，
    // 避免「600519」逐字符触发 4~6 次网络、弱网下卡片与圈一直闪。
    LaunchedEffect(symbol, selectedType) {
        // 立即清掉旧代码的行情显示，避免残留上一条代码的卡片
        fundInfo = null
        stockInfo = null
        loadError = null

        // 存款/负债没有行情可查，切到这些类型时直接跳过，别白发一次网络请求
        if (!symbolVisible || symbol.isBlank()) {
            return@LaunchedEffect
        }

        // 防抖：连续输入时 effect 会反复重启（symbol 变化），delay 被打断不查；
        // 只有停手 500ms 后这一轮才会真正走到下面的查询。
        delay(SYMBOL_DEBOUNCE_MS)

        val cat = detectedCategory
        if (cat == null || cat == CodeCategory.UNKNOWN) {
            if (symbol.length >= 4 && selectedType == AssetType.STOCK) {
                // 无法识别但用户选了股票，走行情门面兜底
                isLoading = true
                try {
                    if (!tryStockAPI(quotes.formatStockCode(symbol))) {
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

        // 自动设置资产类型：仅当用户没手动选过类型时才根据代码自动识别，
        // 尊重用户在类型 chip 上的显式选择（#21）
        if (!userPickedType) {
            when (cat) {
                CodeCategory.STOCK -> selectedType = AssetType.STOCK
                CodeCategory.ETF, CodeCategory.LOF, CodeCategory.EXCHANGE_FUND, CodeCategory.OTC_FUND -> selectedType = AssetType.FUND
                else -> {}
            }
        }

        isLoading = true
        try {
            when (cat) {
                CodeCategory.STOCK, CodeCategory.ETF, CodeCategory.LOF, CodeCategory.EXCHANGE_FUND -> {
                    // 股票/场内基金 → 走行情门面（多源容错 + 60s TTL + 单飞）
                    if (!tryStockAPI(quotes.formatStockCode(symbol))) {
                        // 门面取价失败，尝试天天基金（可能是被误判的场外基金）
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
                interestRate = interestRate,
                // 利率必填仅存款；贷款选填（留空表示不计息）
                rateRequired = isDeposit
            )
        }
    }

    // 「碰过」的字段才报错。否则一打开弹窗，空的必填项全红，
    // 用户还没输入就被判错，体验等于打了脸。
    var touched by remember { mutableStateOf(setOf<String>()) }
    fun markTouched(field: String) {
        touched = touched + field
        // 用户改了任意字段就把底部旧汇总提示撤掉，避免「字段已修好底部还挂旧错」（#11）
        saveError = null
    }

    val allFields = setOf("name", "account", "balance", "symbol", "price", "rate")
    /** 只在该字段已经被碰过时显示红字 */
    fun errorFor(field: String, error: FieldError?): String? =
        if (field in touched) error?.message else null

    // 重复持仓提示（#2）：同 (symbol, account, type) 已存在时，提示追加/覆盖语义。
    // 仅作提醒，不阻断保存；加密货币走覆盖、其它有行情类型走累加。
    val duplicateWarning by remember(symbol, account, selectedType, existingAssets) {
        derivedStateOf {
            if (symbol.isBlank() || account.isBlank()) return@derivedStateOf null
            val normalized = when (selectedType) {
                AssetType.STOCK, AssetType.FUND -> StockCodeManager.normalizeSymbol(symbol)
                AssetType.CRYPTO -> symbol.uppercase().filter { it.isLetterOrDigit() }
                else -> symbol
            }
            val exists = existingAssets.any {
                it.symbol?.equals(normalized, ignoreCase = true) == true &&
                    it.account.equals(account.trim(), ignoreCase = true) &&
                    it.type == selectedType
            }
            if (!exists) null
            else if (selectedType == AssetType.CRYPTO) {
                "该账户已有此加密货币，保存将覆盖原持仓（不记交易）"
            } else {
                "该账户已有此持仓，保存将累加数量并记一笔买入"
            }
        }
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        // 禁用点窗外/返回键直接关闭：表单十几项填到一半误触就没了，且没有确认（#8）
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false
        )
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
                AssetFormHeader(title = "添加资产", onDismiss = { requestDismiss() })

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
                            // 用户手动选过类型：后续代码自动识别不再覆盖（#21）
                            userPickedType = true
                            // 切类型时按类型给币种一个合理默认——但只在用户没手动改过币种时
                            // 才覆盖，否则用户精心选好的 USD 点一下别的类型就被打回 CNY（#14）
                            if (!currencyTouched) currency = defaultCurrencyForType(type)
                            fundInfo = null
                            stockInfo = null
                            loadError = null
                        }
                    )

                    // 基本信息
                    DialogSectionHeader("基本信息")

                    AssetNameField(
                        name = name,
                        onNameChange = {
                            name = it
                            markTouched("name")
                            // 用户手动改了名称 → 不再是行情自动填的
                            if (name != autoFilledName) autoFilledName = null
                        },
                        error = errorFor("name", errors.name),
                        focusRequester = nameFocus,
                        onNext = { focusAfter("name") }
                    )

                    if (symbolVisible) {
                        AssetSymbolField(
                            symbol = symbol,
                            onSymbolChange = {
                                symbol = it
                                markTouched("symbol")
                                // 代码变化：清掉由行情自动填入、且用户没手动改过的名称/单价（#1）。
                                // 用户手填或手改过的值保留，避免「换代码后名称/单价还留着上一只标的」。
                                if (autoFilledName != null && name == autoFilledName) {
                                    name = ""
                                    autoFilledName = null
                                }
                                if (autoFilledPrice != null && purchasePrice == autoFilledPrice) {
                                    purchasePrice = ""
                                    autoFilledPrice = null
                                }
                            },
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

                    // 重复持仓提醒（#2）：填完代码+账户即可看到
                    duplicateWarning?.let { warning ->
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HorizontalDivider()
                            Text(
                                text = "⚠ $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    // 交易信息
                    DialogSectionHeader("交易信息")

                    AssetBalanceField(
                        balance = balance,
                        onBalanceChange = {
                            // 加密货币允许负余额（借币/欠币，Asset.kt 已放开），其它类型不允许（#12）
                            balance = sanitizeDecimalInput(it, selectedType == AssetType.CRYPTO)
                            markTouched("balance")
                        },
                        selectedType = selectedType,
                        error = errorFor("balance", errors.balance),
                        focusRequester = balanceFocus,
                        onNext = { focusAfter("balance") }
                    )

                    // 币种：所有类型都显示（决定市值换算口径，加密默认 USD）
                    AssetCurrencyField(
                        currency = currency,
                        onCurrencyChange = {
                            currency = it
                            currencyTouched = true
                        }
                    )

                    if (showPrice) {
                        val autoPrice = when {
                            fundInfo?.price != null && (fundInfo?.price ?: 0.0) > 0 -> fundInfo?.price
                            stockInfo?.price != null -> stockInfo?.price
                            else -> null
                        }
                        AssetPriceField(
                            purchasePrice = purchasePrice,
                            onPriceChange = {
                                purchasePrice = sanitizeDecimalInput(it)
                                markTouched("price")
                                // 用户手动改了价格 → 不再是自动填，提示撤掉（#10）
                                if (purchasePrice != autoFilledPrice) autoFilledPrice = null
                            },
                            autoPrice = autoPrice,
                            // 仅当价格仍等于行情自动填值时才显示"已按行情价自动填充"（#10）
                            isAutoFilled = autoFilledPrice != null && purchasePrice == autoFilledPrice,
                            error = errorFor("price", errors.price),
                            focusRequester = priceFocus,
                            onNext = { focusAfter("price") }
                        )
                    }

                    if (showRate) {
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

                    if (showDueDate) {
                        AssetDueDateField(
                            dueDate = dueDate,
                            onDueDateChange = { dueDate = it }
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
                    onDismiss = { requestDismiss() },
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
                                stockPrice = stockInfo?.price,
                                currency = currency,
                                dueDate = dueDate
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

        // 退出确认：表单已填内容时误触关闭要先确认（#8）
        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("放弃填写？") },
                text = { Text("已填写的内容将不会保存。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitConfirm = false
                            onDismiss()
                        }
                    ) { Text("放弃") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showExitConfirm = false }
                    ) { Text("继续编辑") }
                }
            )
        }
    }
}

/** 代码查询防抖时长（毫秒）：连续输入只在停手这么久后才真正发请求（#6）。 */
private const val SYMBOL_DEBOUNCE_MS = 500L
