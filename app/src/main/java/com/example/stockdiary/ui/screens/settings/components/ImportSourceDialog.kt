package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

/**
 * 导入来源枚举：「导入持仓与资产」合并入口下的 4 个选项。
 *
 * 设置页原来把 4 种导入方式平铺成 4 行，「数据」区一半是导入、找不到重点；
 * 收敛成一个列表对话框后，点某一项再打开对应的既有导入对话框——
 * 功能一个不少，只是入口多一层抽屉。
 */
enum class ImportSource {
    /** 券商 CSV / 对账单截图 → [ImportBrokerageDialog] */
    BROKERAGE,

    /** 中国结算「一码通持有查询」截图 → [HoldingSnapshotDialog]（source = STOCK_SNAPSHOT） */
    STOCK_SNAPSHOT,

    /** 基金E账户导出的持有明细 → [HoldingSnapshotDialog]（source = FUND_EXPORT） */
    FUND_EXPORT,

    /** 币安现货持仓只读同步 → [BinanceImportDialog] */
    BINANCE
}

/** 每个导入来源在列表里的图标与文案，集中一处避免散落到调用方 */
private data class ImportSourceMeta(
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

private fun ImportSource.meta(): ImportSourceMeta = when (this) {
    ImportSource.BROKERAGE -> ImportSourceMeta(
        icon = Icons.Default.UploadFile,
        title = "导入交割单",
        subtitle = "券商 CSV 或对账单截图，批量导入交易"
    )
    ImportSource.STOCK_SNAPSHOT -> ImportSourceMeta(
        icon = Icons.Default.AccountBalanceWallet,
        title = "导入持仓截图",
        subtitle = "中国结算「一码通持有查询」，场内股票/ETF/LOF/REITs"
    )
    ImportSource.FUND_EXPORT -> ImportSourceMeta(
        icon = Icons.Default.Savings,
        title = "导入场外基金",
        subtitle = "基金E账户导出的持有明细，场外公募基金"
    )
    ImportSource.BINANCE -> ImportSourceMeta(
        icon = Icons.Default.CurrencyBitcoin,
        title = "同步币安资产",
        subtitle = "只读拉取币安现货持仓，按代码合并到加密资产"
    )
}

/**
 * 「导入持仓与资产」列表对话框：4 种导入方式共用一个入口。
 *
 * 点击某一项时先 [onDismiss] 收起本对话框，再 [onSelect] 打开对应的
 * 既有导入对话框，避免两个导入界面叠在一起。
 */
@Composable
fun ImportSourceDialog(
    onSelect: (ImportSource) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "导入持仓与资产",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ImportSource.entries.forEach { source ->
                    val (icon, title, subtitle) = source.meta()
                    ListItem(
                        modifier = Modifier.clickable {
                            onDismiss()
                            onSelect(source)
                        },
                        leadingContent = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        headlineContent = { Text(title) },
                        supportingContent = { Text(subtitle) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
