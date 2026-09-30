package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stockdiary.data.DividendRecord
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.utils.toProfitDisplay

data class ReturnAttribution(
    val priceReturn: Double,
    val dividendReturn: Double,
    val interestReturn: Double,
    val totalReturn: Double
) {
    companion object {
        fun calculate(
            asset: Asset,
            dividends: List<DividendRecord>,
            annualInterestRate: Double? = null
        ): ReturnAttribution {
            val cost = asset.balance * asset.purchasePrice
            val priceReturn = asset.totalValue - cost

            val dividendReturn = when (asset.type) {
                AssetType.STOCK, AssetType.FUND -> {
                    dividends.sumOf { it.dividendPerShare.toDoubleOrNull() ?: 0.0 } / 10.0 * asset.balance
                }
                else -> 0.0
            }

            val interestReturn = when (asset.type) {
                AssetType.BANK_DEPOSIT -> {
                    val rate = annualInterestRate ?: asset.interestRate ?: 0.0
                    asset.totalValue * (rate / 100.0)
                }
                else -> 0.0
            }

            return ReturnAttribution(
                priceReturn = priceReturn,
                dividendReturn = dividendReturn,
                interestReturn = interestReturn,
                totalReturn = priceReturn + dividendReturn + interestReturn
            )
        }
    }
}

@Composable
fun ReturnAttributionCard(
    attribution: ReturnAttribution,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("收益归因", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            ReturnAttributionRow("价差收益", attribution.priceReturn)
            ReturnAttributionRow("分红收益", attribution.dividendReturn)
            ReturnAttributionRow("利息收益", attribution.interestReturn)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Outline.copy(alpha = 0.3f))
            ReturnAttributionRow("总收益", attribution.totalReturn, isTotal = true)
        }
    }
}

@Composable
private fun ReturnAttributionRow(label: String, value: Double, isTotal: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = if (isTotal) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            fontWeight = if (isTotal) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            value.toProfitDisplay("¥"),
            style = if (isTotal) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = when {
                value > 0 -> LossRed
                value < 0 -> ProfitGreen
                else -> OnSurface
            }
        )
    }
}
