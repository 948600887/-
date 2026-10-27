package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.stockdiary.data.prefs.DashboardPreferences
import com.example.stockdiary.data.prefs.SortOrder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                // 关键修复：内容远超一屏，这里必须可滚动，否则 ModalBottomSheet 撑到上限后
                // 下方（排序 / 历史周期 / 恢复默认）被裁掉且够不着——即「不能下滑」。
                .verticalScroll(rememberScrollState())
                // 阈值输入框弹键盘时，把内容顶到键盘之上，避免输入框被键盘挡住。
                .imePadding()
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
            SwitchPreference(
                title = "显示资产增值",
                checked = preferences.showAssetGrowth,
                onCheckedChange = { onPreferencesChange(preferences.copy(showAssetGrowth = it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("收入组件")
            SwitchPreference(
                title = "显示收息进度",
                checked = preferences.showDividendProgress,
                onCheckedChange = { onPreferencesChange(preferences.copy(showDividendProgress = it)) }
            )
            SwitchPreference(
                title = "显示下一笔分红",
                checked = preferences.showNextPayout,
                onCheckedChange = { onPreferencesChange(preferences.copy(showNextPayout = it)) }
            )
            SwitchPreference(
                title = "显示月度现金流",
                checked = preferences.showMonthlyCashFlow,
                onCheckedChange = { onPreferencesChange(preferences.copy(showMonthlyCashFlow = it)) }
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

            SwitchPreference(
                title = "隐藏低价值虚拟货币",
                checked = preferences.hideLowValueCrypto,
                onCheckedChange = { onPreferencesChange(preferences.copy(hideLowValueCrypto = it)) }
            )
            if (preferences.hideLowValueCrypto) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "低于此金额（¥）隐藏")
                    OutlinedTextField(
                        value = preferences.lowValueThresholdCny,
                        onValueChange = { onPreferencesChange(preferences.copy(lowValueThresholdCny = it)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp)
                    )
                }
                // 子开关：仅在「隐藏低价值」开启时有意义，所以嵌套在它下面显示。
                // 默认关：无行情不等于没持仓，开启即表示用户接受「可能藏掉真实持仓」的取舍。
                SwitchPreference(
                    title = "同时隐藏无价格虚拟货币",
                    checked = preferences.hideUnknownPriceCrypto,
                    onCheckedChange = { onPreferencesChange(preferences.copy(hideUnknownPriceCrypto = it)) }
                )
            }

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
            // 用 FlowRow：6 个周期 chip 横排在窄屏会被裁掉/溢出，改成自动换行更整齐。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                onClick = {
                    // 「恢复默认」只重置本 sheet 负责的那部分配置（卡片显示开关 / 类别开关 /
                    // 隐藏低价值阈值 / 排序 / 历史周期），其余一律保持原值。
                    //
                    // 不能整块写 DashboardPreferences()：月度生活费（日历 FIRE 算出来的）、
                    // 年度分红缓存、分组展开态**都不在这个 sheet 里渲染**，整块回默认会把它们
                    // 静默清掉——月度生活费退回默认的 5000，正是「共享整块偏好互相踩踏」那一类 bug。
                    onPreferencesChange(
                        DashboardPreferences(
                            monthlyExpense = preferences.monthlyExpense,
                            annualDividendCache = preferences.annualDividendCache,
                            annualDividendSymbols = preferences.annualDividendSymbols,
                            annualDividendFingerprint = preferences.annualDividendFingerprint,
                            defaultExpanded = preferences.defaultExpanded
                        )
                    )
                },
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
