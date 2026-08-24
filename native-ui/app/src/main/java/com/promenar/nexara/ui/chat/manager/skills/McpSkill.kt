package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.remote.mcp.McpClient
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

class McpSkill(
    override val id: String = "mcp_tool",
    override val name: String,
    override val description: String,
    override val parametersSchema: String,
    private val mcpClient: McpClient,
    override val mcpServerId: String? = null
) : SkillDefinition {
    override val sourceId: String = "mcp"

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        return try {
            val result = mcpClient.callTool(name, args)
            ToolResult(
                id = "mcp_${System.currentTimeMillis()}",
                content = result.toString(),
                status = "success"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult(
                id = "mcp_${System.currentTimeMillis()}",
                content = "MCP Error: ${e.message}",
                status = "error"
            )
        }
    }
}
