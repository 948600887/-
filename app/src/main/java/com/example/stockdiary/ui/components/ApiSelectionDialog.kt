package com.example.stockdiary.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stockdiary.data.price.ApiManager

data class PriceApiOption(
    val name: String,
    val description: String,
    val features: String
)

@Composable
fun ApiSelectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (priceApi: String) -> Unit,
    /** 次级入口：查看各行情接口的可用性与延迟（原设置页「数据源状态」项，入口收敛到这里） */
    onViewStatus: () -> Unit = {}
) {
    val context = LocalContext.current
    
    var selectedPriceApi by remember { 
        mutableStateOf(ApiManager.getPriceApi(context)) 
    }
    
    val priceApiOptions = listOf(
        PriceApiOption("新浪财经", "国内A股数据稳定，支持实时行情", "A股、基金"),
        PriceApiOption("腾讯财经", "数据全面，延迟较低", "A股、港股"),
        // 第三个选项与 ApiManager.API_SKILL 对应：磁盘上存的值可能就是它，
        // 少这一项会导致打开对话框时两个选项都不呈选中态，用户以为设置丢了。
        // 数据链路（StockDataManager.getDataSource）本就支持该值，这里只补入口。
        PriceApiOption(ApiManager.API_SKILL, "需安装东方财富妙想 Skill 后生效", "AI增强")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                text = "股价数据源设置",
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                priceApiOptions.forEach { option ->
                    ApiOptionItem(
                        option = option,
                        isSelected = selectedPriceApi == option.name,
                        onClick = { selectedPriceApi = option.name }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "分红数据源已自动优化",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "股票 → 东方财富\nETF → 新浪财经\n场外基金 → 天天基金",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // 原「数据源状态」设置项的容身之处：选数据源与查接口健康是同一件事的两面
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = onViewStatus) {
                        Text("查看各数据源状态")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ApiManager.savePriceApi(context, selectedPriceApi)
                    onConfirm(selectedPriceApi)
                }
            ) {
                Text("保存并刷新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ApiOptionItem(
    option: PriceApiOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary
                )
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.name,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = option.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = option.features,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
