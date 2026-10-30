package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.source
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GitHubApiForwarderTest {
    private val token = "gh_synthetic_host_only_token"
    private val store = GhTestCredentials(token)
    private val nonces = GitNonceRegistry()
    private val scope = nonces.issue()
    private val diagnostics = mutableListOf<String>()
    private val requests = mutableListOf<Request>()
    private var reply: (Request) -> Response = { response(it, "{\"login\":\"octo\"}") }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        reply(chain.request())
    }.build()
    private fun core() = GitHubApiForwarder(store, nonces, client, diagnostics = diagnostics::add)
    private fun request(host: String = "api.github.com", auth: String? = "token ${scope.nonce}", target: String = "/user", extra: String = "", method: String = "GET", body: String = ""): String =
        "$method $target HTTP/1.1\r\nHost: $host\r\n" + (auth?.let { "Authorization: $it\r\n" } ?: "") + extra + "\r\n" + body
    private fun send(raw: String): String = ByteArrayOutputStream().also { core().serve(ByteArrayInputStream(raw.toByteArray()), it) }.toString("UTF-8")

    @Test fun `有效nonce替换认证头且保持响应状态头和内容`() {
        val raw = send(request(extra = "Accept: application/vnd.github+json\r\nX-GitHub-Api-Version: 2022-11-28\r\n"))
        assertThat(raw).contains("HTTP/1.1 200")
        assertThat(raw).contains("{\"login\":\"octo\"}")
        assertThat(requests.single().header("Authorization")).isEqualTo("token $token")
        assertThat(requests.single().header("X-GitHub-Api-Version")).isEqualTo("2022-11-28")
        assertThat(store.lastToken!!.all { it == 0.toByte() }).isTrue()
        assertThat(raw + diagnostics.joinToString()).doesNotContain(token)
        assertThat(raw + diagnostics.joinToString()).doesNotContain(scope.nonce)
    }

    @Test fun `缺失错误已吊销nonce不访问上游`() {
        listOf(null, "token ${"A".repeat(43)}", "Basic ${scope.nonce}").forEach {
            assertThat(send(request(auth = it))).contains("HTTP/1.1 401")
        }
        scope.close()
        assertThat(send(request())).contains("HTTP/1.1 401")
        assertThat(requests).isEmpty()
    }

    @Test fun `bearer与上传主机可用且下载白名单逐个剥离认证并接受跨主机跳转的无认证请求`() {
        assertThat(send(request(host = "uploads.github.com", auth = "Bearer ${scope.nonce}"))).contains("HTTP/1.1 200")
        assertThat(requests.last().header("Authorization")).isEqualTo("token $token")
        listOf("objects.githubusercontent.com", "codeload.github.com", "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com", "raw.githubusercontent.com").forEach { host ->
            assertThat(send(request(host))).contains("HTTP/1.1 200")
            assertThat(requests.last().url.host).isEqualTo(host)
            assertThat(requests.last().header("Authorization")).isNull()
            // gh 跟随跳转到下载主机时不带 Authorization，按匿名中继转发且不注入凭据。
            assertThat(send(request(host, auth = null))).contains("HTTP/1.1 200")
            assertThat(requests.last().header("Authorization")).isNull()
            assertThat(send(request(host, auth = "token ${"A".repeat(43)}"))).contains("HTTP/1.1 401")
        }
        assertThat(send(request("api.github.com", auth = null))).contains("HTTP/1.1 401")
        scope.close()
        assertThat(send(request("codeload.github.com"))).contains("HTTP/1.1 401")
    }

    @Test fun `未知主机端口绝对地址和重复认证字段被拒绝`() {
        listOf("github.com", "api.github.com.evil.test", "api.github.com:80", "127.0.0.1").forEach {
            assertThat(send(request(it))).contains("HTTP/1.1 403")
        }
        assertThat(send(request(target = "https://evil.test/user"))).contains("HTTP/1.1 400")
        assertThat(send(request(extra = "Authorization: token ${scope.nonce}\r\n"))).contains("HTTP/1.1 400")
        assertThat(send(request(extra = "Host: evil.test\r\n"))).contains("HTTP/1.1 400")
        assertThat(requests).isEmpty()
    }

    @Test fun `没有GitHub凭据返回JSON设置引导`() {
        store.available = false
        val raw = send(request())
        assertThat(raw).contains("HTTP/1.1 401")
        assertThat(raw).contains("Content-Type: application/json")
        assertThat(raw).contains("请在设置 › Git 凭据中登录 GitHub")
        assertThat(requests).isEmpty()
    }

    @Test fun `跳转不跟随并保留Location`() {
        reply = { response(it, "", 302).newBuilder().header("Location", "https://objects.githubusercontent.com/release").build() }
        val raw = send(request())
        assertThat(raw).contains("HTTP/1.1 302")
        assertThat(raw).contains("Location: https://objects.githubusercontent.com/release")
        assertThat(requests).hasSize(1)
    }

    @Test fun `205响应明确零长度且允许复用连接`() {
        reply = { response(it, "", 205) }
        val raw = send(request() + request(extra = "Connection: close\r\n"))
        assertThat(Regex("HTTP/1.1 205").findAll(raw).count()).isEqualTo(2)
        assertThat(Regex("Content-Length: 0\\r\\n").findAll(raw).count()).isEqualTo(2)
        assertThat(raw).doesNotContain("Transfer-Encoding")
    }

    @Test fun `chunked和定长请求体解码且keepAlive不会吞下一请求`() {
        val bodies = mutableListOf<String>()
        reply = { req ->
            bodies += Buffer().also { req.body?.writeTo(it) }.readUtf8()
            response(req, "ok")
        }
        val raw = send(request(method = "POST", extra = "Transfer-Encoding: chunked\r\n", body = "3\r\nabc\r\n2;x=1\r\nde\r\n0\r\nX-Trailer: end\r\n\r\n") +
            request(method = "PATCH", extra = "Content-Length: 4\r\nConnection: close\r\n", body = "fghi"))
        assertThat(bodies).containsExactly("abcde", "fghi").inOrder()
        assertThat(Regex("HTTP/1.1 200").findAll(raw).count()).isEqualTo(2)
    }

    @Test fun `逐跳字段与Connection列举字段不流向上游和客户端`() {
        reply = { response(it, "ok").newBuilder().header("Connection", "X-Upstream-Hop").header("X-Upstream-Hop", "private").header("ETag", "etag").build() }
        val raw = send(request(extra = "Connection: X-Hop\r\nX-Hop: remove\r\nProxy-Authorization: secret\r\nKeep-Alive: timeout=5\r\n"))
        assertThat(requests.single().header("X-Hop")).isNull()
        assertThat(requests.single().header("Proxy-Authorization")).isNull()
        assertThat(requests.single().header("Keep-Alive")).isNull()
        assertThat(raw).doesNotContain("X-Upstream-Hop")
        assertThat(raw).contains("ETag: etag")
    }

    @Test fun `冲突长度和畸形分块不污染下一请求`() {
        assertThat(send(request(method = "POST", extra = "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n", body = "0\r\n\r\n"))).contains("HTTP/1.1 400")
        assertThat(requests).isEmpty()
        reply = { req -> req.body!!.writeTo(Buffer()); response(req, "ok") }
        val raw = send(request(method = "POST", extra = "Transfer-Encoding: chunked\r\n", body = "xyz\r\n") + request())
        assertThat(raw).doesNotContain("HTTP/1.1 200")
        assertThat(requests).hasSize(1)
    }

    @Test fun `响应分块输出且在上游完整读取之前写出大响应`() {
        var produced = 0
        var observedEarly = false
        var bodyBytes = 0L
        val size = 8 * 1024 * 1024 + 19
        reply = { req -> response(req, "").newBuilder().body(object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            private val stream = object : InputStream() {
                override fun read(): Int = if (produced++ < size) 'x'.code else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (produced >= size) return -1
                    val n = minOf(len, size - produced, 4096)
                    java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte()); produced += n; return n
                }
            }.source().buffer()
            override fun source(): BufferedSource = stream
        }).build() }
        val output = object : OutputStream() {
            override fun write(b: Int) { bodyBytes++; if (produced in 1 until size) observedEarly = true }
            override fun write(b: ByteArray, off: Int, len: Int) { bodyBytes += len; if (produced in 1 until size) observedEarly = true }
        }
        core().serve(ByteArrayInputStream(request().toByteArray()), output)
        assertThat(produced).isEqualTo(size)
        assertThat(observedEarly).isTrue()
        assertThat(bodyBytes).isGreaterThan(size.toLong())
    }

    @Test fun `大上传在读取完整请求体之前交给上游`() {
        val size = 8 * 1024 * 1024 + 7
        var produced = 0
        var delivered = 0L
        var observedEarly = false
        val body = object : InputStream() {
            override fun read(): Int = if (produced < size) { produced++; 'u'.code } else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (produced >= size) return -1
                val n = minOf(len, size - produced, 4096)
                java.util.Arrays.fill(b, off, off + n, 'u'.code.toByte()); produced += n; return n
            }
        }
        reply = { req ->
            val sink = object : Sink {
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
                override fun flush() = Unit
                override fun write(source: Buffer, byteCount: Long) {
                    if (produced < size) observedEarly = true
                    delivered += byteCount
                    source.skip(byteCount)
                }
            }.buffer()
            req.body!!.writeTo(sink); sink.flush()
            response(req, "uploaded")
        }
        val input = SequenceInputStream(ByteArrayInputStream(request(method = "POST", extra = "Content-Length: $size\r\n").toByteArray()), body)
        val output = ByteArrayOutputStream()
        core().serve(input, output)
        assertThat(observedEarly).isTrue()
        assertThat(delivered).isEqualTo(size.toLong())
        assertThat(output.toString("UTF-8")).contains("uploaded")
    }

    @Test fun `上游chunked响应转换后字节完整且有结束块`() {
        val payload = "0123456789abcdef".repeat(65537)
        reply = { response(it, payload).newBuilder().header("Transfer-Encoding", "chunked").build() }
        val raw = send(request())
        val stream = ByteArrayInputStream(raw.substringAfter("\r\n\r\n").toByteArray())
        val body = ByteArrayOutputStream()
        while (true) {
            val line = StringBuilder()
            while (true) { val byte = stream.read(); check(byte >= 0); if (byte == 13) { check(stream.read() == 10); break }; line.append(byte.toChar()) }
            val size = line.toString().toInt(16)
            if (size == 0) { check(stream.read() == 13 && stream.read() == 10); break }
            repeat(size) { body.write(stream.read().also { check(it >= 0) }) }
            check(stream.read() == 13 && stream.read() == 10)
        }
        assertThat(body.toString("UTF-8")).isEqualTo(payload)
        assertThat(stream.read()).isEqualTo(-1)
    }

    @Test fun `keepAlive连接不能转交另一命令`() {
        nonces.issue().use { other ->
            val raw = send(request() + request(auth = "token ${other.nonce}"))
            assertThat(raw).contains("HTTP/1.1 401")
            assertThat(requests).hasSize(1)
        }
    }

    @Test fun `上游异常原文不进入诊断和错误响应`() {
        reply = { throw IOException("$token ${scope.nonce} Authorization: token") }
        val raw = send(request())
        assertThat(raw).contains("HTTP/1.1 502")
        assertThat(raw + diagnostics.joinToString()).doesNotContain(token)
        assertThat(raw + diagnostics.joinToString()).doesNotContain(scope.nonce)
    }

    @Test fun `命令吊销关闭连接并中断上游响应`() {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        reply = { req -> response(req, "").newBuilder().body(object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            private val stream = object : InputStream() {
                override fun read(): Int { reading.countDown(); check(closed.await(3, TimeUnit.SECONDS)); throw IOException("closed") }
            }.source().buffer()
            override fun source() = stream
        }).build() }
        val worker = thread { core().serve(ByteArrayInputStream(request().toByteArray()), ByteArrayOutputStream(), AutoCloseable { closed.countDown() }) }
        assertThat(reading.await(3, TimeUnit.SECONDS)).isTrue()
        scope.close()
        assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue()
        worker.join(3000)
        assertThat(worker.isAlive).isFalse()
        assertThat(nonces.activeCount).isEqualTo(0)
    }

    private fun response(request: Request, text: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Result").body(text.toResponseBody()).build()
}

internal class GhTestCredentials(private val value: String = "synthetic_token") : GitCredentialStore {
    var available = true
    var lastToken: ByteArray? = null
    override val credentials = MutableStateFlow(listOf(GitCredential("github.com", "octo")))
    override fun token(host: String): ByteArray? = if (available && host == "github.com") value.toByteArray().also { lastToken = it } else null
    override fun save(host: String, username: String, token: ByteArray) = Unit
    override fun delete(host: String) = Unit
}
