package com.example.stockdiary.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.Transaction
import com.example.stockdiary.database.TransactionType
import com.example.stockdiary.ui.theme.*
import com.example.stockdiary.utils.formatDateLabel
import com.example.stockdiary.utils.formatYAxisLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max

data class CostPoint(
    val date: LocalDate,
    val cost: Double,
    val position: Double
)

data class TradeMarker(
    val date: LocalDate,
    val type: TransactionType,
    val quantity: Double,
    val price: Double
)

@Composable
fun CostCurveCard(
    asset: Asset,
    transactions: List<Transaction>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("持仓成本曲线", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            CostCurveChart(asset = asset, transactions = transactions, modifier = Modifier.fillMaxWidth().height(220.dp))
        }
    }
}

@Composable
fun CostCurveChart(
    asset: Asset,
    transactions: List<Transaction>,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val sortedTransactions = remember(transactions) {
        transactions.filter { it.symbol == asset.symbol }.sortedBy { it.date }
    }

    val costCurve = remember(sortedTransactions) {
        buildCostCurve(sortedTransactions)
    }

    val markers = remember(sortedTransactions) {
        sortedTransactions.mapNotNull { tx ->
            val type = TransactionType.fromValue(tx.type) ?: return@mapNotNull null
            try {
                TradeMarker(
                    date = LocalDate.parse(tx.date, formatter),
                    type = type,
                    quantity = tx.quantity,
                    price = tx.price
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    if (costCurve.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无交易记录", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        }
        return
    }

    val lineColor = Primary
    val gridColor = Outline.copy(alpha = 0.2f)
    val buyColor = LossRed
    val sellColor = ProfitGreen

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val paddingTop = 20f
            val paddingBottom = 64f
            val paddingLeft = 48f
            val paddingRight = 20f

            val cWidth = width - paddingLeft - paddingRight
            val cHeight = height - paddingTop - paddingBottom

            val minDate = costCurve.first().date.toEpochDay().toDouble()
            val maxDate = costCurve.last().date.toEpochDay().toDouble()
            val dateRange = max(1.0, maxDate - minDate)

            val minCost = costCurve.minOf { it.cost }.coerceAtLeast(0.0)
            val maxCost = costCurve.maxOf { it.cost }
            val costRange = if (maxCost - minCost > 0) maxCost - minCost else 1.0

            fun costToY(cost: Double): Float {
                return (paddingTop + cHeight - ((cost - minCost) / costRange * cHeight)).toFloat()
            }

            fun dateToX(date: LocalDate): Float {
                val day = date.toEpochDay().toDouble()
                return (paddingLeft + cWidth * ((day - minDate) / dateRange)).toFloat()
            }

            // Grid lines
            val gridCount = 4
            val stepY = cHeight / gridCount
            for (i in 0..gridCount) {
                val yPos = paddingTop + i * stepY
                drawLine(gridColor, Offset(paddingLeft, yPos), Offset(width - paddingRight, yPos), 1f)
                val yValue = maxCost - (i * costRange / gridCount)
                drawContext.canvas.nativeCanvas.drawText(
                    yValue.formatYAxisLabel(), 2f, yPos + 4f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY; textSize = 22f; isAntiAlias = true
                    }
                )
            }

            // Date labels
            if (costCurve.isNotEmpty()) {
                val firstDate = costCurve.first().date.format(formatter).formatDateLabel()
                val lastDate = costCurve.last().date.format(formatter).formatDateLabel()
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY; textSize = 22f; isAntiAlias = true
                }
                val labelY = paddingTop + cHeight + 24f
                drawContext.canvas.nativeCanvas.drawText(firstDate, paddingLeft, labelY, paint)
                drawContext.canvas.nativeCanvas.drawText(
                    lastDate, width - paddingRight - paint.measureText(lastDate), labelY, paint
                )
            }

            // Cost line
            if (costCurve.size >= 2) {
                val path = Path().apply {
                    moveTo(dateToX(costCurve.first().date), costToY(costCurve.first().cost))
                    for (i in 1 until costCurve.size) {
                        lineTo(dateToX(costCurve[i].date), costToY(costCurve[i].cost))
                    }
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = 2.5f))
            } else {
                drawCircle(lineColor, radius = 4f, center = Offset(dateToX(costCurve.first().date), costToY(costCurve.first().cost)))
            }

            // Trade markers
            markers.forEach { marker ->
                val x = dateToX(marker.date)
                val y = costToY(marker.price)
                val color = if (marker.type == TransactionType.BUY) buyColor else sellColor
                drawCircle(color = color, radius = 5f, center = Offset(x, y))
                drawCircle(color = Color.White, radius = 5f, center = Offset(x, y), style = Stroke(width = 1.5f))
            }
        }

        // Legend
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            LegendItem(color = Primary, label = "成本价")
            LegendItem(color = LossRed, label = "买入")
            LegendItem(color = ProfitGreen, label = "卖出")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).padding(end = 4.dp)) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = color, radius = size.minDimension / 2)
            }
        }
        Text(label, fontSize = 11.sp, color = OnSurfaceVariant)
    }
}

private fun buildCostCurve(transactions: List<Transaction>): List<CostPoint> {
    if (transactions.isEmpty()) return emptyList()

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val grouped = transactions.groupBy { it.date }.toSortedMap()

    var position = 0.0
    var costBasis = 0.0
    val points = mutableListOf<CostPoint>()

    grouped.forEach { (dateStr, txs) ->
        val date = try { LocalDate.parse(dateStr, formatter) } catch (e: Exception) { return@forEach }
        txs.forEach { tx ->
            val type = TransactionType.fromValue(tx.type)
            when (type) {
                TransactionType.BUY -> {
                    val newPosition = position + tx.quantity
                    costBasis = if (newPosition > 0) {
                        (position * costBasis + tx.quantity * tx.price) / newPosition
                    } else {
                        tx.price
                    }
                    position = newPosition
                }
                TransactionType.SELL -> {
                    position = (position - tx.quantity).coerceAtLeast(0.0)
                    if (position <= 0) costBasis = 0.0
                }
                else -> {}
            }
        }
        points.add(CostPoint(date = date, cost = costBasis, position = position))
    }

    return points
}
