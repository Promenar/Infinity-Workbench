package com.promenar.nexara.data.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.data.security.SshCredential
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.sshTools
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** 仅回环内嵌服务与临时合成认证材料，不读取用户主机或私钥。 */
class SshRuntimeIntegrationTest {
    private val root = Files.createTempDirectory("nexara-ssh-test")
    private val commands = AtomicInteger()
    private val authentications = AtomicInteger()
    private val createdSessions = AtomicInteger()
    private lateinit var server: SshServer
    private lateinit var runtime: SshRuntime
    private val hostStore = MemorySshHosts()

    @Before fun setup() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("server.key")).apply { algorithm = "EC" }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ ->
                (password == hostStore.password).also { if (it) authentications.incrementAndGet() }
            }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, _, _ -> true }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
            commandFactory = org.apache.sshd.server.command.CommandFactory { _, command -> TestCommand(command, false, commands) }
            shellFactory = org.apache.sshd.server.shell.ShellFactory { TestCommand("", true, commands) }
            addSessionListener(object : SessionListener {
                override fun sessionCreated(session: Session) { createdSessions.incrementAndGet() }
            })
            start()
        }
        hostStore.host = SshHost(alias = "termux", hostname = "127.0.0.1", port = server.port, username = "synthetic-user", authType = SshAuthType.PASSWORD)
        runtime = SshRuntime(hostStore)
    }
    @After fun cleanup() { if (::runtime.isInitialized) runtime.close(); if (::server.isInitialized) server.stop(true); root.toFile().deleteRecursively() }

    @Test fun `未信任不认证经显式确认后密码执行返回退出码与stderr并复用连接`() = runBlocking {
        val rejected = runCatching { runtime.exec("chat", "termux", "hello") }.exceptionOrNull()
        assertThat((rejected as SshFailure).code).isEqualTo("HOST_KEY_UNTRUSTED")
        trust()
        val result = runtime.exec("chat", "termux", "hello")
        assertThat(result.exitCode).isEqualTo(7)
        assertThat(result.stdout).contains("stdout-ok")
        assertThat(result.stderr).contains("stderr-ok")
        assertThat(result.stdout).doesNotContain("synthetic-password")
        runtime.exec("chat", "termux", "hello")
        assertThat(server.activeSessions.size).isEqualTo(1)
        runtime.closeOwner("chat")
        awaitServerSessionsClosed()
    }

    @Test fun `生成Ed25519公钥认证执行与错误脱敏`() = runBlocking {
        SshKeyGenerator.generate().use { key ->
            hostStore.host = hostStore.host.copy(authType = SshAuthType.KEY)
            hostStore.key = key.privateKey.copyOf()
            trust()
            assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        }
    }

    @Test fun `信任变更在通知延迟时也禁止旧连接承载新操作并按新固定值握手`() = runBlocking {
        withoutHostNotifications()
        trust()
        val trusted = hostStore.host
        runtime.exec("chat", "termux", "hello")
        assertThat(authentications.get()).isEqualTo(1)
        hostStore.host = trusted.copy(trustedFingerprint = "SHA256:" + "A".repeat(43))
        val operations: List<suspend () -> Any?> = listOf(
            { runtime.exec("chat", "termux", "hello") },
            { runtime.list("chat", "termux", "/") },
            { runtime.read("chat", "termux", "/note.txt") },
            { runtime.write("chat", "termux", "/note.txt", "禁止写入") },
            { runtime.openSession("chat", "termux") },
        )
        operations.forEach { operation ->
            val failure = runCatching { operation() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SshFailure::class.java)
            assertThat((failure as SshFailure).code).isEqualTo("HOST_KEY_CHANGED")
        }
        assertThat(Files.exists(root.resolve("note.txt"))).isFalse()
        assertThat(authentications.get()).isEqualTo(1)
        hostStore.host = trusted
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        assertThat(authentications.get()).isEqualTo(2)
    }

    @Test fun `连接身份变化在通知延迟时仍须重新认证`() = runBlocking {
        withoutHostNotifications()
        trust()
        runtime.exec("chat", "termux", "hello")
        assertThat(authentications.get()).isEqualTo(1)
        hostStore.host = hostStore.host.copy(username = "replacement-user")
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        assertThat(authentications.get()).isEqualTo(2)
        hostStore.host = hostStore.host.copy(id = "replacement-host")
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
        assertThat(authentications.get()).isEqualTo(3)
    }

    @Test fun `信任变更后的既有PTY即使尚未收到通知也不能发送新输入`() = runBlocking {
        withoutHostNotifications()
        trust()
        val opened = runtime.openSession("chat", "termux")
        hostStore.host = hostStore.host.copy(trustedFingerprint = "SHA256:" + "A".repeat(43))
        val failure = runCatching { runtime.sendSession("chat", opened.sessionId, "禁止执行\n") }.exceptionOrNull()
        assertThat((failure as SshFailure).code).isEqualTo("HOST_CONFIGURATION_CHANGED")
        assertThat(runtime.sessionHost("chat", opened.sessionId)).isNull()
    }

    @Test fun `信任变化通知回收既有PTY和端口转发`() = runBlocking {
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        hostStore.host = hostStore.host.copy(localForwardPort = localPort, forwardPort = server.port)
        trust()
        val opened = runtime.openSession("ui", "termux")
        runtime.startForward("termux")
        assertThat(runtime.hasPersistentResources).isTrue()
        hostStore.host = hostStore.host.copy(trustedFingerprint = "SHA256:" + "A".repeat(43))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (runtime.hasPersistentResources && System.nanoTime() < deadline) delay(10)
        assertThat(runtime.hasPersistentResources).isFalse()
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isNull()
        assertThat(runtime.forwardStates.value).isEmpty()
        assertThat(runCatching { Socket("127.0.0.1", localPort).close() }.isSuccess).isFalse()
    }

    @Test fun `输出采集有界超时关闭命令且用户取消及时关闭通道`() = runBlocking {
        trust()
        val result = runtime.exec("chat", "termux", "large", maxOutputChars = 100)
        assertThat(result.truncated).isTrue()
        assertThat(result.stdout.length + result.stderr.length).isAtMost(100)
        val timeout = runtime.exec("chat", "termux", "sleep", timeoutSeconds = 1)
        assertThat(timeout.timedOut).isTrue()
        val pending = async { runtime.exec("chat", "termux", "sleep") }
        delay(150)
        pending.cancelAndJoin()
        repeat(20) { if (commands.get() != 0) delay(25) }
        assertThat(commands.get()).isEqualTo(0)
        runtime.closeOwner("chat")
    }

    @Test fun `SFTP文本分段读写哈希冲突拒绝且二进制拒绝`() = runBlocking {
        trust()
        val hash = runtime.write("chat", "termux", "/note.txt", "中文 hello")
        val page = runtime.read("chat", "termux", "/note.txt")
        assertThat(page.content).isEqualTo("中文 hello")
        assertThat(page.eof).isTrue()
        assertThat(runtime.list("chat", "termux", "/").entries.map { it.name }).contains("note.txt")
        val conflict = runCatching { runtime.write("chat", "termux", "/note.txt", "bad", "0".repeat(64)) }.exceptionOrNull()
        assertThat((conflict as SshFailure).code).isEqualTo("SHA256_CONFLICT")
        assertThat(runtime.read("chat", "termux", "/note.txt").content).isEqualTo("中文 hello")
        runtime.write("chat", "termux", "/note.txt", "replaced", hash)
        assertThat(runtime.read("chat", "termux", "/note.txt").content).isEqualTo("replaced")
        Files.write(root.resolve("binary.bin"), byteArrayOf(1, 0, 2, -1))
        assertThat((runCatching { runtime.read("chat", "termux", "/binary.bin") }.exceptionOrNull() as SshFailure).code).isEqualTo("BINARY_FILE")
    }

    @Test fun `SFTP工具条件覆盖后可以读取且冲突不会改写文件`() = runBlocking {
        trust()
        val tools = sshTools(runtime).associateBy { it.name }
        val context = object : SkillExecutionContext {
            override val sessionId = "chat"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }
        suspend fun call(name: String, arguments: String) = tools.getValue(name).execute(Json.parseToJsonElement(arguments).jsonObject, context)
        val initial = call("sftp_write", """{"host":"termux","path":"/tool.txt","content":"设备初始😀"}""")
        assertWithMessage(initial.content).that(initial.status).isEqualTo("success")
        val hash = Json.parseToJsonElement(initial.content).jsonObject.getValue("sha256").jsonPrimitive.content
        val written = call("sftp_write", """{"host":"termux","path":"/tool.txt","content":"条件写入成功","expected_sha256":"$hash"}""")
        assertWithMessage(written.content).that(written.status).isEqualTo("success")
        val conflict = call("sftp_write", """{"host":"termux","path":"/tool.txt","content":"不得覆盖","expected_sha256":"$hash"}""")
        assertThat(conflict.status).isEqualTo("error")
        assertThat(conflict.content).contains("SHA256_CONFLICT")
        val read = call("sftp_read", """{"host":"termux","path":"/tool.txt"}""")
        assertWithMessage(read.content).that(read.status).isEqualTo("success")
        assertThat(Json.parseToJsonElement(read.content).jsonObject.getValue("content").jsonPrimitive.content).isEqualTo("条件写入成功")
        assertThat(Files.readAllBytes(root.resolve("tool.txt")).toString(Charsets.UTF_8)).isEqualTo("条件写入成功")
    }

    @Test fun `PTY往返调整窗口按owner隔离并在生成结束关闭`() = runBlocking {
        trust()
        val session = runtime.openSession("chat", "termux")
        assertThat(runtime.sessionHost("other", session.sessionId)).isNull()
        assertThat((runCatching { runtime.sendSession("other", session.sessionId, "whoami\n") }.exceptionOrNull() as SshFailure).code).isEqualTo("SESSION_NOT_FOUND")
        runtime.resizeSession("chat", session.sessionId, 120, 32)
        runtime.sendSession("chat", session.sessionId, "hello\n")
        val output = buildString {
            repeat(20) {
                append(runtime.readSession("chat", session.sessionId, 50).output)
                if (contains("echo:hello")) return@buildString
            }
        }
        assertThat(output).contains("echo:hello")
        runtime.closeOwner("chat")
        assertThat(runtime.sessionHost("chat", session.sessionId)).isNull()
    }

    @Test fun `目录名和分段文件读取均隐藏连接认证材料`() = runBlocking {
        trust()
        Files.write(root.resolve("synthetic-password"), "prefix synthetic-password suffix".toByteArray())
        assertThat(runtime.list("chat", "termux", "/").entries.joinToString { it.name }).doesNotContain("synthetic-password")
        val first = runtime.read("chat", "termux", "/synthetic-password", 7, 10)
        val second = runtime.read("chat", "termux", "/synthetic-password", first.nextOffset, 10)
        assertThat(first.content + second.content).doesNotContain("synthetic-password")
    }

    @Test fun `同身份凭据更新后已有连接仍按原认证材料脱敏`() = runBlocking {
        trust()
        runtime.exec("chat", "termux", "hello")
        hostStore.password = "new-synthetic-password"
        assertThat(runtime.exec("chat", "termux", "hello").stdout).doesNotContain("synthetic-password")
    }

    @Test fun `UTF8字节采集边界截断仍隐藏多字节口令前缀`() = runBlocking {
        hostStore.password = "合成口令-只用于测试"
        trust()
        val result = runtime.exec("chat", "termux", "unicode-secret", maxOutputChars = 1)
        assertThat(result.stdout).isEqualTo("█")
        assertThat(result.truncated).isTrue()
    }

    @Test fun `较早前台服务的销毁不释放新注册实例的资源`() = runBlocking {
        trust()
        val opened = runtime.openSession("ui", "termux")
        val old = runtime.registerForegroundService()
        val current = runtime.registerForegroundService()
        runtime.closePersistent(old)
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isEqualTo("termux")
        runtime.closePersistent(current)
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isNull()
    }

    @Test fun `更新配置后回收旧连接并且新连接只按最新配置创建`() = runBlocking {
        trust()
        runtime.exec("chat", "termux", "hello")
        hostStore.host = hostStore.host.copy(keepTerminal = true)
        awaitServerSessionsClosed()
        assertThat(runtime.exec("chat", "termux", "hello").exitCode).isEqualTo(7)
    }

    @Test fun `前台服务启动失败可重试服务实例关闭不受其它资源释放影响`() = runBlocking {
        trust()
        runtime.close()
        var starts = 0
        runtime = SshRuntime(hostStore, onPersistentActivity = { active ->
            if (active) { starts++; if (starts == 1) throw IllegalStateException("synthetic unavailable") }
        })
        assertThat(runCatching { runtime.openSession("ui", "termux") }.exceptionOrNull()).isInstanceOf(SshFailure::class.java)
        val opened = runtime.openSession("ui", "termux")
        assertThat(starts).isEqualTo(2)
        val token = runtime.registerForegroundService()
        runtime.exec("agent:test", "termux", "hello")
        runtime.closeOwner("agent:test")
        runtime.closePersistent(token)
        assertThat(runtime.sessionHost("ui", opened.sessionId)).isNull()
        assertThat(runtime.forwardStates.value).isEmpty()
    }

    @Test fun `覆盖保留私有权限符号链接拒绝且取消上传清理临时文件`() = runBlocking {
        trust()
        val path = root.resolve("private.txt")
        Files.write(path, "old".toByteArray())
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        runtime.write("chat", "termux", "/private.txt", "new")
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        Files.createSymbolicLink(root.resolve("link.txt"), path)
        assertThat((runCatching { runtime.write("chat", "termux", "/link.txt", "bad") }.exceptionOrNull() as SshFailure).code).isEqualTo("UNSUPPORTED_WRITE_TARGET")
        val pending = async { runtime.write("chat", "termux", "/cancel.txt", "x".repeat(1_048_576)) }
        delay(10)
        pending.cancelAndJoin()
        delay(100)
        assertThat(Files.list(root).use { paths -> paths.anyMatch { ".nexara-" in it.fileName.toString() } }).isFalse()
    }

    @Test fun `审批等待租约独立于PTY持续输出且闲置会话释放`() = runBlocking {
        trust()
        runtime.close()
        runtime = SshRuntime(hostStore, idleTimeoutMs = 250)
        val opened = runtime.openSession("chat", "termux")
        runtime.pauseOwner("chat")
        repeat(6) { runCatching { runtime.sendSession("chat", opened.sessionId, "ping\n") }; delay(60) }
        assertThat(runtime.sessionHost("chat", opened.sessionId)).isNull()
    }

    private suspend fun trust() {
        val key = runtime.probe("termux")
        hostStore.host = hostStore.host.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint)
        // 探测在客户端断开后立即返回，服务端会话由后台 IO 线程异步移除；等它移除，后续会话断言只反映被测操作。
        awaitServerSessionsClosed()
    }

    /** 等待服务端会话全部移除；期限只作失败兜底，条件满足即返回。 */
    private suspend fun awaitServerSessionsClosed() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (server.activeSessions.isNotEmpty() && System.nanoTime() < deadline) delay(10)
        assertWithMessage("服务端会话未在期限内关闭").that(server.activeSessions).isEmpty()
    }

    private fun withoutHostNotifications() {
        runtime.close()
        runtime = SshRuntime(object : SshHostStore by hostStore {
            override fun observeHosts() = kotlinx.coroutines.flow.emptyFlow<List<SshHost>>()
        })
    }

    @Test fun `旧格式SSH批准目标在执行入口被拒绝`() = runBlocking {
        trust()
        val sessionsBefore = createdSessions.get()
        val result = executeApproved("""["process","termux","[PTY]"]""")
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HOST_AUTHORIZATION_CHANGED")
        assertThat(createdSessions.get()).isEqualTo(sessionsBefore)
        assertThat(server.activeSessions).isEmpty()
    }

    @Test fun `身份保持不变的SSH批准目标可以打开PTY`() = runBlocking {
        trust()
        val result = executeApproved(requireNotNull(approvalTarget()))
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("session_id")
    }

    @Test fun `审批后同名主机身份变化不能执行到新用户名`() = runBlocking {
        trust()
        val target = requireNotNull(approvalTarget())
        hostStore.host = hostStore.host.copy(username = "different-user")
        val sessionsBefore = createdSessions.get()
        val result = executeApproved(target)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HOST_AUTHORIZATION_CHANGED")
        assertThat(createdSessions.get()).isEqualTo(sessionsBefore)
        assertThat(server.activeSessions).isEmpty()
    }

    @Test fun `身份复核与连接间配置变化拒绝远端PTY`() = runBlocking {
        trust()
        val target = requireNotNull(approvalTarget())
        val original = hostStore.host
        runtime.close()
        val reads = AtomicInteger()
        runtime = SshRuntime(object : SshHostStore by hostStore {
            override fun observeHosts() = kotlinx.coroutines.flow.emptyFlow<List<SshHost>>()
            override suspend fun find(alias: String): SshHost = if (reads.incrementAndGet() >= 3) {
                original.copy(username = "changed-during-connect")
            } else original
        })
        val result = executeApproved(target)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HOST_CONFIGURATION_CHANGED")
    }

    private suspend fun approvalTarget(): String? = com.promenar.nexara.data.generation.resolveSessionToolTarget(
        com.promenar.nexara.data.model.Session(id = "chat", agentId = "agent"),
        com.promenar.nexara.data.remote.protocol.ProtocolTool(
            function = com.promenar.nexara.data.remote.protocol.ProtocolToolFunction("ssh_session_open", "测试", "{}"),
            risk = com.promenar.nexara.domain.tool.ToolRisk.PROCESS,
        ),
        """{"host":"termux"}""", null, sshHostStore = hostStore,
    )

    private suspend fun executeApproved(target: String): com.promenar.nexara.data.model.ToolResult {
        val context = object : com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext {
            override val sessionId = "chat"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
            override val approvedExecutionTarget = target
            override val isApprovalBoundExecution = true
        }
        return com.promenar.nexara.ui.chat.manager.skills.sshTools(runtime).single { it.name == "ssh_session_open" }.execute(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"host":"termux"}""") as kotlinx.serialization.json.JsonObject,
            context,
        )
    }
}

internal class MemorySshHosts : SshHostStore {
    private val hosts = MutableStateFlow<List<SshHost>>(emptyList())
    var host: SshHost
        get() = hosts.value.single()
        set(value) { hosts.value = listOf(value) }
    var key: ByteArray? = null
    var password: String = "synthetic-password"
    override fun observeHosts() = hosts
    override suspend fun find(alias: String) = hosts.value.firstOrNull { it.alias == alias }
    override suspend fun save(host: SshHost, credential: SshCredential?) { this.host = host }
    override suspend fun delete(alias: String) = Unit
    override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost) = false
    override fun credential(host: SshHost) = key?.let { SshCredential(privateKey = it.copyOf()) }
        ?: SshCredential(password = password.toByteArray())
}

private class TestCommand(private val command: String, private val shell: Boolean, private val active: AtomicInteger) : Command {
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
        worker = Thread {
            active.incrementAndGet()
            try {
                when {
                    shell -> input.bufferedReader().useLines { lines -> lines.forEach { output.write("echo:$it\n".toByteArray()); output.flush() } }
                    command == "sleep" -> Thread.sleep(30_000)
                    command == "large" -> output.write("x".repeat(100_000).toByteArray())
                    command == "unicode-secret" -> output.write("合成口令-只用于测试".toByteArray())
                    else -> { output.write("stdout-ok synthetic-password".toByteArray()); error.write("stderr-ok".toByteArray()) }
                }
                output.flush(); error.flush(); callback.onExit(7)
            } catch (_: Exception) { } finally { active.decrementAndGet() }
        }.apply { isDaemon = true; start() }
    }
    override fun destroy(channel: ChannelSession) { worker?.interrupt(); runCatching { input.close() } }
}
