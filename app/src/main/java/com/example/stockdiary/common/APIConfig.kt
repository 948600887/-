package com.example.stockdiary.common

object APIEndpoints {
    // ===== 新浪财经 =====
    object Sina {
        const val BASE_URL = "https://finance.sina.com.cn"
        const val HQ_API = "https://hq.sinajs.cn"
        const val HISTORY_API = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData"
        const val STOCK_SEARCH = "https://stock.finance.sina.com.cn/stock/api/openapi.php/StockSearchService.search"
        const val STOCK_SEARCH_KEYWORD = "keyword"
        const val DIVIDEND_PAGE = "https://vip.stock.finance.sina.com.cn/corp/go.php/vISSUE_ShareBonus/stockid/"
        const val BONUS_PAGE = "https://finance.sina.com.cn/realstock/company/"
    }

    // ===== 腾讯财经 =====
    object Tencent {
        const val BASE_URL = "https://gu.qq.com"
        const val QT_API = "https://qt.gtimg.cn/q="
        const val QFQ_KLINE = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
        const val PROXY_URL = "https://proxy.finance.qq.com/ifzq/quot/quote/share/"
    }

    // ===== 东方财富 =====
    object Eastmoney {
        const val BASE_URL = "https://fundf10.eastmoney.com"
        const val FUND_DATA = "https://fund.eastmoney.com/pingzhongdata"  // 原 fundgz 已下线
        const val FUND_DETAIL = "https://fundf10.eastmoney.com/jjjz_{code}.html"
        const val FHSP = "https://fundf10.eastmoney.com/fhsp_"
    }

    // ===== 币安 =====
    object Binance {
        const val BASE_URL = "https://api.binance.com/api/v3"
        const val TICKER = "/ticker/price"
        const val EXCHANGE_INFO = "/exchangeInfo"
        const val KLINE = "/klines"
    }

    // ===== 汤森 =====
    object Tsanghi {
        const val BASE_URL = "https://tsanghi.com/api/fin/stock"
        const val DIVIDEND = "/XSHG/dividends.json"
    }

    // ===== 请求头 =====
    object Headers {
        const val SINA_REFERER = "https://finance.sina.com.cn"
        const val TENCENT_REFERER = "https://gu.qq.com"
        const val EASTMONEY_REFERER = "https://fundf10.eastmoney.com/"
        const val TSANGHI_REFERER = "https://tsanghi.com/"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}

object Timeouts {
    const val CONNECT_MS = 10000
    const val READ_MS = 15000
    const val WRITE_MS = 15000
}

object CacheConfig {
    const val PRICE_CACHE_MS = 60000L
    const val FUND_CACHE_MS = 300000L
    const val FUND_DETAIL_CACHE_MS = 3600000L
    const val FUND_HISTORY_CACHE_MS = 1800000L
    const val DIVIDEND_CACHE_MS = 86400000L
    const val MAX_CACHE_ENTRIES = 100
    const val MAX_FUND_CACHE_ENTRIES = 50
}

object RequestLimits {
    const val MAX_RETRY = 3
    const val BATCH_SIZE = 20
    const val DELAY_MS = 300L
}
