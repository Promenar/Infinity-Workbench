package com.promenar.nexara.data.remote.search

import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class SearXNGProvider(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://searx.be"
) : WebSearchProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): Pair<String, List<Citation>> {
        return try {
            val response = httpClient.get(baseUrl.trimEnd('/') + "/search") {
                parameter("q", query)
                parameter("format", "json")
                header("User-Agent", "Nexara-Native/1.0")
            }
            
            val responseText = response.bodyAsText()
            val root = json.parseToJsonElement(responseText).jsonObject
            val results = root["results"]?.jsonArray ?: emptyList<JsonElement>().toJsonArray()
            
            val citations = mutableListOf<Citation>()
            val contextBuilder = StringBuilder()
            
            results.take(8).forEachIndexed { index, element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val url = obj["url"]?.jsonPrimitive?.content ?: ""
                val snippet = obj["content"]?.jsonPrimitive?.content ?: ""
                
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    citations.add(Citation(title = title, url = url, source = "SearXNG"))
                    contextBuilder.append("[${index + 1}] $title\n$url\n$snippet\n\n")
                }
            }
            
            contextBuilder.toString() to citations
        } catch (e: Exception) {
            "SearXNG Search failed: ${e.message}" to emptyList()
        }
    }
    
    private fun List<JsonElement>.toJsonArray() = JsonArray(this)
}
