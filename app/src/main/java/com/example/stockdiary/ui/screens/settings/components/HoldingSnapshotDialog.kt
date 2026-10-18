package com.example.stockdiary.ui.screens.settings.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.data.importer.HoldingSnapshotService
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.components.import.ImportAccountField
import com.example.stockdiary.ui.components.import.ImportCommitSummary
import com.example.stockdiary.ui.components.import.ImportCommittingStep
import com.example.stockdiary.ui.components.import.ImportErrorDialog
import com.example.stockdiary.ui.components.import.ImportPreviewBottomBar
import com.example.stockdiary.ui.components.import.ImportRawTextDialog
import com.example.stockdiary.ui.components.import.ImportResultStep
import com.example.stockdiary.ui.components.import.ImportWizardScaffold
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.ui.viewmodels.HoldingSnapshotViewModel
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format

/**
 * 持仓导入全屏 Dialog：选图/选文件 → 预览核对 → 完成。
 *
 * 两种来源共用这一个 Dialog：差别只在第一步怎么选、以及预览页要不要显示净值。
 * 不另开一个 Dialog 是刻意的——孪生页面必然各犯一遍同样的 bug，
 * 这个项目已经为这事返工过一次。
 *
 * 全屏外壳、错误弹窗、识别原文、写入中与结果页已抽到
 * `com.example.stockdiary.ui.components.import` 公共骨架，这里只留来源特有步骤。
 */
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

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onImageSelected(uri) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onFileSelected(uri) }

    val isFund = source == HoldingSnapshotViewModel.Source.FUND_EXPORT

    // 识别原文只在本地决定要不要显示，不进 ViewModel：它是排障用的临时视图，
    // 塞进 UiState 会让它跟着 reset() 一起被清掉——而用户正是关掉报错后才想看它
    var showRawText by remember { mutableStateOf(false) }

    // 没有可查行情的持仓（理论上不会走到这——截图里全是证券）就不给按钮，
    // 免得点了什么也没发生
    val canSync = state.preview?.items?.isNotEmpty() == true
    val syncAction: (@Composable () -> Unit)? = if (canSync) {
        { SnapshotSyncSection(state, viewModel) }
    } else {
        null
    }

    /**
     * 主按钮点下去要开哪个选择器。
     *
     * **必须按 [source] 分派，不能只看 isFund**：一码通（中国结算）只有截图这一条路，
     * 基金E账户优先走导出的 xlsx。这里刻意写成**覆盖枚举全部取值的 when 表达式**
     * 而不是 `if (isFund)`——将来加第三个来源时编译器会逼这里表态。
     *
     * 出过的事故：给基金E账户加「改用截图导入」次要按钮时，原本在主按钮里做的
     * 来源分派被挪走了，主按钮直接硬绑 `onPickFile`。于是一码通那个写着
     * 「选择截图」的按钮开的是 xlsx 表格过滤器，用户在里面根本挑不到 PNG，
     * 整条「导入持仓截图」功能对外表现为「用不了了」，而代码一行没报错。
     */
    val primaryPick: () -> Unit = when (source) {
        HoldingSnapshotViewModel.Source.STOCK_SNAPSHOT -> { { imageLauncher.launch(IMAGE_MIME) } }
        HoldingSnapshotViewModel.Source.FUND_EXPORT -> { { fileLauncher.launch(XLSX_MIME) } }
    }

    ImportWizardScaffold(
        title = when (state.step) {
            HoldingSnapshotViewModel.Step.SELECT_IMAGE ->
                if (isFund) "导入场外基金" else "导入持仓截图"
            HoldingSnapshotViewModel.Step.PREVIEW ->
                if (isFund) "核对场外基金" else "核对持仓"
            HoldingSnapshotViewModel.Step.COMMITTING -> "正在导入…"
            HoldingSnapshotViewModel.Step.RESULT -> "导入完成"
        },
        onDismiss = onDismiss,
        onReset = viewModel::reset,
        bottomBar = {
            if (state.step == HoldingSnapshotViewModel.Step.PREVIEW) {
                ImportPreviewBottomBar(
                    resetText = "重选",
                    unit = "条",
                    pendingCount = state.preview?.pendingCount ?: 0,
                    onReset = viewModel::reset,
                    onCommit = viewModel::commit
                )
            }
        }
    ) {
        when (state.step) {
            HoldingSnapshotViewModel.Step.SELECT_IMAGE ->
                SelectSnapshotStep(
                    state = state,
                    isFund = isFund,
                    onPickPrimary = primaryPick,
                    onPickImage = { imageLauncher.launch(IMAGE_MIME) },
                    onShowRawText = { showRawText = true }
                )

            HoldingSnapshotViewModel.Step.PREVIEW ->
                SnapshotPreviewStep(state, viewModel)

            HoldingSnapshotViewModel.Step.COMMITTING -> ImportCommittingStep()

            HoldingSnapshotViewModel.Step.RESULT -> ImportResultStep(
                summary = ImportCommitSummary(
                    inserted = state.result?.inserted ?: 0,
                    updated = state.result?.updated ?: 0,
                    skipped = state.result?.skipped ?: 0,
                    conflicts = state.result?.conflicts ?: 0,
                    extraNote = "持仓截图里没有价格信息，参考单价留空是为了不凭空造出市值。" +
                        "点下面的按钮拉一次行情，市值才会显示出来。"
                ),
                syncAction = syncAction
            )
        }
    }

    state.error?.let { message ->
        ImportErrorDialog(
            title = "没能导入",
            message = message,
            onDismiss = { viewModel.clearError() }
        )
    }

    // 错误提示里写了「可点『查看识别原文』，把内容发出来便于定位格式差异」——
    // 这个按钮必须真的存在，否则那句话是在指一个不存在的控件
    if (showRawText) {
        ImportRawTextDialog(
            lines = state.lines,
            onDismiss = { showRawText = false }
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

private const val IMAGE_MIME = "image/*"

// ---------------- 第一步：选图 / 选文件 ----------------

@Composable
private fun SelectSnapshotStep(
    state: HoldingSnapshotViewModel.UiState,
    isFund: Boolean,
    /** 主按钮：按来源分派，一码通开图片、基金E账户开 xlsx（见调用处的注释） */
    onPickPrimary: () -> Unit,
    /** 次要按钮「改用截图导入」：只有基金E账户有导出之外的这条退路 */
    onPickImage: () -> Unit,
    /** 打开上次识别的原文。只在识别过一次之后才出现 */
    onShowRawText: () -> Unit
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
                if (isFund) "正在读取文件…" else "正在识别截图…",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isFund) "通常只要一瞬间" else "通常需要 1~3 秒",
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
            Text(
                if (isFund) "选择导出的文件" else "选择持仓截图",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (isFund) {
                    "在基金E账户的「公募基金查询」页点右上角导出，得到的就是这个文件。" +
                        "字段齐全，比截图准得多。"
                } else {
                    "支持「中国结算 · 一码通持有查询」的持仓列表。" +
                        "每条有两行（名称在上、代码在下），超出屏幕时请用长截图，识别会自动分块。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPickPrimary) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isFund) "选择文件" else "选择截图")
            }

            // 基金E账户多给一条退路：导出功能不可用时还能靠截图。
            // 但要说清楚它不完整——实测一张长截图只装得下约 50 张卡片，
            // 而导出明细有 136 条
            if (isFund) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onPickImage) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("改用截图导入")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "截图只能装下一屏，容易漏掉后面的持仓，建议优先用导出文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // 上一次识别过才显示。错误提示里承诺了这个入口，
            // 而且它是「读不出持仓」时唯一能看出截图格式差在哪的东西
            if (state.recognizedText != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onShowRawText) {
                    Text("查看上次识别原文（${state.lines.size} 行）")
                }
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
                            tint = AppTheme.colors.lossRed,
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
            ImportAccountField(
                account = state.account,
                knownAccounts = state.knownAccounts,
                onAccountChange = viewModel::onAccountChange
            )
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
        HoldingSnapshotService.ItemStatus.NEW -> "新增" to AppTheme.colors.profitGreen
        HoldingSnapshotService.ItemStatus.UPDATE ->
            "覆盖 ${item.existing?.balance?.format(2)} → ${item.quantity.format(2)}" to
                MaterialTheme.colorScheme.tertiary
        HoldingSnapshotService.ItemStatus.UNCHANGED -> "未变" to MaterialTheme.colorScheme.onSurfaceVariant
        // 库里同代码有多条，不敢替用户决定改哪一条
        HoldingSnapshotService.ItemStatus.CONFLICT -> "待你处理" to AppTheme.colors.lossRed
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
                            color = if (stale) AppTheme.colors.lossRed else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 非人民币必须显式标出来：项目的多币种还没有界面入口，
                    // 用户看不到币种就无从判断这个市值是怎么折算的
                    item.currency?.takeIf { it != "CNY" }?.let { code ->
                        Text(
                            "结算币种 ${ExchangeRateManager.getDisplayName(code)}（$code）",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.lossRed
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

// ---------------- 第三 / 四步 ----------------

/**
 * 结果页的「同步行情」动作组（进度 / 按钮 / 结果文案）。
 *
 * 通过公共骨架 [ImportResultStep] 的 syncAction 插槽挂上去：
 * 骨架只负责「有没有这个插槽」，同步行为本身仍是持仓截图链路特有的。
 */
@Composable
private fun SnapshotSyncSection(
    state: HoldingSnapshotViewModel.UiState,
    viewModel: HoldingSnapshotViewModel
) {
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
