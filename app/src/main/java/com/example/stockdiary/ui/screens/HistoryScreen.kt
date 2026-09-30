package com.example.stockdiary.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.screens.history.components.HistorySaveSnapshotDialog
import com.example.stockdiary.ui.screens.history.components.historyAssetDistribution
import com.example.stockdiary.ui.screens.history.components.historyMonthlyReturnChart
import com.example.stockdiary.ui.screens.history.components.historyOverviewCard
import com.example.stockdiary.ui.screens.history.components.historyPeriodReturnCard
import com.example.stockdiary.ui.screens.history.components.historyPeriodSelector
import com.example.stockdiary.ui.screens.history.components.historyRiskCard
import com.example.stockdiary.ui.screens.history.components.historyTrendChart
import com.example.stockdiary.ui.theme.OnSurfaceVariant
import com.example.stockdiary.ui.viewmodels.HistoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(container))
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    val saveResult = uiState.saveResult
    LaunchedEffect(saveResult) {
        saveResult?.let { result ->
            if (result.isSuccess) {
                snackbarHostState.showSnackbar("保存成功")
                showSaveDialog = false
            }
            viewModel.clearSaveResult()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("资产分析", fontWeight = FontWeight.Bold) },
                actions = {
                    if (uiState.hasTodaySnapshot) {
                        Text(
                            text = "今日已保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(
                        onClick = { showSaveDialog = true },
                        enabled = !uiState.isSaving
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "保存快照",
                                tint = if (uiState.hasTodaySnapshot) OnSurfaceVariant else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.filteredSnapshots.isEmpty()) {
            EmptyHistoryContent(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            val latest = uiState.filteredSnapshots.last()
            val earliest = uiState.filteredSnapshots.first()

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                val trendChartHeight = maxOf(maxHeight * 0.3f, 180.dp)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    historyPeriodSelector(
                        selectedPeriod = uiState.selectedPeriod,
                        onPeriodSelected = { viewModel.selectPeriod(it) }
                    )
                    historyOverviewCard(latest = latest)
                    historyTrendChart(
                        snapshots = uiState.filteredSnapshots,
                        selectedPeriod = uiState.selectedPeriod,
                        height = trendChartHeight
                    )
                    historyPeriodReturnCard(earliest = earliest, latest = latest)

                    val statistics = uiState.statistics
                    if (statistics != null) {
                        historyRiskCard(stats = statistics)
                    }

                    historyMonthlyReturnChart(monthlyReturns = uiState.monthlyReturns)
                    historyAssetDistribution(latest = latest)
                }
            }
        }
    }

    if (showSaveDialog) {
        HistorySaveSnapshotDialog(
            assets = uiState.assets,
            isSaving = uiState.isSaving,
            onDismiss = { showSaveDialog = false },
            onConfirm = { viewModel.saveSnapshot() }
        )
    }
}

@Composable
private fun EmptyHistoryContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.ShowChart, contentDescription = null,
                modifier = Modifier.size(64.dp), tint = OnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("暂无历史数据", style = MaterialTheme.typography.titleMedium)
            Text("点击右上角保存第一份快照", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        }
    }
}
