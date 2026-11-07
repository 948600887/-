package com.example.stockdiary.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死两条回归：提示不能随页面切换消失，也不能因为页面重组重新计时。
 *
 * 计划里用的是 controller 的应用级作用域 + 一个模拟「页面作用域」的对照：
 * 取消页面作用域后提示仍在，说明宿主不再挂在页面组合上。
 */
class AppSnackbarControllerTest {

    @Test
    fun `show 后立刻展示对应文案`() = runTest {
        val hostState = SnackbarHostState()
        val controller = AppSnackbarController(hostState, backgroundScope)

        controller.show("今日部分场外基金分红因天天基金限流未能抓取完整")
        testScheduler.runCurrent()

        assertEquals(
            "今日部分场外基金分红因天天基金限流未能抓取完整",
            hostState.currentSnackbarData?.visuals?.message
        )
    }

    @Test
    fun `页面作用域取消后提示仍在显示`() = runTest {
        val hostState = SnackbarHostState()
        // controller 用应用级作用域；页面只是发起方，切页（取消页面作用域）不该波及提示
        val controller = AppSnackbarController(hostState, backgroundScope)
        val pageScope = CoroutineScope(coroutineContext + Job())

        pageScope.launch { controller.show("限流提示") }
        testScheduler.runCurrent()
        assertTrue(hostState.currentSnackbarData != null)

        // 模拟切 Tab / 页面销毁：取消页面作用域
        pageScope.cancel()
        testScheduler.runCurrent()

        assertEquals(
            "限流提示",
            hostState.currentSnackbarData?.visuals?.message
        )
    }

    @Test
    fun `连续两条按顺序排队，第一条不会被重新计时`() = runTest {
        val hostState = SnackbarHostState()
        val controller = AppSnackbarController(hostState, backgroundScope)

        controller.show("第一条")
        testScheduler.runCurrent()
        val first = hostState.currentSnackbarData

        controller.show("第二条")
        testScheduler.runCurrent()

        // 第二条在排队，不打断正在显示的第一条（也就不会重新计时）
        assertEquals("第一条", hostState.currentSnackbarData?.visuals?.message)
        assertEquals(first, hostState.currentSnackbarData)

        first?.dismiss()
        testScheduler.runCurrent()

        assertEquals("第二条", hostState.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun `带操作的提示点击后回调到应用作用域里执行`() = runTest {
        val hostState = SnackbarHostState()
        val controller = AppSnackbarController(hostState, backgroundScope)
        var clicked = 0

        controller.showWithAction(
            message = "批量删除失败，请重试",
            actionLabel = "重试",
            duration = SnackbarDuration.Indefinite
        ) { clicked++ }
        testScheduler.runCurrent()

        assertEquals("重试", hostState.currentSnackbarData?.visuals?.actionLabel)
        hostState.currentSnackbarData?.performAction()
        testScheduler.runCurrent()

        assertEquals(1, clicked)
    }
}
