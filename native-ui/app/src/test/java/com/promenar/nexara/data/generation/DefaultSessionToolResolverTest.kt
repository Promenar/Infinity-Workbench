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
    fun `resolve exposes safe read and external write tools for session with legacy empty selection`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf(
            "calculator",
            "image_generation",
            "file_write",
        )

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent", activeSkillIds = emptyList())

        val resolved = resolver.resolve(session)
        val resolvedIds = resolved.map { it.runtimeToolId }

        assertThat(resolvedIds).containsExactly("calculator", "image_generation")
        assertThat(resolvedIds).doesNotContain("write_file")
    }

    @Test
    fun `resolve filters out tools not enabled in settings`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf("calculator")

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent", activeSkillIds = emptyList())

        val resolved = resolver.resolve(session)
        assertThat(resolved.map { it.runtimeToolId }).containsExactly("calculator")
    }

    @Test
    fun `resolve allows file write tool when session explicitly selects it`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf(
            "calculator",
            "image_generation",
            "file_write",
        )

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent", activeSkillIds = listOf("file_write"))

        val resolved = resolver.resolve(session)
        val resolvedIds = resolved.map { it.runtimeToolId }

        assertThat(resolvedIds).containsExactly("calculator", "image_generation", "write_file")
    }
}
