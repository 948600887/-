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
        const val EASTMONEY_REFERER = "https://fundf10.eastmoney.com/"
        const val TSANGHI_REFERER = "https://tsanghi.com/"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}

object Timeouts {
    const val CONNECT_MS = 10000
    const val READ_MS = 15000
    const val WRITE_MS = 15000
    /**
     * 单次调用的墙钟硬上限（[okhttp3.OkHttpClient.Builder.callTimeout]）。
     *
     * connect/read 超时只约束「单个连接上的单次 IO」，而 OkHttp 默认
     * `retryOnConnectionFailure(true)`：主机解析出多个 IP（币安常同时给 IPv6/IPv4 一串）
     * 时会逐个地址重试，每个地址各自重新计 connecting 超时，叠加起来单个 call
     * 能挂近一小时——实测 LDLTCUSDT 挂了 3478 秒，把 AssetSyncManager 的并发
     * 信号量整个堵死，一轮同步跑到 70 分钟。
     *
     * callTimeout 覆盖从 enqueue 到响应读完的**整段**时间，包括重试与排队，
     * 是唯一能兜住「主机压根不可达」的手段。
     */
    const val CALL_MS = 20000
}

object CacheConfig {
    const val PRICE_CACHE_MS = 60000L
    const val PORTFOLIO_CACHE_MS = 300000L
    const val FUND_CACHE_MS = 300000L
    const val FUND_DETAIL_CACHE_MS = 3600000L
    const val FUND_HISTORY_CACHE_MS = 1800000L
    const val DIVIDEND_CACHE_MS = 86400000L
    /**
     * 汇率刷新 TTL：6 小时。App 启动与币安导入都会触发刷新，
     * 靠它避免短时间内重复回源；离线时沿用当前汇率，不阻塞任何链路。
     */
    const val EXCHANGE_RATE_TTL_MS = 6 * 60 * 60 * 1000L
    const val MAX_CACHE_ENTRIES = 100
    const val MAX_FUND_CACHE_ENTRIES = 50
}

object RequestLimits {
    const val MAX_RETRY = 3
    const val BATCH_SIZE = 20
    const val DELAY_MS = 300L
}
