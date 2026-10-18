package com.example.stockdiary.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * AppColors 「浅色零变化」钉死测试。
 *
 * 背景：深色模式迁移时，ImportBrokeragePreview 的硬编码 hex 曾误映射到
 * error/success/warning/errorContainer 通用令牌，浅色值发生漂移
 * （如异常行底 #FFCDD2 -> #FFEBEE、成功文字 #2E7D32 -> #43A047）。
 * 现改为 4 个专用令牌（errorText/successText/warningText/errorRowBackground），
 * 浅色值逐字等于原硬编码；本测试防止以后有人改回通用令牌或填错值。
 *
 * 注意：不要改 error/success/warning/errorContainer 的浅色值来迁就本页面——
 * 那些令牌在其他页面也在用；也不要用 primaryGreen 顶替 successText
 * （其深色值 #1B5E20 在深底上对比不足）。
 */
class AppColorsTest {

    /** 导入预览 4 个专用令牌：浅色值必须逐字等于原硬编码字面量。 */
    @Test
    fun `light import-preview tokens equal original hardcoded literals`() {
        assertEquals(Color(0xFF2E7D32), LightAppColors.successText)
        assertEquals(Color(0xFFC62828), LightAppColors.errorText)
        assertEquals(Color(0xFFF57F17), LightAppColors.warningText)
        assertEquals(Color(0xFFFFCDD2), LightAppColors.errorRowBackground)
    }

    /** 重复行底色本就一致（#FFF59D），一并钉死防止回归。 */
    @Test
    fun `light warningContainer equals original hardcoded literal`() {
        assertEquals(Color(0xFFFFF59D), LightAppColors.warningContainer)
    }

    /** 深色值必须是 Color.kt 已有的亮色变体，保证深底对比度。 */
    @Test
    fun `dark import-preview tokens use bright variants`() {
        assertEquals(ProfitGreenBright, DarkAppColors.successText)
        assertEquals(LossRedBright, DarkAppColors.errorText)
        assertEquals(WarningBright, DarkAppColors.warningText)
        assertEquals(ErrorContainerDark, DarkAppColors.errorRowBackground)
    }

    /** 深浅两套不得相同：防止以后有人图省事把两套填成一样，深色模式当场失效。 */
    @Test
    fun `dark tokens differ from light values`() {
        assertNotEquals(LightAppColors.successText, DarkAppColors.successText)
        assertNotEquals(LightAppColors.errorText, DarkAppColors.errorText)
        assertNotEquals(LightAppColors.warningText, DarkAppColors.warningText)
        assertNotEquals(LightAppColors.errorRowBackground, DarkAppColors.errorRowBackground)
        assertNotEquals(LightAppColors.warningContainer, DarkAppColors.warningContainer)
    }
}
