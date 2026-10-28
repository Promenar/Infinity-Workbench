package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.remote.sshRuntimeGitExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** 内置 git 经 SSH 桥使用的流式 exec：仅回环内嵌服务与合成口令。 */
class SshRuntimeGitStreamingTest {
    private val root = Files.createTempDirectory("infinity-ssh-git-test")
    private val commands = CopyOnWriteArrayList<String>()
    private val environments = CopyOnWriteArrayList<Map<String, String>>()
    private val sleeperClosed = CountDownLatch(1)
    private lateinit var server: SshServer
    private lateinit var runtime: SshRuntime
    private val hostStore = MemorySshHosts()

    @Before fun setup() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("server.key")).apply { algorithm = "EC" }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ -> password == hostStore.password }
            commandFactory = org.apache.sshd.server.command.CommandFactory { _, command -> GitLikeCommand(command) }
            start()
        }
        hostStore.host = SshHost(alias = "forge", hostname = "127.0.0.1", port = server.port, username = "git", authType = SshAuthType.PASSWORD)
        runtime = SshRuntime(hostStore)
    }

    @After fun cleanup() {
        if (::runtime.isInitialized) runtime.close()
        if (::server.isInitialized) server.stop(true)
        root.toFile().deleteRecursively()
    }

    private suspend fun trust() {
        val key = runtime.probe("forge")
        hostStore.host = hostStore.host.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint)
    }

    @Test fun `未固定主机密钥时拒绝执行`() = runBlocking {
        val failure = runCatching {
            runtime.execStreaming("git", "forge", "git-upload-pack 'r.git'", ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ByteArrayOutputStream())
        }.exceptionOrNull()
        assertThat((failure as SshFailure).code).isEqualTo("HOST_KEY_UNTRUSTED")
        assertThat(commands).isEmpty()
    }

    @Test fun `二进制stdin与stdout原样往返并传递环境变量退出码与脱敏stderr`() = runBlocking {
        trust()
        val payload = Random(11).nextBytes(300_000)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val started = AtomicInteger()
        val exit = runtime.execStreaming(
            "git", "forge", "git-upload-pack 'owner/r.git'", ByteArrayInputStream(payload), stdout, stderr,
            mapOf("GIT_PROTOCOL" to "version=2"),
        ) { started.incrementAndGet() }
        assertThat(exit).isEqualTo(5)
        assertThat(started.get()).isEqualTo(1)
        assertThat(stdout.toByteArray()).isEqualTo(payload)
        val errors = stderr.toString(Charsets.UTF_8)
        assertThat(errors).contains("remote warning")
        assertThat(errors).doesNotContain(hostStore.password)
        assertThat(commands).containsExactly("git-upload-pack 'owner/r.git'")
        assertThat(environments.single()["GIT_PROTOCOL"]).isEqualTo("version=2")
    }

    @Test fun `生产执行器按主机快照经流式exec执行`() = runBlocking {
        trust()
        val stdout = ByteArrayOutputStream()
        val exit = sshRuntimeGitExecutor(runtime).execute(
            hostStore.host, "git-receive-pack 'r.git'", emptyMap(), ByteArrayInputStream("push".toByteArray()), stdout, ByteArrayOutputStream(),
        ) {}
        assertThat(exit).isEqualTo(5)
        assertThat(stdout.toString(Charsets.UTF_8)).isEqualTo("push")

        // 匹配后主机配置变化（例如端口被改）时拒绝执行。
        val stale = hostStore.host.copy(port = hostStore.host.port + 1)
        val failure = runCatching {
            sshRuntimeGitExecutor(runtime).execute(stale, "git-receive-pack 'r.git'", emptyMap(), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ByteArrayOutputStream()) {}
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SshFailure::class.java)
        assertThat(commands).hasSize(1)
    }

    @Test fun `无效命令不建立通道`() = runBlocking {
        trust()
        listOf("", " ", "a\u0000b").forEach { command ->
            val failure = runCatching {
                runtime.execStreaming("git", "forge", command, ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ByteArrayOutputStream())
            }.exceptionOrNull()
            assertThat((failure as SshFailure).code).isEqualTo("INVALID_COMMAND")
        }
        assertThat(commands).isEmpty()
    }

    @Test fun `取消协程及时断开远端通道`() = runBlocking {
        trust()
        val started = CompletableDeferred<Unit>()
        val job = async {
            runtime.execStreaming("git", "forge", "sleep", ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ByteArrayOutputStream()) {
                started.complete(Unit)
            }
        }
        withTimeout(10_000) { started.await() }
        val begin = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(5_000)
        assertThat(sleeperClosed.await(10, TimeUnit.SECONDS)).isTrue()
    }

    private inner class GitLikeCommand(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var callback: ExitCallback
        private var worker: Thread? = null
        override fun setInputStream(value: InputStream) { input = value }
        override fun setOutputStream(value: OutputStream) { output = value }
        override fun setErrorStream(value: OutputStream) { error = value }
        override fun setExitCallback(value: ExitCallback) { callback = value }
        override fun start(channel: ChannelSession, env: Environment) {
            commands += command
            environments += env.env.toMap()
            worker = Thread {
                try {
                    if (command == "sleep") {
                        Thread.sleep(30_000)
                    } else {
                        input.copyTo(output)
                        error.write("remote warning ${hostStore.password}\n".toByteArray())
                        output.flush(); error.flush()
                        callback.onExit(5)
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
        }
        override fun destroy(channel: ChannelSession) {
            if (command == "sleep") sleeperClosed.countDown()
            worker?.interrupt()
        }
    }
}
