package com.example.stockdiary.database

enum class AssetType(val displayName: String, val icon: String, val isLiability: Boolean = false) {
    BANK_DEPOSIT("银行存款", "💳"),
    STOCK("股票", "📈"),
    FUND("基金", "📊"),
    CRYPTO("虚拟货币", "🪙"),
    
    LOAN("贷款", "🏦", true),
    CREDIT_CARD("信用卡", "💳", true),
    MORTGAGE("房贷", "🏠", true),
    CAR_LOAN("车贷", "🚗", true),
    OTHER_LIABILITY("其他负债", "📋", true);
    
    companion object {
        fun getAssetTypes(): List<AssetType> = entries.filter { !it.isLiability }
        fun getLiabilityTypes(): List<AssetType> = entries.filter { it.isLiability }
        fun getAllTypes(): List<AssetType> = entries
    }
}
