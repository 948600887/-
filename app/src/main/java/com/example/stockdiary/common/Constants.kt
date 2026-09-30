package com.example.stockdiary.common

/**
 * 应用常量配置类
 */
object Constants {
    // 缓存配置
    const val CACHE_EXPIRY_TIME = 60000 // 1分钟缓存过期时间
    const val MAX_CACHE_SIZE = 100 // 最大缓存大小
    const val MAX_LIST_CACHE_SIZE = 1000 // 最大列表缓存大小
    const val PORTFOLIO_CACHE_DURATION = 5 * 60 * 1000 // 5分钟投资组合缓存
    
    // 网络配置
    const val MAX_RETRY_COUNT = 3 // 最大重试次数
    const val BATCH_REQUEST_SIZE = 20 // 批量请求大小
    const val REQUEST_DELAY_MS = 300 // 请求间隔时间
    
    // 数据库配置（必须与 StockDatabase 中的名称/版本保持一致）
    const val DATABASE_NAME = "stock_diary_db"
    const val DATABASE_VERSION = 10
    
    // API配置
    const val BASE_URL_REALTIME = "https://hq.sinajs.cn/list="
    const val BASE_URL_HISTORY = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData"
    
    // 错误消息
    const val ERROR_NETWORK = "网络连接失败，请检查网络设置"
    const val ERROR_API_RATE_LIMIT = "API请求频率过高，请稍后再试"
    const val ERROR_EMPTY_DATA = "未获取到数据"
    const val ERROR_DATABASE = "数据库操作失败"
    
    // 成功消息
    const val SUCCESS_ADD_STOCK = "股票添加成功"
    const val SUCCESS_UPDATE_STOCK = "股票更新成功"
    const val SUCCESS_DELETE_STOCK = "股票删除成功"
    const val SUCCESS_IMPORT_DATA = "数据导入成功"
}