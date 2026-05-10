package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.search.SearXNGProvider
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.*
import android.content.Context

class WebSearchSearXNGSkill(
    private val context: Context,
    private val httpClient: HttpClient
) : SkillDefinition {
    override val id = "search_searxng"
    override val name = "SearXNG Search"
    override val description = "Privacy-focused meta-search using SearXNG instance."
    override val mcpServerId: String? = null
    
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query"
                }
            },
            "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(
        args: Map<String, Any>,
        context: SkillExecutionContext
    ): ToolResult {
        val query = args["query"]?.toString() ?: return ToolResult(id = "err", content = "Missing query", status = "error")
        val prefs = this.context.getSharedPreferences("nexara_search", Context.MODE_PRIVATE)
        val url = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be"
        
        val provider = SearXNGProvider(httpClient, url)
        return try {
            val (results, _) = provider.search(query)
            ToolResult(
                id = "search_searxng_${System.currentTimeMillis()}",
                content = results,
                status = "success"
            )
        } catch (e: Exception) {
            ToolResult(id = "err", content = "SearXNG Search failed: ${e.message}", status = "error")
        }
    }
}
