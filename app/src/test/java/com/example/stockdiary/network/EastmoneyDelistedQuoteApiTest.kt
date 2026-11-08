package com.example.stockdiary.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EastmoneyDelistedQuoteApiTest {

    @Test
    fun `解析最后一根K线_取交易日与收盘价`() {
        val json = """{"rc":0,"data":{"code":"400264","market":0,"name":"R东方1",""" +
            """"decimal":2,"preKPrice":0.23,"klines":["2026-11-06,0.22"]}}"""
        val quote = EastmoneyDelistedQuoteApi.parseLastClose(json)
        assertEquals(0.22, quote!!.price, 1e-9)
        assertEquals("2026-11-06", quote.tradeDate)
    }

    @Test
    fun `数据为空或非零rc或非法价格_都解析为null`() {
        assertNull(
            EastmoneyDelistedQuoteApi.parseLastClose(
                """{"rc":100,"data":null}"""
            )
        )
        assertNull(
            EastmoneyDelistedQuoteApi.parseLastClose(
                """{"rc":0,"data":{"klines":[]}}"""
            )
        )
        assertNull(
            EastmoneyDelistedQuoteApi.parseLastClose(
                """{"rc":0,"data":{"klines":["2026-11-06,0"]}}"""
            )
        )
        assertNull(
            EastmoneyDelistedQuoteApi.parseLastClose(
                """{"rc":0,"data":{"klines":["不是日期,0.22"]}}"""
            )
        )
    }

    @Test
    fun `先试market0_命中即止`() = runBlocking {
        val urls = mutableListOf<String>()
        val client = fakeClient { chain ->
            urls += chain.request().url.toString()
            ok(
                chain,
                """{"rc":0,"data":{"klines":["2026-11-06,0.22"]}}"""
            )
        }

        val quote = EastmoneyDelistedQuoteApi.fetchLastClose("bj400264", client)
        assertEquals(0.22, quote!!.price, 1e-9)
        assertEquals("2026-11-06", quote.tradeDate)
        assertEquals(1, urls.size)
        assertTrue("market 0 是老三板默认市场: ${urls[0]}", urls[0].contains("secid=0.400264"))
    }

    @Test
    fun `market0无数据时回落到market1`() = runBlocking {
        val urls = mutableListOf<String>()
        val client = fakeClient { chain ->
            val url = chain.request().url.toString()
            urls += url
            if (url.contains("secid=0.")) {
                ok(chain, """{"rc":100,"data":null}""")
            } else {
                ok(chain, """{"rc":0,"data":{"klines":["2026-11-06,1.23"]}}""")
            }
        }

        val quote = EastmoneyDelistedQuoteApi.fetchLastClose("sh420102", client)
        assertEquals(1.23, quote!!.price, 1e-9)
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("secid=0.420102"))
        assertTrue(urls[1].contains("secid=1.420102"))
    }

    @Test
    fun `非6位代码不发请求`() = runBlocking {
        var hits = 0
        val client = fakeClient { chain ->
            hits++
            ok(chain, """{"rc":0,"data":{"klines":["2026-11-06,0.22"]}}""")
        }
        assertNull(EastmoneyDelistedQuoteApi.fetchLastClose("bj40026", client))
        assertEquals(0, hits)
    }

    @Test
    fun `HTTP失败返回null而不是抛异常`() = runBlocking {
        val client = fakeClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Server Error")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }
        assertNull(EastmoneyDelistedQuoteApi.fetchLastClose("bj400264", client))
    }

    private fun fakeClient(handler: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handler(chain) }).build()

    private fun ok(chain: Interceptor.Chain, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
