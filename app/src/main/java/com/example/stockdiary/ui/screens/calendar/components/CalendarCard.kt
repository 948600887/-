package com.example.stockdiary.ui.screens.calendar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.compactFormat
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.profitColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CalendarCard(
    currentMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    calendarData: Map<LocalDate, Double>,
    dividendDates: Set<LocalDate>,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate?) -> Unit,
    isAssetCalendar: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 月份导航
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onMonthChange(currentMonth.minusMonths(1)) }) {
                    Icon(Icons.Filled.ChevronLeft, "上一个月", tint = AppTheme.colors.onSurface)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentMonth.format(DateTimeFormatter.ofPattern("yyyy年MM月")),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onSurface
                    )
                    if (currentMonth != YearMonth.now()) {
                        TextButton(
                            onClick = { onMonthChange(YearMonth.now()) },
                            modifier = Modifier.padding(0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Filled.Today, "回到今天", modifier = Modifier.size(14.dp), tint = AppTheme.colors.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("今天", fontSize = 11.sp, color = AppTheme.colors.primary)
                        }
                    }
                }

                IconButton(onClick = { onMonthChange(currentMonth.plusMonths(1)) }) {
                    Icon(Icons.Filled.ChevronRight, "下一个月", tint = AppTheme.colors.onSurface)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 星期头
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { dayName ->
                    val isWeekend = dayName == "日" || dayName == "六"
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = dayName,
                            fontSize = 12.sp,
                            color = if (isWeekend) AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f) else AppTheme.colors.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 日历格子（动态行数）
            val calendarDays = remember(currentMonth, calendarData) {
                buildCalendarDays(currentMonth, calendarData)
            }

            val totalRows = (calendarDays.size + 6) / 7
            for (row in 0 until totalRows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val index = row * 7 + col
                        if (index < calendarDays.size) {
                            CalendarDayCell(
                                day = calendarDays[index],
                                isSelected = selectedDate == calendarDays[index].date,
                                hasDividend = calendarDays[index].isCurrentMonth && calendarDays[index].date in dividendDates,
                                isAssetCalendar = isAssetCalendar,
                                onClick = {
                                    if (calendarDays[index].isCurrentMonth) {
                                        onDateSelected(calendarDays[index].date)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 图例
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = AppTheme.colors.outline.copy(alpha = 0.3f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isAssetCalendar) {
                    LegendItem(color = AppTheme.colors.primary, label = "有数据")
                    Spacer(modifier = Modifier.width(12.dp))
                    LegendItem(color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.3f), label = "无数据")
                } else {
                    LegendItem(color = AppTheme.colors.lossRed, label = "正收益")
                    Spacer(modifier = Modifier.width(12.dp))
                    LegendItem(color = AppTheme.colors.profitGreen, label = "负收益")
                }
                Spacer(modifier = Modifier.width(12.dp))
                LegendItem(color = AppTheme.colors.primary.copy(alpha = 0.35f), label = "今天")
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: CalendarDay,
    isSelected: Boolean,
    hasDividend: Boolean,
    modifier: Modifier = Modifier,
    isAssetCalendar: Boolean = false,
    onClick: () -> Unit = {}
) {
    val isToday = day.date == LocalDate.now() && day.isCurrentMonth
    val isWeekend = day.date.dayOfWeek.value >= 6

    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = when {
            isSelected -> AppTheme.colors.primary.copy(alpha = 0.25f)
            isToday -> AppTheme.colors.primary.copy(alpha = 0.15f)
            else -> AppTheme.colors.surfaceContainerLow
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = day.date.dayOfMonth.toString(),
                fontSize = 12.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !day.isCurrentMonth -> AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f)
                    isWeekend && !isToday -> AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                    isToday -> AppTheme.colors.primary
                    else -> AppTheme.colors.onSurface
                }
            )
            if (day.isCurrentMonth && day.value != 0.0) {
                Text(
                    text = if (isAssetCalendar) day.value.compactFormat()
                    else if (day.value > 0) "+${day.value.format(0)}" else day.value.format(0),
                    fontSize = 9.sp,
                    color = if (isAssetCalendar) AppTheme.colors.primary else day.value.profitColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            if (hasDividend) {
                Text(
                    text = "分",
                    fontSize = 8.sp,
                    color = AppTheme.colors.lossRed,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
internal fun TabButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant
        )
    }
}

internal data class CalendarDay(
    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val value: Double
)

internal fun buildCalendarDays(month: YearMonth, calendarData: Map<LocalDate, Double>): List<CalendarDay> {
    val firstDayOfMonth = month.atDay(1)
    val dayOfWeek = firstDayOfMonth.dayOfWeek.value
    val daysInMonth = month.lengthOfMonth()
    val daysInPreviousMonth = month.minusMonths(1).lengthOfMonth()

    val result = mutableListOf<CalendarDay>()

    // 上月填充
    val offset = if (dayOfWeek == 7) 0 else dayOfWeek
    for (i in 0 until offset) {
        result.add(
            CalendarDay(
                date = month.minusMonths(1).atDay(daysInPreviousMonth - offset + 1 + i),
                isCurrentMonth = false,
                value = 0.0
            )
        )
    }

    // 当月日期
    for (i in 1..daysInMonth) {
        val date = month.atDay(i)
        result.add(
            CalendarDay(
                date = date,
                isCurrentMonth = true,
                value = calendarData[date] ?: 0.0
            )
        )
    }

    // 下月填充到完整周
    val remainingDays = (7 - result.size % 7) % 7
    for (i in 1..remainingDays) {
        result.add(
            CalendarDay(
                date = month.plusMonths(1).atDay(i),
                isCurrentMonth = false,
                value = 0.0
            )
        )
    }

    return result
}
