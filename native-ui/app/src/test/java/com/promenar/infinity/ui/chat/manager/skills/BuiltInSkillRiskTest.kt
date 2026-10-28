package com.promenar.infinity.ui.chat.manager.skills

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import io.mockk.mockk
import org.junit.Test
import kotlinx.serialization.json.JsonObject

class BuiltInSkillRiskTest {
    @Test
    fun mutatingBuiltInsDeclareExplicitRiskWhileUnknownDefinitionsFailClosed() {
        val definitions = listOf(
            FileWriteSkill(mockk<IFileOperationRepository>()) to ToolRisk.FILE_WRITE,
            FilePatchSkill(mockk<IFileOperationRepository>()) to ToolRisk.PATCH,
            ExecJsSkill(mockk<com.promenar.infinity.data.script.ScriptToolRunner>()) to ToolRisk.SCRIPT,
            RunScriptSkill(mockk<com.promenar.infinity.data.script.ScriptToolRunner>()) to ToolRisk.SCRIPT,
            DropPlanSkill(mockk<ITaskRepository>()) to ToolRisk.SESSION_STATE,
            ImageGenerationSkill(mockk<Context>(), mockk<ProviderManager>()) to ToolRisk.EXTERNAL_WRITE,
            InitializePlanSkill(mockk<ITaskRepository>()) to ToolRisk.SESSION_STATE,
            UpdatePlanSkill(mockk<ITaskRepository>()) to ToolRisk.SESSION_STATE,
            CreateFileSkill(mockk<IWorkspaceRepository>(), mockk<IFileOperationRepository>()) to ToolRisk.FILE_WRITE,
            CreateDirectorySkill(mockk<IWorkspaceRepository>()) to ToolRisk.FILE_WRITE,
            MoveFileSkill(mockk<IWorkspaceRepository>(), mockk<FileIndexEventSink>()) to ToolRisk.FILE_WRITE,
            DeleteFileSkill(mockk<IWorkspaceRepository>()) to ToolRisk.DELETE,
        )

        definitions.forEach { (definition, risk) -> assertThat(definition.risk).isEqualTo(risk) }
        assertThat(object : SkillDefinition {
            override val id = "custom"
            override val name = "custom"
            override val description = "custom"
            override val parametersSchema = "{}"
            override val mcpServerId: String? = "mcp"
            override suspend fun execute(
                args: JsonObject,
                context: com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext,
            ) = com.promenar.infinity.data.model.ToolResult("id", "ok")
        }.risk).isEqualTo(ToolRisk.UNKNOWN)
    }
}
