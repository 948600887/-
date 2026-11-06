package com.example.stockdiary.data.price

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SourceIds] 稳定源 ID 映射与旧中文名归一回归（G1 契约骨架）。
 */
class SourceIdsTest {

    // ---------- normalizeLegacySource：旧中文名 / 稳定 ID 归一 ----------

    @Test
    fun `normalizeLegacySource_新浪财经中文名归一成sina`() {
        assertEquals(SourceIds.SINA, SourceIds.normalizeLegacySource("新浪财经"))
    }

    @Test
    fun `normalizeLegacySource_已是稳定ID的sina保持sina`() {
        assertEquals(SourceIds.SINA, SourceIds.normalizeLegacySource("sina"))
    }

    @Test
    fun `normalizeLegacySource_腾讯财经归一成tencent`() {
        assertEquals(SourceIds.TENCENT, SourceIds.normalizeLegacySource("腾讯财经"))
    }

    @Test
    fun `normalizeLegacySource_妙想Skill归一成eastmoney_skill`() {
        assertEquals(SourceIds.EASTMONEY_SKILL, SourceIds.normalizeLegacySource("妙想Skill"))
    }

    @Test
    fun `normalizeLegacySource_未知串原样返回_不崩`() {
        assertEquals("some_unknown_source", SourceIds.normalizeLegacySource("some_unknown_source"))
        // 空串也保底不崩
        assertEquals("", SourceIds.normalizeLegacySource(""))
    }

    // ---------- displayName：稳定 ID → 中文展示名 唯一映射 ----------

    @Test
    fun `displayName_各稳定ID唯一映射到中文名`() {
        assertEquals("新浪财经", SourceIds.displayName(SourceIds.SINA))
        assertEquals("腾讯财经", SourceIds.displayName(SourceIds.TENCENT))
        assertEquals("妙想Skill", SourceIds.displayName(SourceIds.EASTMONEY_SKILL))
        assertEquals("东方财富", SourceIds.displayName(SourceIds.EASTMONEY_FUND))
        assertEquals("币安", SourceIds.displayName(SourceIds.BINANCE))
    }

    @Test
    fun `displayName_未知ID原样返回_不崩`() {
        assertEquals("no_such_source", SourceIds.displayName("no_such_source"))
        assertEquals("", SourceIds.displayName(""))
    }
}
