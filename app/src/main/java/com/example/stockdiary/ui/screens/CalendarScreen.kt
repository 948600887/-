package com.example.stockdiary.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.database.Asset
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.screens.calendar.components.CalendarCard
import com.example.stockdiary.ui.components.FireCalculationDialog
import com.example.stockdiary.ui.screens.calendar.components.DividendDayDetail
import com.example.stockdiary.ui.screens.calendar.components.StatColumn
import com.example.stockdiary.ui.screens.calendar.components.TabButton
import com.example.stockdiary.ui.theme.OnSurface
import com.example.stockdiary.ui.theme.OnSurfaceVariant
import com.example.stockdiary.ui.theme.Primary
import com.example.stockdiary.ui.theme.PrimaryContainer
import com.example.stockdiary.ui.theme.SurfaceContainer
import com.example.stockdiary.ui.viewmodels.CalendarViewModel
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.profitColor
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val viewModel: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory(container))
    val uiState by viewModel.uiState.collectAsState()

    val assets = uiState.assets
    val calendarData = uiState.calendarData
    val dividendDates = uiState.dividendDates
    val dividends = uiState.dividends
    val selectedDate = uiState.selectedDate
    val isLoading = uiState.isLoading
    val errorMessage = uiState.errorMessage
    val monthlyExpense = uiState.monthlyExpense
    val realAnnualIncome = uiState.realAnnualIncome

    var showFireDialog by remember { mutableStateOf(false) }

    // 空状态：无资产
    if (!isLoading && assets.isEmpty()) {
        EmptyCalendarContent(modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top
    ) {
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

        // 月度汇总
        if (!isLoading) {
            MonthlySummary(
                isAssetCalendar = uiState.selectedTab == "资产日历",
                calendarData = calendarData,
                assetCount = assets.size
            )
        }

        if (errorMessage != null) {
            ErrorMessageCard(
                message = errorMessage,
                onDismiss = { viewModel.clearError() }
            )
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
private fun EmptyCalendarContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text("📅", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("暂无资产", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OnSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Text("请先在资产页面添加股票、基金或银行存款", fontSize = 13.sp, color = OnSurfaceVariant)
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
                color = OnSurface
            )
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                color = SurfaceContainer
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
                    tint = Primary
                )
            }
        }
    }
}

@Composable
private fun SelectedDateDetail(
    date: LocalDate,
    value: Double,
    isAssetCalendar: Boolean,
    dividends: List<Pair<String, List<com.example.stockdiary.data.DividendRecord>>>,
    assets: List<Asset>
) {
    val assetMap = remember(assets) { assets.associateBy { it.symbol } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PrimaryContainer)
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
                    color = OnSurface
                )
                Text(
                    text = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA),
                    fontSize = 14.sp,
                    color = OnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isAssetCalendar) {
                Text("当日净资产", fontSize = 13.sp, color = OnSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (value > 0) "¥${value.format(2)}" else "暂无数据",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (value > 0) OnSurface else OnSurfaceVariant
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

@Composable
private fun MonthlySummary(
    isAssetCalendar: Boolean,
    calendarData: Map<LocalDate, Double>,
    assetCount: Int
) {
    val snapshotDays = calendarData.values.count { it > 0 }
    val noDataDays = calendarData.values.count { it == 0.0 }
    val latestValue = calendarData.entries
        .filter { it.value > 0 }
        .maxByOrNull { it.key }?.value ?: 0.0
    val monthlyTotal = calendarData.values.filter { it != 0.0 }.sum()
    val positiveDays = calendarData.values.count { it > 0 }
    val zeroDays = calendarData.values.count { it == 0.0 }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (isAssetCalendar) "本月资产总览" else "本月分红汇总",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = OnSurface
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
                color = if (isAssetCalendar) OnSurface
                else if (monthlyTotal >= 0) com.example.stockdiary.ui.theme.LossRed else com.example.stockdiary.ui.theme.ProfitGreen
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isAssetCalendar) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
                ) {
                    StatColumn("有数据天数", snapshotDays.toString(), Primary)
                    StatColumn("无数据", noDataDays.toString(), OnSurfaceVariant)
                    StatColumn("持仓", assetCount.toString(), Primary)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
                ) {
                    StatColumn("正收益天数", positiveDays.toString(), com.example.stockdiary.ui.theme.LossRed)
                    StatColumn("无收益", zeroDays.toString(), OnSurfaceVariant)
                    StatColumn("持仓", assetCount.toString(), Primary)
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
