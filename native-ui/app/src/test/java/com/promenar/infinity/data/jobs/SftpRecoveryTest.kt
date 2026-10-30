package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.domain.jobs.SftpTransferFailure
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.backgroundJobTools
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

class SftpRecoveryTest {
    @Test fun `重启保留传输进度和清理责任且job_status返回transfer`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("sftp-recovery").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = BackgroundJobStore(File(root, "store"))
            val transfer = SftpTransferRecord("UPLOAD", "workspace", "/a.bin", "/remote/a.bin", 2_000_000, 1_000_000, 200_000,
                remoteTemporaryPath = "/remote/a.bin.infinity-00000000-0000-0000-0000-000000000000.tmp", remoteTemporaryIdentity = "identity")
            store.put(BackgroundJobRecord("job-transfer", "s", "root", "SSH", "上传 a.bin → /remote/a.bin",
                startedAtMillis = System.currentTimeMillis(), maxRuntimeMinutes = 1, onExit = "none", logWorkspacePath = null,
                runDirectory = File(root, "runs/job-transfer").path, remoteHost = "nas", transfer = transfer))
            val jobs = BackgroundJobManager(scope, store, File(root, "runs"),
                BuiltinCommandRunner(listOf("/bin/sh"), "/bin/sh", { _, _ -> }, { File(root, "scratch") }),
                prepareWorkspace = { error("不得调用本机执行器") }, prepareCommand = { _, _, _, _, _ -> error("不得调用本机执行器") })
            jobs.initialize()
            val record = jobs.status("s", "job-transfer").record
            assertThat(record.state).isEqualTo("INTERRUPTED")
            assertThat(record.job().transfer!!.failure).isEqualTo(SftpTransferFailure.NETWORK)
            assertThat(record.transfer!!.remoteTemporaryPath).isEqualTo(transfer.remoteTemporaryPath)
            assertThat(record.transfer.bytesPerSecond).isNull()
            val context = object : SkillExecutionContext {
                override val sessionId = "s"; override val agentId = "a"; override val workspacePath: String? = null; override val workspaceRootUuid = "root"
            }
            val output = backgroundJobTools(jobs).single { it.name == "job_status" }.execute(buildJsonObject { put("job_id", "job-transfer") }, context)
            val value = Json.parseToJsonElement(output.content).jsonObject.getValue("transfer").jsonObject
            assertThat(value.getValue("direction").jsonPrimitive.content).isEqualTo("upload")
            assertThat(value.getValue("bytes_total").jsonPrimitive.long).isEqualTo(2_000_000)
            assertThat(value.getValue("bytes_done").jsonPrimitive.long).isEqualTo(1_000_000)
            assertThat(value.getValue("failure").jsonPrimitive.content).isEqualTo("network")
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun `开始传输前触发服务时限记录TIMED_OUT而不是用户停止`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("sftp-early-timeout").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hosts = com.promenar.infinity.data.ssh.MemorySshHosts().apply {
            host = com.promenar.infinity.data.ssh.SshHost(alias = "nas", hostname = "127.0.0.1", username = "synthetic",
                authType = com.promenar.infinity.data.ssh.SshAuthType.PASSWORD)
        }
        val runtime = com.promenar.infinity.data.ssh.SshRuntime(hosts)
        try {
            val jobs = BackgroundJobManager(scope, BackgroundJobStore(File(root, "store")), File(root, "runs"),
                BuiltinCommandRunner(listOf("/bin/sh"), "/bin/sh", { _, _ -> }, { File(root, "scratch") }),
                prepareWorkspace = { error("不使用本机执行器") }, prepareCommand = { _, _, _, _, _ -> error("不使用本机执行器") })
            val transfer = com.promenar.infinity.domain.jobs.SftpTransfer(com.promenar.infinity.domain.jobs.SftpTransferDirection.DOWNLOAD,
                "workspace", "/data", "/data", 1, 0, null)
            val run = jobs.runSsh(runtime, runtime.hostSnapshot(hosts.host.alias), "s", "root", "下载 data → /data", 600, 32000,
                0, false, 1, com.promenar.infinity.domain.jobs.BackgroundJobExitAction.NONE,
                com.promenar.infinity.domain.tool.ToolLiveOutputSink.None, transfer) { _, control, _ ->
                control.requestTimeLimit()
                awaitCancellation()
            }
            assertThat(run.command!!.errorCode).isEqualTo("SSH_SERVICE_TIME_LIMIT")
            assertThat(run.command.timedOut).isTrue()
            val row = jobs.list("s").single()
            assertThat(row.state).isEqualTo("TIMED_OUT")
            assertThat(row.transfer!!.failure).isEqualTo("TIME_LIMIT")
        } finally { runtime.close(); scope.cancel(); root.deleteRecursively() }
    }

}
