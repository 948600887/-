package com.example.stockdiary.ui.components.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 导入写入中的占位步骤：转圈 + 一行说明。
 *
 * 三个 Dialog 的 COMMITTING 步此前逐字相同，唯一差异是文案：持仓截图与币安是
 * 「正在写入…」，交割单是「正在回放重建持仓…」——用 [text] 参数保留，不强行统一。
 *
 * @param text 提示文案。
 */
@Composable
fun ImportCommittingStep(text: String = "正在写入…") {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
