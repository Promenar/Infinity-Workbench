package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.skills.sshTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SshToolsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "chat"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    @Test fun `全部SSH工具均能通过生产调用预检`() {
        val store = MemorySshHosts().apply { host = SshHost(alias = "termux", hostname = "127.0.0.1", username = "u") }
        val arguments = mapOf(
            "ssh_exec" to """{"host":"termux","command":"whoami"}""",
            "sftp_list" to """{"host":"termux","path":"/"}""",
            "sftp_read" to """{"host":"termux","path":"/note.txt"}""",
            "sftp_write" to """{"host":"termux","path":"/note.txt","content":"正文"}""",
            "ssh_session_open" to """{"host":"termux"}""",
            "ssh_session_send" to """{"session_id":"pty","input":"whoami\n"}""",
            "ssh_session_read" to """{"session_id":"pty"}""",
            "ssh_session_close" to """{"session_id":"pty"}""",
        )
        SshRuntime(store).use { runtime ->
            sshTools(runtime).forEach { tool ->
                val result = ToolInvocationIdentityFactory.fromPreparedToolCall(
                    ToolCall("call", tool.name, arguments.getValue(tool.name)), tool.toProtocolTool(), requiresApproval = true,
                )
                assertWithMessage("%s：%s", tool.name, result).that(result)
                    .isInstanceOf(ToolInvocationIdentityResolution.Valid::class.java)
                assertThat((result as ToolInvocationIdentityResolution.Valid).identity.requiresApproval).isTrue()
            }
        }
    }

    @Test fun `SSH工具仍在运行时拒绝越界数值且不连接主机`() = runBlocking {
        val store = MemorySshHosts().apply { host = SshHost(alias = "termux", hostname = "127.0.0.1", username = "u") }
        val cases = listOf(
            Triple("ssh_exec", """{"host":"termux","command":"whoami","timeout_seconds":0}""", "INVALID_TIMEOUT"),
            Triple("ssh_exec", """{"host":"termux","command":"whoami","timeout_seconds":601}""", "INVALID_TIMEOUT"),
            Triple("ssh_exec", """{"host":"termux","command":"whoami","max_output_chars":0}""", "INVALID_OUTPUT_LIMIT"),
            Triple("ssh_exec", """{"host":"termux","command":"whoami","max_output_chars":256001}""", "INVALID_OUTPUT_LIMIT"),
            Triple("sftp_read", """{"host":"termux","path":"/note.txt","offset":-1}""", "INVALID_READ_RANGE"),
            Triple("sftp_read", """{"host":"termux","path":"/note.txt","offset":16777217}""", "INVALID_READ_RANGE"),
            Triple("sftp_read", """{"host":"termux","path":"/note.txt","limit":1}""", "INVALID_READ_RANGE"),
            Triple("sftp_read", """{"host":"termux","path":"/note.txt","limit":65537}""", "INVALID_READ_RANGE"),
            Triple("ssh_session_read", """{"session_id":"missing","wait_ms":-1}""", "INVALID_WAIT"),
            Triple("ssh_session_read", """{"session_id":"missing","wait_ms":5001}""", "INVALID_WAIT"),
        )
        SshRuntime(store).use { runtime ->
            val tools = sshTools(runtime).associateBy { it.name }
            cases.forEach { (name, arguments, code) ->
                val result = tools.getValue(name).execute(Json.parseToJsonElement(arguments).jsonObject, context)
                assertWithMessage(result.content).that(result.status).isEqualTo("error")
                val body = Json.parseToJsonElement(result.content).jsonObject
                assertThat(body.getValue("error_code").jsonPrimitive.content).isEqualTo("SSH_$code")
                assertThat(body.getValue("guidance").jsonPrimitive.content).isNotEmpty()
            }
        }
    }
    @Test fun `工具拒绝原地址未知字段和凭据而非转发到底层`() = runBlocking {
        val store = MemorySshHosts().apply { host = SshHost(alias="termux", hostname="127.0.0.1", username="u") }
        SshRuntime(store).use { runtime ->
            val exec = sshTools(runtime).single { it.name == "ssh_exec" }
            listOf(
                """{"host":"127.0.0.1","command":"whoami"}""",
                """{"host":"termux","command":"whoami","password":"synthetic-secret"}""",
                """{"host":"termux","command":"whoami","timeout_seconds":601}""",
                """{"host":"termux","command":"whoami","max_output_chars":0}""",
            ).forEach { arguments ->
                val result = exec.execute(Json.parseToJsonElement(arguments).jsonObject, context)
                assertThat(result.status).isEqualTo("error")
                assertThat(result.content).doesNotContain("synthetic-secret")
            }
            val tools = sshTools(runtime).associateBy { it.name }
            assertThat(tools.getValue("sftp_write").risk).isEqualTo(ToolRisk.EXTERNAL_WRITE)
            assertThat(tools.getValue("sftp_list").risk).isEqualTo(ToolRisk.SAFE_READ)
            assertThat(tools.getValue("ssh_session_send").risk).isEqualTo(ToolRisk.PROCESS)
            assertThat(tools.getValue("ssh_session_read").risk).isEqualTo(ToolRisk.SESSION_STATE)
        }
    }
}
