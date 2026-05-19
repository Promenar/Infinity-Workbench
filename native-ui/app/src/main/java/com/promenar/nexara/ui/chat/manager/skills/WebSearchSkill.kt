package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.search.DuckDuckGoProvider
import com.promenar.nexara.data.remote.search.SearXNGProvider
import com.promenar.nexara.data.remote.search.TavilyProvider
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import io.ktor.client.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class WebSearchSkill(
    private val context: Context,
    private val httpClient: HttpClient
) : SkillDefinition {
    override val id = "web_search"
    override val name = "web_search"
    override val description = "Search the web for real-time information, news, or specific topics. Use this when your internal knowledge is outdated or when you need up-to-date facts."
    override val mcpServerId: String? = null
    
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
        args: Map<String, Any>,
        context: SkillExecutionContext
    ): ToolResult {
        val query = args["query"]?.toString() ?: return ToolResult(id = "err", content = "Missing query argument", status = "error")
        
        val provider = getActiveProvider()
        return try {
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
        } catch (e: Exception) {
            ToolResult(
                id = "search_${System.currentTimeMillis()}",
                content = "Search failed: ${e.message}",
                status = "error"
            )
        }
    }

    private fun getActiveProvider(): WebSearchProvider {
        val prefs = context.getSharedPreferences("nexara_search", Context.MODE_PRIVATE)
        val engine = prefs.getString("search_engine", "duckduckgo") ?: "duckduckgo"
        val maxResults = prefs.getInt("result_count", 5)
        val includeDomains = parseDomainList(prefs, "include_domains")
        val excludeDomains = parseDomainList(prefs, "exclude_domains")
        
        return when (engine) {
            "searxng" -> {
                val url = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be"
                SearXNGProvider(httpClient, url, maxResults, includeDomains, excludeDomains)
            }
            "tavily" -> {
                val key = prefs.getString("tavily_api_key", "") ?: ""
                val depth = prefs.getString("search_depth", "advanced") ?: "advanced"
                TavilyProvider(httpClient, key, depth, maxResults, includeDomains, excludeDomains)
            }
            else -> DuckDuckGoProvider(httpClient, maxResults, includeDomains, excludeDomains)
        }
    }

    private fun parseDomainList(prefs: android.content.SharedPreferences, key: String): List<String> {
        val json = prefs.getString(key, "[]") ?: "[]"
        return try { Json.decodeFromString(json) } catch (_: Exception) { emptyList() }
    }
}
