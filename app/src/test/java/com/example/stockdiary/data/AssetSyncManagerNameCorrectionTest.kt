package com.example.stockdiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AssetSyncManager.nameCorrectionOrNull] —— 导入后按代码校正证券名的判定（纯函数）。
 *
 * 背景：一码通截图走 OCR，形近字会把证券名认错（华电REIT→怯电REIT、科创50→料创50）。
 * 行情接口按代码返回标准名，用来纠偏；但**只纠"不同且行情名非空"**，
 * 名字相同或行情名为空时一律不改——后者保证不会把用户自定义名冲掉。
 */
class AssetSyncManagerNameCorrectionTest {

    @Test
    fun OCR错名_行情名非空且不同_改成行情名() {
        assertEquals("华电REIT", AssetSyncManager.nameCorrectionOrNull("怯电REIT", "华电REIT"))
        assertEquals("科创50", AssetSyncManager.nameCorrectionOrNull("料创50", "科创50"))
    }

    @Test
    fun 名字相同_不改() {
        assertNull(AssetSyncManager.nameCorrectionOrNull("华电REIT", "华电REIT"))
    }

    @Test
    fun 行情名为空或空白_不改() {
        assertNull(AssetSyncManager.nameCorrectionOrNull("怯电REIT", null))
        assertNull(AssetSyncManager.nameCorrectionOrNull("怯电REIT", ""))
        assertNull(AssetSyncManager.nameCorrectionOrNull("怯电REIT", "   "))
    }
}
