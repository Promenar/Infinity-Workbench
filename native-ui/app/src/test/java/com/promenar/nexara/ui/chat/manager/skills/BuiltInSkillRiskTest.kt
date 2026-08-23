package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import io.mockk.mockk
import org.junit.Test

class BuiltInSkillRiskTest {
    @Test
    fun mutatingBuiltInsDeclareExplicitRiskWhileUnknownDefinitionsFailClosed() {
        val definitions = listOf(
            FileWriteSkill(mockk<IFileOperationRepository>()) to ToolRisk.FILE_WRITE,
            FilePatchSkill(mockk<IFileOperationRepository>()) to ToolRisk.PATCH,
            ExecJsSkill(mockk<Context>()) to ToolRisk.SCRIPT,
            DropPlanSkill(mockk<ITaskRepository>()) to ToolRisk.DELETE,
            CreateToolSkill(mockk<SkillDao>()) to ToolRisk.FILE_WRITE,
            ImageGenerationSkill(mockk<Context>(), mockk<ProviderManager>()) to ToolRisk.EXTERNAL_WRITE,
            InitializePlanSkill(mockk<ITaskRepository>()) to ToolRisk.FILE_WRITE,
            UpdatePlanSkill(mockk<ITaskRepository>()) to ToolRisk.FILE_WRITE,
        )

        definitions.forEach { (definition, risk) -> assertThat(definition.risk).isEqualTo(risk) }
        assertThat(object : SkillDefinition {
            override val id = "custom"
            override val name = "custom"
            override val description = "custom"
            override val parametersSchema = "{}"
            override val mcpServerId: String? = "mcp"
            override suspend fun execute(
                args: Map<String, Any>,
                context: com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext,
            ) = com.promenar.nexara.data.model.ToolResult("id", "ok")
        }.risk).isEqualTo(ToolRisk.UNKNOWN)
    }
}
