package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.search.SearXNGProvider
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.*
import android.content.Context
import kotlinx.serialization.json.Json

class WebSearchSearXNGSkill(
    private val context: Context,
    private val httpClient: HttpClient
) : SkillDefinition {
    override val id = "search_searxng"
    override val name = "search_searxng"
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
        val maxResults = prefs.getInt("result_count", 5)
        val includeDomains = parseDomainList(prefs, "include_domains")
        val excludeDomains = parseDomainList(prefs, "exclude_domains")
        
        val provider = SearXNGProvider(httpClient, url, maxResults, includeDomains, excludeDomains)
        return try {
            val (results, citations) = provider.search(query)
            val citationData = citations.joinToString("\n") { "${it.title}: ${it.url}" }
            ToolResult(
                id = "search_searxng_${System.currentTimeMillis()}",
                content = results,
                data = citationData.ifEmpty { null },
                status = "success"
            )
        } catch (e: Exception) {
            ToolResult(id = "err", content = "SearXNG Search failed: ${e.message}", status = "error")
        }
    }

    private fun parseDomainList(prefs: android.content.SharedPreferences, key: String): List<String> {
        val json = prefs.getString(key, "[]") ?: "[]"
        return try { Json.decodeFromString(json) } catch (_: Exception) { emptyList() }
    }
}
