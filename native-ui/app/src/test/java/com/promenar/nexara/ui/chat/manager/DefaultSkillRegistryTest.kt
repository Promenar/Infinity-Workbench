package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import org.junit.Before
import org.junit.Test

class DefaultSkillRegistryTest {
    private lateinit var registry: DefaultSkillRegistry

    private val testCalculatorSkill = object : SkillDefinition {
        override val id = "calculator"
        override val name = "calculator"
        override val description = "Evaluate math"
        override val mcpServerId: String? = null
        override val parametersSchema = "{}"
        override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext) = ToolResult("tc1", "2")
    }

    private val testFileListSkill = object : SkillDefinition {
        override val id = "list_files"
        override val name = "list_files"
        override val description = "List files"
        override val mcpServerId: String? = null
        override val parametersSchema = "{}"
        override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext) = ToolResult("tc2", "files")
    }

    private val testFileReadSkill = object : SkillDefinition {
        override val id = "read_file"
        override val name = "read_file"
        override val description = "Read file"
        override val mcpServerId: String? = null
        override val parametersSchema = "{}"
        override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext) = ToolResult("tc3", "content")
    }

    @Before
    fun setUp() {
        registry = DefaultSkillRegistry()
        registry.register(testCalculatorSkill)
        registry.register(testFileListSkill)
        registry.register(testFileReadSkill)
    }

    @Test
    fun getSkillByName() {
        val skill = registry.getSkill("list_files")
        assertThat(skill).isNotNull()
        assertThat(skill?.id).isEqualTo("list_files")
    }

    @Test
    fun getAllSkills() {
        val all = registry.getAllSkills()
        assertThat(all).hasSize(3)
    }

    @Test
    fun getAllToolsWithNullAllowedIds() {
        val tools = registry.getAllTools(null)
        assertThat(tools).hasSize(3)
        val names = tools.map { it.function.name }
        assertThat(names).containsExactly("calculator", "list_files", "read_file")
    }

    @Test
    fun getAllToolsWithStandardFiltering() {
        val tools = registry.getAllTools(listOf("calculator"))
        assertThat(tools).hasSize(1)
        assertThat(tools[0].function.name).isEqualTo("calculator")
    }

    @Test
    fun getAllToolsWithIdMapping() {
        // "file_list" should map to "list_files"
        // "file_read" should map to "read_file"
        val tools = registry.getAllTools(listOf("file_list", "file_read"))
        assertThat(tools).hasSize(2)
        val names = tools.map { it.function.name }
        assertThat(names).containsExactly("list_files", "read_file")
    }
}
