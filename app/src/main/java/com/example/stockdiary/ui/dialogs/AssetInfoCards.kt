package com.example.stockdiary.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stockdiary.data.repository.FundQuote
import com.example.stockdiary.data.repository.StockQuote
import com.example.stockdiary.ui.components.DialogInfoItem
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.StockCodeManager.CodeCategory
import com.example.stockdiary.utils.format

/**
 * 添加/编辑资产弹窗共用的行情结果卡片，集中放在这里避免 AddAssetDialog 体积膨胀。
 */

@Composable
internal fun FundInfoCard(info: FundQuote) {
    // 场外基金净值显示到4位小数（如 1.2345）
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DialogInfoItem("净值", "${info.price.format(4)}")
                if (info.changePercent != null) {
                    DialogInfoItem(
                        label = "涨跌",
                        value = "${info.changePercent.format(2)}%",
                        color = if (info.changePercent >= 0) AppTheme.colors.lossRed else AppTheme.colors.profitGreen
                    )
                }
            }
        }
    }
}

@Composable
internal fun StockInfoCard(info: StockQuote, category: CodeCategory? = null) {
    // ETF/LOF/场内基金价格显示到厘（3位小数），股票显示到分（2位小数）
    val priceDigits = if (category == CodeCategory.ETF || category == CodeCategory.LOF || category == CodeCategory.EXCHANGE_FUND) 3 else 2
    // 统一行情模型的涨跌可空（停牌/无昨收），展示层按 0 处理
    val changePercent = info.changePercent ?: 0.0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DialogInfoItem("现价", "${info.price.format(priceDigits)}")
                DialogInfoItem(
                    label = "涨跌",
                    value = "${changePercent.format(2)}%",
                    color = if (changePercent >= 0) AppTheme.colors.lossRed else AppTheme.colors.profitGreen
                )
            }
        }
    }
}

@Composable
internal fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}
