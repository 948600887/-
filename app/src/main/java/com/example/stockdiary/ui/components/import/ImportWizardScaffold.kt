package com.example.stockdiary.ui.components.import

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 导入向导的公共全屏骨架。
 *
 * 三个全屏导入 Dialog（持仓截图 / 币安 / 交割单）此前各自逐字重复同一套外壳：
 * 全屏 [Dialog] + [Surface] + [Scaffold] + 按 step 切标题的 [TopAppBar] +
 * `DisposableEffect { onDispose { reset } }`。孪生外壳意味着同一处 bug 会各犯一遍，
 * 因此把外壳抽成这一个组件，各 Dialog 只保留来源特有的步骤内容。
 *
 * ## 约定
 *
 * - [onReset] 在向导离开组合时调用（对话框关闭）。ViewModel 挂在宿主 Activity
 *   作用域上，关掉对话框后实例还活着——不复位会残留上次的预览与账户名。
 * - 标题由调用方按自己的 step 枚举计算后传入：各来源的步骤命名不同
 *   （如交割单带「步骤 1/3」前缀、OCR 路径跳过映射步），不在这里统一。
 * - [bottomBar] 可传空 lambda：等价于不显示底部动作条。
 *
 * @param title 顶栏标题（通常随 step 变化）。
 * @param onDismiss 用户主动关闭（顶栏 X / 返回键 / 点击外部）。
 * @param onReset 向导销毁时的复位回调（见上文约定）。
 * @param bottomBar 底部动作条插槽。
 * @param stepContent 当前步骤的内容，放在 Scaffold 的 padding 之内。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWizardScaffold(
    title: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    stepContent: @Composable () -> Unit
) {
    // ViewModel 挂在宿主 Activity 作用域上，关掉对话框后实例还活着——不复位会残留上次的预览
    DisposableEffect(Unit) {
        onDispose { onReset() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(title, fontWeight = FontWeight.Bold)
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                    )
                },
                bottomBar = bottomBar
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    stepContent()
                }
            }
        }
    }
}
