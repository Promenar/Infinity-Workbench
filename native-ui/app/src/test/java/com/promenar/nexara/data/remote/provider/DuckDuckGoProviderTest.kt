package com.promenar.nexara.data.remote.provider

import com.promenar.nexara.data.remote.search.DuckDuckGoProvider
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DuckDuckGoProviderTest {

    @Test
    fun testRealSearchWithModifiedProvider() = runBlocking {
        val httpClient = HttpClient(OkHttp)
        val provider = DuckDuckGoProvider(httpClient = httpClient, maxResults = 8)
        
        println("--- STARTING REAL DUCKDUCKGO SEARCH WITH MODIFIED PROVIDER ---")
        val (resultsStr, citations) = provider.search("南京明天天气预报")
        
        println("Citations parsed size: ${citations.size}")
        citations.forEachIndexed { index, citation ->
            println("[$index] Title: ${citation.title}")
            println("      Url: ${citation.url}")
        }
        println("--- END ---")
        assert(citations.size > 1) { "Expected more than 1 citation, got ${citations.size}" }
    }
}
