package com.example.stockdiary.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 0「通知与日志带跳过量」的纯文案回归。
 *
 * [buildPriceSyncMessage] 是 Worker 日志（经 PriceSyncRoundPlan.message）与
 * [NotificationHelper.showPriceSyncNotification] 正文的**唯一**来源；
 * [buildPriceSyncNotificationText] 让通知的标题/正文也能在 JVM 单测里直调，
 * 不必起 Context / NotificationManager。
 */
class PriceSyncMessagesTest {

    @Test
    fun `全跳过文案_如实说明且不含成功同步0个`() {
        val message = buildPriceSyncMessage(0, 0, 131)
        assertFalse("旧文案会写成「成功同步 0 个资产」", message.contains("成功同步 0 个资产"))
        assertTrue(message.contains("无有效更新"))
        assertTrue(message.contains("131 个资产被跳过"))
        assertTrue(message.contains("价格未刷新"))
        assertFalse("跳过不算失败，文案里不该出现同步失败", message.contains("同步失败"))
    }

    @Test
    fun `全0极端文案_如实说明无可同步资产`() {
        val message = buildPriceSyncMessage(0, 0, 0)
        assertEquals("本轮无可同步资产", message)
        assertFalse(message.contains("成功同步 0 个资产"))
    }

    @Test
    fun `全失败文案_带跳过量`() {
        val message = buildPriceSyncMessage(0, 4, 3)
        assertTrue(message.contains("同步失败"))
        assertTrue(message.contains("跳过 3 个"))
    }

    @Test
    fun `纯成功文案_带跳过量`() {
        val message = buildPriceSyncMessage(107, 0, 131)
        assertTrue(message.contains("成功同步 107 个资产"))
        assertTrue(message.contains("跳过 131 个"))
    }

    @Test
    fun `混合成功失败文案_带跳过量`() {
        val message = buildPriceSyncMessage(5, 2, 7)
        assertTrue(message.contains("成功 5 个"))
        assertTrue(message.contains("失败 2 个"))
        assertTrue(message.contains("跳过 7 个"))
    }

    @Test
    fun `无跳过时_文案不带跳过字样`() {
        assertFalse(buildPriceSyncMessage(5, 0, 0).contains("跳过"))
        assertFalse(buildPriceSyncMessage(0, 3, 0).contains("跳过"))
    }

    @Test
    fun `原因后缀_按数量降序且能看出缓存过期`() {
        val suffix = buildPriceSyncReasonSuffix(
            mapOf("停牌" to 1, "币安熔断" to 130, "缓存过期" to 2)
        )
        assertEquals("（币安熔断 130、缓存过期 2、停牌 1）", suffix)
    }

    @Test
    fun `原因后缀_过滤零计数与空map`() {
        assertEquals("", buildPriceSyncReasonSuffix(emptyMap()))
        assertEquals("", buildPriceSyncReasonSuffix(mapOf("停牌" to 0, "缓存过期" to -1)))
        assertEquals("（缓存过期 2）", buildPriceSyncReasonSuffix(mapOf("停牌" to 0, "缓存过期" to 2)))
    }

    @Test
    fun `通知标题_全跳过为无有效更新`() {
        val text = buildPriceSyncNotificationText(0, 0, 5, mapOf("缓存过期" to 5))
        assertEquals("价格同步无有效更新", text.title)
    }

    @Test
    fun `通知标题_其余情形为价格同步完成`() {
        assertEquals("价格同步完成", buildPriceSyncNotificationText(5, 0, 2).title)
        assertEquals("价格同步完成", buildPriceSyncNotificationText(0, 3, 2).title)
        assertEquals("价格同步完成", buildPriceSyncNotificationText(0, 0, 0).title)
    }

    @Test
    fun `通知正文_等于共用文案加原因后缀`() {
        val reasons = mapOf("币安熔断" to 130, "缓存过期" to 2)
        val text = buildPriceSyncNotificationText(0, 0, 132, reasons)
        assertEquals(
            buildPriceSyncMessage(0, 0, 132) + buildPriceSyncReasonSuffix(reasons),
            text.message
        )
        assertTrue(text.message.contains("缓存过期 2"))
    }

    @Test
    fun `通知正文与Worker日志共用buildPriceSyncMessage`() {
        val reasons = mapOf("停牌" to 3)
        val skipped = reasons.values.sum()
        val logMessage = buildPriceSyncMessage(5, 1, skipped)
        val notification = buildPriceSyncNotificationText(5, 1, skipped, reasons)
        assertTrue(notification.message.startsWith(logMessage))
    }

    @Test
    fun `全跳过通知_标题与正文都不给虚假安心`() {
        val text = buildPriceSyncNotificationText(0, 0, 7, mapOf("币安熔断" to 7))
        assertFalse(text.title.contains("完成"))
        assertFalse(text.message.contains("成功同步 0 个资产"))
        assertTrue(text.message.contains("无有效更新"))
    }
}
