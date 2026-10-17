package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardConfigSheet(
    preferences: DashboardPreferences,
    onPreferencesChange: (DashboardPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "自定义仪表盘",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SectionTitle("摘要组件")
            SwitchPreference(
                title = "显示总资产",
                checked = preferences.showTotalAssets,
                onCheckedChange = { onPreferencesChange(preferences.copy(showTotalAssets = it)) }
            )
            SwitchPreference(
                title = "显示净资产",
                checked = preferences.showNetAssets,
                onCheckedChange = { onPreferencesChange(preferences.copy(showNetAssets = it)) }
            )
            SwitchPreference(
                title = "显示收入预估",
                checked = preferences.showProfit,
                onCheckedChange = { onPreferencesChange(preferences.copy(showProfit = it)) }
            )
            SwitchPreference(
                title = "显示资产分布",
                checked = preferences.showDistribution,
                onCheckedChange = { onPreferencesChange(preferences.copy(showDistribution = it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("资产类别")
            SwitchPreference(
                title = "显示银行存款",
                checked = preferences.showBankDeposits,
                onCheckedChange = { onPreferencesChange(preferences.copy(showBankDeposits = it)) }
            )
            SwitchPreference(
                title = "显示股票",
                checked = preferences.showStocks,
                onCheckedChange = { onPreferencesChange(preferences.copy(showStocks = it)) }
            )
            SwitchPreference(
                title = "显示基金",
                checked = preferences.showFunds,
                onCheckedChange = { onPreferencesChange(preferences.copy(showFunds = it)) }
            )
            SwitchPreference(
                title = "显示虚拟货币",
                checked = preferences.showCrypto,
                onCheckedChange = { onPreferencesChange(preferences.copy(showCrypto = it)) }
            )
            SwitchPreference(
                title = "显示负债",
                checked = preferences.showLiabilities,
                onCheckedChange = { onPreferencesChange(preferences.copy(showLiabilities = it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("默认排序")
            SortOrder.entries.forEach { sortOrder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPreferencesChange(preferences.copy(sortOrder = sortOrder))
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = preferences.sortOrder == sortOrder,
                        onClick = {
                            onPreferencesChange(preferences.copy(sortOrder = sortOrder))
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when (sortOrder) {
                            SortOrder.BY_TYPE -> "按类型"
                            SortOrder.BY_VALUE_DESC -> "金额从高到低"
                            SortOrder.BY_VALUE_ASC -> "金额从低到高"
                            SortOrder.BY_NAME -> "按名称"
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("默认历史周期")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val periods = listOf(
                    "1W" to "1周",
                    "1M" to "1月",
                    "3M" to "3月",
                    "6M" to "6月",
                    "1Y" to "1年",
                    "ALL" to "全部"
                )
                periods.forEach { (value, label) ->
                    FilterChip(
                        selected = preferences.defaultPeriod == value,
                        onClick = { onPreferencesChange(preferences.copy(defaultPeriod = value)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { onPreferencesChange(DashboardPreferences()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("恢复默认设置")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
