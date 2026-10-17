package com.example.stockdiary.ui.components.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 预览步的底部动作条：「重选/重填」+「导入 N 条/项」。
 *
 * 持仓截图与币安两条链路的底部动作条此前逐字相同，差异只有两处：重选按钮
 * 文案（「重选」/「重填」）与导入按钮量词（「条」/「项」）——做成参数保留。
 * 交割单链路的底部动作条按 step 分派的内容完全不同（取消/CSV 文件、上一步、
 * 正在写入…、再导入一次），未并入本组件，保留在其来源文件里。
 *
 * @param resetText 左侧重选按钮文案。
 * @param unit 导入按钮的量词：「条」或「项」。
 * @param pendingCount 待导入条数（NEW + UPDATE）；为 0 时导入按钮禁用。
 * @param onReset 重选/重填回调（通常是 ViewModel.reset）。
 * @param onCommit 确认导入回调（通常是 ViewModel.commit）。
 */
@Composable
fun ImportPreviewBottomBar(
    resetText: String,
    unit: String,
    pendingCount: Int,
    onReset: () -> Unit,
    onCommit: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Text(resetText)
            }
            Button(
                onClick = onCommit,
                enabled = pendingCount > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (pendingCount > 0) "导入 $pendingCount $unit" else "没有需要导入的")
            }
        }
    }
}
