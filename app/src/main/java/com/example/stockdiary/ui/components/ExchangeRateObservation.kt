package com.example.stockdiary.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stockdiary.utils.ExchangeRateManager

/**
 * 订阅汇率版本号，返回当前值（无 UI 输出）。
 *
 * `ExchangeRateManager.rateRevision` 是普通字段——Composition 里读它**不会**触发重组，
 * 于是汇率刷新后面板会一直显示按旧汇率折算的 `¥` 金额，直到别的原因碰巧触发一次重组。
 * 这里把可观察的 [ExchangeRateManager.rateRevisionFlow] 读进 composition 作用域：
 * 汇率一变，调用方（及其子节点）立即重组。
 *
 * 用法：需要把版本号当 `remember` 的 key 时直接用返回值；不需要 key 时把它当语句调用
 * （只为建立订阅）。
 */
@Composable
fun rememberExchangeRateRevision(): Int =
    ExchangeRateManager.rateRevisionFlow.collectAsStateWithLifecycle().value
