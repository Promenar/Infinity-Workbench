package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

class GitSshBridgeTest {
    private val github = SshHost(alias = "github", hostname = "github.com", username = "git", trustedKeyType = "ssh-ed25519", trustedFingerprint = "SHA256:pinned")
    private val nonces = GitNonceRegistry()
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val calls = CopyOnWriteArrayList<Call>()
    private var hosts = listOf(github)
    private var executor = GitSshExecutor { host, command, environment, stdin, stdout, stderr, onStarted ->
        calls += Call(host, command, environment)
        onStarted()
        stderr.write("remote: hello\n".toByteArray())
        stdin.copyTo(stdout)
        7
    }
    private val bridge = GitSshBridge(nonces, { hosts }, { host, command, environment, stdin, stdout, stderr, onStarted ->
        executor.execute(host, command, environment, stdin, stdout, stderr, onStarted)
    }, diagnostics::add)
    private val port = bridge.start()

    private data class Call(val host: SshHost, val command: String, val environment: Map<String, String>)

    @After fun tearDown() = bridge.close()

    @Test fun `命令白名单与sq_quote路径解析`() {
        assertThat(GitSshCommand.parse("git-upload-pack 'owner/repo.git'")).isEqualTo(GitSshCommand("git-upload-pack", "owner/repo.git"))
        assertThat(GitSshCommand.parse("git-receive-pack '/srv/git/r.git'")).isEqualTo(GitSshCommand("git-receive-pack", "/srv/git/r.git"))
        assertThat(GitSshCommand.parse("git-upload-archive '~/r.git'")).isEqualTo(GitSshCommand("git-upload-archive", "~/r.git"))
        val tricky = GitSshCommand.parse("git-upload-pack 'it'\\''s; \$(id) `x`.git'")!!
        assertThat(tricky.path).isEqualTo("it's; \$(id) `x`.git")
        assertThat(tricky.render()).isEqualTo("git-upload-pack 'it'\\''s; \$(id) `x`.git'")
        listOf(
            "git-upload-pack owner/repo.git",
            "git-upload-pack 'a' 'b'",
            "git-upload-pack 'a';id",
            "git-upload-pack 'a'\$(id)",
            "git-upload-pack 'a' && id",
            "git-upload-pack  'a'",
            "git-upload-pack\t'a'",
            "git-upload-pack ''",
            "git-upload-pack '-oProxyCommand=id'",
            "git-upload-pack '--upload-pack=id'",
            "git-upload-pack 'a\nb'",
            "git-upload-pack 'unterminated",
            "git-upload-pack \"a\"",
            "git-upload-packx 'a'",
            "sh -c 'id'",
            "git upload-pack 'a'",
            "git-upload-pack 'a'\\'",
            "git-upload-pack '${"a".repeat(4097)}'",
            "",
        ).forEach { assertThat(GitSshCommand.parse(it)).isNull() }
    }

    @Test fun `主机匹配按主机名端口用户名并要求固定主机密钥`() {
        fun match(list: List<SshHost>, host: String, port: Int?, user: String?) = GitSshHostMatcher.match(list, host, port, user)
        assertThat(match(hosts, "GitHub.com", null, "git")).isEqualTo(GitSshHostMatch.Found(github))
        assertThat(match(hosts, "github.com", 22, null)).isEqualTo(GitSshHostMatch.Found(github))
        assertThat(match(hosts, "github", null, null)).isEqualTo(GitSshHostMatch.Found(github))

        val missing = match(hosts, "github.com", 2222, "git") as GitSshHostMatch.Rejected
        assertThat(missing.code).isEqualTo("SSH_HOST_NOT_CONFIGURED")
        assertThat(missing.message).contains("github.com")
        assertThat(missing.message).contains("用户 git")
        assertThat((match(hosts, "github.com", null, "octocat") as GitSshHostMatch.Rejected).code).isEqualTo("SSH_HOST_NOT_CONFIGURED")
        assertThat((match(hosts, "gitlab.com", null, "git") as GitSshHostMatch.Rejected).code).isEqualTo("SSH_HOST_NOT_CONFIGURED")

        val unpinned = github.copy(alias = "gh2", hostname = "git.example.com", trustedFingerprint = null, trustedKeyType = null)
        assertThat((match(listOf(unpinned), "git.example.com", null, "git") as GitSshHostMatch.Rejected).code).isEqualTo("SSH_HOST_KEY_NOT_PINNED")

        val second = github.copy(alias = "github-work", username = "work")
        val both = listOf(github, second)
        assertThat((match(both, "github.com", null, null) as GitSshHostMatch.Rejected).code).isEqualTo("SSH_HOST_AMBIGUOUS")
        assertThat(match(both, "github.com", null, "work")).isEqualTo(GitSshHostMatch.Found(second))
    }

