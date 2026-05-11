package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.search.TavilyProvider
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.*
import android.content.Context
import kotlinx.serialization.json.Json

class WebSearchTavilySkill(
    private val context: Context,
    private val httpClient: HttpClient
) : SkillDefinition {
    override val id = "search_tavily"
    override val name = "Tavily Search"
    override val description = "Deep search using Tavily AI. Best for research and complex questions."
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
        val key = prefs.getString("tavily_api_key", "") ?: ""
        val depth = prefs.getString("search_depth", "advanced") ?: "advanced"
        val maxResults = prefs.getInt("result_count", 5)
        val includeDomains = parseDomainList(prefs, "include_domains")
        val excludeDomains = parseDomainList(prefs, "exclude_domains")
        
        val provider = TavilyProvider(httpClient, key, depth, maxResults, includeDomains, excludeDomains)
        return try {
            val (results, citations) = provider.search(query)
            ToolResult(
                id = "search_tavily_${System.currentTimeMillis()}",
                content = results,
                status = "success",
                data = citations.joinToString("\n") { "${it.title}: ${it.url}" }
            )
        } catch (e: Exception) {
            ToolResult(id = "err", content = "Tavily Search failed: ${e.message}", status = "error")
        }
    }

    private fun parseDomainList(prefs: android.content.SharedPreferences, key: String): List<String> {
        val json = prefs.getString(key, "[]") ?: "[]"
        return try { Json.decodeFromString(json) } catch (_: Exception) { emptyList() }
    }
}
