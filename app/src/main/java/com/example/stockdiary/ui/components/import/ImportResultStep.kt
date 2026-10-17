package com.example.stockdiary.ui.components.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.stockdiary.ui.theme.LossRed
import com.example.stockdiary.ui.theme.ProfitGreen

/**
 * 导入结果统计。把持仓截图与币安两条链路的 CommitResult 口径统一到这里：
 * 新增 / 覆盖 / 未变 + 冲突数 + 结果页补充说明文案。
 *
 * @param inserted 新增条数。
 * @param updated 覆盖（更新）条数。
 * @param skipped 未变（无变化）条数。
 * @param conflicts 因库里同名持仓不唯一而跳过的条数；为 0 时不展示冲突提示。
 * @param extraNote 结果页的补充说明文案（各来源不同，由调用方给出）。
 */
data class ImportCommitSummary(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val conflicts: Int = 0,
    val extraNote: String
)

/**
 * 导入完成的结果页：统计行 + 冲突提示 + 补充说明 + 可选的后续动作插槽。
 *
 * 合并自持仓截图与币安两条链路的 ResultStep（两者布局逐字相同，只有统计行的
 * 量词「条 / 项」与补充文案不同）。交割单的结果页是另一套形状（逐行失败明细），
 * 没有并入本组件，保留在其来源文件里。
 *
 * @param summary 结果统计。
 * @param unit 统计量词：「条」（持仓截图）或「项」（币安）。
 * @param syncAction 可选的后续动作插槽（如持仓截图链路的「同步行情」按钮组）。
 *   非空时在补充说明下方渲染；插槽内部只放动作本身，间距由本组件统一留。
 */
@Composable
fun ImportResultStep(
    summary: ImportCommitSummary,
    unit: String = "条",
    syncAction: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = ProfitGreen
        )
        Spacer(Modifier.height(16.dp))
        Text("导入完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "新增 ${summary.inserted} $unit · 覆盖 ${summary.updated} $unit · " +
                "未变 ${summary.skipped} $unit",
            style = MaterialTheme.typography.bodyLarge
        )
        // 跳过不是「没做」，是「不敢替你做」。不报出来就等于静默丢数据
        if (summary.conflicts > 0) {
            Spacer(Modifier.height(8.dp))
            Row {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = LossRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "有 ${summary.conflicts} 条因为库里同名持仓不唯一而跳过，" +
                        "请在持仓页手动处理后再导入一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            summary.extraNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (syncAction != null) {
            Spacer(Modifier.height(20.dp))
            syncAction()
        }
    }
}