    @Test fun `桥只监听回环并双向转发标准流与退出码`() {
        assertThat(bridge.boundAddress).isEqualTo(LOOPBACK)
        val scope = nonces.issue()
        val payload = Random(3).nextBytes(200_000)
        Client(port).use { client ->
            val reply = client.hello(request(scope.nonce, command = "git-upload-pack 'owner/repo.git'", protocol = 2))
            assertThat(reply["ok"]!!.jsonPrimitive.boolean).isTrue()
            // 另起线程写 stdin，避免回显时双方都阻塞在已满的套接字缓冲区上。
            val writer = thread {
                val frame = GitSshBridge.MAX_FRAME_BYTES
                for (offset in payload.indices step frame) client.stdin(payload.copyOfRange(offset, minOf(payload.size, offset + frame)))
                client.eof()
            }
            val result = client.drain()
            writer.join(10_000)
            assertThat(result.stdout).isEqualTo(payload)
            assertThat(String(result.stderr)).isEqualTo("remote: hello\n")
            assertThat(result.exit).isEqualTo(7)
            assertThat(result.maxFrame).isAtMost(GitSshBridge.MAX_FRAME_BYTES)
        }
        val call = calls.single()
        assertThat(call.host).isEqualTo(github)
        assertThat(call.command).isEqualTo("git-upload-pack 'owner/repo.git'")
        assertThat(call.environment).containsExactly("GIT_PROTOCOL", "version=2")
        scope.close()
    }

    @Test fun `协议版本0不设置环境变量`() {
        val scope = nonces.issue()
        Client(port).use { client ->
            client.hello(request(scope.nonce, command = "git-receive-pack 'o/r.git'", protocol = 0))
            client.eof()
            assertThat(client.drain().exit).isEqualTo(7)
        }
        assertThat(calls.single().environment).isEmpty()
        scope.close()
    }

    @Test fun `拒绝无效nonce非白名单命令与未配置或未固定主机`() {
        val scope = nonces.issue()
        val revoked = nonces.issue().also { it.close() }
        hosts = listOf(github, github.copy(alias = "loose", hostname = "git.example.com", trustedFingerprint = null, trustedKeyType = null))
        val cases = listOf(
            request(null) to "NONCE_REJECTED",
            request("A".repeat(GitNonceRegistry.NONCE_LENGTH)) to "NONCE_REJECTED",
            request(revoked.nonce) to "NONCE_REJECTED",
            request(scope.nonce, command = "sh -c 'cat ~/.ssh/id_ed25519'") to "COMMAND_REJECTED",
            request(scope.nonce, command = "git-upload-pack 'r.git'; id") to "COMMAND_REJECTED",
            request(scope.nonce, host = "gitlab.com") to "SSH_HOST_NOT_CONFIGURED",
            request(scope.nonce, host = "git.example.com") to "SSH_HOST_KEY_NOT_PINNED",
            request(scope.nonce, host = "github.com\nx") to "INVALID_REQUEST",
            request(scope.nonce, port = 70000) to "INVALID_REQUEST",
            request(scope.nonce).replace("\"port\":null", "\"port\":\"22\"") to "INVALID_REQUEST",
            "not json" to "INVALID_REQUEST",
        )
        cases.forEach { (line, code) ->
            Client(port).use { client ->
                val reply = client.hello(line)
                assertThat(reply["ok"]!!.jsonPrimitive.boolean).isFalse()
                assertThat(reply["code"]!!.jsonPrimitive.content).isEqualTo(code)
                assertThat(reply["message"]!!.jsonPrimitive.content).isNotEmpty()
                assertThat(client.drain().exit).isNull()
            }
        }
        assertThat(calls).isEmpty()
        scope.close()
    }

