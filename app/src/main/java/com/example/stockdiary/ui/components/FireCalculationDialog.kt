package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.ui.dialogs.sanitizeDecimalInput
import com.example.stockdiary.ui.theme.AppTheme
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.totalValueCny
import com.example.stockdiary.utils.sumTotalValueCny
import com.example.stockdiary.utils.sumDebtValueCny
import com.example.stockdiary.utils.excludingDebts
import com.example.stockdiary.utils.debts

@Composable
fun FireCalculationDialog(
    assets: List<Asset>,
    realAnnualIncome: Double,
    monthlyExpense: String,
    onDismiss: () -> Unit,
    onExpenseChange: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val expense = monthlyExpense.toDoubleOrNull() ?: 5000.0
    // FIRE 分子（年分红）是人民币口径，分母也必须折算成人民币；
    // 分母统一用【净资产】口径（非负债资产 − 负债资产），与 AssetSnapshot.fromAssets 一致，
    // 避免账户有贷款/信用卡/房贷等负债时 FIRE 目标被系统性夸大。
    val metrics = computeFireMetrics(assets, realAnnualIncome, expense)
    val totalValue = metrics.netWorth
    val annualDividend = realAnnualIncome
    val monthlyDividend = metrics.monthlyDividend
    val realYield = metrics.realYield
    val fireCoverage = metrics.fireCoverage
    val fireTarget = metrics.fireTarget
    val fireProgress = metrics.fireProgress

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Savings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("FIRE进度计算", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text("FIRE (Financial Independence, Retire Early)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = monthlyExpense,
                    onValueChange = { onExpenseChange(sanitizeDecimalInput(it)) },
                    label = { Text("月度生活费 (元)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("基于年化收入率推算", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${realYield.format(2)}%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (realYield > 0) AppTheme.colors.lossRed else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("实际收益情况", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("年化收入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("¥${annualDividend.format(0)}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("年化收入率", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${realYield.format(2)}%", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("月度收入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("¥${monthlyDividend.format(0)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("FIRE覆盖率", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${fireCoverage.format(1)}%",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (fireCoverage >= 100) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("FIRE进度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (fireProgress / 100).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (fireCoverage >= 100) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("${fireProgress.format(1)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(16.dp))
                if (realYield > 0) {
                    Text("FIRE目标: ¥${fireTarget.format(0)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("当前净资产: ¥${totalValue.format(0)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("暂无分红数据，无法推算FIRE目标", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * FIRE 进度核心计算。分母统一使用【净资产】口径：
 * 非负债资产（含负余额加密货币，其 totalValueCny 为负、自动扣减）减去 负债资产，
 * 与 [com.example.stockdiary.database.AssetSnapshot.fromAssets] 的 netAssets 一致。
 * 用净资产而非毛资产，避免账户有贷款/信用卡/房贷等负债时 FIRE 目标被系统性夸大。
 */
internal data class FireMetrics(
    val netWorth: Double,
    val monthlyDividend: Double,
    val realYield: Double,
    val fireCoverage: Double,
    val fireTarget: Double,
    val fireProgress: Double
)

internal fun computeFireMetrics(
    assets: List<Asset>,
    realAnnualIncome: Double,
    monthlyExpense: Double
): FireMetrics {
    val nonLiabilityValue = assets.excludingDebts().sumTotalValueCny()
    val liabilityValue = assets.debts().sumDebtValueCny()
    val netWorth = nonLiabilityValue - liabilityValue

    val annualDividend = realAnnualIncome
    val monthlyDividend = annualDividend / 12
    val realYield = if (netWorth > 0) (annualDividend / netWorth) * 100 else 0.0
    val fireCoverage = if (monthlyExpense > 0) (monthlyDividend / monthlyExpense) * 100 else 0.0
    val fireTarget = if (realYield > 0) (monthlyExpense * 12) / (realYield / 100) else 0.0
    val fireProgress = if (fireTarget > 0) (netWorth / fireTarget) * 100 else 0.0
    return FireMetrics(netWorth, monthlyDividend, realYield, fireCoverage, fireTarget, fireProgress)
}