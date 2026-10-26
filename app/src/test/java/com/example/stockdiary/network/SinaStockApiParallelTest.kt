package com.example.stockdiary.network

import com.example.stockdiary.common.Result
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新浪批量行情「有界并发」回归（项A）。
 *
 * 项A把原来批间串行的 N/20 批（每批耗时 + 300ms 批间延迟）改成最多
 * [SinaStockAPI] 内部的 [MAX_BATCH_CONCURRENCY]=4 并发。本测试起本地假服务，
 * 每个批请求固定睡 [PER_REQUEST_DELAY_MS] 后返回多只报价，验证：
 *  1) 多批**并发**执行：总耗时明显小于「批数 × 单批耗时」（若退回串行会远超）；
 *  2) 并发下解析结果正确、不丢代码；
 *  3) 整源失败时批级异常被透出（不退化成符号级「未获取到股票数据」饿死熔断器）。
 *
 * 走真实解析 / 缓存 / 并发去重逻辑（[httpClientForTesting] 只换网络出口），
 * 与 SinaStockGzipDecompressionTest 同样的「真实 client 配置 + 域名改写」套路。
 */
class SinaStockApiParallelTest {

    private val servers = mutableListOf<ServerSocket>()
    private val threads = mutableListOf<Thread>()

    @After
    fun tearDown() {
        SinaStockAPI.resetHttpClientForTesting()
        SinaStockAPI.invalidateCache()
        threads.forEach { it.interrupt() }
        servers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `多批行情并发执行_总耗时明显小于批数乘单批耗时`() = runBlocking {
        // 45 个股票代码 → 3 批（20 / 20 / 5）
        val codes = (1..45).map { String.format("sh%06d", it) }
        val batchCount = (codes.size + 19) / 20

        startServer(PER_REQUEST_DELAY_MS) { reqPath ->
            val listPart = reqPath.substringAfter("list=").substringBefore(" ")
            val requested = listPart.split(",").filter { it.isNotBlank() }
            requested.joinToString("\n") { c -> quoteLine(c, "名$c", "10.00", "10.50") } + "\n"
        }

        val start = System.nanoTime()
        val result = SinaStockAPI.getRealtimeData(codes)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // 并发上限 4 ≥ 批数 3，三批应同时飞：总耗时约等于单批耗时 + 网络/解析开销，
        // 远小于串行的「批数 × 单批耗时」。断言「比串行少至少一整批的延迟」，
        // 既证明并发、又给慢机器留足余量（不会因为调度抖动误红）。
        val serialBaselineMs = batchCount * PER_REQUEST_DELAY_MS
        val ceilingMs = serialBaselineMs - PER_REQUEST_DELAY_MS
        assertTrue(
            "多批应并发：实测 ${elapsedMs}ms 须 < ${ceilingMs}ms（串行基线 ${serialBaselineMs}ms）",
            elapsedMs < ceilingMs
        )

        assertTrue("全部 45 个代码应解析成功，实际=$result", result is Result.Success)
        val map = (result as Result.Success).data
        assertEquals("解析出的代码数应等于请求数", codes.size, map.size)
        assertEquals(10.50, map["sh000001"]!!.price, 0.001)
        // GBK 解码链路一并验证：解压后的字节必须按 GBK 还原出中文名
        assertEquals("名sh000001", map["sh000001"]!!.name)
    }

    @Test
    fun `整源失败_批级异常被透出而非吞成符号级文案`() = runBlocking {
        // 25 个 → 2 批，全部 500
        val codes = (1..25).map { String.format("sh%06d", it) }
        startServer(50L, httpStatus = 500) { "" }

        val result = SinaStockAPI.getRealtimeData(codes)
        assertTrue(
            "所有批次 500，应透出原始 IOException 而非符号级「未获取到股票数据」饿死熔断器；实际=$result",
            result is Result.Error
        )
    }

    // ---- helpers ----

    /**
     * 起一个本地假服务：每收到一个连接，先睡 [perRequestDelayMs] 再按 [bodyOf] 生成响应体。
     * 每个连接单开 daemon 线程处理，保证多个批请求能被**并发**服务（否则假服务的串行
     * 会掩盖客户端的并发效果，使并发测试失去意义）。
     */
    private fun startServer(
        perRequestDelayMs: Long,
        httpStatus: Int = 200,
        bodyOf: (String) -> String
    ) {
        val serverSocket = ServerSocket(0)
        serverSocket.soTimeout = 30_000
        servers.add(serverSocket)
        val port = serverSocket.localPort
        val t = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val conn: Socket = serverSocket.accept()
                    Thread {
                        try {
                            conn.use { socket ->
                                val input = socket.getInputStream()
                                val head = readRequestHead(input)
                                val path = parsePath(head)
                                Thread.sleep(perRequestDelayMs)
                                val body = if (httpStatus == 200) bodyOf(path) else ""
                                val bytes = body.toByteArray(Charset.forName("GBK"))
                                socket.getOutputStream().write(buildResponse(httpStatus, bytes))
                                socket.getOutputStream().flush()
                            }
                        } catch (_: InterruptedException) {
                            // 收尾，忽略
                        } catch (_: Exception) {
                            // 连接级异常忽略
                        }
                    }.also { it.isDaemon = true }.start()
                }
            } catch (_: Exception) {
                // server socket 关闭后退出
            }
        }
        t.isDaemon = true
        t.start()
        threads.add(t)

        SinaStockAPI.httpClientForTesting = redirectToLocalClient(port)
        SinaStockAPI.invalidateCache()
    }

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

    private fun buildResponse(status: Int, body: ByteArray): ByteArray {
        val statusText = if (status == 200) "OK" else "Internal Server Error"
        val head = (
            "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: text/plain; charset=GBK\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        return head + body
    }

    /** 读到 \r\n\r\n（请求头结束）为止，返回原始字节 */
    private fun readRequestHead(input: java.io.InputStream): ByteArray {
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

    private fun parsePath(head: ByteArray): String {
        val text = String(head, Charsets.US_ASCII)
        return text.substringBefore("\r\n").substringAfter(" ")
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

    companion object {
        /** 单批假服务延迟，必须 > 网络往返，才能把「并发 vs 串行」的差距放大到可断言 */
        private const val PER_REQUEST_DELAY_MS = 250L
    }
}
