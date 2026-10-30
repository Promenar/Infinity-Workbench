package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.jobs.*
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.backgroundJobTools
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Before
import org.junit.Test

/** 仅用进程内回环 SSH 和合成认证材料。 */
class SftpTransferIntegrationTest {
    private val root = Files.createTempDirectory("sftp-transfer-server").toFile()
    private val remote = File(root, "remote").apply { mkdir() }
    private val local = File(root, "local").apply { mkdir() }
    private val hosts = MemorySshHosts()
    private lateinit var runtime: SshRuntime
    private lateinit var server: SshServer
    @Before fun setup() = runBlocking<Unit> {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(root, "server.key").toPath()).apply { algorithm = "EC" }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ -> password == hosts.password }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(remote.toPath())
            start()
        }
        hosts.host = SshHost(alias = "nas", hostname = "127.0.0.1", port = server.port, username = "synthetic-user", authType = SshAuthType.PASSWORD)
        runtime = SshRuntime(hosts)
        val key = runtime.probe("nas")
        hosts.host = hosts.host.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint)
    }
    @After fun cleanup() { if (::runtime.isInitialized) runtime.close(); if (::server.isInitialized) server.stop(true); root.deleteRecursively() }

    private fun endpoint(file: File, slow: Boolean = false, wrongHash: Boolean = false) = object : SftpLocalFile {
        override suspend fun stat() = file.takeIf { it.exists() }?.let { SftpLocalStat(it.length(), "${it.lastModified()}:${it.length()}") }
        override suspend fun <T> read(reader: (InputStream) -> T): T = file.inputStream().use { raw ->
            val stream = if (!slow) raw else object : java.io.FilterInputStream(raw) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { Thread.sleep(80); return super.read(bytes, offset, length) }
            }
            reader(stream)
        }
        override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit, beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String {
            if (stat() != expected || expected != null && !overwrite) throw SshFailure("TARGET_CONFLICT")
            val temp = File(file.parentFile, "${file.name}.tmp")
            try {
                val checksum = temp.outputStream().use(writer)
                val actual = temp.inputStream().use { sftpHash(it, size, check) }
                if (wrongHash || actual != checksum) throw SshFailure("CHECKSUM_MISMATCH")
                check()
                beforeCommit()
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                onCommitted(checksum)
                return checksum
            } finally { temp.delete() }
        }
    }
    @Test fun `空文件和多分块二进制经真实SFTP往返字节摘要一致`() = runBlocking<Unit> {
        for (size in listOf(0, 262_161)) {
            val bytes = ByteArray(size) { (it * 137).toByte() }
            val source = File(local, "source").apply { writeBytes(bytes) }
            val upload = runtime.transfer("agent:s", "nas", "/data", endpoint(source), true, true, SshExecControl(60))
            val destination = File(local, "destination")
            val download = runtime.transfer("agent:s", "nas", "/data", endpoint(destination), false, true, SshExecControl(60))
            assertThat(destination.readBytes()).isEqualTo(bytes)
            assertThat(download.sha256).isEqualTo(upload.sha256)
            assertThat(download.bytes).isEqualTo(size.toLong())
            assertThat(remote.listFiles()!!.map { it.name }).containsExactly("data")
        }
    }
    @Test fun `冲突目录符号链接和父目录缺失都拒绝且不替换目标`() = runBlocking<Unit> {
        val source = File(local, "source").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        File(remote, "data").writeText("原文")
        File(remote, "directory").mkdir()
        Files.createSymbolicLink(File(remote, "link").toPath(), File(remote, "data").toPath())
        for ((path, overwrite, code) in listOf(Triple("/data", false, "TARGET_CONFLICT"), Triple("/directory", true, "UNSUPPORTED_TRANSFER_TARGET"),
            Triple("/link", true, "UNSUPPORTED_TRANSFER_TARGET"), Triple("/missing/data", false, "REMOTE_PARENT_MISSING"))) {
            val failure = runCatching { runtime.transfer("agent:s", "nas", path, endpoint(source), true, overwrite, SshExecControl(60)) }.exceptionOrNull()
            assertThat((failure as SshFailure).code).isEqualTo(code)
        }
        assertThat(File(remote, "data").readText()).isEqualTo("原文")
    }
    @Test fun `取消上传清理临时文件且保留原目标`() = runBlocking<Unit> {
        val source = File(local, "source").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }
        File(remote, "data").writeText("原文")
        val firstChunk = CompletableDeferred<Unit>()
        val task = launch(Dispatchers.IO) {
            runtime.transfer("agent:s", "nas", "/data", endpoint(source, slow = true), true, true, SshExecControl(60)) { _, done, _ ->
                if (done >= 1024 * 1024) firstChunk.complete(Unit)
            }
        }
        withTimeout(10_000) { firstChunk.await() }
        task.cancelAndJoin()
        assertThat(File(remote, "data").readText()).isEqualTo("原文")
        assertThat(remote.listFiles()!!.map { it.name }).containsExactly("data")
    }
    @Test fun `下载校验失败不替换现有本地文件`() = runBlocking<Unit> {
        File(remote, "data").writeBytes(ByteArray(200_000) { it.toByte() })
        val destination = File(local, "destination").apply { writeText("原文") }
        val failure = runCatching { runtime.transfer("agent:s", "nas", "/data", endpoint(destination, wrongHash = true), false, true, SshExecControl(60)) }.exceptionOrNull()
        assertThat((failure as SshFailure).code).isEqualTo("CHECKSUM_MISMATCH")
        assertThat(destination.readText()).isEqualTo("原文")
        assertThat(local.listFiles()!!.map { it.name }).containsExactly("destination")
    }
    @Test fun `真实Linux端口的校验错误保留稳定分类且目标不变`() = runBlocking<Unit> {
        File(remote, "data").writeBytes(byteArrayOf(0, -1, 2))
        val target = File(local, "destination").apply { writeText("原文") }
        val fs = com.promenar.infinity.data.linux.LinuxFileSystemImpl(local.canonicalFile, { true },
            com.promenar.infinity.data.linux.TestLinuxFileMetadata).forAgentTools()
        val real = com.promenar.infinity.data.workspace.SftpLinuxFile(fs, "/destination")
        val badHash = object : SftpLocalFile by real {
            override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit,
                beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String =
                real.write(size, overwrite, expected, check, beforeCommit, onCommitted) { output -> writer(output); "0".repeat(64) }
        }
        val failure = runCatching { runtime.transfer("agent:s", "nas", "/data", badHash, false, true, SshExecControl(60)) }.exceptionOrNull()
        assertThat(com.promenar.infinity.ui.chat.manager.skills.transferErrorCode(failure as Exception)).isEqualTo("CHECKSUM_MISMATCH")
        assertThat(target.readText()).isEqualTo("原文")
        assertThat(local.list()!!.toList()).containsExactly("destination")
    }

    @Test fun `停止与服务时限能打断等待本地发布锁的传输`() = runBlocking<Unit> {
        File(remote, "data").writeBytes(byteArrayOf(0, -1, 2))
        for (timeLimit in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val endpoint = object : SftpLocalFile by endpoint(File(local, "destination")) {
                override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit,
                    beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val control = SshExecControl(60)
            val pending = async(Dispatchers.IO) {
                runCatching { runtime.transfer("agent:s", "nas", "/data", endpoint, false, false, control) }.exceptionOrNull()
            }
            withTimeout(5_000) { entered.await() }
            if (timeLimit) control.requestTimeLimit() else control.requestStop()
            val failure = withTimeout(5_000) { pending.await() }
            assertThat((failure as SshFailure).code).isEqualTo(if (timeLimit) "SERVICE_TIME_LIMIT" else "STOPPED")
            assertThat(File(local, "destination").exists()).isFalse()
        }
    }

    @Test fun `断连保留原目标且恢复同一主机连接后清除登记临时文件`() = runBlocking<Unit> {
        val source = File(local, "source").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }
        File(remote, "data").writeText("原文")
        val firstChunk = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.IO) {
            runCatching { runtime.transfer("agent:s", "nas", "/data", endpoint(source, slow = true), true, true,
                SshExecControl(60)) { _, done, _ ->
                if (done >= 1024 * 1024 && !firstChunk.isCompleted) {
                    firstChunk.complete(Unit)
                    disconnected.await()
                }
            } }.exceptionOrNull()
        }
        withTimeout(10_000) { firstChunk.await() }
        server.activeSessions.forEach { it.close(true).await(5_000) }
        disconnected.complete(Unit)
        assertThat(withTimeout(10_000) { pending.await() }).isNotNull()
        runtime.transferStat("agent:reconnect", "nas", "/data")
        assertThat(File(remote, "data").readText()).isEqualTo("原文")
        assertThat(remote.list()!!.toList()).containsExactly("data")
    }

    @Test fun `原子发布后的最终进度阶段停止仍返回已提交成功摘要`() = runBlocking<Unit> {
        val bytes = ByteArray(196_625) { (it * 137).toByte() }
        val source = File(local, "source").apply { writeBytes(bytes) }
        File(remote, "data").writeText("远端原文")
        for (upload in listOf(true, false)) {
            val target = if (upload) File(remote, "data") else File(local, "destination").apply { writeText("本地原文") }
            val control = SshExecControl(60)
            val result = runtime.transfer("agent:s", "nas", "/data", if (upload) endpoint(source) else endpoint(target), upload, true, control) { total, done, _ ->
                if (done == total && target.exists() && target.readBytes().contentEquals(bytes)) {
                    control.requestStop()
                    awaitCancellation()
                }
            }
            assertThat(result.sha256).isEqualTo(bytes.inputStream().use { sftpHash(it, bytes.size.toLong()) })
            assertThat(target.readBytes()).isEqualTo(bytes)
        }
    }

    @Test fun `提交后的真实索引错误优先于同时收到的停止`() = runBlocking<Unit> {
        File(remote, "data").writeBytes(byteArrayOf(0, -1, 2))
        val target = File(local, "destination")
        val control = SshExecControl(60)
        val base = endpoint(target)
        val failedIndex = object : SftpLocalFile by base {
            override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit,
                beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String {
                base.write(size, overwrite, expected, check, beforeCommit, onCommitted, writer)
                control.requestStop()
                throw SshFailure("WORKSPACE_INDEX_FAILED")
            }
        }
        val failure = runCatching { runtime.transfer("agent:s", "nas", "/data", failedIndex, false, false, control) }.exceptionOrNull()
        assertThat((failure as SshFailure).code).isEqualTo("WORKSPACE_INDEX_FAILED")
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(0, -1, 2))
    }

    @Test fun `让出后台观察进度且job_status返回transfer`() = runBlocking<Unit> {
        val source = File(local, "source").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = BackgroundJobStore(File(root, "store"))
        val jobs = BackgroundJobManager(scope, store, File(root, "runs"), BuiltinCommandRunner(listOf("/bin/sh"), "/bin/sh", { _, _ -> }, { File(root, "scratch") }),
            prepareWorkspace = { error("不调用本机命令") }, prepareCommand = { _, _, _, _, _ -> error("不调用本机命令") })
        try {
            val transfer = SftpTransfer(SftpTransferDirection.UPLOAD, "workspace", "/source", "/data", null, 0, null)
            val run = jobs.runSsh(runtime, hosts.host, "s", "root", "上传 source → /data", 600, 32000, 1, false, 1,
                BackgroundJobExitAction.NONE, ToolLiveOutputSink.None, transfer) { owner, control, progress ->
                val value = runtime.transfer(owner, "nas", "/data", endpoint(source, slow = true), true, false, control, progress = progress)
                com.promenar.infinity.domain.tool.CommandToolResult(exitCode = 0, backendFields = value.toJson())
            }
            assertThat(run.yieldedAfterSeconds).isEqualTo(1)
            val id = requireNotNull(run.job).id
            val progress = withTimeout(10_000) { jobs.observe("s").first { rows -> rows.any { (it.transfer?.transferredBytes ?: 0) > 0 } } }
            assertThat(progress.single().transfer!!.totalBytes).isEqualTo(source.length())
            val context = object : SkillExecutionContext {
                override val sessionId = "s"; override val agentId = "a"; override val workspacePath: String? = null; override val workspaceRootUuid = "root"
            }
            val result = backgroundJobTools(jobs).single { it.name == "job_status" }.execute(buildJsonObject { put("job_id", id) }, context)
            assertThat(Json.parseToJsonElement(result.content).jsonObject["transfer"]!!.jsonObject["direction"]!!.jsonPrimitive.content).isEqualTo("upload")
            val ended = jobs.waitFor("s", id, 30).record
            assertThat(ended.state).isEqualTo("EXITED")
            assertThat(ended.transfer!!.sha256).isNotEmpty()
        } finally { runtime.disconnectAll(); scope.cancel() }
    }
}
