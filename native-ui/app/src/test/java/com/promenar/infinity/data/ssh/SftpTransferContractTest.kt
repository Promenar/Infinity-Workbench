package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.skills.sshTools
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.domain.tool.SshExecutionIdentity

class SftpTransferContractTest {
    @Test fun `二进制传输工具注册并采用上传外部写下载文件写风险`() {
        val hosts = MemorySshHosts().apply { host = SshHost(alias = "test", hostname = "localhost", username = "test") }
        SshRuntime(hosts).use { runtime ->
            val tools = sshTools(runtime).associateBy { it.id }
            assertThat(tools.keys).containsAtLeast("sftp_upload", "sftp_download")
            assertThat(tools.getValue("sftp_upload").risk).isEqualTo(ToolRisk.EXTERNAL_WRITE)
            assertThat(tools.getValue("sftp_download").risk).isEqualTo(ToolRisk.FILE_WRITE)
        }
    }

    @Test fun `非Linux会话越界路径及未知参数在连接前拒绝`() = runBlocking<Unit> {
        val hosts = MemorySshHosts().apply { host = SshHost(alias = "test", hostname = "localhost", username = "test") }
        val context = object : SkillExecutionContext {
            override val sessionId = "s"; override val agentId = "a"; override val workspacePath: String? = null; override val workspaceRootUuid = "root"
        }
        SshRuntime(hosts).use { runtime ->
            val tool = sshTools(runtime).single { it.name == "sftp_download" }
            for ((extra, code) in listOf(
                "\"local_scope\":\"linux\"" to "LINUX_SCOPE_REQUIRES_LINUX_SESSION",
                "\"local_scope\":\"workspace\",\"local_path\":\"/../a\"" to "INVALID_LOCAL_PATH",
                "\"local_scope\":\"workspace\",\"local_path\":\"/.agents/skills/a/SKILL.md\"" to "PROTECTED_LOCAL_PATH",
                "\"password\":\"synthetic\"" to "INVALID_ARGUMENTS")) {
                val args = Json.parseToJsonElement("""{"host":"test","remote_path":"/a","local_path":"/a",$extra}""").jsonObject
                val result = tool.execute(args, context)
                assertThat(result.status).isEqualTo("error")
                assertThat(result.content).contains("SSH_$code")
            }
        }
    }
    @Test fun `审批同时绑定主机身份方向两端路径范围与覆盖选项`() {
        val hosts = MemorySshHosts().apply { host = SshHost(alias = "test", hostname = "localhost", username = "test") }
        SshRuntime(hosts).use { runtime ->
            val tools = sshTools(runtime).associateBy { it.id }
            val args = """{"host":"test","remote_path":"/remote","local_path":"/local"}"""
            val identity = hosts.host.executionIdentity()
            fun target(arguments: String = args, tool: String = "sftp_upload", id: SshExecutionIdentity = identity) =
                ToolExecutionTargets.extract(tools.getValue(tool).toProtocolTool(), arguments, sshIdentities = mapOf("test" to id))
            val original = target()
            assertThat(original).isNotNull()
            assertThat(ToolExecutionTargets.canRemember(requireNotNull(original))).isFalse()
            assertThat(target(tool = "sftp_download")).isNotEqualTo(original)
            assertThat(target(args.replace("/local", "/other"))).isNotEqualTo(original)
            assertThat(target(args.replace("/remote", "/other"))).isNotEqualTo(original)
            assertThat(target(args.dropLast(1) + ",\"overwrite\":true}")).isNotEqualTo(original)
            assertThat(target(args.dropLast(1) + ",\"local_scope\":\"linux\"}")).isNotEqualTo(original)
            assertThat(target(id = identity.copy(digest = identity.digest + "changed"))).isNotEqualTo(original)
        }
    }

    @Test fun `上传超限在建立连接与打开源流前失败`() = runBlocking<Unit> {
        val hosts = MemorySshHosts().apply { host = SshHost(alias = "test", hostname = "localhost", username = "test") }
        val local = object : SftpLocalFile {
            override suspend fun stat() = SftpLocalStat(MAX_SFTP_TRANSFER_BYTES + 1, "large")
            override suspend fun <T> read(reader: (java.io.InputStream) -> T): T = error("不得读取")
            override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit, beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (java.io.OutputStream) -> String): String = error("不得写入")
        }
        SshRuntime(hosts).use { runtime ->
            val failure = runCatching { runtime.transfer("agent:s", "test", "/data", local, true, false, SshExecControl(60)) }.exceptionOrNull()
            assertThat((failure as SshFailure).code).isEqualTo("TRANSFER_TOO_LARGE")
        }
    }
    @Test fun `下载执行拒绝审批后主机身份变化`() = runBlocking<Unit> {
        val hosts = MemorySshHosts().apply { host = SshHost(alias = "test", hostname = "localhost", username = "test") }
        val root = java.nio.file.Files.createTempDirectory("sftp-approval").toFile()
        try {
            val files = com.promenar.infinity.data.linux.LinuxFileSystemImpl(root, { true }, com.promenar.infinity.data.linux.TestLinuxFileMetadata)
            SshRuntime(hosts).use { runtime ->
                val tool = sshTools(runtime, transferAccess = com.promenar.infinity.ui.chat.manager.skills.SftpTransferAccess(linux = files, linuxAvailable = { true })).single { it.name == "sftp_download" }
                val args = Json.parseToJsonElement("""{"host":"test","local_path":"/a","remote_path":"/b","local_scope":"linux"}""").jsonObject
                val approved = ToolExecutionTargets.extract(tool.toProtocolTool(), args.toString(), mapOf("sftp:local-identity" to "linux:root"), mapOf("test" to hosts.host.executionIdentity()))
                hosts.host = hosts.host.copy(username = "changed")
                val context = object : SkillExecutionContext {
                    override val sessionId = "s"; override val agentId = "a"; override val workspacePath: String? = null; override val workspaceRootUuid = "root"
                    override val runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX
                    override val isApprovalBoundExecution = true
                    override val approvedExecutionTarget = approved
                }
                val result = tool.execute(args, context)
                assertThat(result.status).isEqualTo("error")
                assertThat(result.content).contains("HOST_AUTHORIZATION_CHANGED")
            }
        } finally { root.deleteRecursively() }
    }
}
