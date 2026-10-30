package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.jobs.*
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Before
import org.junit.Test

/** 回环服务和延迟代理锁定小文件开销；不访问外部网络。 */
class SftpTransferLatencyTest {
    private val root = Files.createTempDirectory("sftp-latency").toFile()
    private val remote = File(root, "remote").apply { mkdir() }
    private val local = File(root, "local").apply { mkdir() }
    private val hosts = MemorySshHosts()
    private lateinit var runtime: SshRuntime
    private lateinit var server: SshServer
    private val sessions = AtomicInteger()
    private val channels = AtomicInteger()
    private val ops = mutableListOf<String>()
    private val blockNextStat = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    private var proxy: ServerSocket? = null
    private val relaySockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var oneWayDelayMs = 0L

    @Before fun setup() = runBlocking<Unit> {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(root, "server.key").toPath()).apply { algorithm = "EC" }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ -> password == hosts.password }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build().apply {
                addSftpEventListener(object : org.apache.sshd.sftp.server.SftpEventListener {
                    override fun received(session: org.apache.sshd.server.session.ServerSession, type: Int, id: Int) {
                        synchronized(ops) { ops += org.apache.sshd.sftp.common.SftpConstants.getCommandMessageName(type).removePrefix("SSH_FXP_") }
                        if (type == org.apache.sshd.sftp.common.SftpConstants.SSH_FXP_LSTAT) blockNextStat.getAndSet(null)?.invoke()
                    }
                })
            })
            fileSystemFactory = VirtualFileSystemFactory(remote.toPath())
            shellFactory = org.apache.sshd.server.shell.ShellFactory { LatencyShell() }
            addSessionListener(object : SessionListener { override fun sessionCreated(session: Session) { sessions.incrementAndGet() } })
            addChannelListener(object : ChannelListener { override fun channelOpenSuccess(channel: Channel) { channels.incrementAndGet() } })
            start()
        }
        val socket = ServerSocket(0).also { proxy = it }
        Thread { while (!socket.isClosed) { val client = runCatching { socket.accept() }.getOrNull() ?: break; relay(client) } }.apply { isDaemon = true; start() }
        hosts.host = SshHost(alias = "nas", hostname = "127.0.0.1", port = socket.localPort, username = "synthetic-user", authType = SshAuthType.PASSWORD)
        runtime = SshRuntime(hosts)
        val key = runtime.probe("nas")
        hosts.host = hosts.host.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint)
    }
    @After fun cleanup() { jobScope.cancel(); if (::runtime.isInitialized) runtime.close(); proxy?.close(); relaySockets.forEach { runCatching { it.close() } }; if (::server.isInitialized) server.stop(true); root.deleteRecursively() }

    /** 每个方向按块延迟转发，模拟往返时延。 */
    private fun relay(client: Socket) {
        val upstream = Socket("127.0.0.1", server.port)
        relaySockets.add(client); relaySockets.add(upstream)
        fun pump(from: Socket, to: Socket) = Thread {
            val buffer = ByteArray(65536)
            try {
                val input = from.getInputStream(); val output = to.getOutputStream()
                while (true) {
                    val read = input.read(buffer); if (read < 0) break
                    if (oneWayDelayMs > 0) Thread.sleep(oneWayDelayMs)
                    output.write(buffer, 0, read); output.flush()
                }
            } catch (_: Exception) { } finally { runCatching { to.shutdownOutput() } }
        }.apply { isDaemon = true; start() }
        pump(client, upstream); pump(upstream, client)
    }

    private fun endpoint(file: File) = object : SftpLocalFile {
        override suspend fun stat() = file.takeIf { it.exists() }?.let { SftpLocalStat(it.length(), "${it.lastModified()}:${it.length()}") }
        override suspend fun <T> read(reader: (InputStream) -> T): T = file.inputStream().use(reader)
        override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit, beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String {
            val temp = File(file.parentFile, "${file.name}.tmp")
            val checksum = temp.outputStream().use(writer)
            beforeCommit(); Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); onCommitted(checksum)
            return checksum
        }
    }

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { BackgroundJobStore(File(root, "store")) }
    private val jobs by lazy { BackgroundJobManager(jobScope, store, File(root, "runs"),
        BuiltinCommandRunner(listOf("/bin/sh"), "/bin/sh", { _, _ -> }, { File(root, "scratch") }),
        prepareWorkspace = { error("不执行命令") }, prepareCommand = { _, _, _, _, _ -> error("不执行命令") }) }

    private suspend fun once(upload: Boolean, name: String, expectedSource: String = "transferred", expectedSessions: Int = 0,
        beforeRun: suspend () -> Unit = {}) {
        val bytes = ByteArray(if (upload) 14 else 4) { it.toByte() }
        val source = File(local, "source-$name").apply { writeBytes(bytes) }
        if (!upload) File(remote, name).writeBytes(bytes)
        val target = File(local, "dl-$name")
        val port = endpoint(if (upload) source else target)
        val snapshot = runtime.hostSnapshot("nas")
        val preview = runtime.withHostSnapshot(snapshot) {
            com.promenar.infinity.ui.chat.manager.skills.sftpApprovalSnapshot(runtime, "s", "nas", "/$name", port, upload, false)
        }
        beforeRun()
        val sessionsBefore = sessions.get()
        val channelsBefore = channels.get()
        synchronized(ops) { ops.clear() }
        val liveLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val live = object : ToolLiveOutputSink by ToolLiveOutputSink.None {
            override fun emit(stream: ToolOutputStream, text: String) { liveLines += text }
        }
        val transfer = SftpTransfer(if (upload) SftpTransferDirection.UPLOAD else SftpTransferDirection.DOWNLOAD,
            "workspace", "/x", "/$name", bytes.size.toLong(), 0, null)
        val startedAt = System.nanoTime()
        var transferEndedAt = 0L
        val run = jobs.runSsh(runtime, snapshot, "s", "root", "传输", 600, 32000, 15, false, 120,
            BackgroundJobExitAction.NONE, live, transfer) { owner, control, progress ->
            // 首行必须在调用运行时建连之前出现，并显示实际总字节。
            assertThat(liveLines.first()).isEqualTo("\r已传 0 B / ${bytes.size} B")
            val value = runtime.transfer(owner, "nas", "/$name", port, upload, false, control,
                SftpLocalExpectation(preview.local), SftpRemoteExpectation(preview.remote), agentOwner = "agent:s", progress = progress)
            transferEndedAt = System.nanoTime()
            com.promenar.infinity.domain.tool.CommandToolResult(exitCode = 0, backendFields = value.toJson())
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertThat(run.job).isNull()
        val result = requireNotNull(run.command)
        assertThat(result.errorCode).isNull()
        assertThat((System.nanoTime() - transferEndedAt) / 1_000_000).isLessThan(3_000L)
        assertThat(elapsedMs).isLessThan(if (oneWayDelayMs > 0) 12_000L else 5_000L)
        assertThat(sessions.get() - sessionsBefore).isEqualTo(expectedSessions)
        assertThat(channels.get() - channelsBefore).isEqualTo(1)
        val requests = synchronized(ops) { ops.toList() }
        assertThat(requests.size).isAtMost(if (upload) 17 else 9)
        assertThat(requests.count { it == "INIT" }).isEqualTo(1)
        assertThat(result.backendFields["connection_source"]!!.jsonPrimitive.content).isEqualTo(expectedSource)
        val timings = result.backendFields.getValue("timings_ms").jsonObject
        assertThat(timings.keys).containsAtLeast("queue_prepare", "connection", "sftp_open", "stat_verify", "data_transfer", "verify_readback", "publish", "cleanup")
        timings.values.forEach { assertThat(it.jsonPrimitive.double).isAtLeast(0.0) }
        val record = store.all().single { it.transfer?.remotePath == "/$name" }
        assertThat(jobs.readLog("s", requireNotNull(record.logWorkspacePath)).text).contains("SFTP 分段耗时")
        assertThat(record.commandResult!!.getValue("timings_ms").jsonObject).isEqualTo(timings)
        val status = com.promenar.infinity.ui.chat.manager.skills.backgroundJobTools(jobs).single { it.name == "job_status" }
            .execute(buildJsonObject { put("job_id", record.id) }, approvalContext(null))
        assertThat(Json.parseToJsonElement(status.content).jsonObject.getValue("timings_ms").jsonObject).isEqualTo(timings)
        assertThat(liveLines.joinToString("")).doesNotContain("MiB")
        assertThat(liveLines.joinToString("")).contains("已传 ${bytes.size} B / ${bytes.size} B")
        assertThat((if (upload) File(remote, name) else target).readBytes()).isEqualTo(bytes)
        assertThat(runtime.activity.value.connections.none { it.owner.startsWith("job:") }).isTrue()
    }

    @Test fun `审批连接转交后上传下载与连续传输均不重新握手`() = runBlocking<Unit> {
        once(true, "up-1")
        once(false, "down-1")
        once(true, "up-2")
        once(false, "down-2")
    }

    @Test fun `没有可借连接时新建的任务连接也会归还`() = runBlocking<Unit> {
        once(true, "new-up", "new", 1) { runtime.closeOwner("agent:s") }
        once(false, "reuse-down")
    }

    @Test fun `注入300毫秒往返后小文件仍在有余量的上限内完成`() = runBlocking<Unit> {
        // 认证不在计时范围；传输阶段才注入延迟。
        runtime.transferStat("agent:s", "nas", "/missing")
        oneWayDelayMs = 150
        once(true, "delayed-up")
        once(false, "delayed-down")
    }

    @Test fun `Agent连接有PTY时使用新连接且不覆盖原槽位`() = runBlocking<Unit> {
        val terminal = runtime.openSession("agent:s", "nas")
        try {
            once(true, "pty-up", "new", 1)
            assertThat(runtime.activity.value.agentSessions.single().id).isEqualTo(terminal.sessionId)
        } finally { runtime.closeSession("agent:s", terminal.sessionId) }
    }

    @Test fun `Agent文件连接被占用时回退新连接`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        var reading: Deferred<SftpRemoteStat?>? = null
        try {
            once(true, "busy-up", "new", 1) {
                blockNextStat.set { entered.complete(Unit); check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
                reading = jobScope.async { runtime.transferStat("agent:s", "nas", "/missing") }
                withTimeout(5_000) { entered.await() }
            }
        } finally { release.countDown(); reading?.await() }
    }
    @Test fun `停止时限断连与服务回收都不归还任务连接`() = runBlocking<Unit> {
        for (cause in listOf("stop", "limit", "deadline", "disconnect", "all", "persistent")) {
            runtime.closeOwner("agent:s")
            val file = File(remote, "cancel-$cause").apply { writeText("data") }
            runtime.transferStat("agent:s", "nas", "/${file.name}")
            val entered = CompletableDeferred<Unit>()
            val control = SshExecControl(if (cause == "deadline") 1 else 60)
            val epoch = runtime.registerForegroundService()
            runtime.registerJob(cause, "s", "nas", control::requestStop, control::requestTimeLimit)
            val port = object : SftpLocalFile by endpoint(File(local, cause)) {
                override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit,
                    beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val task = async(Dispatchers.IO) {
                runCatching { runtime.transfer("job:$cause", "nas", "/${file.name}", port, false, false, control,
                    agentOwner = "agent:s") }.exceptionOrNull()
            }
            withTimeout(5_000) { entered.await() }
            assertThat(runtime.activity.value.connections.single().owner).isEqualTo("job:$cause")
            runtime.closeOwner("agent:s")
            assertThat(task.isCompleted).isFalse()
            when (cause) {
                "stop" -> control.requestStop()
                "limit" -> control.requestTimeLimit()
                "disconnect" -> runtime.disconnect("job:$cause", "nas")
                "all" -> runtime.disconnectAll()
                "persistent" -> runtime.closePersistent(epoch, timeLimit = true)
            }
            val failure = withTimeout(5_000) { task.await() }
            assertThat((failure as SshFailure).code).isEqualTo(when (cause) {
                "limit", "persistent" -> "SERVICE_TIME_LIMIT"
                "deadline" -> "TIMEOUT"
                else -> "STOPPED"
            })
            runtime.finishJob(cause)
            assertThat(runtime.activity.value.connections).isEmpty()
        }
    }

    @Test fun `归还前停止或更换主机记录时丢弃成功任务的连接`() = runBlocking<Unit> {
        for (changeHost in listOf(false, true)) {
            val control = SshExecControl(60)
            val id = "late-$changeHost"
            runtime.registerJob(id, "s", "nas", control::requestStop)
            val source = File(local, id).apply { writeText("x") }
            runtime.transfer("job:$id", "nas", "/$id", endpoint(source), true, false, control, agentOwner = "agent:s")
            if (changeHost) hosts.host = hosts.host.copy(username = "changed-user") else control.requestStop()
            runtime.finishJob(id)
            assertThat(runtime.activity.value.connections).isEmpty()
        }
    }

    @Test fun `失败任务日志保留已到达阶段的耗时摘要`() = runBlocking<Unit> {
        val transfer = SftpTransfer(SftpTransferDirection.DOWNLOAD, "workspace", "/local", "/missing", 4, 0, null)
        val failure = runCatching {
            jobs.runSsh(runtime, hosts.host, "s", "root", "失败传输", 600, 32000, 15, false, 1,
                BackgroundJobExitAction.NONE, ToolLiveOutputSink.None, transfer) { owner, control, progress ->
                runtime.transfer(owner, "nas", "/missing", endpoint(File(local, "failed")), false, false, control,
                    agentOwner = "agent:s", progress = progress)
                error("不得成功")
            }
        }.exceptionOrNull()
        assertThat((failure as SshFailure).code).isEqualTo("REMOTE_NOT_FOUND")
        val record = store.all().single()
        val log = jobs.readLog("s", requireNotNull(record.logWorkspacePath)).text
        assertThat(log).contains("SFTP 分段耗时")
        assertThat(log).contains("stat_verify=")
        assertThat(log).doesNotContain("data_transfer=")
        assertThat(runtime.activity.value.connections).isEmpty()
    }

    @Test fun `实际技能上传下载复用审批连接且不再单独打开快照通道`() = runBlocking<Unit> {
        val files = com.promenar.infinity.data.linux.LinuxFileSystemImpl(local, { true }, com.promenar.infinity.data.linux.TestLinuxFileMetadata)
        val access = com.promenar.infinity.ui.chat.manager.skills.SftpTransferAccess(linux = files, linuxAvailable = { true })
        val tools = com.promenar.infinity.ui.chat.manager.skills.sftpTransferTools(runtime, jobs, access)
        val source = File(local, "skill-source").apply { writeText("private body") }
        for (upload in listOf(true, false)) {
            val path = if (upload) "/skill-source" else "/skill-download"
            val tool = tools.single { it.id == if (upload) "sftp_upload" else "sftp_download" }
            val port = com.promenar.infinity.data.workspace.SftpLinuxFile(files.forAgentTools(), path)
            val args = buildJsonObject { put("host", "nas"); put("local_scope", "linux"); put("local_path", path); put("remote_path", "/skill-target") }
            val preview = com.promenar.infinity.ui.chat.manager.skills.sftpApprovalSnapshot(runtime, "s", "nas", "/skill-target", port, upload, false)
            val base = requireNotNull(com.promenar.infinity.domain.tool.ToolExecutionTargets.extract(tool.toProtocolTool(), args.toString(),
                mapOf("sftp:local-identity" to "linux:root"), mapOf("nas" to hosts.host.executionIdentity())))
            val approved = com.promenar.infinity.domain.tool.ToolExecutionTargets.withSftpPreview(base, preview.fields)
            val before = sessions.get()
            synchronized(ops) { ops.clear() }
            val output = tool.execute(args, approvalContext(approved))
            assertThat(output.status).isEqualTo("success")
            assertThat(sessions.get() - before).isEqualTo(0)
            assertThat(synchronized(ops) { ops.count { it == "INIT" } }).isEqualTo(1)
            assertThat(synchronized(ops) { ops.size }).isAtMost(if (upload) 17 else 9)
            assertThat((if (upload) File(remote, "skill-target") else File(local, "skill-download")).readBytes()).isEqualTo(source.readBytes())
        }
    }

    @Test fun `完整审批字段逐项冲突时实际技能不得写入`() = runBlocking<Unit> {
        val files = com.promenar.infinity.data.linux.LinuxFileSystemImpl(local, { true }, com.promenar.infinity.data.linux.TestLinuxFileMetadata)
        val tool = com.promenar.infinity.ui.chat.manager.skills.sftpTransferTools(runtime, jobs,
            com.promenar.infinity.ui.chat.manager.skills.SftpTransferAccess(linux = files, linuxAvailable = { true })).single { it.id == "sftp_upload" }
        File(local, "source").writeText("private body")
        val port = com.promenar.infinity.data.workspace.SftpLinuxFile(files.forAgentTools(), "/source")
        val args = buildJsonObject { put("host", "nas"); put("local_scope", "linux"); put("local_path", "/source"); put("remote_path", "/target") }
        val preview = com.promenar.infinity.ui.chat.manager.skills.sftpApprovalSnapshot(runtime, "s", "nas", "/target", port, true, false)
        val base = requireNotNull(com.promenar.infinity.domain.tool.ToolExecutionTargets.extract(
            tool.toProtocolTool(), args.toString(),
            mapOf("sftp:local-identity" to "linux:root"), mapOf("nas" to hosts.host.executionIdentity())))
        for ((field, value) in mapOf("bytes_total" to JsonPrimitive(999), "target_exists" to JsonPrimitive(true),
            "will_overwrite" to JsonPrimitive(true), "source_revision" to JsonPrimitive("changed"), "target_revision" to JsonPrimitive("changed"))) {
            val approved = com.promenar.infinity.domain.tool.ToolExecutionTargets.withSftpPreview(base, JsonObject(preview.fields + (field to value)))
            val output = tool.execute(args, approvalContext(approved))
            assertThat(output.status).isEqualTo("error")
            assertThat(output.content).contains("SSH_TARGET_CONFLICT")
            assertThat(remote.list()!!.toList()).isEmpty()
            assertThat(File(local, "source").readText()).isEqualTo("private body")
        }
    }

    @Test fun `审批后上传源消失报冲突且不创建远端临时文件`() = runBlocking<Unit> {
        val files = com.promenar.infinity.data.linux.LinuxFileSystemImpl(local, { true }, com.promenar.infinity.data.linux.TestLinuxFileMetadata)
        val tool = com.promenar.infinity.ui.chat.manager.skills.sftpTransferTools(runtime, jobs,
            com.promenar.infinity.ui.chat.manager.skills.SftpTransferAccess(linux = files, linuxAvailable = { true })).single { it.id == "sftp_upload" }
        val source = File(local, "source").apply { writeText("private body") }
        val port = com.promenar.infinity.data.workspace.SftpLinuxFile(files.forAgentTools(), "/source")
        val args = buildJsonObject { put("host", "nas"); put("local_scope", "linux"); put("local_path", "/source"); put("remote_path", "/target") }
        val preview = com.promenar.infinity.ui.chat.manager.skills.sftpApprovalSnapshot(runtime, "s", "nas", "/target", port, true, false)
        val base = requireNotNull(com.promenar.infinity.domain.tool.ToolExecutionTargets.extract(tool.toProtocolTool(), args.toString(),
            mapOf("sftp:local-identity" to "linux:root"), mapOf("nas" to hosts.host.executionIdentity())))
        val approved = com.promenar.infinity.domain.tool.ToolExecutionTargets.withSftpPreview(base, preview.fields)
        source.delete()
        val output = tool.execute(args, approvalContext(approved))
        assertThat(output.content).contains("SSH_TARGET_CONFLICT")
        assertThat(remote.list()!!.toList()).isEmpty()
    }

    @Test fun `发布后最终进度阶段超时仍成功但不归还连接`() = runBlocking<Unit> {
        val source = File(local, "deadline-source").apply { writeText("x") }
        runtime.transferStat("agent:s", "nas", "/deadline-target")
        val control = SshExecControl(1)
        runtime.registerJob("deadline-committed", "s", "nas", control::requestStop, control::requestTimeLimit)
        val result = withTimeout(5_000) { runtime.transfer("job:deadline-committed", "nas", "/deadline-target", endpoint(source), true, false, control,
            agentOwner = "agent:s", progress = { total, done, _ -> if (total == done && File(remote, "deadline-target").exists()) awaitCancellation() }) }
        assertThat(result.bytes).isEqualTo(1)
        assertThat(File(remote, "deadline-target").readText()).isEqualTo("x")
        runtime.finishJob("deadline-committed")
        assertThat(runtime.activity.value.connections).isEmpty()
    }

    @Test fun `任务终态持久化先于连接归还且收尾时job_stop不再接受`() = runBlocking<Unit> {
        runtime.close()
        val returning = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        runtime = SshRuntime(hosts, onPersistentActivity = { active ->
            if (!active && armed.get()) {
                returning.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        })
        val source = File(local, "settle-source").apply { writeText("x") }
        val transfer = SftpTransfer(SftpTransferDirection.UPLOAD, "workspace", "/source", "/settle-target", 1, 0, null)
        val pending = async(Dispatchers.IO) {
            jobs.runSsh(runtime, hosts.host, "s", "root", "传输收尾", 600, 32000, 15, false, 1,
                BackgroundJobExitAction.NONE, ToolLiveOutputSink.None, transfer) { owner, control, progress ->
                val value = runtime.transfer(owner, "nas", "/settle-target", endpoint(source), true, false, control,
                    agentOwner = "agent:s", progress = progress)
                com.promenar.infinity.domain.tool.CommandToolResult(exitCode = 0, backendFields = value.toJson())
            }
        }
        try {
            withTimeout(5_000) { returning.await() }
            val record = store.all().single()
            assertThat(record.running).isFalse()
            assertThat(withTimeout(2_000) { jobs.stop(record.id) }).isFalse()
        } finally { armed.set(false); release.countDown() }
        assertThat(withTimeout(5_000) { pending.await() }.command!!.exitCode).isEqualTo(0)
        assertThat(runtime.activity.value.connections.single().owner).isEqualTo("agent:s")
    }

    private fun approvalContext(approved: String?) = object : com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX
        override val isApprovalBoundExecution = approved != null
        override val approvedExecutionTarget = approved
    }

}

private class LatencyShell : org.apache.sshd.server.command.Command {
    override fun setInputStream(value: InputStream) = Unit
    override fun setOutputStream(value: OutputStream) = Unit
    override fun setErrorStream(value: OutputStream) = Unit
    override fun setExitCallback(value: org.apache.sshd.server.ExitCallback) = Unit
    override fun start(channel: org.apache.sshd.server.channel.ChannelSession, env: org.apache.sshd.server.Environment) = Unit
    override fun destroy(channel: org.apache.sshd.server.channel.ChannelSession) = Unit
}
