package com.promenar.infinity.data.git.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** 展示给用户的设备码；设备密钥仅用于模块内部的令牌轮询。 */
class GitHubDeviceCode internal constructor(
    internal val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMillis: Long,
    val intervalSeconds: Int,
) {
    override fun toString(): String =
        "GitHubDeviceCode(expiresAtMillis=$expiresAtMillis, intervalSeconds=$intervalSeconds)"
}

enum class GitHubDeviceFailure { NOT_CONFIGURED, EXPIRED, DENIED, DEVICE_FLOW_DISABLED, NETWORK, INVALID_RESPONSE, SAVE_FAILED }

class GitHubDeviceFlowException(val failure: GitHubDeviceFailure) : Exception("GitHub 设备码登录失败：$failure")

interface GitHubDeviceLogin {
    /** client_id 已配置时为 true；未配置时界面仅保留手动令牌入口。 */
    val configured: Boolean

    /**
     * 在 IO 调度器申请设备码并调用 [onCode]，按服务端间隔轮询、确认登录名后保存 github.com 凭据。
     * 返回登录名前清零令牌数组；失败抛 [GitHubDeviceFlowException]，取消时向上传播 [CancellationException]。
     */
    suspend fun login(onCode: (GitHubDeviceCode) -> Unit): String
}

