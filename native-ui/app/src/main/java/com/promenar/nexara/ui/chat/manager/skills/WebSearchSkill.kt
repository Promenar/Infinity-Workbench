package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.WebSearchContextProvider
import com.promenar.nexara.data.security.SecretStore
import io.ktor.client.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.promenar.nexara.ui.chat.manager.registry.stringArgument

class WebSearchSkill(
    private val context: Context,
    private val httpClient: HttpClient,
    private val secretStore: SecretStore,
) : SkillDefinition {
    override val id = "web_search"
    override val name = "web_search"
    override val description = "Search the web for real-time information, news, or specific topics. Use this when your internal knowledge is outdated or when you need up-to-date facts."
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query to look up on the web"
                }
            },
            "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(
        args: JsonObject,
        context: SkillExecutionContext
    ): ToolResult {
        val query = args.stringArgument("query") ?: return ToolResult(id = "err", content = "Missing query argument", status = "error")
        
        return try {
            val provider = WebSearchContextProvider(this.context, httpClient, secretStore)
            val (results, citations) = provider.search(query)
            
            val citationsJson = if (citations.isNotEmpty()) {
                try {
                    Json.encodeToString(citations)
                } catch (_: Exception) {
                    citations.joinToString("\n") { "${it.title}: ${it.url}" }
                }
            } else null

            ToolResult(
                id = "search_${System.currentTimeMillis()}",
                content = results,
                status = if (results.isNotEmpty()) "success" else "error",
                data = citationsJson
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult(
                id = "search_${System.currentTimeMillis()}",
                content = "Search failed: ${e.message}",
                status = "error"
            )
        }
    }

}
