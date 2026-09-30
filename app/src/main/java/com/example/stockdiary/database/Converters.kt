package com.example.stockdiary.database

import androidx.room.TypeConverter
import com.example.stockdiary.utils.AppLogger

class Converters {
    @TypeConverter
    fun fromAssetType(value: AssetType): String {
        return value.name
    }
    
    @TypeConverter
    fun toAssetType(value: String): AssetType {
        return try {
            AssetType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            AppLogger.w("未知资产类型: $value，回退为 STOCK", "Converters")
            AssetType.STOCK
        }
    }
}
