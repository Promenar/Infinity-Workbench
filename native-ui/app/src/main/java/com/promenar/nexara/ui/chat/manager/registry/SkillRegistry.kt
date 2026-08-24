package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.serialization.json.JsonObject

interface SkillRegistry {
    fun getSkill(name: String): SkillDefinition?
    fun getSkillByRuntimeToolId(runtimeToolId: String): SkillDefinition? =
        getAllSkills().singleOrNull { it.runtimeToolId == runtimeToolId }
            ?: getSkill(runtimeToolId)?.takeIf { it.runtimeToolId == runtimeToolId }
    fun getAllSkills(): List<SkillDefinition>
    fun getAllTools(allowedIds: List<String>? = null): List<ProtocolTool>
}

interface SkillDefinition {
    val id: String
    val runtimeToolId: String get() = id
    val sourceId: String get() = "builtin"
    val name: String
    val description: String
    val mcpServerId: String?
    val parametersSchema: String
    val risk: ToolRisk get() = ToolRisk.UNKNOWN
    suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult
}

fun SkillDefinition.toProtocolTool(): ProtocolTool = ProtocolTool(
    type = "function",
    function = ProtocolToolFunction(
        name = name,
        description = description,
        parameters = parametersSchema,
    ),
    risk = risk,
    runtimeToolId = runtimeToolId,
    sourceId = sourceId,
    mcpServerId = mcpServerId,
)

interface SkillExecutionContext {
    val sessionId: String
    val agentId: String
    val workspacePath: String?
    val workspaceRootUuid: String
}