class OAuthDeviceLogin(
    private val clientId: String,
    httpClient: OkHttpClient,
    private val credentials: GitCredentialStore,
    private val webBase: HttpUrl = "https://github.com/".toHttpUrl(),
    private val apiBase: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GitHubDeviceLogin {
    // OAuth 表单含设备密钥，禁止自动重定向把表单交给其它地址。
    private val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    override val configured: Boolean get() = clientId.isNotBlank()

    override suspend fun login(onCode: (GitHubDeviceCode) -> Unit): String {
        if (!configured) fail(GitHubDeviceFailure.NOT_CONFIGURED)
        return withContext(ioDispatcher) {
            val code = requestDeviceCode()
            currentCoroutineContext().ensureActive()
            onCode(code)
            val token = pollForToken(code)
            try {
                val username = requestUsername(token)
                currentCoroutineContext().ensureActive()
                try {
                    credentials.saveGitHubLogin(username, token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail(GitHubDeviceFailure.SAVE_FAILED)
                }
                username
            } finally {
                token.fill(0)
            }
        }
    }

    private suspend fun requestDeviceCode(): GitHubDeviceCode {
        val response = requestJson(
            Request.Builder().url(webBase.resolve("login/device/code")!!)
                .header("Accept", "application/json")
                .post(FormBody.Builder().add("client_id", clientId).add("scope", "repo").build()).build(),
            oauth = true,
        )
        if ("error" in response) failOAuth(response.requiredString("error"))
        val deviceCode = response.requiredString("device_code")
        val userCode = response.requiredString("user_code")
        val verificationUri = response.requiredString("verification_uri")
        val verificationUrl = verificationUri.toHttpUrlOrNull() ?: fail(GitHubDeviceFailure.INVALID_RESPONSE)
        if (!verificationUrl.isHttps || verificationUrl.username.isNotEmpty() || verificationUrl.password.isNotEmpty()) {
            fail(GitHubDeviceFailure.INVALID_RESPONSE)
        }
        val lifetimeMillis = response.positiveInt("expires_in").toLong() * 1_000L
        val now = clock()
        if (now > Long.MAX_VALUE - lifetimeMillis) fail(GitHubDeviceFailure.INVALID_RESPONSE)
        return GitHubDeviceCode(deviceCode, userCode, verificationUri, now + lifetimeMillis, response.positiveInt("interval"))
    }

    private suspend fun pollForToken(code: GitHubDeviceCode): ByteArray {
        var intervalSeconds = code.intervalSeconds.toLong()
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = clock()
            if (now >= code.expiresAtMillis) fail(GitHubDeviceFailure.EXPIRED)
            // 有效期比下一次轮询等待短时，到期即结束，不发送已经过期的设备码。
            delay(minOf(intervalSeconds * 1_000L, code.expiresAtMillis - now))
            if (clock() >= code.expiresAtMillis) fail(GitHubDeviceFailure.EXPIRED)
            val response = requestJson(
                Request.Builder().url(webBase.resolve("login/oauth/access_token")!!)
                    .header("Accept", "application/json")
                    .post(FormBody.Builder().add("client_id", clientId).add("device_code", code.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()).build(),
                oauth = true,
            )
            if ("error" in response) {
                when (val error = response.requiredString("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> intervalSeconds = if ("interval" in response) {
                        maxOf(intervalSeconds, response.positiveInt("interval").toLong())
                    } else {
                        intervalSeconds + 5L
                    }
                    else -> failOAuth(error)
                }
            } else {
                return response.accessToken()
            }
        }
    }

    private fun JsonObject.accessToken(): ByteArray {
        if (!requiredString("token_type").equals("bearer", ignoreCase = true)) fail(GitHubDeviceFailure.INVALID_RESPONSE)
        requiredString("scope", allowEmpty = true)
        val token = requiredString("access_token").toByteArray(Charsets.UTF_8)
        if (!SecretGitCredentialStore.validToken(token)) {
            token.fill(0)
            fail(GitHubDeviceFailure.INVALID_RESPONSE)
        }
        return token
    }

    private suspend fun requestUsername(token: ByteArray): String {
        val response = requestJson(
            Request.Builder().url(apiBase.resolve("user")!!)
                .header("Authorization", "Bearer ${token.toString(Charsets.US_ASCII)}")
                .header("Accept", "application/vnd.github+json").build(),
        )
        val username = response.requiredString("login")
        if (username.length > 256 || ':' in username || username.any { it.isWhitespace() || it.isISOControl() }) {
            fail(GitHubDeviceFailure.INVALID_RESPONSE)
        }
        return username
    }

    private suspend fun requestJson(request: Request, oauth: Boolean = false): JsonObject {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            // 同步读取始终在 ioDispatcher；取消时主动关闭调用，包含正在读取的响应体。
            val result: Result<JsonObject> = try {
                Result.success(call.execute().use { response ->
                    if (response.code in 500..599) fail(GitHubDeviceFailure.NETWORK)
                    if (!response.isSuccessful && (!oauth || response.code !in 400..499)) {
                        fail(GitHubDeviceFailure.INVALID_RESPONSE)
                    }
                    val body = response.body?.string() ?: fail(GitHubDeviceFailure.INVALID_RESPONSE)
                    val json = Json.parseToJsonElement(body) as? JsonObject ?: fail(GitHubDeviceFailure.INVALID_RESPONSE)
                    if (!response.isSuccessful) {
                        if ("error" in json) failOAuth(json.requiredString("error"))
                        fail(GitHubDeviceFailure.INVALID_RESPONSE)
                    }
                    json
                })
            } catch (_: IOException) {
                Result.failure(GitHubDeviceFlowException(GitHubDeviceFailure.NETWORK))
            } catch (_: SerializationException) {
                // JSON 异常可能附带响应片段，不能作为 cause 保留。
                Result.failure(GitHubDeviceFlowException(GitHubDeviceFailure.INVALID_RESPONSE))
            } catch (failure: GitHubDeviceFlowException) {
                Result.failure(failure)
            }
            continuation.resumeWith(result)
        }
    }

    private fun JsonObject.requiredString(name: String, allowEmpty: Boolean = false): String {
        val value = this[name] as? JsonPrimitive ?: fail(GitHubDeviceFailure.INVALID_RESPONSE)
        if (!value.isString || (!allowEmpty && value.content.isBlank())) fail(GitHubDeviceFailure.INVALID_RESPONSE)
        return value.content
    }

    private fun JsonObject.positiveInt(name: String): Int {
        val value = this[name] as? JsonPrimitive ?: fail(GitHubDeviceFailure.INVALID_RESPONSE)
        if (value.isString) fail(GitHubDeviceFailure.INVALID_RESPONSE)
        return value.intOrNull?.takeIf { it > 0 } ?: fail(GitHubDeviceFailure.INVALID_RESPONSE)
    }

    private fun failOAuth(error: String): Nothing = fail(when (error) {
        "expired_token" -> GitHubDeviceFailure.EXPIRED
        "access_denied" -> GitHubDeviceFailure.DENIED
        "device_flow_disabled" -> GitHubDeviceFailure.DEVICE_FLOW_DISABLED
        else -> GitHubDeviceFailure.INVALID_RESPONSE
    })

    private fun fail(failure: GitHubDeviceFailure): Nothing = throw GitHubDeviceFlowException(failure)
}
