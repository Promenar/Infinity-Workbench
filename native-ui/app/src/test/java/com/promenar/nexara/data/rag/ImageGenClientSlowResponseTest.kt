package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 生图上游常需十几秒到一分钟才开始回包；默认客户端不能沿用 OkHttp 10 秒读超时把请求切断。
 * 用本机套接字服务真实走 OkHttp 引擎，响应延迟超过 10 秒。
 */
class ImageGenClientSlowResponseTest {
    @Test
    fun `上游超过十秒才回包时仍能取到图片`() = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { input.read() }
                    Thread.sleep(11_500)
                    val body = """{"data":[{"url":"https://images.example.test/earth.png"}]}"""
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                        )
                        flush()
                    }
                }
            }
            val client = ImageGenClient(
                baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                apiKey = "test-key",
                model = "sensenova-u1.5-lite",
            )

            val result = client.generate("earth from geostationary orbit")

            assertThat(result.images.single().url).isEqualTo("https://images.example.test/earth.png")
            worker.join(1_000)
        }
    }
}
