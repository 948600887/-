package com.example.stockdiary.data.price

/**
 * 逻辑数据源域（G4 脚手架，轻量枚举）。
 *
 * 与 [com.example.stockdiary.database.AssetType] 语义**不同**：后者是「资产 / 负债」的
 * 资产分类（股票 / 基金 / 加密 / 存款 / 负债 …），这里是「按取价渠道划分的逻辑数据源」，
 * 例如 A 股行情、场外基金净值、场内基金、REITs、加密、外汇、分红数据。
 *
 * 本组只做结构就位 + 占位注册，不接任何真实取价、不替换现有取价逻辑；为后续按域接入
 * 真实 [PriceSourceAdapter]、并让 [com.example.stockdiary.network.SourceRegistry] 能按域
 * 解析适配器铺路。
 */
enum class SourceDomain {
    /** A 股 / 股票行情 */
    STOCK_A,

    /** 场外基金（净值，天天基金） */
    FUND_OTC,

    /** 场内基金（ETF / LOF / 场内基金） */
    FUND_EXCHANGE,

    /** 基础设施 REITs */
    REIT,

    /** 加密货币（币安） */
    CRYPTO,

    /** 外汇 */
    FX,

    /** 分红数据 */
    DIVIDEND
}
