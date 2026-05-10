package com.promenar.nexara.data.remote.search

import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class TavilyProvider(
    private val httpClient: HttpClient,
    private val apiKey: String
) : WebSearchProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): Pair<String, List<Citation>> {
        if (apiKey.isBlank()) return "Tavily API Key is missing." to emptyList()
        
        return try {
            val response = httpClient.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("api_key", apiKey)
                    put("query", query)
                    put("search_depth", "advanced")
                    put("max_results", 5)
                }.toString())
            }
            
            val responseText = response.bodyAsText()
            val root = json.parseToJsonElement(responseText).jsonObject
            val results = root["results"]?.jsonArray ?: emptyList<JsonElement>().toJsonArray()
            
            val citations = mutableListOf<Citation>()
            val contextBuilder = StringBuilder()
            
            results.forEachIndexed { index, element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val url = obj["url"]?.jsonPrimitive?.content ?: ""
                val content = obj["content"]?.jsonPrimitive?.content ?: ""
                
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    citations.add(Citation(title = title, url = url, source = "Tavily"))
                    contextBuilder.append("[${index + 1}] $title\n$url\n$content\n\n")
                }
            }
            
            contextBuilder.toString() to citations
        } catch (e: Exception) {
            "Tavily Search failed: ${e.message}" to emptyList()
        }
    }
    
    private fun List<JsonElement>.toJsonArray() = JsonArray(this)
}
