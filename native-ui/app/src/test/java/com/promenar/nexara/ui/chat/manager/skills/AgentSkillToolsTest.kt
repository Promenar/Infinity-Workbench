package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.skills.AgentSkillMetadata
import com.promenar.nexara.data.skills.AgentSkillStore
import com.promenar.nexara.data.skills.InMemoryPreferences
import com.promenar.nexara.ui.chat.manager.SystemPromptSections
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentSkillToolsTest {
    private val store = AgentSkillStore(Files.createTempDirectory("skills").toFile(), InMemoryPreferences())
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "r"
    }

    @Test
    fun `tools are advertised only while an enabled skill exists`() {
        val registry = AgentSkillToolRegistry(store)
        assertThat(registry.getAllTools(null)).isEmpty()

        store.installMarkdown("---\nname: demo\ndescription: Demo skill\n---\nDo it.")
        assertThat(registry.getAllTools(null).map { it.function.name })
            .containsExactly("activate_skill", "read_skill_file")

        store.setEnabled("demo", false)
        assertThat(registry.getAllTools(null)).isEmpty()
        assertThat(registry.getSkill("activate_skill")).isNotNull()
    }

    @Test
    fun `activate returns body and file list and rejects disabled skills`() = runTest {
        store.save(null, AgentSkillMetadata("demo", "Demo skill"), "Follow these steps.")
        val activate = ActivateSkillTool(store)

        val ok = activate.execute(skillArgs("name" to "demo"), context)
        store.setEnabled("demo", false)
        val disabled = activate.execute(skillArgs("name" to "demo"), context)

        assertThat(ok.status).isEqualTo("success")
        assertThat(ok.content).contains("Follow these steps.")
        assertThat(disabled.status).isEqualTo("error")
    }

    @Test
    fun `read skill file surfaces path errors as tool errors`() = runTest {
        store.save(null, AgentSkillMetadata("demo", "Demo skill"), "Body")

        val result = ReadSkillFileTool(store).execute(skillArgs("name" to "demo", "path" to "../x"), context)

        assertThat(result.status).isEqualTo("error")
    }

    @Test
    fun `system prompt catalog lists names and descriptions within budget`() {
        val skills = (1..60).map { AgentSkillMetadata("skill-$it", "Description $it") }

        val catalog = SystemPromptSections.skillCatalog(skills)

        assertThat(catalog).contains("## Skills")
        assertThat(catalog).contains("- skill-1: Description 1")
        assertThat(catalog).contains("10 more skills are installed")
        assertThat(SystemPromptSections.skillCatalog(emptyList())).isEmpty()
    }
}
