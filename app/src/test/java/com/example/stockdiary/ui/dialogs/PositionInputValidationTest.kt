package com.example.stockdiary.ui.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionInputValidationTest {

    private fun errors(
        action: PositionAction = PositionAction.ADD,
        amount: String = "100",
        price: String = "",
        currentBalance: Double = 1000.0,
        label: String = "买卖数量"
    ) = validatePositionInput(action, amount, price, currentBalance, label)

    // ---------- 合法输入 ----------

    @Test
    fun 加仓数量合法且单价留空时没有任何错误() {
        val e = errors()
        assertTrue(e.isValid)
        assertNull(e.summary)
        assertNull(e.firstField)
    }

    @Test
    fun 加仓填了合法单价也没有错误() {
        assertTrue(errors(price = "12.34").isValid)
    }

    @Test
    fun 加仓数量可以等于当前持仓_不受减仓限制约束() {
        assertTrue(errors(action = PositionAction.ADD, amount = "1000").isValid)
    }

    @Test
    fun 减仓数量小于持仓是合法的() {
        assertTrue(errors(action = PositionAction.REDUCE, amount = "999").isValid)
    }

    // ---------- 数量字段 ----------

    @Test
    fun 数量不是数字时报错在数量字段上() {
        val e = errors(amount = "abc")
        assertFalse(e.isValid)
        assertEquals("买卖数量", e.amount?.label)
        assertEquals("请输入有效数字", e.amount?.message)
        assertEquals("amount", e.firstField)
    }

    @Test
    fun 数量为零或负数报错() {
        assertEquals("必须大于 0", errors(amount = "0").amount?.message)
        assertEquals("必须大于 0", errors(amount = "-5").amount?.message)
    }

    // ---------- 单价字段：错误必须落在单价上，不能甩锅给数量 ----------

    @Test
    fun 单价非法时报错在单价字段上_数量字段无错() {
        // 这条是这个用例存在的理由：改造前 isValid 把数量的条件和单价的条件
        // 混在一起算，结果却挂在数量字段的 isError 上——
        // 单价填错时标红的是数量框、提示「数量不合法」，而单价框一声不吭。
        val e = errors(amount = "100", price = "abc")
        assertFalse(e.isValid)
        assertNull("数量是合法的，不该报错", e.amount)
        assertNotNull("单价错了，必须报在单价上", e.price)
        assertEquals("成交单价", e.price?.label)
        assertEquals("请输入有效数字", e.price?.message)
        assertEquals("price", e.firstField)
    }

    @Test
    fun 单价为负数报错在单价字段上() {
        val e = errors(price = "-3")
        assertNull(e.amount)
        assertEquals("不能为负数", e.price?.message)
    }

    @Test
    fun 单价留空表示沿用参考价_不算错误() {
        val e = errors(price = "   ")
        assertNull(e.price)
        assertTrue(e.isValid)
    }

    @Test
    fun 单价为零是合法的_对应送股继承这类零成本获得() {
        assertTrue(errors(price = "0").isValid)
    }

    // ---------- 减仓边界 ----------

    @Test
    fun 减仓数量超过持仓报错() {
        val e = errors(action = PositionAction.REDUCE, amount = "1001", currentBalance = 1000.0)
        assertFalse(e.isValid)
        // 提示里带具体持仓数，整数不拖小数点
        assertEquals("减仓数量不能超过持仓 1000", e.amount?.message)
    }

    @Test
    fun 减仓数量等于持仓报错_因为那等于删除资产() {
        val e = errors(action = PositionAction.REDUCE, amount = "1000", currentBalance = 1000.0)
        assertFalse(e.isValid)
        assertEquals("会清空该持仓（等于删除资产），若要清仓请用删除", e.amount?.message)
    }

    @Test
    fun 持仓为小数时提示不拖多余的零() {
        val e = errors(action = PositionAction.REDUCE, amount = "1", currentBalance = 0.5)
        assertEquals("减仓数量不能超过持仓 0.5", e.amount?.message)
    }

    // ---------- 汇总与先后顺序 ----------

    @Test
    fun 数量和单价都错时_firstField指向数量_汇总列出两项() {
        val e = errors(amount = "abc", price = "xyz")
        assertEquals("amount", e.firstField)
        assertEquals("请检查：买卖数量、成交单价", e.summary)
    }

    @Test
    fun 只有单价错时_汇总只列单价() {
        assertEquals("请检查：成交单价", errors(price = "xyz").summary)
    }

    @Test
    fun 提示里的数量叫法跟随资产类型() {
        assertEquals("买卖份额", errors(label = "买卖份额", amount = "x").amount?.label)
    }
}
