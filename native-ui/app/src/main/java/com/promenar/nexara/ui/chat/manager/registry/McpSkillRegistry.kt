package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.ui.chat.manager.skills.McpSkill
import com.promenar.nexara.data.remote.mcp.McpClient
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import io.ktor.client.HttpClient

class McpSkillRegistry(
    private val repository: SkillRepository,
    private val httpClient: HttpClient
) : SkillRegistry {
    private val mcpSkills = mutableMapOf<String, SkillDefinition>()

    override fun getSkill(name: String): SkillDefinition? {
        return mcpSkills[name]
    }

    override fun getAllSkills(): List<SkillDefinition> {
        return mcpSkills.values.toList()
    }

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        val filteredSkills = if (allowedIds == null) {
            mcpSkills.values
        } else {
            mcpSkills.values.filter { it.id in allowedIds }
        }
        
        return filteredSkills.map { skill ->
            ProtocolTool(
                type = "function",
                function = ProtocolToolFunction(
                    name = skill.name,
                    description = skill.description,
                    parameters = skill.parametersSchema
                )
            )
        }
    }

    // This would be called whenever MCP servers are updated or synced
    fun updateMcpTools(serverName: String, tools: List<com.promenar.nexara.data.remote.mcp.McpTool>, serverUrl: String) {
        tools.forEach { tool ->
            mcpSkills[tool.name] = McpSkill(
                mcpClient = McpClient(httpClient, serverUrl),
                name = tool.name,
                description = tool.description ?: "",
                parametersSchema = tool.inputSchema?.toString() ?: "{}"
            )
        }
    }
}
