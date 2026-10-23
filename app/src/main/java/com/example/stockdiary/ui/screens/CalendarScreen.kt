package com.example.stockdiary.ui.screens

import com.example.stockdiary.data.dividend.DividendRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.screens.calendar.components.CalendarCard
import com.example.stockdiary.ui.components.FireCalculationDialog
import com.example.stockdiary.ui.screens.calendar.components.DividendDayDetail
import com.example.stockdiary.ui.screens.calendar.components.StatColumn
import com.example.stockdiary.ui.screens.calendar.components.TabButton
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.ui.viewmodels.CalendarViewModel
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.profitColor
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    onNavigateToAssets: () -> Unit = {}
) {
    val container = LocalAppContainer.current
    val viewModel: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val assets = uiState.assets
    val calendarData = uiState.calendarData
    val dividendDates = uiState.dividendDates
    val dividends = uiState.dividends
    val selectedDate = uiState.selectedDate
    val isLoading = uiState.isLoading
    val errorMessage = uiState.errorMessage
    val monthlyExpense = uiState.monthlyExpense
    val realAnnualIncome = uiState.realAnnualIncome

    val dividendWarning by viewModel.dividendWarning.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 天天基金限流提示：手动刷新撞 514 时，部分场外基金分红没抓全，给个非阻塞 Snackbar + 可稍后重试。
    // 与下面的 errorMessage（ErrorMessagCard，代表「同步失败」）分开，避免混淆。
    LaunchedEffect(dividendWarning) {
        dividendWarning?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDividendWarning()
        }
    }

    var showFireDialog by remember { mutableStateOf(false) }

    // 空状态：无资产
    if (!isLoading && assets.isEmpty()) {
        EmptyCalendarContent(
            modifier = modifier,
            onNavigateToAssets = onNavigateToAssets
        )
        return
    }

    // 首次加载：日历数据还没回来，先给进度条，别让用户对着一张全空的日历猜
    if (isLoading && calendarData.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Snackbar 必须由 Scaffold 固定托管；放进 verticalScroll 会滚到页面底部且撑高内容。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = AppTheme.colors.background,
        contentColor = AppTheme.colors.onBackground
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top
        ) {
            // 切月份/切 Tab 时的进度：数据要重新算，不给反馈会像卡住了
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            CalendarHeader(
                selectedTab = uiState.selectedTab,
                onTabSelected = { viewModel.selectTab(it) },
                onFireClick = { showFireDialog = true }
            )

            CalendarCard(
                currentMonth = uiState.currentMonth,
                onMonthChange = { viewModel.selectMonth(it) },
                calendarData = calendarData,
                dividendDates = dividendDates,
                selectedDate = selectedDate,
                onDateSelected = { viewModel.selectDate(it) },
                isAssetCalendar = uiState.selectedTab == "资产日历"
            )

            // 选中日期详情
            if (selectedDate != null) {
                SelectedDateDetail(
                    date = selectedDate,
                    value = calendarData[selectedDate] ?: 0.0,
                    isAssetCalendar = uiState.selectedTab == "资产日历",
                    dividends = dividends,
                    assets = assets
                )
            }

            // 月度汇总：刷新时保持占位并置灰，避免每次切月都整块消失再出现（闪烁）
            MonthlySummary(
                isAssetCalendar = uiState.selectedTab == "资产日历",
                calendarData = calendarData,
                assetCount = assets.size,
                modifier = Modifier.alpha(if (isLoading) 0.5f else 1f)
            )

            if (errorMessage != null) {
                ErrorMessageCard(
                    message = errorMessage,
                    onDismiss = { viewModel.clearError() }
                )
            }
        }
    }

    if (showFireDialog) {
        FireCalculationDialog(
            assets = assets,
            realAnnualIncome = realAnnualIncome,
            monthlyExpense = monthlyExpense,
            onDismiss = { showFireDialog = false },
            onExpenseChange = { viewModel.updateMonthlyExpense(it) }
        )
    }
}

@Composable
private fun EmptyCalendarContent(
    modifier: Modifier = Modifier,
    onNavigateToAssets: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        // 使用 Material 图标而非平台相关 emoji，保证不同设备上的视觉与无障碍语义一致。
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint = AppTheme.colors.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("暂无资产", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Text("请先在资产页面添加股票、基金或银行存款", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onNavigateToAssets,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppTheme.colors.primary,
                contentColor = AppTheme.colors.onPrimary
            )
        ) {
            Text("去添加资产")
        }
    }
}

