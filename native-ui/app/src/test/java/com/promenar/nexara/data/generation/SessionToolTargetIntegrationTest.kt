package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.domain.tool.ToolRisk
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionToolTargetIntegrationTest {
    private val session = Session(id = "session", agentId = "agent")
    private var mountId = "mount-1"
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { visible("session") } answers {
            listOf(ExternalMount(mountId, "docs", "saf_tree", "content://test/tree/docs", "session", false, 1))
        }
    }
    private val leases = mutableMapOf(("agent:session" to "pty") to "server")

    @Test
    fun `挂载路由与SSH别名命令和SFTP远端路径同时生效`() = runTest {
        assertThat(resolve("ssh_exec", """{"host":"server","command":"cat /mnt/docs/a.txt"}""", ToolRisk.PROCESS))
            .isEqualTo("""["process","server","cat /mnt/docs/a.txt"]""")
        listOf("sftp_list", "sftp_read", "sftp_write").forEach { name ->
            assertThat(resolve(name, """{"host":"server","path":"/mnt/docs/a.txt"}"""))
                .isEqualTo("""["remote_path","server","/mnt/docs/a.txt"]""")
        }
        val target = resolve("read_file", """{"path":"/mnt/docs/a.txt"}""")!!
        assertThat(target).contains("\"mountIds\":{\"docs\":\"mount-1\"}")
        mountId = "mount-2"
        assertThat(resolve("read_file", """{"path":"/mnt/docs/a.txt"}""")).isNotEqualTo(target)
    }

    @Test
    fun `SSH会话在挂载存在时绑定owner别名和输入且拒绝失效租约`() = runTest {
        val arguments = """{"session_id":"pty","input":"echo 中文\n"}"""
        assertThat(resolve("ssh_session_send", arguments))
            .isEqualTo("""["process","server","echo 中文\n"]""")
        leases.clear()
        leases["agent:other" to "pty"] = "server"
        assertThat(resolve("ssh_session_send", arguments)).isNull()
    }

    private suspend fun resolve(name: String, arguments: String, risk: ToolRisk = ToolRisk.UNKNOWN): String? =
        resolveSessionToolTarget(
            session, ProtocolTool(function = ProtocolToolFunction(name, "集成测试工具", "{}"), risk = risk),
            arguments, null, mounts,
        ) { owner, id -> leases[owner to id] }
}
