package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "dividend",
    indices = [
        androidx.room.Index(value = ["symbol"]),
        androidx.room.Index(value = ["symbol", "date"], unique = true)
    ]
)
data class Dividend(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val symbol: String,
    val name: String,
    val amount: Double,
    /** 除息日（决定你有没有资格拿这笔分红的日子） */
    val date: String,
    /**
     * 分红发放日——钱真正到账的日子，通常比除息日晚 1~4 周。
     * 只有部分数据源给得出（天天基金的「分红发放日」列）；拿不到就是 null，
     * 此时界面/通知退回用 [date]（除息日），并在文案上说明那是除息日。
     */
    val payDate: String? = null,
    /** 是否为用户手动添加/修正的记录。全量刷新时保留此类记录，不被自动抓取数据覆盖。 */
    val isManual: Boolean = false
)