package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.domain.tool.ToolRisk

interface SkillRegistry {
    fun getSkill(name: String): SkillDefinition?
    fun getAllSkills(): List<SkillDefinition>
    fun getAllTools(allowedIds: List<String>? = null): List<ProtocolTool>
}

interface SkillDefinition {
    val id: String
    val name: String
    val description: String
    val mcpServerId: String?
    val parametersSchema: String
    val risk: ToolRisk get() = ToolRisk.UNKNOWN
    suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult
}

interface SkillExecutionContext {
    val sessionId: String
    val agentId: String
    val workspacePath: String?
    val workspaceRootUuid: String
}
