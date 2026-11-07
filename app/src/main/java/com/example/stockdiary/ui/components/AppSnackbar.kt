package com.example.stockdiary.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 应用级提示控制器：宿主与 [SnackbarHostState] 都挂在导航层，不在任何一个页面里。
 *
 * 页面自身持有 HostState 时，切 Tab 会把整个页面移出组合，`showSnackbar` 的
 * 调用协程随之取消，`currentSnackbarData` 被清空 —— 提示刚弹出就消失；
 * 页面若把「已提示」状态清在 await 之后，那个 clear 永远不会执行，
 * 回到该页又会用完整时长重弹一遍，看起来就像「停留时间被刷新了」。
 *
 * 因此所有提示都经由这里发出：协程跑在 [scope]（导航层的作用域）上，
 * 与具体页面的生命周期无关，切页不取消、也不重新计时。
 */
class AppSnackbarController(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope
) {

    /** 普通提示：立刻返回，不阻塞调用方，也不受页面切走影响。 */
    fun show(message: String, duration: SnackbarDuration = SnackbarDuration.Short) {
        scope.launch {
            hostState.showSnackbar(message, duration = duration)
        }
    }

    /**
     * 带操作的提示（如「重试」「去设置」）。
     *
     * 等待用户操作的过程也必须跑在应用作用域里：改用页面协程 await 的话，
     * 切走页面会取消 await，连正在显示的提示一起关掉，与普通提示是同一个毛病。
     */
    fun showWithAction(
        message: String,
        actionLabel: String,
        duration: SnackbarDuration = SnackbarDuration.Indefinite,
        onAction: () -> Unit
    ) {
        scope.launch {
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = duration
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/** 全应用唯一的 Snackbar 入口；缺少 Provider 说明被错误地在页面里直接使用。 */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbarController> {
    error("LocalAppSnackbar 未提供：提示必须由 GoldVaultNavHost 的应用级宿主统一展示")
}
