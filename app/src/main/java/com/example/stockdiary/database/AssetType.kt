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

        /**
         * 有行情、参与价格同步的类型。
         *
         * 银行存款与负债没有行情来源：传给 [com.example.stockdiary.data.sync.AssetSyncManager]
         * 会被 else 分支静默跳过——不报错，但会白占进度分母（"3/10" 里永远有几个不算完成）。
         *
         * 各页「刷新」按钮统一用这个集合决定**同步范围**，避免出现
         * 「资产页同步含负债、持仓页刷新漏加密货币」这类同一个意图三种范围的问题。
         *
         * 注意它只用于同步范围：**收入/分红计算必须用「全部非负债资产」**
         * （银行存款的利息是收入的一部分），两者不要混用。
         */
        fun getPriceSyncedTypes(): List<AssetType> = listOf(STOCK, FUND, CRYPTO)
    }
}
