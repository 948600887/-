package com.example.stockdiary.ui.dialogs

import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetInputValidationTest {

    private fun errors(
        type: AssetType = AssetType.STOCK,
        name: String = "贵州茅台",
        symbol: String = "600519",
        account: String = "招商证券",
        balance: String = "100",
        price: String = "",
        rate: String = ""
    ) = validateAssetInput(
        selectedType = type,
        name = name,
        symbol = symbol,
        account = account,
        balance = balance,
        purchasePrice = price,
        interestRate = rate
    )

    // ---------- 基本必填 ----------

    @Test
    fun 全填合法的股票输入没有任何错误() {
        val e = errors()
        assertTrue(e.isValid)
        assertNull(e.summary)
        assertNull(e.firstField)
    }

    @Test
    fun 名称为空报错() {
        val e = errors(name = "   ")
        assertEquals("资产名称", e.name?.label)
        assertEquals("必填", e.name?.message)
        assertEquals("name", e.firstField)
    }

    @Test
    fun 账户为空报错() {
        assertEquals("account", errors(account = "").firstField)
    }

    @Test
    fun 证券缺代码会报无法同步行情() {
        val e = errors(symbol = "")
        assertEquals("代码", e.symbol?.label)
        assertEquals("请填写代码，否则无法同步行情价格", e.symbol?.message)
    }

    @Test
    fun 虚拟货币同样要求代码() {
        assertFalse(errors(type = AssetType.CRYPTO, symbol = "").isValid)
    }

    // ---------- 存款：不要代码、但要利率 ----------

    @Test
    fun 存款不要求代码() {
        val e = errors(type = AssetType.BANK_DEPOSIT, symbol = "", rate = "2.75")
        assertNull(e.symbol)
        assertTrue(e.isValid)
    }

    @Test
    fun 存款缺利率报错() {
        val e = errors(type = AssetType.BANK_DEPOSIT, symbol = "", rate = "")
        assertEquals("年利率", e.rate?.label)
        assertEquals("必填，请输入有效数字", e.rate?.message)
        assertEquals("rate", e.firstField)
    }

    @Test
    fun 存款利率为负报错() {
        assertEquals("不能为负数", errors(type = AssetType.BANK_DEPOSIT, symbol = "", rate = "-1").rate?.message)
    }

    // ---------- 编辑场景：利率留空 = 沿用原值 ----------

    @Test
    fun 编辑时存款利率留空合法_沿用原值() {
        val e = validateAssetInput(
            selectedType = AssetType.BANK_DEPOSIT,
            name = "三年定存",
            symbol = "",
            account = "招商银行",
            balance = "50000",
            purchasePrice = "",
            interestRate = "",
            rateRequired = false
        )
        assertNull(e.rate)
        assertTrue(e.isValid)
    }

    @Test
    fun 编辑时利率填了非法值仍然报错() {
        val e = validateAssetInput(
            selectedType = AssetType.BANK_DEPOSIT,
            name = "三年定存",
            symbol = "",
            account = "招商银行",
            balance = "50000",
            purchasePrice = "",
            interestRate = "-1",
            rateRequired = false
        )
        assertEquals("不能为负数", e.rate?.message)
    }

    @Test
    fun 新增时利率留空仍然非法_默认必填() {
        // 不传 rateRequired 就走默认 true，保证新增资产的既有行为不变
        val e = validateAssetInput(
            selectedType = AssetType.BANK_DEPOSIT,
            name = "三年定存",
            symbol = "",
            account = "招商银行",
            balance = "50000",
            purchasePrice = "",
            interestRate = ""
        )
        assertEquals("必填，请输入有效数字", e.rate?.message)
        assertFalse(e.isValid)
    }

    @Test
    fun 非存款类型完全不校验利率() {
        // 界面上利率字段对非存款根本不显示，填了脏值也不该拦住提交
        assertNull(errors(type = AssetType.STOCK, rate = "abc").rate)
        assertTrue(errors(type = AssetType.STOCK, rate = "abc").isValid)
    }

    // ---------- 数量 ----------

    @Test
    fun 数量非数字报错() {
        val e = errors(balance = "abc")
        assertEquals("请输入有效数字", e.balance?.message)
        assertEquals("balance", e.firstField)
    }

    @Test
    fun 数量为负报错() {
        assertEquals("不能为负数", errors(balance = "-1").balance?.message)
    }

    @Test
    fun 数量为零是合法的() {
        assertTrue(errors(balance = "0").isValid)
    }

    @Test
    fun 数量标签随资产类型变化() {
        assertEquals("持股数量", errors(type = AssetType.STOCK, balance = "x").balance?.label)
        assertEquals("持有份额", errors(type = AssetType.FUND, balance = "x").balance?.label)
        assertEquals("存款金额", errors(type = AssetType.BANK_DEPOSIT, symbol = "", rate = "1", balance = "x").balance?.label)
        assertEquals("数量/金额", errors(type = AssetType.LOAN, symbol = "", balance = "x").balance?.label)
    }

    // ---------- 参考单价：可选，但填了就得合法 ----------

    @Test
    fun 参考单价留空不报错() {
        assertTrue(errors(price = "").isValid)
    }

    @Test
    fun 参考单价非数字报错() {
        assertEquals("请输入有效数字", errors(price = "abc").price?.message)
        assertEquals("price", errors(price = "abc").firstField)
    }

    @Test
    fun 参考单价为负报错() {
        assertEquals("不能为负数", errors(price = "-3").price?.message)
    }

    // ---------- 汇总与聚焦顺序 ----------

    @Test
    fun 汇总提示按界面顺序列出全部缺失项() {
        val e = errors(name = "", account = "", balance = "abc")
        assertEquals("请检查：资产名称、账户/平台、持股数量", e.summary)
    }

    @Test
    fun firstField始终取最靠上的那个错误() {
        assertEquals("name", errors(name = "", account = "", balance = "").firstField)
        assertEquals("account", errors(account = "", balance = "").firstField)
        assertEquals("balance", errors(balance = "").firstField)
    }

    // ---------- 与构造口径的一致性（最重要的一条） ----------

    @Test
    fun 校验认为合法的输入_构造Asset时一定不抛异常() {
        val cases = listOf(
            AssetType.STOCK to Triple("600519", "100", "10.5"),
            AssetType.FUND to Triple("515080", "0", ""),
            AssetType.FUND to Triple("007467", "1000", "1.2345"),
            AssetType.CRYPTO to Triple("BTC", "0.5", "30000"),
            AssetType.BANK_DEPOSIT to Triple("", "50000", "2.75"),
            AssetType.LOAN to Triple("", "120000", ""),
            AssetType.CREDIT_CARD to Triple("", "3000", "")
        )
        cases.forEach { (type, input) ->
            val (sym, bal, price) = input
            val rate = if (type == AssetType.BANK_DEPOSIT) "2.75" else ""
            val e = validateAssetInput(
                selectedType = type,
                name = "测试",
                symbol = sym,
                account = "测试账户",
                balance = bal,
                purchasePrice = price,
                interestRate = rate
            )
            assertTrue("${type} 应判定为合法，实际: ${e.summary}", e.isValid)
            // 校验放行却构造失败 = 两处口径漂移，会直接崩在主线程
            buildAssetFromInput(
                selectedType = type,
                name = "测试",
                symbol = sym,
                account = "测试账户",
                balance = bal,
                purchasePrice = price,
                interestRate = rate,
                notes = "",
                fundPrice = null,
                stockPrice = null
            )
        }
    }

    // ---------- 数字输入过滤 ----------

    @Test
    fun 过滤掉多余的小数点() {
        assertEquals("1.23", sanitizeDecimalInput("1.2.3"))
    }

    @Test
    fun 过滤掉字母与符号() {
        assertEquals("1234.56", sanitizeDecimalInput("1,234.56"))
        assertEquals("12", sanitizeDecimalInput("12a"))
        assertEquals("", sanitizeDecimalInput("abc"))
    }

    @Test
    fun 负号被过滤_因为数量与单价都不允许负数() {
        assertEquals("5", sanitizeDecimalInput("-5"))
    }

    @Test
    fun 合法小数保持原样() {
        assertEquals("3.14", sanitizeDecimalInput("3.14"))
        assertEquals("", sanitizeDecimalInput(""))
    }

    @Test
    fun 单独一个小数点不会被吞掉_否则没法输入0点几() {
        assertEquals(".", sanitizeDecimalInput("."))
        assertEquals("0.5", sanitizeDecimalInput("0.5"))
    }
}
