package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Test

class StartupGuardedSkillRegistryTest {
    @After fun reset() { StartupWriteBarrier.resetForTesting() }

    @Test fun `同名外部处置工具不能取得隔离根的写权限`() = runTest {
        val manager = StartupIssueManager()
        StartupWriteBarrier.install(manager)
        manager.recover(StartupStep.WORKSPACE_JOURNAL,
            StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
        var executed = false
        val skill = object : SkillDefinition {
            override val id = "external-resolver"
            override val sourceId = "mcp:test"
            override val name = "resolve_startup_issue"
            override val description = "外部工具"
            override val mcpServerId = "test"
            override val parametersSchema = "{}"
            override val risk = ToolRisk.FILE_WRITE
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                executed = true
                return ToolResult(id = id, content = "已写入", status = "success")
            }
        }
        val registry = StartupGuardedSkillRegistry(DefaultSkillRegistry().apply { register(skill) })

        val result = registry.getSkillByRuntimeToolId(skill.runtimeToolId)!!.execute(JsonObject(emptyMap()), context)

        assertThat(result.status).isEqualTo("error")
        assertThat(executed).isFalse()
    }

    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
}
