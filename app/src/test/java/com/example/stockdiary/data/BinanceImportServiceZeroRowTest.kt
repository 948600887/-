package com.example.stockdiary.data

import com.example.stockdiary.data.importer.BinanceImportService
import com.example.stockdiary.data.importer.SnapshotAlignment
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BinanceImportService.buildZeroRows] 回归。
 *
 * 核心不变量：re-import 时，上一轮就已置 0 的折叠壳行（balance≈0）不再生成冗余的
 * 「覆盖 0 → 0」UPDATE——它们只是日志/计数噪音与一次无谓的 DB 写。
 * 仍真实持有余额（> DUST_THRESHOLD = 1e-8）的壳行照常保留为零行。
 */
class BinanceImportServiceZeroRowTest {

    @Test
    fun 已清零的折叠壳行被过滤_仅保留有余额的() {
        val assets = listOf(
            // 上轮已置 0 的壳行：本次不应再写
            Asset(name = "x", type = AssetType.CRYPTO, account = "默认", balance = 0.0),
            // 低于 DUST_THRESHOLD(1e-8) 的零头：同样视为已清零，过滤掉
            Asset(name = "y", type = AssetType.CRYPTO, account = "默认", balance = 1e-9),
            // 仍持有真实余额、需要清 0 的壳行：保留
            Asset(name = "z", type = AssetType.CRYPTO, account = "默认", balance = 144.99)
        )

        val rows = BinanceImportService.buildZeroRows(assets)

        assertEquals(1, rows.size)
        val kept = rows.single()
        // 零行数量恒为 0
        assertEquals(0.0, kept.quantity, 1e-9)
        // 零行状态恒为 UPDATE（覆盖旧行数量）
        assertEquals(SnapshotAlignment.ItemStatus.UPDATE, kept.status)
    }
}
