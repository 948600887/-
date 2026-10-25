package com.example.stockdiary.data.dividend

import com.example.stockdiary.common.Result as AppResult
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
 * 新浪分红兜底源 gzip 透明解压契约的端到端回归。
 *
 * ## 根因（2026-10-25 QA 三重证据闭合：代码 + curl 对照实验 + 诊断日志）
 * [DividendService] 的 HEADERS 曾手动给请求加 `Accept-Encoding: gzip, deflate, br`。
 * OkHttp 的 BridgeInterceptor 只在「请求**没有** Accept-Encoding 头」时才：
 *   ① 自动补 `Accept-Encoding: gzip`；
 *   ② 对响应做透明解压（响应带 Content-Encoding: gzip → 解压后再交给调用方）。
 * 手动加了这个头，OkHttp 就认为调用方要自己解压，于是 `body.bytes()` 拿到的是
 * gzip 压缩字节（魔数 1f 8b），被直接按 GBK 解码成乱码 → Jsoup 找不到任何
 * 分红表格 → 解析 0 条记录 → 上层吞成「新浪分红源未应答」→ markFailed 6 小时
 * 退避。curl 实证：带该头请求 vip.stock.finance.sina.com.cn 分红页返回
 * `Content-Encoding: gzip` 压缩体；不带则返回 58740 字节明文 HTML。
 * 与 SinaStockAPI（commit ee1fbba）、`&rn=` URL 污染是同型第三例。
 *
 * ## 怎么钉死
 * 不起真网：本地 ServerSocket 按新浪真实行为返回 **Content-Encoding: gzip**
 * 的压缩响应体（页面实体按 GBK 编码）；client 用**真实**的
 * [DividendService.createDividendHttpClient] 配置，只加一条把新浪分红域名
 * 改写到 127.0.0.1 的拦截器。链路走通 = OkHttp 确实在自动解压；
 * 谁把手动 Accept-Encoding 加回来，透明解压即被关掉，本测试立刻红。
 */
class DividendServiceGzipDecompressionTest {

    private val service = DividendService()
    private val recordedAcceptEncoding = AtomicReference<String>(null)
    private val serverError = AtomicReference<Exception>()
    private var serverSocket: ServerSocket? = null

    @After
    fun tearDown() {
        // DividendService 自身不持缓存，无 resetHttpClientForTesting 可调
        // （client 已是可注入的 internal var，见 DividendServiceTest）；
        // 这里收尾假服务端口并把 client 复位到真实配置，防止将来加缓存时漏掉。
        runCatching { serverSocket?.close() }
        serverSocket = null
        service.client = DividendService.createDividendHttpClient()
    }

    @Test
    fun `真实client配置下_gzip压缩分红页面能被自动解压并解析出分红记录`() = runBlocking {
        val socket = ServerSocket(0)
        serverSocket = socket
        socket.soTimeout = 30_000
        val port = socket.localPort

        val body = sinaDividendHtml()
        val gzBytes = gzip(body.toByteArray(Charset.forName("GBK")))

        val serverDone = CountDownLatch(1)
        val serverThread = Thread {
            try {
                socket.use { s ->
                    s.accept().use { conn ->
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

        service.client = redirectToLocalClient(port)

        val result = service.fetchDividendData("600519", "sh")

        assertTrue("本地假服务不应报错：${serverError.get()}", serverError.get() == null)
        assertTrue(
            "gzip 响应必须被自动解压并解析成功（否则即 Accept-Encoding 手动头回归）；实际=$result",
            result is AppResult.Success
        )
        val records = (result as AppResult.Success).data
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals("2023-12-19", r.announceDate)
        assertEquals("308.76", r.dividendPerShare)
        assertEquals("2023-12-27", r.exDividendDate)
        assertEquals("2023", r.fiscalYear)
        // 表格入选条件「表文本含『分红』且含『派息』」命中即证明 GBK 解码还原了中文表头：
        // 若解压被关掉、字节按 GBK 解成乱码，一个表格都选不出来，records 必为空。

        // 请求头契约：绝不能带手动 "gzip, deflate"（OkHttp 自动补的是裸 "gzip"）
        val sent = recordedAcceptEncoding.get()
        assertTrue("服务端应收到 OkHttp 自动补的 Accept-Encoding，实际=$sent", sent != null && sent.contains("gzip"))
        assertFalse("绝不能手动带 deflate——那会关掉 OkHttp 的透明解压", sent.contains("deflate"))

        assertTrue("假服务线程应已收尾", serverDone.await(10, TimeUnit.SECONDS))
    }

    // ---- helpers ----

    /** 真实分红 client 配置 + 一条把新浪分红域名改写到本地假服务的拦截器 */
    private fun redirectToLocalClient(port: Int): OkHttpClient =
        DividendService.createDividendHttpClient().newBuilder()
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

    /**
     * 最小可解析的新浪分红页面 fixture（结构照 DividendServiceTest.sinaHtml）：
     * 表文本必须含「分红」且含「派息」才会入选；表头给足
     * 公告日期/派息/除权除息日 三列，数据行给一条完整记录。
     */
    private fun sinaDividendHtml(): String = """
        <html><body>
        <table>
          <caption>分红配送记录</caption>
          <tr><th>公告日期</th><th>送股(股)</th><th>转增(股)</th>
              <th>派息(税前)(元)</th><th>进度</th><th>除权除息日</th></tr>
          <tr><td>2023-12-19</td><td>0</td><td>0</td><td>308.76</td><td>实施</td><td>2023-12-27</td></tr>
        </table>
        </body></html>
    """.trimIndent()

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(data) } }.toByteArray()

    private fun buildGzipResponse(gz: ByteArray): ByteArray {
        // Content-Type 不带 charset：与真实新浪页面一致，逼出 DividendService 的 GBK 回退路径
        val head = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
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
}