    @Test fun `命令结束吊销nonce时取消进行中的远端命令并断开`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        executor = GitSshExecutor { _, _, _, _, stdout, _, onStarted ->
            onStarted()
            stdout.write("partial".toByteArray())
            started.countDown()
            try { awaitCancellation() } finally { cancelled.countDown() }
        }
        val scope = nonces.issue()
        Client(port).use { client ->
            assertThat(client.hello(request(scope.nonce))["ok"]!!.jsonPrimitive.boolean).isTrue()
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
            scope.close()
            assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue()
            val result = client.drain()
            assertThat(String(result.stdout)).isEqualTo("partial")
            assertThat(result.exit).isNull()
        }
        // 吊销后同一 nonce 不能再建立连接。
        Client(port).use { client -> assertThat(client.hello(request(scope.nonce))["code"]!!.jsonPrimitive.content).isEqualTo("NONCE_REJECTED") }
    }

    @Test fun `超长或未知类型的stdin帧中止命令而不是当作正常结束`() {
        listOf(GitSshBridge.STDIN to GitSshBridge.MAX_FRAME_BYTES + 1, 9 to 1).forEach { (type, size) ->
            val cancelled = CountDownLatch(1)
            val finished = CountDownLatch(1)
            executor = GitSshExecutor { _, _, _, stdin, stdout, _, onStarted ->
                onStarted()
                try {
                    stdin.copyTo(stdout)
                    finished.countDown()
                    awaitCancellation()
                } finally { cancelled.countDown() }
            }
            val scope = nonces.issue()
            Client(port).use { client ->
                assertThat(client.hello(request(scope.nonce))["ok"]!!.jsonPrimitive.boolean).isTrue()
                client.raw(type, size)
                assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue()
                assertThat(client.drain().exit).isNull()
            }
            scope.close()
        }
    }

    @Test fun `远端执行失败映射为错误码`() {
        val scope = nonces.issue()
        executor = GitSshExecutor { _, _, _, _, _, _, _ -> throw SshFailure("SSH_AUTH_FAILED") }
        Client(port).use { client ->
            val reply = client.hello(request(scope.nonce))
            assertThat(reply["code"]!!.jsonPrimitive.content).isEqualTo("SSH_AUTH_FAILED")
            assertThat(reply["message"]!!.jsonPrimitive.content).contains("github")
        }
        executor = GitSshExecutor { _, _, _, _, _, _, onStarted -> onStarted(); throw IOException("channel broken") }
        Client(port).use { client ->
            assertThat(client.hello(request(scope.nonce))["ok"]!!.jsonPrimitive.boolean).isTrue()
            val result = client.drain()
            assertThat(String(result.stderr)).contains("SSH_IO_FAILED")
            assertThat(String(result.stderr)).doesNotContain("channel broken")
            assertThat(result.exit).isEqualTo(255)
        }
        assertThat(diagnostics).containsAtLeast("git-ssh-bridge: SSH_AUTH_FAILED", "git-ssh-bridge: SSH_IO_FAILED")
        scope.close()
    }

    private fun request(
        nonce: String?,
        host: String = "github.com",
        port: Int? = null,
        user: String? = "git",
        command: String = "git-upload-pack 'owner/repo.git'",
        protocol: Int = 2,
    ): String = buildJsonObject {
        nonce?.let { put("nonce", it) }
        put("host", host)
        put("port", port?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("user", user?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("command", command)
        put("protocol", protocol)
    }.toString()

    private class Result(val stdout: ByteArray, val stderr: ByteArray, val exit: Int?, val maxFrame: Int)

    private class Client(port: Int) : AutoCloseable {
        private val socket = Socket(LOOPBACK, port).apply { soTimeout = 15_000 }
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        fun hello(line: String): JsonObject {
            output.write((line + "\n").toByteArray())
            output.flush()
            return Json.parseToJsonElement(input.readAsciiLine(65_536)!!).jsonObject
        }

        fun stdin(bytes: ByteArray) = frame(GitSshBridge.STDIN, bytes)
        fun eof() = frame(GitSshBridge.STDIN_EOF, ByteArray(0))

        /** 只写帧头与 [size] 字节载荷，用于构造违规帧。 */
        fun raw(type: Int, size: Int) = runCatching { frame(type, ByteArray(size)) }

        private fun frame(type: Int, bytes: ByteArray) {
            output.writeByte(type)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        /** 读帧直到退出码帧或连接关闭。 */
        fun drain(): Result {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exit: Int? = null
            var maxFrame = 0
            try {
                while (exit == null) {
                    val type = input.read()
                    if (type < 0) break
                    val size = input.readInt()
                    maxFrame = maxOf(maxFrame, size)
                    val payload = ByteArray(size).also(input::readFully)
                    when (type) {
                        GitSshBridge.STDOUT -> stdout.write(payload)
                        GitSshBridge.STDERR -> stderr.write(payload)
                        GitSshBridge.EXIT -> exit = DataInputStream(payload.inputStream()).readInt()
                        else -> error("未知帧类型 $type")
                    }
                }
            } catch (_: EOFException) {
            } catch (_: IOException) {
            }
            return Result(stdout.toByteArray(), stderr.toByteArray(), exit, maxFrame)
        }

        override fun close() = socket.close()
    }
}
