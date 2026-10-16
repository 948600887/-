package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.importer.estimatedValueCny
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.dialogs.sanitizeDecimalInput
import com.example.stockdiary.ui.theme.ProfitGreen
import com.example.stockdiary.ui.viewmodels.BinanceImportViewModel
import com.example.stockdiary.utils.format

/**
 * 同步币安资产全屏 Dialog：填密钥 → 拉取 → 预览核对 → 完成。
 *
 * 镜像 [HoldingSnapshotDialog] 的结构与状态机，区别只在第一步是填 API 密钥
 * 而不是选文件。孪生页面刻意共用一套布局套路，减少「两边各犯一遍同样的 bug」。
 *
 * ## 只读与安全提示
 *
 * 界面明确告诉用户：要的是**只读**密钥、App 不会动你的币、密钥只存本地。
 * 这不是免责声明——是让用户敢用这个功能的前提。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinanceImportDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val viewModel: BinanceImportViewModel = viewModel(
        factory = BinanceImportViewModel.Factory(container)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ViewModel 挂在宿主 Activity 作用域上，关掉后实例还活着——不复位会残留上次的预览
    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            val title = when (state.step) {
                                BinanceImportViewModel.Step.INPUT -> "同步币安资产"
                                BinanceImportViewModel.Step.FETCHING -> "正在拉取…"
                                BinanceImportViewModel.Step.PREVIEW -> "核对币安持仓"
                                BinanceImportViewModel.Step.COMMITTING -> "正在导入…"
                                BinanceImportViewModel.Step.RESULT -> "导入完成"
                            }
                            Text(title, fontWeight = FontWeight.Bold)
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                    )
                },
                bottomBar = {
                    if (state.step == BinanceImportViewModel.Step.PREVIEW) {
                        BinanceBottomBar(state, viewModel)
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (state.step) {
                        BinanceImportViewModel.Step.INPUT ->
                            BinanceInputStep(state = state, viewModel = viewModel)

                        BinanceImportViewModel.Step.FETCHING -> BinanceFetchingStep()

                        BinanceImportViewModel.Step.PREVIEW ->
                            BinancePreviewStep(state, viewModel)

                        BinanceImportViewModel.Step.COMMITTING -> BinanceCommittingStep()

                        BinanceImportViewModel.Step.RESULT ->
                            BinanceResultStep(state)
                    }
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("没能同步", fontWeight = FontWeight.Bold) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("知道了") }
            }
        )
    }
}

// ---------------- 第一步：填密钥 ----------------

@Composable
private fun BinanceInputStep(
    state: BinanceImportViewModel.UiState,
    viewModel: BinanceImportViewModel
) {
    val focusManager = LocalFocusManager.current
    // Secret 默认掩码；这个显隐只在本地决定，不进 ViewModel（同 HoldingSnapshot 的识别原文思路）
    var secretVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 只读 + 安全说明。放在最上面，让用户填密钥前先知道这是干嘛的、密钥会去哪
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Row(modifier = Modifier.padding(12.dp)) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "只读拉取你的币安现货持仓，按代码合并进加密资产。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "App 只用它查询余额，不能下单、不能提币。密钥只存在本机，" +
                            "请使用开启了「读取」权限、关闭了「交易/提币」权限的 API Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (state.fetching) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在拉取账户余额…", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "通常只要一两秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.apiSecret,
                onValueChange = viewModel::onApiSecretChange,
                label = { Text("API Secret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                // Secret 掩码显示，并可临时显形核对（币安 Secret 很长，抄错一位就签名失败）
                visualTransformation = if (secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(
                            if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (secretVisible) "隐藏 Secret" else "显示 Secret"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.thresholdText,
                // 只留数字与一个小数点：挡掉键盘能打出来但无意义的输入
                onValueChange = { viewModel.onThresholdChange(sanitizeDecimalInput(it)) },
                label = { Text("零碎资产阈值（¥）") },
                placeholder = { Text("10") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                supportingText = {
                    Text(
                        "忽略市值低于该金额（人民币）的资产，默认 ¥10；填 0 表示不过滤",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::fetch,
                enabled = state.canFetch,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("拉取持仓")
            }

            if (state.apiKey.isBlank() || state.apiSecret.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "请填写 API Key 与 Secret 后拉取。已保存过的密钥会在进入时自动填好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// ---------------- 拉取中 ----------------

@Composable
private fun BinanceFetchingStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("正在拉取…", style = MaterialTheme.typography.bodyLarge)
    }
}

// ---------------- 第二步：预览核对 ----------------

@Composable
private fun BinancePreviewStep(
    state: BinanceImportViewModel.UiState,
    viewModel: BinanceImportViewModel
) {
    val preview = state.preview ?: return
    val focusManager = LocalFocusManager.current
    // 同一份 priceOf 既用于「是否被过滤」也用于每行市值显示
    val priceOf: (String) -> Double? = { state.prices[it] }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "共读到 ${preview.items.size} 项持仓",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("新增 ${preview.newItems.size} · 覆盖 ${preview.updatedItems.size} · ")
                            append("未变 ${preview.unchangedItems.size}")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "覆盖只改数量，不会动你已经整理过的名称和账户。" +
                            "新资产的现价留空，导入后要点「持仓」页的刷新才会显示市值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 有资产被市值阈值挡掉时明确告知数量——静默丢持仓最伤信任
        if (state.filteredCount > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已过滤 ${state.filteredCount} 个市值低于 " +
                                "¥${state.minValueCny.toInt()} 的零碎资产",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Column {
                Text("导入到账户", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.account,
                    onValueChange = viewModel::onAccountChange,
                    label = { Text("账户名") },
                    placeholder = { Text("不填则记为「币安」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    supportingText = {
                        Text(
                            "只用于新建的持仓；已存在的持仓保持它原来的账户不变",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
                if (state.knownAccounts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.knownAccounts.take(4).forEach { name ->
                            SuggestionChip(
                                onClick = { viewModel.onAccountChange(name) },
                                label = { Text(name, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "逐条核对",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(preview.items, key = { it.symbol }) { item ->
            BinancePreviewItemRow(item, priceOf)
        }
    }
}

@Composable
private fun BinancePreviewItemRow(
    item: BinanceImportService.PreviewItem,
    priceOf: (String) -> Double?
) {
    val (statusText, statusColor) = when (item.status) {
        BinanceImportService.ItemStatus.NEW -> "新增" to ProfitGreen
        BinanceImportService.ItemStatus.UPDATE ->
            "覆盖 ${item.existing?.balance?.format(2) ?: 0.0.format(2)} → ${item.quantity.format(2)}" to
                MaterialTheme.colorScheme.tertiary
        BinanceImportService.ItemStatus.UNCHANGED -> "未变" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 估算人民币市值：拿不到价显示「—」（这类资产会被保留，不会被过滤）
    val valueCny = estimatedValueCny(item.name, item.quantity, priceOf)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // name 是基础资产名（BTC），symbol 是交易对（BTCUSDT）——
                // 两个都显示：前者好认，后者是真正落库/取价的代码
                Text(item.name, fontWeight = FontWeight.Medium)
                Text(
                    item.symbol,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(item.quantity.format(4), fontWeight = FontWeight.Bold)
                Text(
                    valueCny?.let { "≈ ¥${it.format(2)}" } ?: "市值 —",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
        }
    }
}

@Composable
private fun BinanceBottomBar(
    state: BinanceImportViewModel.UiState,
    viewModel: BinanceImportViewModel
) {
    val preview = state.preview
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::reset,
                modifier = Modifier.weight(1f)
            ) {
                Text("重填")
            }
            Button(
                onClick = viewModel::commit,
                enabled = (preview?.pendingCount ?: 0) > 0,
                modifier = Modifier.weight(1f)
            ) {
                val count = preview?.pendingCount ?: 0
                Text(if (count > 0) "导入 $count 项" else "没有需要导入的")
            }
        }
    }
}

// ---------------- 第三 / 四步 ----------------

@Composable
private fun BinanceCommittingStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("正在写入…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BinanceResultStep(state: BinanceImportViewModel.UiState) {
    val result = state.result
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = ProfitGreen
        )
        Spacer(Modifier.height(16.dp))
        Text("导入完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "新增 ${result?.inserted ?: 0} 项 · 覆盖 ${result?.updated ?: 0} 项 · " +
                "未变 ${result?.skipped ?: 0} 项",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "新导入的加密货币现价留空，去「持仓」页刷新一次行情就会显示市值。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
