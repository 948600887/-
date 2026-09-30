package com.example.stockdiary.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val symbol: String,
    val name: String,
    val type: String,
    val quantity: Double,
    val price: Double,
    val date: String,
    val fee: Double = 0.0
)