@Composable
private fun CalendarHeader(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    onFireClick: () -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "日历",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onSurface
            )
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                color = AppTheme.colors.surfaceContainer
            ) {
                Row {
                    TabButton(
                        text = "资产日历",
                        isSelected = selectedTab == "资产日历",
                        onClick = { onTabSelected("资产日历") }
                    )
                    TabButton(
                        text = "收支日历",
                        isSelected = selectedTab == "收支日历",
                        onClick = { onTabSelected("收支日历") }
                    )
                }
            }

            IconButton(onClick = onFireClick) {
                Icon(
                    imageVector = Icons.Filled.Calculate,
                    contentDescription = "FIRE 计算器",
                    tint = AppTheme.colors.primary
                )
            }
        }
    }
}

private data class DividendAssetSignature(
    val symbol: String?,
    val name: String,
    val type: AssetType,
    val balance: Double,
    val currency: String
)

@Composable
private fun SelectedDateDetail(
    date: LocalDate,
    value: Double,
    isAssetCalendar: Boolean,
    dividends: List<Pair<String, List<DividendRecord>>>,
    assets: List<Asset>
) {
    // Room 会因实时价变化频繁发出新 Asset 列表，但分红详情只依赖以下五个字段。
    // 先按 UI 字段生成签名，让价格/同步时间变化时沿用旧 Map，避免整表 associateBy。
    val assetSignature = remember(assets) {
        assets.map {
            DividendAssetSignature(
                symbol = it.symbol,
                name = it.name,
                type = it.type,
                balance = it.balance,
                currency = it.currency
            )
        }
    }
    val assetMap = remember(assetSignature) { assets.associateBy { it.symbol } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${date.monthValue}月${date.dayOfMonth}日",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.onSurface
                )
                Text(
                    text = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA),
                    fontSize = 14.sp,
                    color = AppTheme.colors.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isAssetCalendar) {
                Text("当日净资产", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (value > 0) "¥${value.format(2)}" else "暂无数据",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (value > 0) AppTheme.colors.onSurface else AppTheme.colors.onSurfaceVariant
                )
            }

            DividendDayDetail(
                date = date,
                dividends = dividends,
                assetMap = assetMap
            )
        }
    }
}

private data class MonthlyAggregates(
    val snapshotDays: Int,
    val noDataDays: Int,
    val latestValue: Double,
    val monthlyTotal: Double,
    val positiveDays: Int,
    val zeroDays: Int
)

@Composable
private fun MonthlySummary(
    isAssetCalendar: Boolean,
    calendarData: Map<LocalDate, Double>,
    assetCount: Int,
    modifier: Modifier = Modifier
) {
    // 同一份月数据一次遍历算齐所有指标，并缓存到数据真正变化时再更新。
    val aggregates = remember(calendarData) {
        var snapshotDays = 0
        var noDataDays = 0
        var latestDate: LocalDate? = null
        var latestValue = 0.0
        var monthlyTotal = 0.0
        var positiveDays = 0
        var zeroDays = 0

        calendarData.forEach { (date, value) ->
            if (value > 0.0) {
                snapshotDays++
                positiveDays++
                val previousLatestDate = latestDate
                if (previousLatestDate == null || date > previousLatestDate) {
                    latestDate = date
                    latestValue = value
                }
            }
            if (value == 0.0) {
                noDataDays++
                zeroDays++
            } else {
                monthlyTotal += value
            }
        }
        MonthlyAggregates(
            snapshotDays = snapshotDays,
            noDataDays = noDataDays,
            latestValue = latestValue,
            monthlyTotal = monthlyTotal,
            positiveDays = positiveDays,
            zeroDays = zeroDays
        )
    }
    val snapshotDays = aggregates.snapshotDays
    val noDataDays = aggregates.noDataDays
    val latestValue = aggregates.latestValue
    val monthlyTotal = aggregates.monthlyTotal
    val positiveDays = aggregates.positiveDays
    val zeroDays = aggregates.zeroDays

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (isAssetCalendar) "本月资产总览" else "本月分红汇总",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    isAssetCalendar && latestValue > 0 -> "¥${latestValue.format(2)}"
                    monthlyTotal >= 0 -> "+¥${monthlyTotal.format(2)}"
                    else -> "-¥${(-monthlyTotal).format(2)}"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (isAssetCalendar) AppTheme.colors.onSurface
                else if (monthlyTotal >= 0) AppTheme.colors.lossRed else AppTheme.colors.profitGreen
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isAssetCalendar) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
                ) {
                    StatColumn("有数据天数", snapshotDays.toString(), AppTheme.colors.primary)
                    StatColumn("无数据", noDataDays.toString(), AppTheme.colors.onSurfaceVariant)
                    StatColumn("持仓", assetCount.toString(), AppTheme.colors.primary)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
                ) {
                    StatColumn("正收益天数", positiveDays.toString(), AppTheme.colors.lossRed)
                    StatColumn("无收益", zeroDays.toString(), AppTheme.colors.onSurfaceVariant)
                    StatColumn("持仓", assetCount.toString(), AppTheme.colors.primary)
                }
            }
        }
    }
}

@Composable
private fun ErrorMessageCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onDismiss) {
                Text("关闭", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
