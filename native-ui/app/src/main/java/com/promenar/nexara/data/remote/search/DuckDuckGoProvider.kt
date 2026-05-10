package com.promenar.nexara.data.remote.search

import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup

class DuckDuckGoProvider(private val httpClient: HttpClient) : WebSearchProvider {
    override suspend fun search(query: String): Pair<String, List<Citation>> {
        return try {
            // Use DuckDuckGo HTML (Lite) version
            val response = httpClient.get("https://html.duckduckgo.com/html/") {
                parameter("q", query)
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            }
            
            val html = response.bodyAsText()
            val doc = Jsoup.parse(html)
            val results = doc.select(".result")
            
            val citations = mutableListOf<Citation>()
            val contextBuilder = StringBuilder()
            
            results.take(8).forEachIndexed { index, element ->
                val title = element.select(".result__a").text()
                val url = element.select(".result__a").attr("href")
                val snippet = element.select(".result__snippet").text()
                
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    citations.add(Citation(title = title, url = url, source = "DuckDuckGo"))
                    contextBuilder.append("[${index + 1}] $title\n$url\n$snippet\n\n")
                }
            }
            
            if (citations.isEmpty()) {
                "No results found." to emptyList()
            } else {
                contextBuilder.toString() to citations
            }
        } catch (e: Exception) {
            "Search failed: ${e.message}" to emptyList()
        }
    }
}
