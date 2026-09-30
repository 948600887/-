package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import com.example.stockdiary.database.AssetType

/**
 * 对话框通用组件，供 AddAssetDialog、EditAssetDialog 等复用
 */

@Composable
fun DialogSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun DialogTypeChip(
    type: AssetType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customLabel: String? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                customLabel ?: type.displayName,
                style = MaterialTheme.typography.labelMedium
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = modifier
    )
}

@Composable
fun DialogInfoItem(
    label: String,
    value: String,
    color: Color? = null
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}