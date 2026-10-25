package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新浪行情 gzip 透明解压契约的端到端回归。
 *
 * ## 根因（2026-10-25 诊断日志 12:56 轮「新浪成0/符败13」）
 * SinaStockAPI 的 client 曾手动给请求加 `Accept-Encoding: gzip, deflate`。
 * OkHttp 的 BridgeInterceptor 只在「请求**没有** Accept-Encoding 头」时才：
 *   ① 自动补 `Accept-Encoding: gzip`；
 *   ② 对响应做透明解压（响应带 Content-Encoding: gzip → 解压后再交给调用方）。
 * 手动加了这个头，OkHttp 就认为调用方要自己解压，于是 `body.bytes()` 拿到的是
 * gzip 压缩字节（魔数 1f 8b），被直接按 GBK 解码成乱码 → `var hq_str_...` 正则
 * 一条都匹配不到 → 所有代码判「无数据」→ 静默回退腾讯。腾讯 client 没加这个头，
 * 所以一直正常（与日志「腾讯 13/13 成功」吻合）。
 *
 * ## 怎么钉死
 * 不起真网：本地 ServerSocket 按新浪真实行为返回 **Content-Encoding: gzip**
 * 的压缩响应体；client 用**真实**的 [SinaStockAPI.createSinaHttpClient] 配置，
 * 只加一条把域名改写到 127.0.0.1 的拦截器。链路走通 = OkHttp 确实在自动解压；
 * 谁把手动 Accept-Encoding 加回来，透明解压即被关掉，本测试立刻红。
 */
class SinaStockGzipDecompressionTest {

    private val recordedAcceptEncoding = AtomicReference<String>(null)
    private val serverError = AtomicReference<Exception>()

    @After
    fun tearDown() {
        SinaStockAPI.resetHttpClientForTesting()
        SinaStockAPI.invalidateCache()
    }

    @Test
    fun `真实client配置下_gzip压缩响应体能被自动解压并解析出报价`() = runBlocking {
        val serverSocket = ServerSocket(0)
        serverSocket.soTimeout = 30_000
        val port = serverSocket.localPort

        val body = quoteLine("sh600001", "浦发银行", "10.00", "10.50") + "\n" +
            quoteLine("sh600002", "齐鲁石化", "5.20", "5.35")
        val gzBytes = gzip(body.toByteArray(Charset.forName("GBK")))

        val serverDone = CountDownLatch(1)
        val serverThread = Thread {
            try {
                serverSocket.use { socket ->
                    socket.accept().use { conn ->
                        val requestHead = readRequestHead(conn.getInputStream())
                        recordedAcceptEncoding.set(parseAcceptEncoding(requestHead))
                        val out = conn.getOutputStream()
                        out.write(buildGzipResponse(gzBytes))
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                serverError.set(e)
            } finally {
                serverDone.countDown()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        SinaStockAPI.httpClientForTesting = redirectToLocalClient(port)
        SinaStockAPI.invalidateCache()

        val result = SinaStockAPI.getRealtimeData(listOf("sh600001", "sh600002"))

        assertTrue("本地假服务不应报错：${serverError.get()}", serverError.get() == null)
        assertTrue(
            "gzip 响应必须被自动解压并解析成功（否则即 Accept-Encoding 手动头回归）；实际=$result",
            result is Result.Success
        )
        val map = (result as Result.Success).data
        assertTrue("sh600001 应解析出来", map.containsKey("sh600001"))
        assertTrue("sh600002 应解析出来", map.containsKey("sh600002"))
        assertEquals(10.50, map["sh600001"]!!.price, 0.001)
        // GBK 解码链路一并验证：解压后的字节必须按 GBK 还原出中文名
        assertEquals("浦发银行", map["sh600001"]!!.name)

        // 请求头契约：绝不能带手动 "gzip, deflate"（OkHttp 自动补的是裸 "gzip"）
        val sent = recordedAcceptEncoding.get()
        assertTrue("服务端应收到 OkHttp 自动补的 Accept-Encoding，实际=$sent", sent != null && sent.contains("gzip"))
        assertFalse("绝不能手动带 deflate——那会关掉 OkHttp 的透明解压", sent.contains("deflate"))

        assertTrue("假服务线程应已收尾", serverDone.await(10, TimeUnit.SECONDS))
    }

    // ---- helpers ----

    /** 真实 sina client 配置 + 一条把 hq.sinajs.cn 改写到本地假服务的拦截器 */
    private fun redirectToLocalClient(port: Int): OkHttpClient =
        SinaStockAPI.createSinaHttpClient().newBuilder()
            .addInterceptor { chain: Interceptor.Chain ->
                val request = chain.request()
                val newUrl = request.url.newBuilder()
                    .scheme("http")
                    .host("127.0.0.1")
                    .port(port)
                    .build()
                chain.proceed(request.newBuilder().url(newUrl).build())
            }
            .build()

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(data) } }.toByteArray()

    private fun buildGzipResponse(gz: ByteArray): ByteArray {
        val head = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=GBK\r\n" +
                "Content-Encoding: gzip\r\n" +
                "Content-Length: ${gz.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        return head + gz
    }

    /** 读到 \r\n\r\n（请求头结束）为止，返回原始字节 */
    private fun readRequestHead(input: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            bos.write(b)
            val arr = bos.toByteArray()
            if (arr.size >= 4 &&
                arr[arr.size - 4] == '\r'.code.toByte() &&
                arr[arr.size - 3] == '\n'.code.toByte() &&
                arr[arr.size - 2] == '\r'.code.toByte() &&
                arr[arr.size - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return bos.toByteArray()
    }

    private fun parseAcceptEncoding(requestHead: ByteArray): String? {
        val text = String(requestHead, Charsets.US_ASCII)
        return text.split("\r\n")
            .firstOrNull { it.startsWith("accept-encoding:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
    }

    /** 一条 32 字段的新浪行情报价行 */
    private fun quoteLine(code: String, name: String, prevClose: String, price: String): String {
        val fields = List(32) { i ->
            when (i) {
                0 -> name
                2 -> prevClose
                3 -> price
                else -> "0"
            }
        }
        return """var hq_str_$code="${fields.joinToString(",")}";"""
    }
}
