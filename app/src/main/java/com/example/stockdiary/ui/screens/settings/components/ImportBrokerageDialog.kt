package com.example.stockdiary.ui.screens.settings.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.ui.components.import.ImportCommittingStep
import com.example.stockdiary.ui.components.import.ImportErrorDialog
import com.example.stockdiary.ui.components.import.ImportWizardScaffold
import com.example.stockdiary.ui.viewmodels.ImportBrokerageViewModel

/**
 * 交割单批量导入全屏 Dialog：选文件 → 列映射 → 预览 → 提交 → 报告。
 *
 * 全屏外壳、错误弹窗与写入中占位已抽到
 * `com.example.stockdiary.ui.components.import` 公共骨架；结果报告页是
 * 逐行失败明细的另一套形状，没有并入公共 ImportResultStep，保留在本文件外部的
 * ImportBrokerageComponents 里。
 */
@Composable
fun ImportBrokerageDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val viewModel: ImportBrokerageViewModel = viewModel(
        factory = ImportBrokerageViewModel.factory(container)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.error != null) {
        ImportErrorDialog(
            title = "导入未完成",
            message = state.error!!,
            onDismiss = { viewModel.clearError() },
            rawText = state.ocrRawText,
            // 交割单的错误弹窗旧行为：标题不加粗、无「知道了」确认按钮、
            // 只有「关闭」；有 OCR 原文时确认按钮是「复制原文」
            titleBold = false,
            confirmText = null,
            dismissText = "关闭"
        )
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onFileSelected(uri) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onImageSelected(uri) }

    // OCR 路径跳过 MAPPING，不显示步骤计数，避免误导
    val isOcr = state.source == ImportBrokerageViewModel.Source.OCR

    ImportWizardScaffold(
        title = when (state.step) {
            ImportBrokerageViewModel.Step.SELECT_FILE -> "导入交割单"
            ImportBrokerageViewModel.Step.MAPPING -> "步骤 1/3 · 列映射"
            ImportBrokerageViewModel.Step.PREVIEW ->
                if (isOcr) "预览确认" else "步骤 2/3 · 预览确认"
            ImportBrokerageViewModel.Step.COMMITTING -> "正在导入…"
            ImportBrokerageViewModel.Step.RESULT ->
                if (isOcr) "导入完成" else "步骤 3/3 · 导入完成"
        },
        onDismiss = onDismiss,
        onReset = viewModel::reset,
        bottomBar = {
            BottomActionBar(state, viewModel, onDismiss, fileLauncher)
        }
    ) {
        when (state.step) {
            ImportBrokerageViewModel.Step.SELECT_FILE ->
                SelectFileStep(fileLauncher, imageLauncher, state.recognizing)
            ImportBrokerageViewModel.Step.MAPPING -> MappingStep(state, viewModel)
            ImportBrokerageViewModel.Step.PREVIEW -> PreviewStep(state, viewModel)
            ImportBrokerageViewModel.Step.COMMITTING ->
                ImportCommittingStep(text = "正在回放重建持仓…")
            ImportBrokerageViewModel.Step.RESULT -> ResultStep(state)
        }
    }
}
