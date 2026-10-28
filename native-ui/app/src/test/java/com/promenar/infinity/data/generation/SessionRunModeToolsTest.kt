package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class SessionRunModeToolsTest {
    @Test fun `会话定义转换保留底层指纹且再次转换保持一致`() {
        val original = ProtocolTool(function = ProtocolToolFunction("shell_exec", "base", "{\"type\":\"object\"}"), runtimeToolId = "shell_exec")
        val linux = SessionRunModeToolDefinitions.forSession(original, SessionRunMode.LINUX)
        assertThat(linux.baseDefinitionDigest).isNotNull()
        assertThat(SessionRunModeToolDefinitions.forSession(linux, SessionRunMode.LINUX)).isEqualTo(linux)
        val changed = SessionRunModeToolDefinitions.forSession(original.copy(function = original.function.copy(description = "changed")), SessionRunMode.LINUX)
        assertThat(com.promenar.infinity.data.repository.ToolInvocationIdentityFactory.definitionDigest(linux))
            .isNotEqualTo(com.promenar.infinity.data.repository.ToolInvocationIdentityFactory.definitionDigest(changed))
        assertThat(SessionRunModeToolDefinitions.forSession(original, SessionRunMode.BUILTIN)).isSameInstanceAs(original)
    }

    @Test fun `模式切换后下一张工具表和命令描述立即对应会话`() {
        val settings = mockk<SharedPreferences>(relaxed = true)
        every { settings.getStringSet(any(), any()) } returns null
        every { settings.getBoolean("termux_integration_enabled", false) } returns true
        val names = listOf("shell_exec", "python_exec", "python_sandbox", "exec_js", "run_script", "termux_exec", "read_file", "web_search", "job_wait")
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools(null) } returns names.map { name -> ProtocolTool(function = ProtocolToolFunction(name, "builtin description", "{}"), runtimeToolId = name) }
        val resolver = DefaultSessionToolResolver(settings, registry)
        val session = Session("s", "a", runMode = SessionRunMode.LINUX)
        val linux = resolver.resolve(session)
        assertThat(linux.map { it.function.name }).containsExactly("shell_exec", "read_file", "web_search", "job_wait")
        assertThat(linux.single { it.function.name == "shell_exec" }.function.description).contains("Alpine Linux")
        val builtin = resolver.resolve(session.copy(runMode = SessionRunMode.BUILTIN))
        assertThat(builtin.map { it.function.name }).containsAtLeastElementsIn(names)
        assertThat(builtin.single { it.function.name == "shell_exec" }.function.description).isEqualTo("builtin description")
    }
}
