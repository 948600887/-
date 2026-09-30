package com.example.stockdiary.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.screens.*
import com.example.stockdiary.ui.viewmodels.DashboardViewModel
import com.example.stockdiary.ui.viewmodels.PortfolioViewModel
import com.example.stockdiary.ui.viewmodels.AssetDetailViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "资产", Icons.Filled.AccountBalance)
    data object Portfolio : Screen("portfolio", "持仓", Icons.Filled.PieChart)
    data object Calendar : Screen("calendar", "日历", Icons.Filled.CalendarMonth)
    data object History : Screen("history", "历史", Icons.Filled.History)
    data object Settings : Screen("settings", "我的", Icons.Filled.Settings)
}

private val screensByRoute = listOf(
    Screen.Dashboard,
    Screen.Portfolio,
    Screen.Calendar,
    Screen.History,
    Screen.Settings
).associateBy { it.route }

@Composable
fun GoldVaultNavHost() {
    val container = LocalAppContainer.current

    var selectedScreenRoute by rememberSaveable { mutableStateOf(Screen.Dashboard.route) }
    var currentDetailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedScreen = screensByRoute[selectedScreenRoute] ?: Screen.Dashboard

    val dashboardViewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory(container))
    val portfolioViewModel: PortfolioViewModel = viewModel(factory = PortfolioViewModel.Factory(container))

    val detailViewModel = currentDetailId?.let { id ->
        viewModel<AssetDetailViewModel>(
            key = "detail_$id",
            factory = AssetDetailViewModel.Factory(container, id)
        )
    }

    val bottomNavigationItems = listOf(
        Screen.Dashboard,
        Screen.Portfolio,
        Screen.Calendar,
        Screen.History,
        Screen.Settings
    )

    val isDetailScreen = currentDetailId != null

    BackHandler(enabled = isDetailScreen) {
        currentDetailId = null
    }

    Scaffold(
        bottomBar = {
            if (!isDetailScreen) {
                NavigationBar {
                    bottomNavigationItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = selectedScreenRoute == screen.route,
                            onClick = { selectedScreenRoute = screen.route }
                        )
                    }
                }
            }
        }
    ) { padding ->
        val detailId = currentDetailId
        val detailVm = detailViewModel
        if (detailId != null && detailVm != null) {
            AssetDetailScreen(
                assetId = detailId,
                viewModel = detailVm,
                onNavigateBack = { currentDetailId = null },
                modifier = Modifier.padding(padding)
            )
        } else {
            when (selectedScreen) {
                Screen.Dashboard -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    onNavigateToAssetDetail = { currentDetailId = it },
                    modifier = Modifier.padding(padding)
                )
                Screen.Portfolio -> PortfolioScreen(
                    viewModel = portfolioViewModel,
                    onNavigateToAssetDetail = { currentDetailId = it },
                    modifier = Modifier.padding(padding)
                )
                Screen.Calendar -> CalendarScreen(
                    modifier = Modifier.padding(padding)
                )
                Screen.History -> HistoryScreen(
                    modifier = Modifier.padding(padding)
                )
                Screen.Settings -> SettingsScreen(
                    container = container,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}