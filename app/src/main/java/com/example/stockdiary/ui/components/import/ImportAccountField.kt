package com.example.stockdiary.ui.components.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「导入到账户」输入块：标题 + 账户名输入框 + 已有账户快捷 chips。
 *
 * 持仓截图与币安两条链路的预览页此前逐字重复这一块，唯一差异是输入框的
 * placeholder（留空时记为「默认账户」还是「币安」）——用 [placeholder] 保留。
 *
 * @param account 当前账户名。
 * @param knownAccounts 库里已有的账户名，全部以候选 chip 展示并自动换行；当前选中的会高亮。
 * @param onAccountChange 账户名变更回调。
 * @param placeholder 输入框占位文案。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportAccountField(
    account: String,
    knownAccounts: List<String>,
    onAccountChange: (String) -> Unit,
    placeholder: String = "不填则记为「默认账户」"
) {
    val focusManager = LocalFocusManager.current
    Column {
        Text("导入到账户", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = account,
            onValueChange = onAccountChange,
            label = { Text("账户名") },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            supportingText = {
                Text(
                    "只用于新建的持仓；已存在的持仓保持它原来的账户不变",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        )
        if (knownAccounts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 展示库里全部已知账户（不再截断前 4 个）：账户多时自动换行，
                // 第 5 个起也能点选，而不是只能手敲精确名字。
                // selected 高亮当前选中项：init 会预填第一个账户，点已选中的那个
                // 值不会变，有高亮才看得出来「已经选了它」，避免「选了没反应」的错觉。
                knownAccounts.forEach { name ->
                    FilterChip(
                        selected = account == name,
                        onClick = { onAccountChange(name) },
                        label = { Text(name, fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}
