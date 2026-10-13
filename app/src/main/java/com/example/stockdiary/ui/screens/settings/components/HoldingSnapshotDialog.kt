package com.example.stockdiary.ui.screens.settings.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.data.HoldingSnapshotService
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.theme.LossRed
import com.example.stockdiary.ui.theme.ProfitGreen
import com.example.stockdiary.ui.viewmodels.HoldingSnapshotViewModel
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format

/**
 * 持仓导入全屏 Dialog：选图/选文件 → 预览核对 → 完成。
 *
 * 两种来源共用这一个 Dialog：差别只在第一步怎么选、以及预览页要不要显示净值。
 * 不另开一个 Dialog 是刻意的——孪生页面必然各犯一遍同样的 bug，
 * 这个项目已经为这事返工过一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingSnapshotDialog(
    container: AppContainer,
    source: HoldingSnapshotViewModel.Source,
    onDismiss: () -> Unit
) {
    // key 必须带 source：否则从「导入持仓截图」切到「导入场外基金」时，
    // 会复用上一个 Dialog 的 ViewModel，带着上一次的预览和账户名
    val viewModel: HoldingSnapshotViewModel = viewModel(
        key = source.name,
        factory = HoldingSnapshotViewModel.Factory(container, source)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ViewModel 挂在宿主 Activity 作用域上，关掉对话框后实例还活着——不复位会残留上次的预览
    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onImageSelected(uri) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onFileSelected(uri) }

    val isFund = source == HoldingSnapshotViewModel.Source.FUND_EXPORT

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
                                HoldingSnapshotViewModel.Step.SELECT_IMAGE ->
                                    if (isFund) "导入场外基金" else "导入持仓截图"
                                HoldingSnapshotViewModel.Step.PREVIEW ->
                                    if (isFund) "核对场外基金" else "核对持仓"
                                HoldingSnapshotViewModel.Step.COMMITTING -> "正在导入…"
                                HoldingSnapshotViewModel.Step.RESULT -> "导入完成"
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
                    if (state.step == HoldingSnapshotViewModel.Step.PREVIEW) {
                        SnapshotBottomBar(state, viewModel)
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (state.step) {
                        HoldingSnapshotViewModel.Step.SELECT_IMAGE ->
                            SelectSnapshotStep(state) {
                                if (isFund) {
                                    fileLauncher.launch(XLSX_MIME)
                                } else {
                                    imageLauncher.launch("image/*")
                                }
                            }

                        HoldingSnapshotViewModel.Step.PREVIEW ->
                            SnapshotPreviewStep(state, viewModel)

                        HoldingSnapshotViewModel.Step.COMMITTING -> SnapshotCommittingStep()

                        HoldingSnapshotViewModel.Step.RESULT -> SnapshotResultStep(state, viewModel)
                    }
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("没能导入", fontWeight = FontWeight.Bold) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("知道了") }
            }
        )
    }
}

/**
 * xlsx 的 MIME。这里刻意不用「任意类型」：
 * 用户选错文件（比如选成 CSV）时，解析器会报「没找到基金代码这一列」，
 * 比静默读出 0 条要好，但先挡一道更省事。
 */
private const val XLSX_MIME =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

// ---------------- 第一步：选图 / 选文件 ----------------

