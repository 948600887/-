package com.example.stockdiary.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * [BinanceAccountAPI.classifyFailure] 归类回归。
 *
 * 核心不变量：[BinanceApiException] 带真实 HTTP/业务码，**必须先按码分类**，
 * 绝不能让 message 里可能出现的 "timeout"/"interrupted" 关键词把它误判成网络失败
 *（例如 -1007 业务码响应体常带 timeout 字样）。401/403/451/-2015 归权限类，
 * 418/429/5xx、404、-1007 归服务端/限流；纯网络异常（SocketTimeout）仍归 NETWORK，
 * 普通无关键词异常兜底归 PERMISSION_OR_OTHER。
 */
class BinanceAccountAPIClassifyFailureTest {

    @Test
    fun 负1007业务码含timeout字样_不再误归网络() {
        // message 里带 timeout，旧实现会被 isNetworkFailure 误判成 NETWORK；
        // 按码分类后 -1007 是币安侧业务错误 → SERVER_OR_RATE_LIMIT
        val e = BinanceApiException(
            httpCode = 400,
            binanceCode = -1007,
            message = "recvWindow not reached (timeout)"
        )
        assertEquals(BinanceFailureKind.SERVER_OR_RATE_LIMIT, BinanceAccountAPI.classifyFailure(e))
    }

    @Test
    fun http404_归服务端而非权限() {
        // 404 是端点/路由错（币安侧），不是用户权限问题 → SERVER_OR_RATE_LIMIT
        val e = BinanceApiException(httpCode = 404, binanceCode = 404, message = "not found")
        assertEquals(BinanceFailureKind.SERVER_OR_RATE_LIMIT, BinanceAccountAPI.classifyFailure(e))
    }

    @Test
    fun http401_归权限类() {
        val e = BinanceApiException(httpCode = 401, binanceCode = -2011, message = "api key invalid")
        assertEquals(BinanceFailureKind.PERMISSION_OR_OTHER, BinanceAccountAPI.classifyFailure(e))
    }

    @Test
    fun http403_归权限类() {
        val e = BinanceApiException(httpCode = 403, binanceCode = -2015, message = "permission denied")
        assertEquals(BinanceFailureKind.PERMISSION_OR_OTHER, BinanceAccountAPI.classifyFailure(e))
    }

    @Test
    fun http451_归权限类_保留换节点提示() {
        // 451 地区限制 → 用户可修（换代理节点），仍归 PERMISSION_OR_OTHER
        val e = BinanceApiException(httpCode = 451, binanceCode = 0, message = "restricted location")
        assertEquals(BinanceFailureKind.PERMISSION_OR_OTHER, BinanceAccountAPI.classifyFailure(e))
    }

    @Test
    fun http429_保留归限流() {
        // 行为与旧实现一致：限流 → SERVER_OR_RATE_LIMIT
        val e = BinanceApiException(httpCode = 429, binanceCode = -1006, message = "too many requests")
        assertEquals(BinanceFailureKind.SERVER_OR_RATE_LIMIT, BinanceAccountAPI.classifyFailure(e))
    }

    @Test
    fun http500_保留归服务端() {
        // 行为与旧实现一致：服务端抖动 → SERVER_OR_RATE_LIMIT
        val e = BinanceApiException(httpCode = 500, binanceCode = -1001, message = "internal error")
        assertEquals(BinanceFailureKind.SERVER_OR_RATE_LIMIT, BinanceAccountAPI.classifyFailure(e))
    }

    @Test
    fun socket超时_归网络() {
        // 纯网络异常（无 BinanceApiException 包装）→ NETWORK，保留旧行为
        assertEquals(BinanceFailureKind.NETWORK, BinanceAccountAPI.classifyFailure(SocketTimeoutException()))
    }

    @Test
    fun 普通异常无关键词_归权限兜底() {
        // 普通 Exception("boom") 既非 API 异常也无网络关键词 → PERMISSION_OR_OTHER，保留旧行为
        assertEquals(
            BinanceFailureKind.PERMISSION_OR_OTHER,
            BinanceAccountAPI.classifyFailure(Exception("boom"))
        )
    }
}
