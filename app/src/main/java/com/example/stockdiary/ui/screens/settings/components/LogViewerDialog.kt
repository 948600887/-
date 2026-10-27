package com.example.stockdiary.ui.screens.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.stockdiary.utils.AppLogger
import com.example.stockdiary.utils.LogDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运行日志与崩溃报告。
 *
 * ## 为什么 App 里要有这个入口
 *
 * 日志和崩溃报告都写在 `getExternalFilesDir()` 下（`Android/data/...`），
 * 而**从 Android 11 起这个目录对用户不可见**：系统文件管理器进不去、
 * 第三方文件管理器进不去、USB 传文件时也不显示。
 * 于是「把 app.log 发给我」是一句用户执行不了的话，闪退排障就断在这儿。
 *
 * App 自己读写这个目录则完全正常。所以正确的解法不是教用户挖文件，
 * 而是 App 把内容读出来、显示出来、再用系统分享送出去。
 *
 * ## 为什么不做成"自动上报"
 *
 * 日志里有持仓、代码、净值，是要给用户看的私密数据。
 * 由他**主动点分享、自己选发给谁**，和后台悄悄上传是两回事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<LogDiagnostics.Report?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askClear by remember { mutableStateOf(false) }
    var levelFilter by remember { mutableStateOf(LogLevelFilter.ALL) }

    fun reload() {
        scope.launch {
            loading = true
            try {
                // 读文件 + 拼装都在 IO：app.log 被截到 5000 行，字符串处理在主线程会掉帧。
                // 环境信息（版本/机型/系统）也在这里取——界面上看到的和分享出去的都带它
                val collected = withContext(Dispatchers.IO) { LogDiagnostics.collect(context) }
                val composed = withContext(Dispatchers.IO) {
                    LogDiagnostics.composeReport(
                        collected,
                        envInfo = LogDiagnostics.environmentInfo(context),
                        // 界面只显示崩溃堆栈尾部，避免长堆栈拖垮渲染；分享出口传 MAX_VALUE 给完整版
                        crashMaxLines = LogDiagnostics.DISPLAY_CRASH_STACK_LINES
                    )
                }
                report = collected
                text = composed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 读文件失败（权限 / 存储不可用）不能让界面永远停在转圈：给出可读的错误
                text = "读取日志失败：${e.message ?: e::class.java.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("运行日志与崩溃报告", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                    )
                },
                bottomBar = {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { reload() },
                                enabled = !loading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.height(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("刷新")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { askClear = true },
                                enabled = !loading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.height(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("清空")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val current = report ?: return@launch
                                    val uri = withContext(Dispatchers.IO) {
                                        LogDiagnostics.export(context, current)
                                    }
                                    if (uri != null) {
                                        LogDiagnostics.share(context, uri)
                                    } else {
                                        AppLogger.e("诊断报告导出失败", null, "LogViewer")
                                        message = "没能生成可分享的文件，请重试。"
                                    }
                                }
                            },
                            enabled = !loading && report != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.height(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("分享给我排查（全文，不截断）")
                        }
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // 级别过滤：只过滤带 [LEVEL/TAG] 前缀的 app.log 行；
                    // 头部（=== ...）、崩溃段、分隔行没有该前缀，始终保留，不会被误删
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        enumValues<LogLevelFilter>().forEach { f ->
                            FilterChip(
                                selected = levelFilter == f,
                                onClick = { levelFilter = f },
                                label = { Text(f.label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        when {
                            loading || text == null -> {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }

                            else -> {
                                // 用 remember 缓存解析结果：split + 逐行正则过滤是 O(行数) 的全量扫描，
                                // 放在组合期直接跑的话，每次重组（切 chip、loading 变化、父级刷状态）
                                // 都会把几千行重扫一遍。只有文本或过滤级别变化时才需要重算。
                                val visibleLines = remember(text, levelFilter) {
                                    val allLines = text.orEmpty().split('\n')
                                    if (levelFilter == LogLevelFilter.ALL) {
                                        allLines
                                    } else {
                                        allLines.filter { isLogLineVisible(it, levelFilter) }
                                    }
                                }
                                // 用 LazyColumn 替代单个巨型 Text：只布局可见项，
                                // 几千行日志 / 长崩溃堆栈也不会一次性卡掉主线程
                                SelectionContainer {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize().padding(12.dp)
                                    ) {
                                        items(visibleLines) { line ->
                                            Text(
                                                text = line,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 15.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("清空日志？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "会删掉 app.log 与全部崩溃报告，删了就没了。\n\n" +
                        "如果只是想把这次闪退的线索留下来，应该先点「分享」再清。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askClear = false
                        scope.launch {
                            withContext(Dispatchers.IO) { LogDiagnostics.clear(context) }
                            reload()
                        }
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { askClear = false }) { Text("取消") }
            }
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("没能分享", fontWeight = FontWeight.Bold) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }
        )
    }
}

/** 日志行级别 → 优先级，用于界面级别过滤。未知级别按可见处理。 */
private val LOG_LEVEL_PRIORITY = mapOf(
    "DEBUG" to 0, "INFO" to 1, "API" to 1, "DB" to 1, "WARN" to 2, "ERROR" to 3
)

/** 匹配 app.log 行的 `[LEVEL/TAG]` 前缀，提取 LEVEL。 */
private val LOG_LINE_LEVEL_REGEX = Regex("^\\[[^\\]]*\\]\\[([A-Z]+)/")

private enum class LogLevelFilter(val label: String, val minPriority: Int) {
    ALL("全部", 0),
    INFO("INFO+", 1),
    WARN("WARN+", 2),
    ERROR("ERROR", 3)
}

/**
 * 级别过滤：只隐藏低于所选阈值的 app.log 行。
 * 头部（=== ...）、崩溃段、分隔行没有 `[LEVEL/TAG]` 前缀，[LOG_LINE_LEVEL_REGEX] 匹配不到，
 * 始终返回 true（可见），不会被误删。
 */
private fun isLogLineVisible(line: String, filter: LogLevelFilter): Boolean {
    if (filter == LogLevelFilter.ALL) return true
    val m = LOG_LINE_LEVEL_REGEX.find(line) ?: return true
    val prio = LOG_LEVEL_PRIORITY[m.groupValues[1]] ?: return true
    return prio >= filter.minPriority
}