@Composable
private fun SelectSnapshotStep(
    state: HoldingSnapshotViewModel.UiState,
    onPick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (state.recognizing) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                "正在读取文件…",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "通常只要一瞬间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Icon(
                Icons.Default.Upload,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("选择导出的文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "在基金E账户的「公募基金查询」页点右上角导出，得到的就是这个文件。" +
                    "字段齐全，比截图准得多。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPick) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("选择文件")
            }
            if (state.knownAccounts.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "已有账户：${state.knownAccounts.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------- 第二步：预览核对 ----------------

@Composable
private fun SnapshotPreviewStep(
    state: HoldingSnapshotViewModel.UiState,
    viewModel: HoldingSnapshotViewModel
) {
    val preview = state.preview ?: return
    val focusManager = LocalFocusManager.current

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
                        "共读到 ${preview.items.size} 条持仓",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("新增 ${preview.newItems.size} · 覆盖 ${preview.updatedItems.size} · ")
                            append("未变 ${preview.unchangedItems.size}")
                            if (preview.conflictItems.isNotEmpty()) {
                                append(" · 待你处理 ${preview.conflictItems.size}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    preview.dataDate?.let { date ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "数据日期 $date",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (preview.items.any { it.nav != null }) {
                            "净值会一并写入，市值立刻可见。" +
                                "覆盖只改数量，不会动你已经整理过的名称和账户。"
                        } else {
                            "覆盖只改数量，不会动你已经整理过的名称和账户。" +
                                "导入后市值要等行情同步完成才会显示。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 没读懂的行单独提示：多半是截图被裁在中间，让用户知道少了什么而不是静默丢
        if (preview.incomplete.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = LossRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "有 ${preview.incomplete.size} 处没读懂",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                preview.incomplete.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "通常是截图被裁掉了半截，补一张完整的再导一次。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                    placeholder = { Text("不填则记为「默认账户」") },
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
            SnapshotItemRow(item, preview.dataDate)
        }
    }
}

@Composable
private fun SnapshotItemRow(
    item: HoldingSnapshotService.SnapshotItem,
    dataDate: String?
) {
    val (statusText, statusColor) = when (item.status) {
        HoldingSnapshotService.ItemStatus.NEW -> "新增" to ProfitGreen
        HoldingSnapshotService.ItemStatus.UPDATE ->
            "覆盖 ${item.existing?.balance?.format(2)} → ${item.quantity.format(2)}" to
                MaterialTheme.colorScheme.tertiary
        HoldingSnapshotService.ItemStatus.UNCHANGED -> "未变" to MaterialTheme.colorScheme.onSurfaceVariant
        // 库里同代码有多条，不敢替用户决定改哪一条
        HoldingSnapshotService.ItemStatus.CONFLICT -> "待你处理" to LossRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Medium)
                    // 显示截图上的裸码：库里存的是 sh601988 这种带市场前缀的形态，
                    // 直接拿它给用户对照截图，会让人以为代码读错了
                    Text(
                        StockCodeManager.extractPureCode(item.symbol),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    // 数量给完整数字。截图里写的是「1.44万」，
                    // 这里必须让用户看见 14400 本身——差一万倍就藏在这一步
                    val shown = formatQuantity(item.quantity)
                    Text(shown, fontWeight = FontWeight.Bold)
                    if (item.rawQuantity != shown) {
                        Text(
                            "原文 ${item.rawQuantity}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 净值只有基金E账户的导出有。不给的话用户无从判断市值是怎么算出来的
                    item.nav?.let { nav ->
                        val stale = item.navDate != null && item.navDate != dataDate
                        Text(
                            if (stale) {
                                // 净值日期比数据日期旧：实测 QDII 就会这样（基金账户 T-3，
                                // QDII 的净值还要再滞后）。不标出来用户会以为全是同一天的数
                                "净值 ${formatNav(nav)}（${item.navDate}，滞后）"
                            } else {
                                "净值 ${formatNav(nav)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stale) LossRed else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 同代码多来源（不同券商托管）时说明一下，否则用户会奇怪数量怎么比截图某一行大
                if (item.sources.size > 1) {
                    Text(
                        "由 ${item.sources.size} 条合并（${item.sources.joinToString(" + ") { it.quantity.format(0) }}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.width(0.dp))
                }
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
            // 冲突必须说清楚为什么跳过：什么都不写的话，用户只会看到
            // 「这行没进去」，以为是解析失败
            if (item.status == HoldingSnapshotService.ItemStatus.CONFLICT) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "库里有 ${item.matchedAccounts.size} 条同名持仓（" +
                        item.matchedAccounts.joinToString("、") +
                        "），这张快照只有一个总数，分不清该改哪一条 → 已跳过，" +
                        "请在持仓页手动合并或删除多余的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 持仓数量的显示口径：整数就整着写，有小数才带两位（基金份额可能是小数）。 */
private fun formatQuantity(value: Double): String =
    value.format(if (value % 1.0 == 0.0) 0 else 2)

/**
 * 净值的显示口径：**四位小数**。
 * 基金的净值精度是 4 位（如 1.1663），按 2 位显示会看不出差别，
 * 而「净值一模一样」恰恰是判断有没有读错列的最直接依据。
 */
private fun formatNav(value: Double): String = value.format(4)

@Composable
private fun SnapshotBottomBar(
    state: HoldingSnapshotViewModel.UiState,
    viewModel: HoldingSnapshotViewModel
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
                Text("重选")
            }
            Button(
                onClick = viewModel::commit,
                enabled = (preview?.pendingCount ?: 0) > 0,
                modifier = Modifier.weight(1f)
            ) {
                val count = preview?.pendingCount ?: 0
                Text(if (count > 0) "导入 $count 条" else "没有需要导入的")
            }
        }
    }
}

// ---------------- 第三 / 四步 ----------------

@Composable
private fun SnapshotCommittingStep() {
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
private fun SnapshotResultStep(
    state: HoldingSnapshotViewModel.UiState,
    viewModel: HoldingSnapshotViewModel
) {
    val result = state.result
    // 没有可查行情的持仓（理论上不会走到这——截图里全是证券）就不给按钮，
    // 免得点了什么也没发生
    val canSync = state.preview?.items?.isNotEmpty() == true

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
            "新增 ${result?.inserted ?: 0} 条 · 覆盖 ${result?.updated ?: 0} 条 · " +
                "未变 ${result?.skipped ?: 0} 条",
            style = MaterialTheme.typography.bodyLarge
        )
        // 跳过不是「没做」，是「不敢替你做」。不报出来就等于静默丢数据
        val conflicts = result?.conflicts ?: 0
        if (conflicts > 0) {
            Spacer(Modifier.height(8.dp))
            Row {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = LossRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "有 $conflicts 条因为库里同名持仓不唯一而跳过，" +
                        "请在持仓页手动处理后再导入一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "持仓截图里没有价格信息，参考单价留空是为了不凭空造出市值。" +
                "点下面的按钮拉一次行情，市值才会显示出来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (canSync) {
            Spacer(Modifier.height(20.dp))
            if (state.syncing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.syncMessage ?: "正在同步行情…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                OutlinedButton(onClick = viewModel::syncPrices) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("同步行情")
                }
                // 同步完的结果留在原地，用户看得见成功几条、失败几条
                state.syncMessage?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
