package com.example.stockdiary.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.stockdiary.ui.theme.AppTheme

/**
 * 单行文本：统一带 `maxLines = 1` + 省略号。
 *
 * ## 为什么要有这个组件
 *
 * 改造前全 App 的 `TextOverflow` 只出现过 1 次（`CalendarCard` 的日历格子），其余所有
 * 单行文本要么只写了 `maxLines = 1`（默认是 Clip，长名字被从中间硬切、没有省略号），
 * 要么连 `maxLines` 都没有（账户名、设置项副标题直接撑成多行 / 把右侧金额挤出屏幕）。
 *
 * 资产名、账户名都是用户自己填或从券商导入的，几十个字的基金名很常见
 * （"易方达中证海外互联网ETF联接A"），不统一收口的话每加一个列表行就要重踩一次坑。
 *
 * ## 用法约定
 *
 * 谁调用都要保证**它所在的容器是可压缩的**：`Row` 里给文本那侧 `Modifier.weight(1f)`、
 * 给右侧金额那侧不加 weight。否则文本不会被压缩，weight 加在哪一侧就决定了谁先被挤掉——
 * 这里的取舍是「文字可以省略，金额一个字符都不能少」。
 */
@Composable
fun SingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = AppTheme.colors.onSurface,
    fontWeight: FontWeight? = null
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 与 [SingleLineText] 同源，区别是允许显示到 [maxLines] 行再省略。
 *
 * 给「内容本身值得占两行」的场景用（例如设置项的说明文字：整句截成一行会丢掉
 * "上次同步时间"这类关键信息，但也不能任由它无限撑高卡片）。
 */
@Composable
fun BoundedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = AppTheme.colors.onSurfaceVariant,
    maxLines: Int = 2
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
