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
            
            ToolResult(
                id = "search_${System.currentTimeMillis()}",
                content = results,
                status = if (results.isNotEmpty()) "success" else "error",
                data = if (citations.isNotEmpty()) citations.joinToString("\n") { "${it.title}: ${it.url}" } else null
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
        
        return when (engine) {
            "duckduckgo" -> DuckDuckGoProvider(httpClient)
            "searxng" -> {
                val url = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be"
                SearXNGProvider(httpClient, url)
            }
            "tavily" -> {
                val key = prefs.getString("tavily_api_key", "") ?: ""
                TavilyProvider(httpClient, key)
            }
            else -> DuckDuckGoProvider(httpClient)
        }
    }
}
