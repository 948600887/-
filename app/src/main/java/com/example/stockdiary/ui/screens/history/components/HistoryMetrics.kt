package com.example.stockdiary.ui.screens.history.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format

@Composable
internal fun OverviewMetric(label: String, value: String, valueColor: Color = AppTheme.colors.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = valueColor)
        Text(text = label, fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
internal fun RiskMetric(modifier: Modifier = Modifier, label: String, value: String, valueColor: Color = AppTheme.colors.onSurface) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = valueColor)
        Text(text = label, fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
internal fun DistributionLegend(
    name: String,
    value: Double,
    percent: Double,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = color)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, fontSize = 13.sp, color = AppTheme.colors.onSurface)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("¥${value.format(0)}", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = AppTheme.colors.onSurface)
            Text("${percent.format(1)}%", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
        }
    }
}
