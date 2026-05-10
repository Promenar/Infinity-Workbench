package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.remote.mcp.McpClient
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.*

class McpSkill(
    override val id: String = "mcp_tool",
    override val name: String,
    override val description: String,
    override val parametersSchema: String,
    private val mcpClient: McpClient,
    override val mcpServerId: String? = null
) : SkillDefinition {

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        return try {
            val jsonArgs = Json.encodeToJsonElement(args)
            val result = mcpClient.callTool(name, jsonArgs)
            ToolResult(
                id = "mcp_${System.currentTimeMillis()}",
                content = result.toString(),
                status = "success"
            )
        } catch (e: Exception) {
            ToolResult(
                id = "mcp_${System.currentTimeMillis()}",
                content = "MCP Error: ${e.message}",
                status = "error"
            )
        }
    }
}
