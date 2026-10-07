package com.example.stockdiary.ui.navigation

import android.app.Activity
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
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

private const val BACK_TO_EXIT_WINDOW_MS = 2000L

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

    // 一级页面：先退回「资产」页；已在首页则要求「再按一次」才退出，避免误触丢 App。
    // 子页面里注册的 BackHandler（如持仓批量选择态）优先级更高，会先于这里处理。
    val context = LocalContext.current
    var lastBackPressAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = !isDetailScreen) {
        if (selectedScreenRoute != Screen.Dashboard.route) {
            selectedScreenRoute = Screen.Dashboard.route
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressAt < BACK_TO_EXIT_WINDOW_MS) {
                (context as? Activity)?.finish()
            } else {
                lastBackPressAt = now
                Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
            }
        }
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