package com.promenar.nexara.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.sshTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class SshToolsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "chat"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
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
