package com.promenar.nexara.ui.chat.manager

import android.content.Context
import com.promenar.nexara.data.remote.search.DuckDuckGoProvider
import com.promenar.nexara.data.remote.search.SearXNGProvider
import com.promenar.nexara.data.remote.search.TavilyProvider
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

class WebSearchContextProvider(
    private val context: Context,
    private val httpClient: HttpClient
) : WebSearchProvider {

    private val prefs get() = context.getSharedPreferences("nexara_search", Context.MODE_PRIVATE)

    override suspend fun search(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>> {
        val provider = getActiveProvider()
        return provider.search(query)
    }

    private fun getActiveProvider(): WebSearchProvider {
        val engine = prefs.getString("search_engine", "duckduckgo") ?: "duckduckgo"
        val maxResults = prefs.getInt("result_count", 5)
        val includeDomains = parseDomainList("include_domains")
        val excludeDomains = parseDomainList("exclude_domains")

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

    private fun parseDomainList(key: String): List<String> {
        val json = prefs.getString(key, "[]") ?: "[]"
        return try { Json.decodeFromString(json) } catch (_: Exception) { emptyList() }
    }
}
