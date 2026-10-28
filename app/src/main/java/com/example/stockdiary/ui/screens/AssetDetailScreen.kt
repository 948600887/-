package com.example.stockdiary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.data.dividend.DividendYieldCalculator
import com.example.stockdiary.data.dividend.DividendIncomeCalculator
import com.example.stockdiary.ui.components.SingleLineText
import com.example.stockdiary.ui.dialogs.AdjustPositionDialog
import com.example.stockdiary.ui.dialogs.EditAssetDialog
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.ui.viewmodels.AssetDetailViewModel
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.formatMoney
import com.example.stockdiary.utils.CRYPTO_DECIMALS
import com.example.stockdiary.utils.formatTrim
import com.example.stockdiary.utils.pricePrecisionFor
import com.example.stockdiary.utils.shareUnitWord
import com.example.stockdiary.utils.toRelativeTime
import com.example.stockdiary.utils.totalValueCny
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    @Suppress("UNUSED_PARAMETER") assetId: Long,
    viewModel: AssetDetailViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    // 六条流已合流为 AssetDetailViewModel.AssetDetailUiState（同 Dashboard/Portfolio 模式）：
    // 一次加载/刷新改哪几个字段集中在一处，UI 只保留一条订阅。
    // 下面按原变量名取别名，页面主体逻辑不动。
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val asset = uiState.asset
    val dividends = uiState.dividends
    val isLoading = uiState.isLoading
    val isDividendLoading = uiState.isDividendLoading
    val errorMessage = uiState.errorMessage
    val isRefreshing = uiState.isRefreshing

    var showEditDialog by remember { mutableStateOf(false) }
    var showAdjustDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val shareBonus = remember(asset, dividends) {
        val assetVal = asset ?: return@remember null
        val symbol = StockCodeManager.normalizeSymbol(assetVal.symbol ?: "")
        if (symbol.isBlank()) return@remember null
        // 与首页收息进度同源：「今年已宣告优先、无则 TTM」（见 DividendYieldCalculator.getDeclaredOrTTMPerTenShares）
        DividendYieldCalculator.getDeclaredOrTTMPerTenShares(dividends)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val currentAsset = asset
                    if (currentAsset != null) {
                        // 资产名加省略号：标题栏右侧还挂着编辑/删除/刷新三个按钮，
                        // 基金名动辄二十几个字，不省略的话会一路顶到图标上。
                        // 这里不需要 weight —— TopAppBar 的 title 槽本身就被限制在
                        // 返回键与操作按钮之间的剩余宽度里，Text 拿到的是有界宽度，
                        // maxLines + Ellipsis 就能生效（title 槽不是 RowScope，写 weight 编译不过）。
                        Column {
                            SingleLineText(
                                text = currentAsset.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            currentAsset.symbol?.let {
                                Text(text = it, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant)
                            }
                        }
                    } else {
                        Text("资产详情")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val currentAsset = asset
                    if (currentAsset != null) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = AppTheme.colors.error)
                        }
                    }
                    // STOCK/FUND/CRYPTO 都有实时行情，都该能单独刷新。
                    // 加密货币不是被刻意排除：AssetSyncManager.syncSingleAsset 有完整的
                    // CRYPTO 分支（币安取价 + 主机熔断 + LD 按底层 1:1 计价）。
                    // 银行存款没有实时行情，不加刷新按钮（正确）
                    if (currentAsset != null && (currentAsset.type == AssetType.STOCK || currentAsset.type == AssetType.FUND || currentAsset.type == AssetType.CRYPTO)) {
                        IconButton(
                            onClick = { viewModel.refreshData() },
                            enabled = !isRefreshing
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                            }
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = AppTheme.colors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(errorMessage ?: "", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadAssetData() }) {
                        Text("重试")
                    }
                }
            }
        } else {
            val a = asset ?: return@Scaffold
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 基本信息卡片
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("基本信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                AssistChip(
                                    onClick = { },
                                    enabled = false,
                                    label = { Text(a.type.displayName, fontSize = 11.sp) }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            DetailRow("账户", a.account)
                            val holdingText = when {
                                a.type == AssetType.STOCK -> a.balance.format(0)
                                a.type == AssetType.CRYPTO -> a.balance.formatTrim(CRYPTO_DECIMALS)
                                else -> a.balance.format(2)
                            }
                            DetailRow("持仓", "$holdingText${if (a.type == AssetType.STOCK) "股" else "份"}")
                            // 价格/市值都带 ¥ 符号，那就必须是人民币口径：
                            // 原币价格要按资产币种折算，不能直接套 ¥
                            a.currentPrice?.let { price ->
                                // 精度与持仓页、加减仓弹窗共用同一套判定（utils.pricePrecisionFor），
                                // 以前这里内联写了一遍，三处各写一份必然漂移
                                DetailRow(
                                    "当前价",
                                    (if (a.type == AssetType.CRYPTO)
                                        "¥" + ExchangeRateManager.toCny(price, a.currency).formatTrim(CRYPTO_DECIMALS)
                                    else
                                        ExchangeRateManager.toCny(price, a.currency)
                                            .formatMoney(digits = pricePrecisionFor(a.type, a.symbol))) + syncStamp(a.lastSyncTime)
                                )
                            }
                            DetailRow("当前市值", a.totalValueCny.formatMoney())
                            a.interestRate?.let { rate ->
                                if (rate > 0) DetailRow("年利率", "${rate.format(2)}%")
                            }
                            // 到期日：存款/贷款/信用卡等固定期限资产才有，有值就显示
                            a.dueDate?.let { due ->
                                DetailRow("到期日", due)
                            }
                        }
                    }
                }

                // 分红信息（仅股票/基金）
                if (a.type == AssetType.STOCK || a.type == AssetType.FUND) {
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("分红信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                if (isDividendLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                } else if (dividends.isEmpty()) {
                                    Text("暂无分红数据", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
                                } else {
                                    val ttm = shareBonus
                                    if (ttm != null) {
                                        // 口径与首页「今年预计」同源：今年已宣告优先、无则 TTM
                                        DetailRow("预计每十${a.shareUnitWord}分红", ttm.formatMoney())
                                        // 股息率与上面展示的每十股分红同源：用同一个 per-ten-shares 口径算，
                                        // 不再单独走 TTM 窗口，否则「每十股分红」与「股息率」两套尺子对不上
                                        val price = a.currentPrice ?: a.purchasePrice
                                        DetailRow(
                                            "股息率",
                                            if (price > 0) "${((ttm / 10.0 / price) * 100).format(2)}%" else "—"
                                        )
                                        val annualIncome = DividendIncomeCalculator.calculateAnnualIncome(a, ttm)
                                        DetailRow("年化收益", annualIncome.formatMoney())
                                    }
                                    DetailRow("分红次数", "${dividends.size}次")
                                }
                            }
                        }
                    }
                }

                // 操作按钮。
                // 放开 CRYPTO：以前只对 STOCK/FUND 显示，想改加密货币持仓只能进「编辑资产」
                // 手改总数——那样既不会生成交易流水，参考单价也不会按余额加权摊薄，
                // 等于绕开了整条加减仓链路。AssetSyncManager.syncSingleAsset 本来就有完整的
                // CRYPTO 分支（见上方刷新按钮的注释），这里没理由再拦一道。
                // 银行存款确实没有"加仓/减仓"语义（本金直接改），继续不显示。
                if (a.type == AssetType.STOCK || a.type == AssetType.FUND || a.type == AssetType.CRYPTO) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { showEditDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary)
                            ) {
                                Text("编辑资产")
                            }
                            Button(
                                // 加仓减仓此前只有弹窗没有入口，整条链路（含参考价摊薄与流水记录）
                                // 一直处于「写好了但谁也点不到」的状态。
                                onClick = { showAdjustDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("加仓 / 减仓")
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑弹窗
    val currentAsset = asset
    if (showEditDialog && currentAsset != null) {
        EditAssetDialog(
            asset = currentAsset,
            onDismiss = { showEditDialog = false },
            onConfirm = { updatedAsset ->
                // 同 Dashboard/Portfolio：保存失败也要关闭弹窗并提示，
                // 不能把用户留在一个按钮永久转圈、又没有其它出口的对话框里
                scope.launch {
                    runCatching { viewModel.updateAsset(updatedAsset) }
                        .onFailure {
                            AppLogger.e("更新资产失败: ${it.message}", it, "AssetDetailScreen")
                            snackbarHostState.showSnackbar("保存失败：${it.message ?: "未知错误"}")
                        }
                    showEditDialog = false
                }
            }
        )
    }

    // 加仓 / 减仓：按增量记一笔交易流水，参考单价由仓储层按余额加权摊薄
    if (showAdjustDialog && currentAsset != null) {
        AdjustPositionDialog(
            asset = currentAsset,
            onDismiss = { showAdjustDialog = false },
            onConfirm = { updatedAsset ->
                scope.launch {
                    val failure = runCatching {
                        viewModel.adjustPosition(updatedAsset)
                    }.exceptionOrNull()
                    if (failure == null) {
                        snackbarHostState.showSnackbar("已记录")
                    } else {
                        AppLogger.e("调整持仓失败: ${failure.message}", failure, "AssetDetailScreen")
                        snackbarHostState.showSnackbar("调整失败：${failure.message ?: "未知错误"}")
                    }
                    // 成败都要关：否则按钮永久转圈且没有出口
                    showAdjustDialog = false
                }
            }
        )
    }

    // 删除确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${asset?.name ?: ""}」吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        if (isDeleting) return@Button
                        isDeleting = true
                        scope.launch {
                            val failure = runCatching { viewModel.deleteAsset() }.exceptionOrNull()
                            // 无论成败都要收尾：失败时留在原页面并提示，不能卡在转圈 + 对话框不关
                            isDeleting = false
                            showDeleteDialog = false
                            if (failure == null) {
                                onNavigateBack()
                            } else {
                                AppLogger.e("删除资产失败: ${failure.message}", failure, "AssetDetailScreen")
                                snackbarHostState.showSnackbar("删除失败：${failure.message ?: "未知错误"}")
                            }
                        }
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AppTheme.colors.onError
                        )
                    } else {
                        Text("删除")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

/**
 * 行情新鲜度后缀，形如 " · 12分钟前更新"。
 *
 * 用户看到「¥12.34」时无从判断这个价是刚抓的还是三天前的——尤其页面已经给出
 * 「价格待更新 / 币安暂不可达」这类状态时，一个"多久之前"比状态词更有用。
 *
 * `lastSyncTime == 0` 表示这条资产从没同步过（手工录入、或行情一直没抓到），
 * 此时必须返回空串：toRelativeTime(0) 会算成"几十年前"，比不显示更糟。
 */
private fun syncStamp(lastSyncTime: Long): String =
    if (lastSyncTime > 0) " · ${lastSyncTime.toRelativeTime()}更新" else ""

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = AppTheme.colors.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}