package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 工作区状态与切换放在会话标题下方的状态行，文件页只负责浏览文件。 */
class WorkspaceStatusLineContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/nexara/ui")

    private fun source(relativePath: String): String =
        Files.readAllBytes(sourceRoot.resolve(relativePath)).toString(Charsets.UTF_8)

    @Test
    fun `顶栏状态行显示工作区并打开可达范围面板`() {
        val screen = source("chat/ChatScreen.kt")
        assertThat(screen).contains("UiTags.CHAT_WORKSPACE_LINE")
        assertThat(screen).contains("onWorkspaceStatus")
        val route = source("chat/ChatRoute.kt")
        assertThat(route).contains("AgentReachSheet(")
        assertThat(route).contains("WorkspacePickerSheet(")
    }

    @Test
    fun `文件页不再有顶部工作区栏`() {
        val explorer = source("chat/ResourceExplorerSheet.kt")
        assertThat(explorer).doesNotContain("SessionWorkspaceBar")
        assertThat(explorer).doesNotContain("WorkspacePickerSheet(")
        assertThat(explorer).doesNotContain("AgentReachSheet(")
    }

    @Test
    fun `可达范围面板的工作区一节提供切换`() {
        val reach = source("workspace/AgentReachSheet.kt")
        assertThat(reach).contains("agent_reach_workspace_switch")
    }
}
