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
    fun `normalizeSymbol 场外基金统一落成 fund_ 形态`() {
        // 从前这里返回纯数字，于是同一只基金在库里有两种形态：
        // 手动添加 / 一码通截图得到 `004502`，基金E导入与基金截图得到 `fund_004502`。
        // 两者 isOTCFund 都认，但快照对齐按 symbol 精确分组 → 同一只基金落两条资产
        assertEquals("fund_007467", StockCodeManager.normalizeSymbol("007467"))
        assertEquals("fund_010000", StockCodeManager.normalizeSymbol("010000"))
        // 「手动添加」与「基金E导入」必须落到同一个 symbol，否则持仓翻倍
        assertEquals(
            StockCodeManager.normalizeSymbol("004502"),
            StockCodeManager.normalizeSymbol("fund_004502")
        )
    }

    @Test
    fun `normalizeSymbol 不把 LOF 与老三板并进场外形态`() {
        // LOF 场内外份额（sz163415 场内 / fund_163415 场外）与老三板
        // （400030 既是场外基金号段也是退市股票号段）刻意保持两态 ——
        // 合并它们正是当年场内外互相覆盖的老坑
        assertEquals("sz163415", StockCodeManager.normalizeSymbol("163415"))
        assertEquals("fund_163415", StockCodeManager.normalizeSymbol("fund_163415"))
        assertEquals("bj400264", StockCodeManager.normalizeSymbol("400264"))
    }

    @Test
    fun `normalizeSymbol preserves fund_ prefix`() {
        assertEquals("fund_123456", StockCodeManager.normalizeSymbol("fund_123456"))
    }

    @Test
    fun `normalizeSymbol 幂等，重复调用结果不变`() {
        // 存储边界（AssetRepository.addOrUpdateStockAsset）会对 symbol 兜底做一次规范化，
        // 而调用方大多已经规范化过了 —— 这整条链依赖 normalizeSymbol 幂等，
        // 否则兜底那一次会把已经正确的代码二次改写。
        listOf(
            "600519", "SH600519", "sh600519",   // 沪市：补前缀 / 已带前缀转小写
            "000001", "sz000001",               // 深市
            "007467", "010000",                 // 场外基金：落成 fund_ 形态
            "fund_000001",                      // 场外前缀原样保留
            "163415", "SH510050"                // LOF / ETF
        ).forEach { raw ->
            val once = StockCodeManager.normalizeSymbol(raw)
            val twice = StockCodeManager.normalizeSymbol(once)
            assertEquals("normalizeSymbol 不幂等：$raw → $once → $twice", once, twice)
        }
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

    // ========== isDelisted ==========

    @Test
    fun `isDelisted returns true for 老三板 400xxx`() {
        assertTrue(StockCodeManager.isDelisted("bj400264"))
    }

    @Test
    fun `isDelisted returns true for 老三板 420xxx`() {
        assertTrue(StockCodeManager.isDelisted("bj420123"))
    }

    @Test
    fun `isDelisted returns false for 北交所 normal codes`() {
        assertFalse(StockCodeManager.isDelisted("bj830799"))
    }

    @Test
    fun `isDelisted returns false for SH stocks`() {
        assertFalse(StockCodeManager.isDelisted("sh600519"))
    }

    @Test
    fun `isDelisted returns false for SZ stocks`() {
        assertFalse(StockCodeManager.isDelisted("sz000001"))
    }

    @Test
    fun `isDelisted returns false for 货币 场外基金`() {
        assertFalse(StockCodeManager.isDelisted("519518"))
    }

    @Test
    fun `isDelisted returns false for null`() {
        assertFalse(StockCodeManager.isDelisted(null))
    }

    @Test
    fun `isDelisted returns false for blank`() {
        assertFalse(StockCodeManager.isDelisted(""))
    }

    @Test
    fun `isDelisted returns true for 老三板 under sh prefix`() {
        // 老三板退市代码也可能以 sh/sz 前缀形态入库，判定不能只认 bj
        assertTrue(StockCodeManager.isDelisted("sh400123"))
    }

    @Test
    fun `isDelisted returns false for 场内基金 ETF 与 LOF`() {
        // 同步侧对 STOCK+FUND 做退市短路，前提是基金代码段不可能落在 400/420：
        // ETF 510/159、LOF 16/18、场内 5/1 开头，均无 400/420。这里钉住不误杀。
        assertFalse(StockCodeManager.isDelisted("sz164701"))
        assertFalse(StockCodeManager.isDelisted("sh510050"))
        assertFalse(StockCodeManager.isDelisted("sz161725"))
        assertFalse(StockCodeManager.isDelisted("sz159919"))
    }

    @Test
    fun `isDelisted returns false for 场外基金 fund_ prefix`() {
        // fund_ 前缀是场外份额标记，extractDigits 后仍是 6 位，必须确保不误判
        assertFalse(StockCodeManager.isDelisted("fund_012350"))
        assertFalse(StockCodeManager.isDelisted("fund_000001"))
    }

    // ========== isDelistedForSync ==========

    @Test
    fun `isDelisted 对 fund_ 场外基金返回 true（同步链路因此必须用 isDelistedForSync）`() {
        // 这里钉住的是「错的前提」本身：isDelisted 只看 6 位数字 + 400/420 前缀，
        // 而天天基金场外代码段里真有 400xxx/420xxx。语义没变、也不能改——
        // UI 的「退市」文案和其他调用方还依赖它，改小口径会漏掉真退市股。
        assertTrue(StockCodeManager.isDelisted("fund_400030"))
        assertTrue(StockCodeManager.isDelisted("fund_420102"))
    }

    @Test
    fun `isDelistedForSync 仍能认出老三板退市股`() {
        assertTrue(StockCodeManager.isDelistedForSync("bj400264"))
        assertTrue(StockCodeManager.isDelistedForSync("sh400123"))
    }

    @Test
    fun `isDelistedForSync 放行 fund_ 前缀的场外基金`() {
        // 本次 bug 的回归：400030（东方添益债券）、420102 都是用户持仓里的真基金。
        // 用 isDelisted 判它们会命中 → 同步侧不发行情请求、不计失败，
        // 价格永久冻结且用户端零信号，比没有豁免更糟。
        assertFalse(StockCodeManager.isDelistedForSync("fund_400030"))
        assertFalse(StockCodeManager.isDelistedForSync("fund_420102"))
    }

    @Test
    fun `isDelistedForSync 对 fund_ 前缀大小写不敏感`() {
        assertFalse(StockCodeManager.isDelistedForSync("FUND_400030"))
        assertFalse(StockCodeManager.isDelistedForSync("Fund_420102"))
    }

    @Test
    fun `isDelistedForSync 放行其他正常标的`() {
        assertFalse(StockCodeManager.isDelistedForSync("fund_012350"))
        assertFalse(StockCodeManager.isDelistedForSync("sh600519"))
        assertFalse(StockCodeManager.isDelistedForSync("sz164701"))
    }

    @Test
    fun `isDelistedForSync 空值返回 false`() {
        assertFalse(StockCodeManager.isDelistedForSync(null))
        assertFalse(StockCodeManager.isDelistedForSync(""))
    }

    // ========== fund_ 前缀（场外份额） ==========

    @Test
    fun `hasFundPrefix 只认 fund_ 前缀且大小写不敏感`() {
        assertTrue(StockCodeManager.hasFundPrefix("fund_400030"))
        assertTrue(StockCodeManager.hasFundPrefix("FUND_400030"))
        assertTrue(StockCodeManager.hasFundPrefix("Fund_420102"))
        assertTrue(StockCodeManager.hasFundPrefix(" fund_400030 "))
        assertFalse(StockCodeManager.hasFundPrefix("400030"))
        assertFalse(StockCodeManager.hasFundPrefix("sh600519"))
        assertFalse(StockCodeManager.hasFundPrefix(null))
        assertFalse(StockCodeManager.hasFundPrefix(""))
        assertFalse(StockCodeManager.hasFundPrefix("   "))
    }

    @Test
    fun `classifyCode 对 fund_ 前缀一律返回场外基金`() {
        // 本次修的根因：场外基金与深市股票共用 00xxxx 号段、与老三板共用 400xxx/420xxx 号段，
        // 只按数字推会把 400030（东方添益债券）判成「股票」。前缀是显式标记，必须优先。
        // 调用方：HoldingSnapshotParser（持仓预览的类别标签）、AddAssetDialog（自动识别）、
        // isStock（→ DividendRepository 的分红上限）。
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("fund_400030"))
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("fund_420102"))
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("fund_000001"))
        // 场外份额与场内同号（LOF 163415 的场外份额）：前缀意味着按净值申赎，仍是场外
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("fund_163415"))
    }

    @Test
    fun `classifyCode 对 fund_ 前缀大小写不敏感`() {
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("FUND_400030"))
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("Fund_420102"))
    }

    @Test
    fun `classifyCode 对已剥掉前缀的纯数字仍按号段走`() {
        // AssetSyncManager 同步链路是先把 fund_ 剥掉再调 classifyCode（那边要用号段
        // 区分 ETF/LOF/场内基金，靠的是「是否场外」的另一路判据 isOffExchange）。
        // 纯数字不含前缀，加了前缀分支后这条路的结论必须一点不变。
        assertEquals(CodeCategory.STOCK, StockCodeManager.classifyCode("400030"))
        assertEquals(CodeCategory.STOCK, StockCodeManager.classifyCode("420102"))
        assertEquals(CodeCategory.LOF, StockCodeManager.classifyCode("163415"))
        assertEquals(CodeCategory.ETF, StockCodeManager.classifyCode("510050"))
        assertEquals(CodeCategory.OTC_FUND, StockCodeManager.classifyCode("007467"))
    }

    @Test
    fun `isStock 对 fund_ 前缀返回 false`() {
        // 单独钉住 isStock：它先把 symbol 压成纯数字再交给 classifyCode，
        // 前缀到那时已经没了，所以 classifyCode 里的前缀分支**救不了它**，
        // 必须在本函数里判。这一条直接决定 DividendRepository 的每 10 份分红上限
        // 走基金的 100 还是股票的 1000 —— 判成股票会把异常大的脏数据放行进库。
        assertFalse(StockCodeManager.isStock("fund_400030"))
        assertFalse(StockCodeManager.isStock("fund_420102"))
        assertFalse(StockCodeManager.isStock("fund_000001"))
        assertFalse(StockCodeManager.isStock("FUND_400030"))
    }

    @Test
    fun `stripFundPrefix 剥掉前缀_不带前缀时原样返回`() {
        assertEquals("400030", StockCodeManager.stripFundPrefix("fund_400030"))
        assertEquals("400030", StockCodeManager.stripFundPrefix("FUND_400030"))
        assertEquals("400030", StockCodeManager.stripFundPrefix(" fund_400030 "))
        assertEquals("400030", StockCodeManager.stripFundPrefix("400030"))
        assertEquals("sh600519", StockCodeManager.stripFundPrefix("sh600519"))
    }

    @Test
    fun `isOTCFund 认 fund_ 前缀_而 isOTCFundCode 认不出`() {
        // 「是不是场外标的」与「属不属于场外号段」不是一回事：分红链路正是靠这个结论
        // 选数据源（天天基金 vs 东财 + 新浪），用错就会拿股票接口去问一只基金。
        assertTrue(StockCodeManager.isOTCFund("fund_400030"))
        assertTrue(StockCodeManager.isOTCFund("fund_420102"))
        assertTrue(StockCodeManager.isOTCFund("FUND_400030"))
        // 剥掉前缀后按号段判是 false —— 这就是误判的来源，钉住它别再退回去
        assertFalse(StockCodeManager.isOTCFundCode("400030"))
        assertFalse(StockCodeManager.isOTCFundCode("420102"))
        // 没前缀的老三板真退市股、真股票不能被当场外
        assertFalse(StockCodeManager.isOTCFund("400030"))
        assertFalse(StockCodeManager.isOTCFund("bj400264"))
        assertFalse(StockCodeManager.isOTCFund("sh600519"))
        // 号段就能认出来的场外基金照旧
        assertTrue(StockCodeManager.isOTCFund("004502"))
        assertTrue(StockCodeManager.isOTCFund("010001"))
        assertFalse(StockCodeManager.isOTCFund(null))
        assertFalse(StockCodeManager.isOTCFund(""))
    }

    @Test
    fun `isStock 对真股票仍返回 true`() {
        assertTrue(StockCodeManager.isStock("sh600519"))
        assertTrue(StockCodeManager.isStock("sz000001"))
        assertTrue(StockCodeManager.isStock("bj400264"))
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