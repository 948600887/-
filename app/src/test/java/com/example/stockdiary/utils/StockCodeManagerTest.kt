package com.example.stockdiary.utils

import com.example.stockdiary.utils.StockCodeManager.CodeCategory
import org.junit.Assert.*
import org.junit.Test

class StockCodeManagerTest {

    // ========== isETF ==========

    @Test
    fun `isETF returns true for SH ETF codes`() {
        assertTrue(StockCodeManager.isETF("sh510050"))
        assertTrue(StockCodeManager.isETF("sh510300"))
        assertTrue(StockCodeManager.isETF("sh512880"))
        assertTrue(StockCodeManager.isETF("sh513100"))
        assertTrue(StockCodeManager.isETF("sh515000"))
        assertTrue(StockCodeManager.isETF("sh588000"))
    }

    @Test
    fun `isETF returns true for SZ ETF codes`() {
        assertTrue(StockCodeManager.isETF("sz159919"))
        assertTrue(StockCodeManager.isETF("sz159928"))
        assertTrue(StockCodeManager.isETF("sz159949"))
    }

    @Test
    fun `isETF returns false for stocks`() {
        assertFalse(StockCodeManager.isETF("sh600519"))
        assertFalse(StockCodeManager.isETF("sz000001"))
        assertFalse(StockCodeManager.isETF("sh688981"))
    }

    @Test
    fun `isETF returns false for null or blank`() {
        assertFalse(StockCodeManager.isETF(null))
        assertFalse(StockCodeManager.isETF(""))
        assertFalse(StockCodeManager.isETF("   "))
    }

    // ========== isStock ==========

    @Test
    fun `isStock returns true for valid stock codes`() {
        assertTrue(StockCodeManager.isStock("sh600519"))
        assertTrue(StockCodeManager.isStock("sz000001"))
        assertTrue(StockCodeManager.isStock("sh688981"))
        assertTrue(StockCodeManager.isStock("sz300750"))
    }

    @Test
    fun `isStock returns false for ETF and fund codes`() {
        assertFalse(StockCodeManager.isStock("sh510050"))
        assertFalse(StockCodeManager.isStock("sz159919"))
        assertFalse(StockCodeManager.isStock("007467"))
    }

    // ========== isOTCFundCode ==========

    @Test
    fun `isOTCFundCode returns true for OTC fund codes`() {
        assertTrue(StockCodeManager.isOTCFundCode("007467"))
        assertTrue(StockCodeManager.isOTCFundCode("010000"))
        assertTrue(StockCodeManager.isOTCFundCode("020000"))
        assertTrue(StockCodeManager.isOTCFundCode("005000"))
    }

    @Test
    fun `isOTCFundCode returns false for SH stocks`() {
        assertFalse(StockCodeManager.isOTCFundCode("000001"))
        assertFalse(StockCodeManager.isOTCFundCode("000002"))
        assertFalse(StockCodeManager.isOTCFundCode("000333"))
    }

    @Test
    fun `isOTCFundCode returns false for ETF codes`() {
        assertFalse(StockCodeManager.isOTCFundCode("510050"))
        assertFalse(StockCodeManager.isOTCFundCode("159919"))
    }

    @Test
    fun `isOTCFundCode returns false for LOF codes`() {
        assertFalse(StockCodeManager.isOTCFundCode("161005"))
        assertFalse(StockCodeManager.isOTCFundCode("161725"))
    }

    // ========== extractDigits ==========

    @Test
    fun `extractDigits filters non-digit characters`() {
        assertEquals("600519", StockCodeManager.extractDigits("sh600519"))
        assertEquals("000001", StockCodeManager.extractDigits("000001.SZ"))
        assertEquals("510050", StockCodeManager.extractDigits("sh.510050"))
        assertEquals("123456", StockCodeManager.extractDigits("abc123def456"))
    }

    @Test
    fun `extractDigits returns empty for all non-digit input`() {
        assertEquals("", StockCodeManager.extractDigits("abc"))
        assertEquals("", StockCodeManager.extractDigits(""))
    }

    // ========== classifyCode ==========

    @Test
    fun `classifyCode identifies stocks`() {
        assertEquals(CodeCategory.STOCK, StockCodeManager.classifyCode("600519"))
        assertEquals(CodeCategory.STOCK, StockCodeManager.classifyCode("000001"))
        assertEquals(CodeCategory.STOCK, StockCodeManager.classifyCode("300750"))
        assertEquals(CodeCategory.STOCK, StockCodeManager.classifyCode("688981"))
    }

    @Test
    fun `classifyCode identifies ETFs`() {
        assertEquals(CodeCategory.ETF, StockCodeManager.classifyCode("510050"))
        assertEquals(CodeCategory.ETF, StockCodeManager.classifyCode("159919"))
    }

    @Test
    fun `classifyCode identifies LOFs`() {
        assertEquals(CodeCategory.LOF, StockCodeManager.classifyCode("161005"))
        assertEquals(CodeCategory.LOF, StockCodeManager.classifyCode("182001"))
    }

