package com.example.stockdiary.ui.screens.calendar.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.dividend.cashDateOrNull
import com.example.stockdiary.database.Asset
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.database.amountFor
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.shareUnitWord
import com.example.stockdiary.utils.ExchangeRateManager
import java.time.LocalDate

@Composable
fun DividendDayDetail(
    date: LocalDate,
    dividends: List<Pair<String, List<DividendRecord>>>,
    assetMap: Map<String, Asset>
) {
    // 按**到账日**匹配选中日，与 CalendarViewModel 打「分」标记同一口径：
    // 除息日只决定资格，钱 1~4 周后才到账；拿除息日比对会点开一个并没进钱的日子。
    // remember(date, dividends)：选中日期后页面任意重组（例如上方净资产卡片重算）都不重跑
    // flatMap+filter，避免无谓计算（#E）。
    val dayDividendDetails = remember(date, dividends) {
        dividends.flatMap { (sym, records) ->
            records.filter { it.cashDateOrNull() == date }.map { sym to it }
        }
    }

    if (dayDividendDetails.isEmpty()) {
        Text("当日无分红", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text("¥0.00", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurfaceVariant)
        return
    }

    // 预计算每个分红的金额（避免两次计算）
    // 找不到对应持仓时给出 null 而不是拿 1 份兜底 —— 兜底会凭空变出一个「每份」的假金额，
    // 界面上显示「—」比显示一个错的数诚实
    val details = dayDividendDetails.map { (sym, record) ->
        val asset = assetMap[sym]
        // 折算成人民币：外币基金（USD/HKD）的每份金额按标的币种换算，
        // 否则界面会把美元金额当人民币显示，钱数错得无声无息。
        val amount = asset?.let { ExchangeRateManager.toCny(record.amountFor(it.balance), it.currency) }
        Triple(sym, record, amount)
    }
    val totalDividend = details.sumOf { it.third ?: 0.0 }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = AppTheme.colors.outline.copy(alpha = 0.3f)
    )

    Text("当日分红", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "+¥${totalDividend.format(2)}",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.lossRed
    )

    Spacer(modifier = Modifier.height(4.dp))
    // 说清口径：分红记录只存「每十份派息」，没有存派息当天的持仓数量，
    // 所以这里（以及日历格子的金额）都是按**当前持仓**折算的。卖出后回看历史分红会变小/消失，
    // 这不是算错，是模型没有历史持仓——界面必须讲明白，别让用户以为数据丢了。
    Text(
        "金额按当前持仓估算，不回溯历史买卖；日期按到账日（无派息日时用除息日）",
        fontSize = 11.sp,
        color = AppTheme.colors.onSurfaceVariant
    )

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = AppTheme.colors.outline.copy(alpha = 0.3f)
    )

    details.forEach { (symbol, record, amount) ->
        val asset = assetMap[symbol]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = asset?.name ?: symbol,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.onSurface
                )
                Text(
                    text = "每十${asset?.shareUnitWord ?: "份"}派息: ¥${ExchangeRateManager.toCny(record.dividendPerShare.toDoubleOrNull() ?: 0.0, asset?.currency ?: "CNY").format(2)}",
                    fontSize = 12.sp,
                    color = AppTheme.colors.onSurfaceVariant
                )
            }
            Text(
                text = amount?.let { "+¥${it.format(2)}" } ?: "—",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (amount == null) AppTheme.colors.onSurfaceVariant else AppTheme.colors.lossRed
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
