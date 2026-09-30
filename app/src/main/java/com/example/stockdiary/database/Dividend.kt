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
    val date: String,
    /** 是否为用户手动添加/修正的记录。全量刷新时保留此类记录，不被自动抓取数据覆盖。 */
    val isManual: Boolean = false
)