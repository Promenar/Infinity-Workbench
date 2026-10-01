package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class DefaultSessionToolResolverTest {

    private val settings = mockk<SharedPreferences>(relaxed = true)
    private val skillRegistry = mockk<SkillRegistry>(relaxed = true)

    private val safeReadTool = ProtocolTool(
        function = ProtocolToolFunction(name = "calculator", description = "Calc", parameters = "{}"),
        sourceId = "builtin",
        runtimeToolId = "calculator",
        risk = ToolRisk.SAFE_READ,
    )

    private val imageGenTool = ProtocolTool(
        function = ProtocolToolFunction(name = "generate_image", description = "Image Gen", parameters = "{}"),
        sourceId = "builtin",
        runtimeToolId = "image_generation",
        risk = ToolRisk.EXTERNAL_WRITE,
    )

    private val fileWriteTool = ProtocolTool(
        function = ProtocolToolFunction(name = "write_file", description = "Write file", parameters = "{}"),
        sourceId = "builtin",
        runtimeToolId = "write_file",
        risk = ToolRisk.FILE_WRITE,
    )

    @Before
    fun setUp() {
        every { skillRegistry.getAllTools(null) } returns listOf(
            safeReadTool,
            imageGenTool,
            fileWriteTool,
        )
    }

    @Test
    fun `empty session selection still exposes every globally enabled builtin tool`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf(
            "calculator",
            "image_generation",
            "file_write",
        )

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent", activeSkillIds = emptyList())

        val resolvedIds = resolver.resolve(session).map { it.runtimeToolId }

        assertThat(resolvedIds).containsExactly("calculator", "image_generation", "write_file")
    }

    @Test
    fun `custom skill selection never changes builtin tool exposure`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf("calculator")
        val customTool = ProtocolTool(
            function = ProtocolToolFunction(name = "my_tool", description = "Custom", parameters = "{}"),
            sourceId = "custom",
            runtimeToolId = "user_1",
            risk = ToolRisk.UNKNOWN,
        )
        every { skillRegistry.getAllTools(null) } returns listOf(safeReadTool, fileWriteTool, customTool)

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val empty = resolver.resolve(Session(id = "s1", agentId = "agent"))
        val selected = resolver.resolve(
            Session(id = "s2", agentId = "agent", activeSkillIds = listOf("user_1")),
        )

        assertThat(empty.map { it.runtimeToolId }).containsExactly("calculator")
        assertThat(selected.map { it.runtimeToolId }).containsExactly("calculator")
    }

    @Test
    fun `tools disabled for session exposes nothing`() {
        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent")
        val disabled = session.copy(options = session.options.copy(toolsEnabled = false))

        assertThat(resolver.resolve(disabled)).isEmpty()
    }

    @Test
    fun `resolve filters out tools not enabled in settings`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf("calculator")

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent", activeSkillIds = emptyList())

        val resolved = resolver.resolve(session)
        assertThat(resolved.map { it.runtimeToolId }).containsExactly("calculator")
    }

}