    @Test
    fun `classifyCode identifies OTC funds`() {
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("007467"))
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("010000"))
    }

    @Test
    fun `classifyCode identifies exchange funds`() {
        assertEquals(CodeCategory.EXCHANGE_FUND, StockCodeManager.classifyCode("519888"))
    }

    // ========== formatToSinaCode ==========

    @Test
    fun `formatToSinaCode handles SH stocks`() {
        assertEquals("sh600519", StockCodeManager.formatToSinaCode("600519"))
        assertEquals("sh600000", StockCodeManager.formatToSinaCode("600000"))
    }

    @Test
    fun `formatToSinaCode handles SZ stocks`() {
        assertEquals("sz000001", StockCodeManager.formatToSinaCode("000001"))
        assertEquals("sz300750", StockCodeManager.formatToSinaCode("300750"))
    }

    @Test
    fun `formatToSinaCode handles BJ stocks`() {
        assertEquals("bj430047", StockCodeManager.formatToSinaCode("430047"))
        assertEquals("bj830765", StockCodeManager.formatToSinaCode("830765"))
    }

    @Test
    fun `formatToSinaCode handles already-formatted codes`() {
        assertEquals("sh600519", StockCodeManager.formatToSinaCode("sh600519"))
        assertEquals("sz000001", StockCodeManager.formatToSinaCode("SZ000001"))
    }

    @Test
    fun `formatToSinaCode handles dot notation`() {
        assertEquals("sh600519", StockCodeManager.formatToSinaCode("600519.SH"))
        assertEquals("sz000001", StockCodeManager.formatToSinaCode("000001.SZ"))
    }

    // ========== normalizeSymbol ==========

    @Test
    fun `normalizeSymbol lowercases market prefix`() {
        assertEquals("sh600519", StockCodeManager.normalizeSymbol("SH600519"))
        assertEquals("sz000001", StockCodeManager.normalizeSymbol("SZ000001"))
    }

    @Test
    fun `normalizeSymbol adds market prefix for pure numbers`() {
        assertEquals("sh600519", StockCodeManager.normalizeSymbol("600519"))
        assertEquals("sz000001", StockCodeManager.normalizeSymbol("000001"))
    }

    @Test
    fun `normalizeSymbol keeps OTC fund codes as pure numbers`() {
        assertEquals("007467", StockCodeManager.normalizeSymbol("007467"))
        assertEquals("010000", StockCodeManager.normalizeSymbol("010000"))
    }

    @Test
    fun `normalizeSymbol preserves fund_ prefix`() {
        assertEquals("fund_123456", StockCodeManager.normalizeSymbol("fund_123456"))
    }

    // ========== inferMarketPrefix ==========

    @Test
    fun `inferMarketPrefix returns sh for 6,5,9 codes`() {
        assertEquals("sh", StockCodeManager.inferMarketPrefix("600519"))
        assertEquals("sh", StockCodeManager.inferMarketPrefix("510050"))
        assertEquals("sh", StockCodeManager.inferMarketPrefix("900000"))
    }

    @Test
    fun `inferMarketPrefix returns sz for 0,3 codes`() {
        assertEquals("sz", StockCodeManager.inferMarketPrefix("000001"))
        assertEquals("sz", StockCodeManager.inferMarketPrefix("300750"))
    }

    @Test
    fun `inferMarketPrefix returns bj for 4,8 codes`() {
        assertEquals("bj", StockCodeManager.inferMarketPrefix("430047"))
        assertEquals("bj", StockCodeManager.inferMarketPrefix("830765"))
    }

    // ========== extractPureCode ==========

    @Test
    fun `extractPureCode removes market prefix`() {
        assertEquals("600519", StockCodeManager.extractPureCode("sh600519"))
        assertEquals("000001", StockCodeManager.extractPureCode("sz000001"))
    }

    @Test
    fun `extractPureCode returns original for non-prefixed codes`() {
        assertEquals("007467", StockCodeManager.extractPureCode("007467"))
        assertEquals("600519", StockCodeManager.extractPureCode("600519"))
    }

    // ========== getMarketPrefix ==========

    @Test
    fun `getMarketPrefix returns correct prefix`() {
        assertEquals("sh", StockCodeManager.getMarketPrefix("sh600519"))
        assertEquals("sz", StockCodeManager.getMarketPrefix("000001"))
    }

    // ========== parseDividendAmount ==========

    @Test
    fun `parseDividendAmount handles plain number`() {
        assertEquals(15.0f, StockCodeManager.parseDividendAmount("15"))
        assertEquals(1.5f, StockCodeManager.parseDividendAmount("1.5"))
    }

    @Test
    fun `parseDividendAmount handles 派 format`() {
        assertEquals(15.0f, StockCodeManager.parseDividendAmount("10派15元"))
        assertEquals(2.5f, StockCodeManager.parseDividendAmount("每10股派2.5元"))
    }

    @Test
    fun `parseDividendAmount handles 元 suffix`() {
        assertEquals(15.0f, StockCodeManager.parseDividendAmount("15元"))
    }

    @Test
    fun `parseDividendAmount returns 0 for invalid input`() {
        assertEquals(0f, StockCodeManager.parseDividendAmount("无"))
        assertEquals(0f, StockCodeManager.parseDividendAmount(""))
    }

    // ========== extractFiscalYear ==========

    @Test
    fun `extractFiscalYear extracts year from date`() {
        assertEquals(2024, StockCodeManager.extractFiscalYear("2024-06-15"))
        assertEquals(2023, StockCodeManager.extractFiscalYear("2023/12/31"))
    }

    @Test
    fun `extractFiscalYear returns 0 for invalid input`() {
        assertEquals(0, StockCodeManager.extractFiscalYear("abc"))
        assertEquals(0, StockCodeManager.extractFiscalYear(""))
    }

    // ========== Edge Cases ==========

    @Test
    fun `classifyCode returns UNKNOWN for short codes`() {
        assertEquals(CodeCategory.UNKNOWN, StockCodeManager.classifyCode("12"))
        assertEquals(CodeCategory.UNKNOWN, StockCodeManager.classifyCode(""))
    }

    @Test
    fun `normalizeSymbol returns empty for blank input`() {
        assertEquals("", StockCodeManager.normalizeSymbol(""))
        assertEquals("", StockCodeManager.normalizeSymbol("   "))
    }
}