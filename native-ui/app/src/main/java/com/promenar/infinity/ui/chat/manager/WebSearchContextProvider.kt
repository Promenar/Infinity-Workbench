package com.promenar.infinity.ui.chat.manager

import android.content.Context
import com.promenar.infinity.data.remote.search.DuckDuckGoProvider
import com.promenar.infinity.data.remote.search.KeenableProvider
import com.promenar.infinity.data.remote.search.SearXNGProvider
import com.promenar.infinity.data.remote.search.TavilyProvider
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class WebSearchContextProvider(
    private val context: Context,
    private val httpClient: HttpClient,
    private val secretStore: SecretStore,
) : WebSearchProvider {

    private val prefs get() = context.getSharedPreferences("infinity_search", Context.MODE_PRIVATE)

    override suspend fun search(query: String): Pair<String, List<com.promenar.infinity.data.model.Citation>> = withContext(Dispatchers.IO) {
        val provider = getActiveProvider()
        provider.search(query)
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
                val key = readSecret(SecretCatalog.tavilyApiKey)
                val depth = prefs.getString("search_depth", "advanced") ?: "advanced"
                TavilyProvider(httpClient, key, depth, maxResults, includeDomains, excludeDomains)
            }
            "keenable" -> KeenableProvider(
                httpClient, readSecret(SecretCatalog.keenableApiKey), maxResults, includeDomains, excludeDomains,
            )
            else -> DuckDuckGoProvider(httpClient, maxResults, includeDomains, excludeDomains)
        }
    }

    private fun readSecret(id: SecretId): String = try {
        val bytes = secretStore.get(id)
        if (bytes == null) "" else try {
            bytes.toString(Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw IllegalStateException("无法安全读取搜索凭据。")
    }

    private fun parseDomainList(key: String): List<String> {
        val json = prefs.getString(key, "[]") ?: "[]"
        return try { Json.decodeFromString(json) } catch (_: Exception) { emptyList() }
    }
}
