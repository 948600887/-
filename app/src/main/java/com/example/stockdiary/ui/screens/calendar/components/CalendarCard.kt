package com.example.stockdiary.ui.screens.calendar.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextOverflow
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
                    Spacer(modifier = Modifier.width(8.dp))
                    LegendItem(color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.3f), label = "无数据")
                } else {
                    LegendItem(color = AppTheme.colors.lossRed, label = "正收益")
                    Spacer(modifier = Modifier.width(8.dp))
                    LegendItem(color = AppTheme.colors.profitGreen, label = "负收益")
                }
                Spacer(modifier = Modifier.width(8.dp))
                DividendLegendItem()
                Spacer(modifier = Modifier.width(8.dp))
                // 今天只用描边，不再用与“负收益”相近的绿色底色，色弱环境也能区分。
                LegendItem(color = AppTheme.colors.primary, label = "今天", outlined = true)
            }
        }
    }
}

/**
 * 日历格子固定高度：行高不因内容多少而参差不齐。
 * 槽位高度按「字号 + Compose 默认字体 padding」给足，贴底的分红标记才不会被 clip 切尾。
 */
private val CALENDAR_CELL_HEIGHT = 56.dp
/** 三段固定「槽位」高度：日期 / 金额 / 分红标记各占一位，有无内容都占位，保证横向对齐。
 *  金额升到 11sp 后同步把金额槽位加到 18dp；三段之和 18+18+16=52dp，
 *  仍小于格子扣除上下 1dp padding 后约 54dp 的内容区，底部保留约 2dp，分红字不会被 clip。 */
private val CALENDAR_SLOT_DATE = 18.dp
private val CALENDAR_SLOT_VALUE = 18.dp
private val CALENDAR_SLOT_DIVIDEND = 16.dp

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
    val hasValue = day.isCurrentMonth && day.value != 0.0

    Surface(
        modifier = modifier
            .height(CALENDAR_CELL_HEIGHT)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = when {
            isSelected -> AppTheme.colors.primary.copy(alpha = 0.25f)
            else -> AppTheme.colors.surfaceContainerLow
        },
        // “今天”用边框而非语义色底色；被选中时优先展示选中态，避免双重强调。
        border = if (isToday && !isSelected) BorderStroke(1.dp, AppTheme.colors.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Top：三段从上往下排，剩余空隙垫在底部——分红标记不被顶到格子下沿（用户要求「分」往上移一点）。
            // 槽位固定高度，有无内容都占位，所以每个格子的三段仍对齐在同一水平线，行高也一致。
            verticalArrangement = Arrangement.Top
        ) {
            Box(modifier = Modifier.height(CALENDAR_SLOT_DATE), contentAlignment = Alignment.Center) {
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
            }
            Box(modifier = Modifier.height(CALENDAR_SLOT_VALUE), contentAlignment = Alignment.Center) {
                if (hasValue) {
                    Text(
                        text = if (isAssetCalendar) day.value.compactFormat()
                        else if (day.value > 0) "+${day.value.format(0)}" else day.value.format(0),
                        fontSize = 11.sp,
                        color = if (isAssetCalendar) AppTheme.colors.primary else day.value.profitColor(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(modifier = Modifier.height(CALENDAR_SLOT_DIVIDEND), contentAlignment = Alignment.Center) {
                if (hasDividend) {
                    Text(
                        text = "分",
                        fontSize = 10.sp,
                        color = AppTheme.colors.lossRed,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, outlined: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = RoundedCornerShape(2.dp),
            color = if (outlined) Color.Transparent else color,
            border = if (outlined) BorderStroke(1.dp, color) else null
        ) {}
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun DividendLegendItem() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "分",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.lossRed
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = "= 有分红", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
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
