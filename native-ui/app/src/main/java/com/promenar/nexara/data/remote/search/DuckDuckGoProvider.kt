package com.promenar.nexara.data.remote.search

import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup

class DuckDuckGoProvider(
    private val httpClient: HttpClient,
    private val maxResults: Int = 8,
    private val includeDomains: List<String> = emptyList(),
    private val excludeDomains: List<String> = emptyList()
) : WebSearchProvider {
    override suspend fun search(query: String): Pair<String, List<Citation>> {
        return try {
            val enhancedQuery = buildString {
                append(query)
                includeDomains.forEach { append(" site:$it") }
            }

            val response = httpClient.get("https://html.duckduckgo.com/html/") {
                parameter("q", enhancedQuery)
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            }
            
            val html = response.bodyAsText()
            val doc = Jsoup.parse(html)
            val results = doc.select(".result")
            
            val citations = mutableListOf<Citation>()
            val contextBuilder = StringBuilder()
            
            results.forEach { element ->
                if (citations.size >= maxResults) return@forEach
                
                val title = element.select(".result__a").text()
                val url = element.select(".result__a").attr("href")
                val snippet = element.select(".result__snippet").text()
                
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    if (excludeDomains.any { domain -> url.contains(domain, ignoreCase = true) }) return@forEach
                    val citation = Citation(title = title, url = url, source = "DuckDuckGo")
                    citations.add(citation)
                    contextBuilder.append("[${citations.size}] ${citation.title}\n${citation.url}\n$snippet\n\n")
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
