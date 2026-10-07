package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    isDestructive: Boolean = false,
    isSwitch: Boolean = false,
    switchChecked: Boolean = false,
    onSwitchCheckedChange: ((Boolean) -> Unit)? = null,
    /** false 时整行置灰且不可点击 */
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    val headlineColor = when {
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = headlineColor.copy(alpha = contentAlpha)
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
        },
        leadingContent = {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDestructive) {
                    MaterialTheme.colorScheme.error.copy(alpha = contentAlpha)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                }
            )
        },
        trailingContent = if (isSwitch) {
            {
                Switch(
                    checked = switchChecked,
                    onCheckedChange = onSwitchCheckedChange,
                    enabled = enabled
                )
            }
        } else null,
        modifier = if (isSwitch) {
            Modifier.clickable(enabled = enabled) { onSwitchCheckedChange?.invoke(!switchChecked) }
        } else {
            Modifier.clickable(enabled = enabled, onClick = onClick)
        }
    )
}
