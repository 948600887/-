package com.example.stockdiary.ui.screens.settings.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.viewmodels.ImportBrokerageViewModel

/**
 * 交割单批量导入全屏 Dialog：选文件 → 列映射 → 预览 → 提交 → 报告。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBrokerageDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val viewModel: ImportBrokerageViewModel = viewModel(
        factory = ImportBrokerageViewModel.Factory(container)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ViewModel 挂在宿主 Activity 的作用域上，对话框关掉后实例还活着——
    // 不复位的话下次打开会残留上一次的文件、映射和预览数据
    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    if (state.error != null) {
        ErrorDialog(
            error = state.error!!,
            rawText = state.ocrRawText,
            onDismiss = { viewModel.clearError() }
        )
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onFileSelected(uri) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onImageSelected(uri) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            // OCR 路径跳过 MAPPING，不显示步骤计数，避免误导
                            val isOcr = state.source == ImportBrokerageViewModel.Source.OCR
                            val title = when (state.step) {
                                ImportBrokerageViewModel.Step.SELECT_FILE -> "导入交割单"
                                ImportBrokerageViewModel.Step.MAPPING -> "步骤 1/3 · 列映射"
                                ImportBrokerageViewModel.Step.PREVIEW ->
                                    if (isOcr) "预览确认" else "步骤 2/3 · 预览确认"
                                ImportBrokerageViewModel.Step.COMMITTING -> "正在导入…"
                                ImportBrokerageViewModel.Step.RESULT ->
                                    if (isOcr) "导入完成" else "步骤 3/3 · 导入完成"
                            }
                            Text(title, fontWeight = FontWeight.Bold)
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                    )
                },
                bottomBar = {
                    BottomActionBar(state, viewModel, onDismiss, fileLauncher)
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (state.step) {
                        ImportBrokerageViewModel.Step.SELECT_FILE ->
                            SelectFileStep(fileLauncher, imageLauncher, state.recognizing)
                        ImportBrokerageViewModel.Step.MAPPING -> MappingStep(state, viewModel)
                        ImportBrokerageViewModel.Step.PREVIEW -> PreviewStep(state, viewModel)
                        ImportBrokerageViewModel.Step.COMMITTING -> CommittingStep()
                        ImportBrokerageViewModel.Step.RESULT -> ResultStep(state)
                    }
                }
            }
        }
    }
}
