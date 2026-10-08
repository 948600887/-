package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.ui.theme.LossRed
import com.example.stockdiary.utils.format

@Composable
fun FireCalculationDialog(
    assets: List<Asset>,
    realAnnualIncome: Double,
    monthlyExpense: String,
    onDismiss: () -> Unit,
    onExpenseChange: (String) -> Unit
) {
    val totalValue = assets.sumOf { it.totalValue }
    val annualDividend = realAnnualIncome
    val monthlyDividend = annualDividend / 12
    val expense = monthlyExpense.toDoubleOrNull() ?: 5000.0
    val realYield = if (totalValue > 0) (annualDividend / totalValue) * 100 else 0.0
    val fireCoverage = if (expense > 0) (monthlyDividend / expense) * 100 else 0.0
    val fireTarget = if (realYield > 0) (expense * 12) / (realYield / 100) else 0.0
    val fireProgress = if (fireTarget > 0) (totalValue / fireTarget) * 100 else 0.0

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
                    onValueChange = onExpenseChange,
                    label = { Text("月度生活费 (元)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
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
                            color = if (realYield > 0) LossRed else MaterialTheme.colorScheme.onSurfaceVariant
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
                        Text("月度收益", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("当前持仓: ¥${totalValue.format(0)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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