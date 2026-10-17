package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stockdiary.ui.viewmodels.ImportBrokerageViewModel

/**
 * 交割单导入向导的各步骤 UI 组件，从 ImportBrokerageDialog 拆出，避免单文件过长。
 * 这些组件只被 ImportBrokerageDialog 调用。
 *
 * 错误弹窗（ErrorDialog）、识别原文（OcrRawTextDialog）与写入中占位（CommittingStep）
 * 已抽到 `com.example.stockdiary.ui.components.import` 公共骨架，本文件只保留
 * 交割单特有的步骤：选来源、列映射、逐日结果报告，以及按 step 分派的底部动作条。
 */

@Composable
internal fun BottomActionBar(
    state: ImportBrokerageViewModel.UiState,
    viewModel: ImportBrokerageViewModel,
    onDismiss: () -> Unit,
    fileLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state.step) {
                ImportBrokerageViewModel.Step.SELECT_FILE -> {
                    // OCR 识别中禁用按钮，避免同一张图被重复提交两次识别
                    OutlinedButton(onClick = onDismiss, enabled = !state.recognizing) { Text("取消") }
                    Button(
                        onClick = { fileLauncher.launch(arrayOf("text/csv", "*/*")) },
                        enabled = !state.recognizing
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("CSV 文件")
                    }
                }
                ImportBrokerageViewModel.Step.MAPPING -> {
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { viewModel.applyMapping() },
                        enabled = state.parseResult != null
                    ) { Text("下一步：预览") }
                }
                ImportBrokerageViewModel.Step.PREVIEW -> {
                    // OCR 来源没有映射步骤，上一步回到选文件
                    val backAction = if (state.source == ImportBrokerageViewModel.Source.OCR) {
                        { viewModel.reset() }
                    } else {
                        { viewModel.backToMapping() }
                    }
                    OutlinedButton(onClick = backAction) { Text("上一步") }
                    Button(
                        onClick = { viewModel.commit() },
                        enabled = !state.committing && state.transactions.any { it.errors.isEmpty() && !it.skip }
                    ) { Text("确认导入") }
                }
                ImportBrokerageViewModel.Step.COMMITTING -> {
                    Text("正在写入…", modifier = Modifier.padding(8.dp))
                }
                ImportBrokerageViewModel.Step.RESULT -> {
                    OutlinedButton(onClick = onDismiss) { Text("完成") }
                    Button(onClick = { viewModel.reset() }) { Text("再导入一次") }
                }
            }
        }
    }
}

@Composable
internal fun SelectFileStep(
    fileLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    imageLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    recognizing: Boolean = false
) {
    if (recognizing) {
        // OCR 识别中：给明确进度，不要让用户对着选择页以为没点上。
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在识别截图…", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "首次识别需加载模型，通常需要 1~3 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.UploadFile, contentDescription = null,
            modifier = Modifier.height(96.dp).width(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("选择导入方式", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持 CSV 文件（推荐，数据准确）\n或交割单截图 OCR 识别（需手动补填代码）",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { fileLauncher.launch(arrayOf("text/csv", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null)
            Spacer(Modifier.width(8.dp)); Text("从 CSV 文件导入")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { imageLauncher.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(Modifier.width(8.dp)); Text("从截图 OCR 识别")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "OCR 仅支持中国银河证券对账单格式",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun MappingStep(
    state: ImportBrokerageViewModel.UiState,
    viewModel: ImportBrokerageViewModel
) {
    val parsed = state.parseResult ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val headers = parsed.csv.headers
    val mapping = parsed.mapping

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("文件编码", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CharsetSelector(
                current = state.charsetName,
                options = viewModel.supportedCharsets.map { it.first },
                onSelected = { viewModel.changeCharset(it) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "检测到 ${headers.size} 列，${parsed.csv.rows.size} 行数据",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("列映射（可调整）", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        item {
            val fields = listOf(
                Triple("日期", mapping.dateCol, "dateCol"),
                Triple("代码", mapping.symbolCol, "symbolCol"),
                Triple("名称", mapping.nameCol, "nameCol"),
                Triple("方向", mapping.typeCol, "typeCol"),
                Triple("数量", mapping.quantityCol, "quantityCol"),
                Triple("价格", mapping.priceCol, "priceCol"),
                Triple("手续费", mapping.feeCol, "feeCol"),
                Triple("账户", mapping.accountCol, "accountCol")
            )
            fields.forEach { (label, col, _) ->
                ColumnRow(label, col, headers) { viewModel.updateMapping(label, it) }
                HorizontalDivider()
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "前 3 行预览：",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            parsed.csv.rows.take(3).forEachIndexed { i, row ->
                Text("$i: ${row.joinToString(" | ")}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
internal fun ColumnRow(
    label: String,
    currentCol: Int,
    headers: List<String>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = if (currentCol in headers.indices) headers[currentCol] else "不映射"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Box {
            TextButton(onClick = { expanded = true }) { Text(currentLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("不映射") }, onClick = {
                    onSelected(-1); expanded = false
                })
                headers.forEachIndexed { idx, h ->
                    DropdownMenuItem(text = { Text("$idx: $h") }, onClick = {
                        onSelected(idx); expanded = false
                    })
                }
            }
        }
    }
}

@Composable
internal fun CharsetSelector(
    current: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("当前：", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(onClick = { expanded = true }) { Text(current) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach {
                    DropdownMenuItem(text = { Text(it) }, onClick = {
                        onSelected(it); expanded = false
                    })
                }
            }
        }
    }
}

/**
 * 交割单的结果报告页：成功/跳过/失败计数 + 逐行失败明细。
 *
 * 与持仓截图/币安那种「新增 X · 覆盖 Y · 未变 Z」的结果页不是一套形状
 * （这里是逐行明细），因此没有并入公共 ImportResultStep。
 */
@Composable
internal fun ResultStep(
    state: ImportBrokerageViewModel.UiState
) {
    val result = state.commitResult ?: return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("导入完成", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("成功写入：${result.successCount} 条")
            Text("跳过重复：${result.skippedDuplicate} 条")
            Text("用户跳过：${result.skippedByUser} 条")
            Text("异常跳过：${result.skippedError} 条")
            Text("失败：${result.failedCount} 条")
            if (result.failures.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("失败明细：", fontWeight = FontWeight.Bold)
            }
        }
        items(result.failures, key = { it.rowNumber }) { f ->
            Text(
                "行 ${f.rowNumber}: ${f.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
