package com.example.stockdiary.ui.screens.calendar.components

import com.example.stockdiary.common.DateFormats
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
    isAssetCalendar: Boolean = false,
    /**
     * 「今天」由调用方算一次传进来（原来每个格子各调一次 LocalDate.now()，一屏 42 次）。
     * 它同时决定「今天」描边与「回到今天」按钮的目标月份。
     */
    today: LocalDate = LocalDate.now(),
    /**
     * 生成中（切月/切 Tab/刷新）时禁用月份导航：旧任务已被取消、新结果还没回来，
     * 此时再翻月只会不停打断生成，最后停在哪个个月份全看点击时机。
     */
    monthNavEnabled: Boolean = true
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
                IconButton(
                    onClick = { onMonthChange(currentMonth.minusMonths(1)) },
                    enabled = monthNavEnabled
                ) {
                    Icon(Icons.Filled.ChevronLeft, "上一个月", tint = AppTheme.colors.onSurface)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentMonth.format(DateFormats.CN_MONTH_FORMATTER),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onSurface
                    )
                    if (currentMonth != YearMonth.from(today)) {
                        TextButton(
                            onClick = { onMonthChange(YearMonth.from(today)) },
                            enabled = monthNavEnabled,
                            modifier = Modifier.padding(0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Filled.Today, "回到今天", modifier = Modifier.size(14.dp), tint = AppTheme.colors.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("今天", fontSize = 11.sp, color = AppTheme.colors.primary)
                        }
                    }
                }

                IconButton(
                    onClick = { onMonthChange(currentMonth.plusMonths(1)) },
                    enabled = monthNavEnabled
                ) {
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
            val calendarDays = remember(currentMonth, calendarData, today) {
                buildCalendarDays(currentMonth, calendarData, today)
            }
            // 分红日期转成 epochDay 集合：Set<LocalDate> 对 Compose 是不稳定类型，
            // 传进格子会让所有格子都无法跳过；Long 集合只和「有没有分红」有关。
            val dividendEpochDays = remember(dividendDates) {
                dividendDates.mapTo(HashSet(dividendDates.size)) { it.toEpochDay() }
            }
            val selectedEpochDay = selectedDate?.toEpochDay()
            // 上层每次重组都会新建 onDateSelected（如 `{ viewModel.selectDate(it) }`），
            // `remember(onDateSelected)` 会被新 lambda 击穿：整屏格子收到新的 onDayClick 身份，
            // 未变化的格子也随之失效。适配器身份只创建一次、回调只做「更新」，
            // 这样格子参数保持全等，跳过重组才真正生效。
            val dateClickAdapter = remember { StableDateClickAdapter(onDateSelected) }
            dateClickAdapter.update(onDateSelected)

            val totalRows = (calendarDays.size + 6) / 7
            for (row in 0 until totalRows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val index = row * 7 + col
                        if (index < calendarDays.size) {
                            val day = calendarDays[index]
                            CalendarDayCell(
                                day = day,
                                isSelected = selectedEpochDay == day.epochDay,
                                // 无数据的格子（当日无快照、没有金额）不显示「分」：
                                // 否则「有分红记录但当日无数据」的日期上只剩一个孤立的红色「分」，像无数据格子上的噪点。
                                hasDividend = day.isCurrentMonth &&
                                    day.value != 0.0 &&
                                    day.epochDay in dividendEpochDays,
                                isAssetCalendar = isAssetCalendar,
                                onDayClick = dateClickAdapter.onEpochDay,
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
 * 「身份稳定、但永远调用最新回调」的格子点击适配器。
 *
 * 为什么需要它：格子只认 `(Long) -> Unit`，而上层给的是 `(LocalDate?) -> Unit` 且**每次重组新建**。
 * 若用 `remember(onDateSelected) { ... }`，每次上层换 lambda 都会重新生成 adapter，
 * 所有格子的 `onDayClick` 参数随之变化 → 未变化的格子也无法跳过重组。
 *
 * 这里把 adapter 用无 key 的 `remember` 创建一次，后续重组只调用 [update] 更新回调引用；
 * [onEpochDay] 始终是同一个实例，格子才能稳定跳过。
 * 单测（CalendarCardTest）锁住「身份不变 + 调用最新回调」这两条契约。
 */
internal class StableDateClickAdapter(private var onDateSelected: (LocalDate?) -> Unit) {
    val onEpochDay: (Long) -> Unit = { epochDay -> onDateSelected(LocalDate.ofEpochDay(epochDay)) }

    fun update(callback: (LocalDate?) -> Unit) {
        onDateSelected = callback
    }
}

/**
 * 日历格子固定高度：行高不因内容多少而参差不齐。
 * 槽位高度按「字号 + Compose 默认字体 padding」给足，贴底的分红标记才不会被 clip 切尾。
 *
 * ⚠️ 56 → 60 的原因（2026-10-23 用户截图复现）：分红标记从 8sp 提到 10sp 后，
 * 16dp 槽位装不下「10sp + Compose 默认字体 padding」的实际字高；而当时
 * 「三段 + 抬升间距」恰好等于内容区（54dp）、底部零余量，超出的部分立刻被
 * Surface 的 clip 切掉——这就是用户看到的「分又被遮挡」。
 * 格子加高到 60dp 后内容区 58dp，三段 + 间距 = 18+18+1+18 = 55dp，
 * 底部留 3dp 余量，10sp 的字在 18dp 槽位里也放得下，两头都不切。
 */
private val CALENDAR_CELL_HEIGHT = 60.dp
/** 三段固定「槽位」高度：日期 / 金额 / 分红标记各占一位，有无内容都占位，保证横向对齐。
 *  字号分别为 日期 12sp / 金额 11sp / 分红 10sp。
 *  分红槽 18dp 是专为「10sp + 默认字体 padding」留的余量——8sp 时代 16dp 够，
 *  提到 10sp 后 16dp 会被 clip 切尾（见 [CALENDAR_CELL_HEIGHT] 注释）。 */
private val CALENDAR_SLOT_DATE = 18.dp
private val CALENDAR_SLOT_VALUE = 18.dp
private val CALENDAR_SLOT_DIVIDEND = 18.dp
/**
 * 金额槽与分红槽之间的间距。
 *
 * 曾经是 2dp（用户当时要求「分再高 2dp」）。但那个间距加上两端文字各自的
 * Compose 默认字体 padding 后，「分」与金额之间的**视觉**空隙有约 5~6dp，
 * 用户 2026-10-23 反馈「分字上面的空格太大」，要求「减到 1 就行」。
 *
 * 放在分红槽之前、无条件渲染，所以有分/无分的格子仍对齐在同一水平线。
 * 若以后再想抬高分就加大这个值，但**三段 + 间距不能超过内容区 58dp**，
 * 否则底部零余量、10sp 的字一溢出就会被 clip 切尾（见 [CALENDAR_CELL_HEIGHT] 注释）。
 */
private val DIVIDEND_LIFT = 1.dp

@Composable
private fun CalendarDayCell(
    day: CalendarDay,
    isSelected: Boolean,
    hasDividend: Boolean,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isAssetCalendar: Boolean = false
) {
    // day 里已经算好 isToday/isWeekend：这里不再调 LocalDate.now()/取 dayOfWeek，
    // 格子参数全是 Double/Int/Long/Boolean，未变化的格子可被 Compose 跳过。
    val isToday = day.isToday
    val isWeekend = day.isWeekend
    val hasValue = day.isCurrentMonth && day.value != 0.0

    Surface(
        modifier = modifier
            .height(CALENDAR_CELL_HEIGHT)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = { if (day.isCurrentMonth) onDayClick(day.epochDay) }),
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
                    text = day.dayOfMonth.toString(),
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
            // 把「分」往上抬 2dp（用户要求）。放在条件渲染之外，所以有分/无分的格子仍对齐。
            Spacer(modifier = Modifier.height(DIVIDEND_LIFT))
            // 贴顶对齐（不是 Center）：让「分」紧跟在金额下方。
            // 用 Center 时字会浮在 18dp 槽位中间，与上面的金额之间空出约 8dp，
            // 用户反馈「间隙太大」。固定槽位 + 无条件 Spacer 仍保证各格对齐。
            Box(
                modifier = Modifier.height(CALENDAR_SLOT_DIVIDEND),
                contentAlignment = Alignment.TopCenter
            ) {
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
internal fun TabButton(
    text: String,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(enabled = enabled, onClick = onClick)
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
    /** epochDay 是稳定类型：原来直接塞 java.time.LocalDate，Compose 视为不稳定，格子无法跳过。 */
    val epochDay: Long,
    val dayOfMonth: Int,
    val isCurrentMonth: Boolean,
    val value: Double,
    val isWeekend: Boolean,
    val isToday: Boolean
)

internal fun buildCalendarDays(
    month: YearMonth,
    calendarData: Map<LocalDate, Double>,
    today: LocalDate = LocalDate.now()
): List<CalendarDay> {
    val todayEpochDay = today.toEpochDay()
    val firstDayOfMonth = month.atDay(1)
    val dayOfWeek = firstDayOfMonth.dayOfWeek.value
    val daysInMonth = month.lengthOfMonth()
    val daysInPreviousMonth = month.minusMonths(1).lengthOfMonth()

    fun makeDay(date: LocalDate, isCurrentMonth: Boolean, value: Double) = CalendarDay(
        epochDay = date.toEpochDay(),
        dayOfMonth = date.dayOfMonth,
        isCurrentMonth = isCurrentMonth,
        value = value,
        isWeekend = date.dayOfWeek.value >= 6,
        // 只有当月格子才算「今天」：看别的月份时，今天可能落在上下月填充格里，
        // 那些格子按旧逻辑也不该有今天描边。
        isToday = isCurrentMonth && date.toEpochDay() == todayEpochDay
    )

    val result = mutableListOf<CalendarDay>()

    // 上月填充
    val offset = if (dayOfWeek == 7) 0 else dayOfWeek
    for (i in 0 until offset) {
        result.add(
            makeDay(month.minusMonths(1).atDay(daysInPreviousMonth - offset + 1 + i), false, 0.0)
        )
    }

    // 当月日期
    for (i in 1..daysInMonth) {
        val date = month.atDay(i)
        result.add(makeDay(date, true, calendarData[date] ?: 0.0))
    }

    // 下月填充到完整周
    val remainingDays = (7 - result.size % 7) % 7
    for (i in 1..remainingDays) {
        result.add(makeDay(month.plusMonths(1).atDay(i), false, 0.0))
    }

    return result
}
