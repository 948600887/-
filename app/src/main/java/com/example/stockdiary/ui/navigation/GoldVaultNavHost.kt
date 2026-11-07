package com.example.stockdiary.ui.navigation

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockdiary.di.LocalAppContainer
import com.example.stockdiary.ui.components.AppSnackbarController
import com.example.stockdiary.ui.components.LocalAppSnackbar
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

/** 沿 ContextWrapper 链找到真正的 Activity（LocalContext 里拿到的往往是被包过的 Context） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun GoldVaultNavHost() {
    val container = LocalAppContainer.current

    var selectedScreenRoute by rememberSaveable { mutableStateOf(Screen.Dashboard.route) }
    var currentDetailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedScreen = screensByRoute[selectedScreenRoute] ?: Screen.Dashboard

    val dashboardViewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
    val portfolioViewModel: PortfolioViewModel = viewModel(factory = PortfolioViewModel.factory(container))

    val detailViewModel = currentDetailId?.let { id ->
        viewModel<AssetDetailViewModel>(
            key = "detail_$id",
            factory = AssetDetailViewModel.factory(container, id)
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

    // 全应用唯一的 Snackbar 宿主：页面只负责「说什么」，不持有宿主。
    // 原先每个页面各自 remember { SnackbarHostState() } + Scaffold(snackbarHost=...)，
    // 切 Tab 时页面整个移出组合，宿主与 showSnackbar 的协程一起被取消，
    // 提示刚弹出就消失；回到该页又因为状态没清而全时长重弹一遍（看起来像停留时间被刷新）。
    //
    // 现在 HostState 与协程作用域都挂在导航层（Activity 级），切页不受影响，
    // Material3 的自动关闭计时也只在创建时启动一次，不会被页面重组重置。
    val snackbarHostState = remember { SnackbarHostState() }
    val navScope = rememberCoroutineScope()
    val appSnackbar = remember(snackbarHostState, navScope) {
        AppSnackbarController(snackbarHostState, navScope)
    }

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
                // 不能用 `context as? Activity`：LocalContext.current 常被 ContextThemeWrapper
                // 之类的装饰类包住，强转得到 null 后 finish() 静默失效，
                // 用户连按两次返回键也退不出 App。沿 ContextWrapper 链解包才稳。
                context.findActivity()?.finish()
            } else {
                lastBackPressAt = now
                // 用 Snackbar 代替 Toast，与全应用的反馈方式保持一致；
                // 已在显示时不再重复触发，避免「Snackbar host 正在显示」异常。
                if (snackbarHostState.currentSnackbarData == null) {
                    appSnackbar.show("再按一次退出")
                }
            }
        }
    }

    CompositionLocalProvider(LocalAppSnackbar provides appSnackbar) {
        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
            // 宿主只有这一处。底部多留 88dp：首页/持仓页的 FAB 就贴在内容区右下角，
            // 不留空隙的话提示条会正好压在按钮上（提示期间用户还得能点到「+」）。
            snackbarHost = {
                SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = 88.dp))
            },
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
                        onNavigateToPortfolio = { selectedScreenRoute = Screen.Portfolio.route },
                        modifier = Modifier.padding(padding)
                    )
                    Screen.Portfolio -> PortfolioScreen(
                        viewModel = portfolioViewModel,
                        onNavigateToAssetDetail = { currentDetailId = it },
                        modifier = Modifier.padding(padding)
                    )
                    Screen.Calendar -> CalendarScreen(
                        // 日历空状态的「去添加资产」：跳到仪表盘（添加资产入口所在页）
                        onNavigateToAssets = { selectedScreenRoute = Screen.Dashboard.route },
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
}