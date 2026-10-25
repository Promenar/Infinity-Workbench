package com.promenar.nexara.data.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchProxyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import com.promenar.nexara.data.security.SshCredential
import com.promenar.nexara.data.tailnet.DisabledTailnetRouting
import com.promenar.nexara.data.tailnet.InstalledTailnetRouting
import com.promenar.nexara.data.tailnet.TailnetRouting
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.sshTools
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

/** 假代理在建立 socket 前失败；只验证生产连接与工具错误边界，不连接网络。 */
class SshFailureClassificationTest {
    private lateinit var previousRouting: TailnetRouting
    private val context = object : SkillExecutionContext {
        override val sessionId = "ssh-errors"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    private val calls = mapOf(
        "ssh_exec" to """{"host":"synthetic","command":"true"}""",
        "sftp_list" to """{"host":"synthetic","path":"/"}""",
        "sftp_read" to """{"host":"synthetic","path":"/note.txt"}""",
        "sftp_write" to """{"host":"synthetic","path":"/note.txt","content":"正文"}""",
        "ssh_session_open" to """{"host":"synthetic"}""",
    )

    @Before fun rememberRouting() { previousRouting = InstalledTailnetRouting.routing }
    @After fun restoreRouting() { InstalledTailnetRouting.routing = previousRouting }

    @Test fun `SSH和SFTP连接拒绝提示检查目标端口的SSH服务`() = runBlocking {
        calls.keys.forEach { tool ->
            val result = failure(tool, JSchProxyException(PRIVATE_DETAIL, ConnectException(PRIVATE_DETAIL)))
            assertCode(result, "SSH_CONNECTION_REFUSED")
            assertGuidance(result, "目标端口", "SSH 服务")
        }
    }

    @Test fun `SOCKS5无cause的拒绝回复提示检查SSH服务`() = runBlocking {
        val result = failure("ssh_exec", JSchProxyException("ProxySOCKS5: server returns 5"))
        assertCode(result, "SSH_CONNECTION_REFUSED")
        assertGuidance(result, "目标端口", "SSH 服务")
    }

    @Test fun `SOCKS5无cause的网络不可达回复提示检查路由`() = runBlocking {
        val result = failure("sftp_list", JSchProxyException("ProxySOCKS5: server returns 3"))
        assertCode(result, "SSH_HOST_UNREACHABLE")
        assertGuidance(result, "网络", "路由")
    }

    @Test fun `SOCKS5无cause的主机不可达回复提示检查路由`() = runBlocking {
        val result = failure("sftp_read", JSchProxyException("ProxySOCKS5: server returns 4"))
        assertCode(result, "SSH_HOST_UNREACHABLE")
        assertGuidance(result, "网络", "路由")
    }

    @Test fun `SOCKS5未知回复及相似异常文本保留兼容兜底`() = runBlocking {
        listOf(
            JSchProxyException("ProxySOCKS5: server returns 1"),
            JSchProxyException("ProxySOCKS5: server returns 9"),
            JSchProxyException("ProxySOCKS5: server returns 5 $PRIVATE_DETAIL"),
            JSchException("ProxySOCKS5: server returns 5"),
        ).forEach { error ->
            assertCode(failure("ssh_exec", error), "SSH_AUTH_OR_CONNECTION_FAILED")
        }
    }

    @Test fun `DNS解析失败与路由不可达有各自排查方向`() = runBlocking {
        val dns = failure("ssh_exec", JSchException(PRIVATE_DETAIL, UnknownHostException(PRIVATE_DETAIL)))
        assertCode(dns, "SSH_DNS_FAILED")
        assertGuidance(dns, "域名", "DNS")
        val unreachable = failure("sftp_list", JSchException(PRIVATE_DETAIL, NoRouteToHostException(PRIVATE_DETAIL)))
        assertCode(unreachable, "SSH_HOST_UNREACHABLE")
        assertGuidance(unreachable, "网络", "路由")
    }

    @Test fun `连接超时不提示修改认证材料`() = runBlocking {
        val result = failure("sftp_read", JSchException(PRIVATE_DETAIL, SocketTimeoutException(PRIVATE_DETAIL)))
        assertCode(result, "SSH_TIMEOUT")
        assertGuidance(result, "超时", "网络")
        assertThat(result.getValue("guidance").jsonPrimitive.content).doesNotContain("重新配置凭据")
    }

    @Test fun `JSch主机密钥变化要求核对指纹而未确认要求显式确认`() = runBlocking {
        val changed = failure("ssh_exec", hostKeyFailure(JSchChangedHostKeyException::class.java))
        assertCode(changed, "SSH_HOST_KEY_CHANGED")
        assertGuidance(changed, "核对", "指纹", "不要重试")
        val untrusted = failure("ssh_session_open", hostKeyFailure(JSchUnknownHostKeyException::class.java))
        assertCode(untrusted, "SSH_HOST_KEY_UNTRUSTED")
        assertGuidance(untrusted, "确认", "指纹", "不要重试")
    }

    @Test fun `缺少凭据要求用户补充配置并保留错误码`() = runBlocking {
        val result = failure("ssh_exec", JSchException("缺少凭据时禁止连接"), missingCredential = true)
        assertCode(result, "SSH_CREDENTIAL_MISSING")
        assertGuidance(result, "缺少", "设置", "凭据", "不要重试")
    }

    @Test fun `口令认证的空凭据在连接前提示补充凭据`() = runBlocking {
        val result = failure("ssh_exec", JSchException("空口令时禁止连接"), emptyCredential = true)
        assertCode(result, "SSH_CREDENTIAL_MISSING")
        assertGuidance(result, "缺少", "设置", "凭据")
    }

    @Test fun `公钥认证的空凭据不误报私钥格式错误`() = runBlocking {
        val result = failure("sftp_list", JSchException("空私钥时禁止连接"), emptyCredential = true, authType = SshAuthType.KEY)
        assertCode(result, "SSH_CREDENTIAL_MISSING")
        assertGuidance(result, "缺少", "设置", "凭据")
    }

    @Test fun `已配置凭据被拒绝时不归为主机离线`() = runBlocking {
        val result = failure("sftp_write", JSchException("Auth fail for methods 'password'"))
        assertCode(result, "SSH_AUTH_REJECTED")
        assertGuidance(result, "认证被拒绝", "凭据", "不要重试")
    }

    @Test fun `未知连接错误保留兼容兜底且不从任意文本猜测分类`() = runBlocking {
        listOf(
            JSchException("unknown failure $PRIVATE_DETAIL"),
            JSchException("unrelated Auth fail for methods 'password' $PRIVATE_DETAIL"),
            IllegalStateException("Auth fail for methods 'password' $PRIVATE_DETAIL"),
        ).forEach { error ->
            assertCode(failure("ssh_exec", error), "SSH_AUTH_OR_CONNECTION_FAILED")
        }
    }

    private suspend fun failure(
        tool: String,
        error: Throwable,
        missingCredential: Boolean = false,
        emptyCredential: Boolean = false,
        authType: SshAuthType = SshAuthType.PASSWORD,
    ): JsonObject {
        InstalledTailnetRouting.routing = object : TailnetRouting by DisabledTailnetRouting {
            override fun jschProxy(host: String): Proxy = object : Proxy {
                override fun connect(socketFactory: SocketFactory?, host: String?, port: Int, timeout: Int) { throw error }
                override fun getInputStream(): InputStream = throw AssertionError("失败代理不能读取网络")
                override fun getOutputStream(): OutputStream = throw AssertionError("失败代理不能写入网络")
                override fun getSocket(): Socket = throw AssertionError("失败代理不能建立 socket")
                override fun close() = Unit
            }
        }
        val host = SshHost(alias = "synthetic", hostname = "synthetic.invalid", username = "synthetic-user", authType = authType)
        val store = object : SshHostStore {
            override fun observeHosts() = flowOf(listOf(host))
            override suspend fun find(alias: String) = host.takeIf { it.alias == alias }
            override suspend fun save(host: SshHost, credential: SshCredential?) = Unit
            override suspend fun delete(alias: String) = Unit
            override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost) = false
            override fun credential(host: SshHost) = when {
                missingCredential -> null
                emptyCredential -> SshCredential()
                else -> SshCredential(password = "synthetic-password".toByteArray())
            }
        }
        return SshRuntime(store).use { runtime ->
            val result = sshTools(runtime).single { it.name == tool }.execute(Json.parseToJsonElement(calls.getValue(tool)).jsonObject, context)
            assertWithMessage(result.content).that(result.status).isEqualTo("error")
            listOf(PRIVATE_DETAIL, "synthetic-password", "synthetic-user", "synthetic.invalid").forEach { privateValue ->
                assertThat(result.content).doesNotContain(privateValue)
            }
            Json.parseToJsonElement(result.content).jsonObject
        }
    }

    // 当前 JSch 的异常类型公开，但构造器是包可见；仅在测试中构造库的实际类型。
    private fun hostKeyFailure(type: Class<out JSchException>): JSchException =
        type.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(PRIVATE_DETAIL)

    private fun assertCode(result: JsonObject, code: String) = assertThat(result.getValue("error_code").jsonPrimitive.content).isEqualTo(code)
    private fun assertGuidance(result: JsonObject, vararg actions: String) {
        actions.forEach { assertThat(result.getValue("guidance").jsonPrimitive.content).contains(it) }
    }

    private companion object {
        const val PRIVATE_DETAIL = "/private/synthetic-user/.ssh/private_key synthetic-secret"
    }
}
