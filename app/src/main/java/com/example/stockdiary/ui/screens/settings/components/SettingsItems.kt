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
import com.example.stockdiary.ui.components.BoundedText

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
            // 副标题最长的一句能到 40 多个字（"交易时段每 15 分钟、其余时段每 4 小时 ·
            // 上次: 09-30 14:20"）。以前完全没有行数上限，极端情况下会一路撑高整行；
            // 但截成一行又会丢掉"上次同步时间"这类关键信息，所以给 2 行 + 省略号，
            // 而不是照抄其它单行文本的 1 行策略。
            BoundedText(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 2
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
