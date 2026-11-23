package com.example.stockdiary.network.base

/**
 * `network.base` 包只放**数据契约接口**（[PriceData] / [MarketData]），
 * 供 `network.model.DataModels` 实现、由各行情源填充。
 *
 * 这里原本还有一个 `abstract class BaseAPI<T>`（OkHttp 抓取 + 正则 JSON 解析的基类）。
 * 它已于本轮删除：**全项目没有任何子类**，类名与 6 个成员在 main/test/androidTest/
 * 反射字符串/proguard 规则里全部零引用——一个没人继承的抽象基类不可能有调用方，
 * 留着只会让人以为「新行情源应当继承它」，而真实的行情源都直接调
 * [com.example.stockdiary.network.UnifiedPriceService]（带缓存/熔断/健康度）。
 *
 * 结论：新增行情源请实现 `PriceSourceAdapter` + 注册到 `SourceRegistry`，
 * **不要**再引入「基类 + 每源自写解析」的模式。
 */

/**
 * 统一的报价契约：所有行情源解析出的价格都要满足它。
 */
interface PriceData {
    val code: String
    val name: String
    val price: Double
    val change: Double?
    val changePercent: Double?
    val timestamp: Long
}

/**
 * K 线/盘口补充字段契约（可缺省）。
 */
interface MarketData {
    val high: Double?
    val low: Double?
    val open: Double?
    val volume: Long?
    val amount: Double?
}
