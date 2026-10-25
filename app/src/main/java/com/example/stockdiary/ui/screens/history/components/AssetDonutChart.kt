package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.AssetSnapshot
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format

@Composable
fun AssetDonutChart(
    latest: AssetSnapshot,
    modifier: Modifier = Modifier
) {
    val segments = listOfNotNull(
        if (latest.stockValue > 0) Triple("股票", latest.stockValue, AppTheme.colors.lossRed) else null,
        if (latest.fundValue > 0) Triple("基金", latest.fundValue, AppTheme.colors.accentOrange) else null,
        if (latest.cryptoValue > 0) Triple("加密货币", latest.cryptoValue, AppTheme.colors.accentBlue) else null,
        if (latest.bankDepositValue > 0) Triple("银行存款", latest.bankDepositValue, AppTheme.colors.primaryGreen) else null
    )
    if (segments.isEmpty()) return
    val total = segments.sumOf { it.second }

    // 使用主题色，确保深色模式下文字可见
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()

    // 缓存 Paint 对象，避免每次绘制重新创建
    val labelPaint = remember(onSurfaceVariant) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant
            textSize = 22f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val valuePaint = remember(onSurface) {
        android.graphics.Paint().apply {
            color = onSurface
            textSize = 24f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }

    Canvas(modifier = modifier) {
        val strokeWidth = 24f
        val radius = (size.minDimension - strokeWidth) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        var startAngle = -90f

        segments.forEach { (_, value, color) ->
            val sweepAngle = (value / total * 360f).toFloat()
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth)
            )
            startAngle += sweepAngle
        }

        // 中心文字：显示快照里权威的净资产口径「总资产」(latest.totalAssets)，
        // 与仪表盘一致；而非仅正值合计 total。当用户有负余额加密货币（借币/欠币）时，
        // total 会漏掉这部分负债、低估总资产，必须用快照原始 totalAssets。
        // 弧线比例仍按 total（正值合计）绘制，仅中心数字改用权威值。
        drawContext.canvas.nativeCanvas.drawText(
            "总资产", center.x, center.y - 4f, labelPaint
        )
        drawContext.canvas.nativeCanvas.drawText(
            "¥${latest.totalAssets.format(0)}", center.x, center.y + 18f, valuePaint
        )
    }
}
