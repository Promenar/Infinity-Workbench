package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubDeviceFlowTest {
    @Test fun `申请设备码后按间隔轮询并以登录名覆盖GitHub凭据`() = runTest {
        val fixture = fixture()
        fixture.store.save("github.com", "old-user", "old-token".toByteArray())
        fixture.store.save("other.test", "other-user", "other-token".toByteArray())
        fixture.http.deviceCode()
        fixture.http.reply(TOKEN_PATH, """{"error":"authorization_pending"}""")
        fixture.http.reply(TOKEN_PATH, """{"error":"authorization_pending"}""")
        fixture.http.authorized()
        fixture.http.user()
        var displayed: GitHubDeviceCode? = null

        assertThat(fixture.login.login { displayed = it }).isEqualTo("octocat")

        assertThat(displayed!!.userCode).isEqualTo("WDJB-MJHT")
        assertThat(displayed!!.verificationUri).isEqualTo(VERIFICATION_URI)
        assertThat(displayed!!.expiresAtMillis).isEqualTo(900_000L)
        assertThat(displayed!!.intervalSeconds).isEqualTo(5)
        assertThat(fixture.http.pollTimes).containsExactly(5_000L, 10_000L, 15_000L).inOrder()
        val deviceRequest = fixture.http.requests.first()
        assertThat(deviceRequest.url.host).isEqualTo("web.test")
        assertThat(deviceRequest.method).isEqualTo("POST")
        assertThat(deviceRequest.header("Accept")).isEqualTo("application/json")
        assertThat(deviceRequest.form()).containsExactly("client_id", CLIENT_ID, "scope", "repo")
        fixture.http.requests.filter { it.url.encodedPath == TOKEN_PATH }.forEach { request ->
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.header("Accept")).isEqualTo("application/json")
            assertThat(request.form()).containsExactly(
                "client_id", CLIENT_ID, "device_code", DEVICE_CODE,
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
            )
        }
        val userRequest = fixture.http.requests.last()
        assertThat(userRequest.url.host).isEqualTo("api.test")
        assertThat(userRequest.method).isEqualTo("GET")
        assertThat(userRequest.header("Accept")).isEqualTo("application/vnd.github+json")
        assertThat(userRequest.header("Authorization")).isEqualTo("Bearer $TOKEN")
        assertThat(fixture.store.credentials.value).containsExactly(
            GitCredential("github.com", "octocat"), GitCredential("other.test", "other-user"),
        )
        assertThat(fixture.store.token("github.com")).isEqualTo(TOKEN.toByteArray())
        assertThat(fixture.store.savedTokenReference).isEqualTo(ByteArray(TOKEN.length))
        assertThat(fixture.http.remainingReplies).isEqualTo(0)
    }

    @Test fun `slow_down使用服务端新间隔并在缺省时增加五秒`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        fixture.http.reply(TOKEN_PATH, """{"error":"slow_down","interval":12}""")
        fixture.http.reply(TOKEN_PATH, """{"error":"authorization_pending"}""")
        fixture.http.reply(TOKEN_PATH, """{"error":"slow_down"}""")
        fixture.http.authorized()
        fixture.http.user()

        fixture.login.login { }

        assertThat(fixture.http.pollTimes).containsExactly(5_000L, 17_000L, 29_000L, 46_000L).inOrder()
    }

    @Test fun `服务端终止错误映射为对应失败且不保存`() = runTest {
        mapOf(
            "expired_token" to GitHubDeviceFailure.EXPIRED,
            "access_denied" to GitHubDeviceFailure.DENIED,
            "device_flow_disabled" to GitHubDeviceFailure.DEVICE_FLOW_DISABLED,
            "unsupported_grant_type" to GitHubDeviceFailure.INVALID_RESPONSE,
            "incorrect_client_credentials" to GitHubDeviceFailure.INVALID_RESPONSE,
            "incorrect_device_code" to GitHubDeviceFailure.INVALID_RESPONSE,
            "unknown_error" to GitHubDeviceFailure.INVALID_RESPONSE,
        ).forEach { (error, expected) ->
            val fixture = fixture()
            fixture.http.deviceCode()
            fixture.http.reply(TOKEN_PATH, """{"error":"$error","error_description":"$DEVICE_CODE $TOKEN"}""")
            expectFailure(expected) { fixture.login.login { } }
            assertThat(fixture.store.saveCalls).isEqualTo(0)
        }
    }

    @Test fun `申请阶段设备流程未开启也映射为明确失败`() = runTest {
        val fixture = fixture()
        fixture.http.reply(DEVICE_PATH, """{"error":"device_flow_disabled"}""", 400)
        expectFailure(GitHubDeviceFailure.DEVICE_FLOW_DISABLED) { fixture.login.login { error("不应展示设备码") } }
        assertThat(fixture.store.saveCalls).isEqualTo(0)
    }

    @Test fun `剩余有效期短于下一次间隔时到期立即停止且不再轮询`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode(expiresIn = 7)
        fixture.http.reply(TOKEN_PATH, """{"error":"authorization_pending"}""")

        expectFailure(GitHubDeviceFailure.EXPIRED) { fixture.login.login { } }

        assertThat(fixture.http.pollTimes).containsExactly(5_000L)
        assertThat(testScheduler.currentTime).isEqualTo(7_000L)
        assertThat(fixture.store.saveCalls).isEqualTo(0)
    }

    @Test fun `展示设备码期间本地时钟越过有效期时不发轮询`() = runTest {
        var clockOffset = 0L
        val fixture = fixture(clock = { testScheduler.currentTime + clockOffset })
        fixture.http.deviceCode()
        expectFailure(GitHubDeviceFailure.EXPIRED) {
            fixture.login.login { clockOffset = it.expiresAtMillis + 1 }
        }
        assertThat(fixture.http.pollTimes).isEmpty()
        assertThat(fixture.store.saveCalls).isEqualTo(0)
    }

    @Test fun `网络异常和各阶段5xx均为网络失败且不保存`() = runTest {
        listOf(DEVICE_PATH, TOKEN_PATH, USER_PATH).forEach { path ->
            listOf(false, true).forEach { ioFailure ->
                val fixture = fixture()
                if (path != DEVICE_PATH) fixture.http.deviceCode()
                if (path == USER_PATH) fixture.http.authorized()
                if (ioFailure) fixture.http.fail(path) else fixture.http.reply(path, "非 JSON", 503)
                expectFailure(GitHubDeviceFailure.NETWORK) { fixture.login.login { } }
                assertThat(fixture.store.saveCalls).isEqualTo(0)
            }
        }
    }

    @Test fun `用户接口401不保存凭据且视为无效响应`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        fixture.http.authorized()
        fixture.http.reply(USER_PATH, """{"error":"access_denied","message":"$TOKEN"}""", 401)
        expectFailure(GitHubDeviceFailure.INVALID_RESPONSE) { fixture.login.login { } }
        assertThat(fixture.store.saveCalls).isEqualTo(0)
    }

    @Test fun `clientId空白时不配置入口且不发请求`() = runTest {
        listOf("", " \t\n").forEach { clientId ->
            val fixture = fixture(clientId)
            assertThat(fixture.login.configured).isFalse()
            expectFailure(GitHubDeviceFailure.NOT_CONFIGURED) { fixture.login.login { error("不应展示设备码") } }
            assertThat(fixture.http.requests).isEmpty()
            assertThat(fixture.store.saveCalls).isEqualTo(0)
        }
        assertThat(fixture().login.configured).isTrue()
    }

    @Test fun `取消等待中的登录后不再发出轮询且向上传播取消`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        fixture.http.reply(TOKEN_PATH, """{"error":"authorization_pending"}""")
        var cancelled = false
        val job = launch {
            try { fixture.login.login { } } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
        }
        runCurrent()
        assertThat(fixture.http.pollTimes).isEmpty()
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(fixture.http.pollTimes).hasSize(1)

        job.cancelAndJoin()
        advanceTimeBy(60_000)
        runCurrent()

        assertThat(cancelled).isTrue()
        assertThat(fixture.http.pollTimes).hasSize(1)
        assertThat(fixture.store.saveCalls).isEqualTo(0)
    }

    @Test fun `网络请求进行时取消会取消OkHttp调用且不保存`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        lateinit var job: Job
        var callCancelled = false
        fixture.http.reply(TOKEN_PATH, AUTHORIZED) { chain ->
            job.cancel()
            callCancelled = chain.call().isCanceled()
        }
        job = launch { fixture.login.login { } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(callCancelled).isTrue()
        assertThat(fixture.http.requests).hasSize(2)
        assertThat(fixture.store.saveCalls).isEqualTo(0)
    }

    @Test fun `设备码和失败诊断字符串不输出设备密钥或令牌`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        fixture.http.reply(TOKEN_PATH, """{"access_token":"$TOKEN",broken:$DEVICE_CODE}""")
        var displayed: GitHubDeviceCode? = null
        val failure = expectFailure(GitHubDeviceFailure.INVALID_RESPONSE) { fixture.login.login { displayed = it } }
        assertThat(displayed!!.toString()).doesNotContain(DEVICE_CODE)
        assertThat(displayed!!.toString()).doesNotContain(TOKEN)
        assertThat(failure.cause).isNull()
    }

    @Test fun `设备响应拒绝缺字段错误类型非法有效期和非法验证地址`() = runTest {
        val valid = deviceResponse()
        listOf(
            "{", "[]", "{}",
            valid.replace("\"device_code\":\"$DEVICE_CODE\",", ""),
            valid.replace("\"WDJB-MJHT\"", "123"),
            valid.replace("\"expires_in\":900", "\"expires_in\":0"),
            valid.replace("\"expires_in\":900", "\"expires_in\":\"900\""),
            valid.replace("\"interval\":5", "\"interval\":-1"),
            valid.replace(VERIFICATION_URI, "http://github.com/login/device"),
        ).forEach { body ->
            val fixture = fixture()
            fixture.http.reply(DEVICE_PATH, body)
            expectFailure(GitHubDeviceFailure.INVALID_RESPONSE) { fixture.login.login { error("不应展示无效设备码") } }
            assertThat(fixture.http.requests).hasSize(1)
            assertThat(fixture.store.saveCalls).isEqualTo(0)
        }
    }

    @Test fun `令牌响应拒绝缺字段错误类型非法令牌和非法退避间隔`() = runTest {
        listOf(
            "{}", "[]", "{",
            """{"access_token":123,"token_type":"bearer","scope":"repo"}""",
            AUTHORIZED.replace(TOKEN, ""),
            AUTHORIZED.replace(TOKEN, "bad\\ntoken"),
            AUTHORIZED.replace("bearer", "basic"),
            AUTHORIZED.replace(",\"scope\":\"repo\"", ""),
            """{"error":"slow_down","interval":0}""",
            """{"error":"slow_down","interval":"10"}""",
            """{"error":null}""",
        ).forEach { body ->
            val fixture = fixture()
            fixture.http.deviceCode()
            fixture.http.reply(TOKEN_PATH, body)
            expectFailure(GitHubDeviceFailure.INVALID_RESPONSE) { fixture.login.login { } }
            assertThat(fixture.http.requests).hasSize(2)
            assertThat(fixture.store.saveCalls).isEqualTo(0)
        }
    }

    @Test fun `用户响应的登录名必须符合凭据存储校验`() = runTest {
        listOf(
            "{}", "[]", "{", """{"login":123}""", """{"login":null}""",
            """{"login":""}""", """{"login":"white space"}""", """{"login":" octocat"}""",
            """{"login":"has:colon"}""", """{"login":"has\u0001control"}""",
            """{"login":"${"a".repeat(257)}"}""",
        ).forEach { body ->
            val fixture = fixture()
            fixture.http.deviceCode()
            fixture.http.authorized()
            fixture.http.reply(USER_PATH, body)
            expectFailure(GitHubDeviceFailure.INVALID_RESPONSE) { fixture.login.login { } }
            assertThat(fixture.store.saveCalls).isEqualTo(0)
        }
    }

    @Test fun `存储异常映射为保存失败且清零传入令牌并丢弃原始异常`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        fixture.http.authorized()
        fixture.http.user()
        fixture.store.saveFailure = IllegalStateException("$TOKEN $DEVICE_CODE")

        val failure = expectFailure(GitHubDeviceFailure.SAVE_FAILED) { fixture.login.login { } }

        assertThat(failure.cause).isNull()
        assertThat(fixture.store.savedTokenReference).isEqualTo(ByteArray(TOKEN.length))
        assertThat(fixture.store.credentials.value).isEmpty()
    }

    @Test fun `保存时的取消异常向上传播且清零令牌`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        fixture.http.authorized()
        fixture.http.user()
        val cancellation = CancellationException("模拟取消")
        fixture.store.saveFailure = cancellation
        try {
            fixture.login.login { }
            error("应向上传播取消")
        } catch (failure: CancellationException) {
            // 协程跨 withContext 恢复堆栈时会复制异常，只比较消息。
            assertThat(failure).hasMessageThat().isEqualTo(cancellation.message)
        }
        assertThat(fixture.store.savedTokenReference).isEqualTo(ByteArray(TOKEN.length))
    }

    @Test fun `OAuth接口重定向被拒绝且不会向目标转发设备密钥`() = runTest {
        val fixture = fixture()
        fixture.http.deviceCode()
        fixture.http.reply(TOKEN_PATH, "{}", 307, mapOf("Location" to "https://other.test/token"))
        expectFailure(GitHubDeviceFailure.INVALID_RESPONSE) { fixture.login.login { } }
        assertThat(fixture.http.requests).hasSize(2)
        assertThat(fixture.store.saveCalls).isEqualTo(0)
    }

    private fun TestScope.fixture(clientId: String = CLIENT_ID, clock: () -> Long = { testScheduler.currentTime }): Fixture {
        val http = FakeHttp { testScheduler.currentTime }
        val store = MemoryCredentials()
        return Fixture(
            OAuthDeviceLogin(clientId, http.client, store, "https://web.test/".toHttpUrl(),
                "https://api.test/".toHttpUrl(), clock, StandardTestDispatcher(testScheduler)),
            http, store,
        )
    }

    private suspend fun expectFailure(expected: GitHubDeviceFailure, block: suspend () -> Unit): GitHubDeviceFlowException {
        try {
            block()
        } catch (failure: GitHubDeviceFlowException) {
            assertThat(failure.failure).isEqualTo(expected)
            assertThat(failure.toString()).doesNotContain(DEVICE_CODE)
            assertThat(failure.toString()).doesNotContain(TOKEN)
            assertThat(failure.stackTraceToString()).doesNotContain(DEVICE_CODE)
            assertThat(failure.stackTraceToString()).doesNotContain(TOKEN)
            return failure
        }
        error("应抛出 GitHub 设备登录异常")
    }

    private class Fixture(val login: OAuthDeviceLogin, val http: FakeHttp, val store: MemoryCredentials)

    private class MemoryCredentials : GitCredentialStore {
        override val credentials = MutableStateFlow<List<GitCredential>>(emptyList())
        private val tokens = mutableMapOf<String, ByteArray>()
        var saveCalls = 0
        var saveFailure: Exception? = null
        var savedTokenReference: ByteArray? = null
        override fun save(host: String, username: String, token: ByteArray) {
            saveCalls++
            savedTokenReference = token
            saveFailure?.let { throw it }
            tokens[host] = token.copyOf()
            credentials.value = credentials.value.filterNot { it.host == host } + GitCredential(host, username)
        }
        override fun delete(host: String) {
            tokens.remove(host)?.fill(0)
            credentials.value = credentials.value.filterNot { it.host == host }
        }
        override fun token(host: String): ByteArray? = tokens[host]?.copyOf()
    }

    private class FakeHttp(private val clock: () -> Long) {
        val requests = mutableListOf<Request>()
        val pollTimes = mutableListOf<Long>()
        private val replies = ArrayDeque<(Interceptor.Chain) -> Response>()
        val remainingReplies: Int get() = replies.size
        // 所有请求都由此拦截器截断，未安排的请求直接失败，绝不访问真实网络。
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            if (chain.request().url.encodedPath == TOKEN_PATH) pollTimes += clock()
            check(replies.isNotEmpty()) { "出现未安排的请求" }
            replies.removeFirst()(chain)
        }.build()

        fun reply(path: String, body: String, status: Int = 200, headers: Map<String, String> = emptyMap(),
                  beforeReturn: (Interceptor.Chain) -> Unit = {}) {
            replies.addLast { chain ->
                assertThat(chain.request().url.encodedPath).isEqualTo(path)
                beforeReturn(chain)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("模拟响应").apply { headers.forEach { (key, value) -> header(key, value) } }
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        fun fail(path: String) {
            replies.addLast { chain ->
                assertThat(chain.request().url.encodedPath).isEqualTo(path)
                throw IOException("模拟网络失败：$DEVICE_CODE $TOKEN")
            }
        }
        fun deviceCode(expiresIn: Int = 900) = reply(DEVICE_PATH, deviceResponse(expiresIn))
        fun authorized() = reply(TOKEN_PATH, AUTHORIZED)
        fun user() = reply(USER_PATH, """{"login":"octocat"}""")
    }

    private fun Request.form(): Map<String, String> {
        val form = body as FormBody
        return (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    companion object {
        private const val CLIENT_ID = "synthetic-client"
        private const val DEVICE_CODE = "synthetic-device-secret"
        private const val TOKEN = "synthetic-access-token"
        private const val VERIFICATION_URI = "https://github.com/login/device"
        private const val DEVICE_PATH = "/login/device/code"
        private const val TOKEN_PATH = "/login/oauth/access_token"
        private const val USER_PATH = "/user"
        private const val AUTHORIZED = """{"access_token":"$TOKEN","token_type":"bearer","scope":"repo"}"""
        private fun deviceResponse(expiresIn: Int = 900): String =
            """{"device_code":"$DEVICE_CODE","user_code":"WDJB-MJHT","verification_uri":"$VERIFICATION_URI","expires_in":$expiresIn,"interval":5}"""
    }
}
