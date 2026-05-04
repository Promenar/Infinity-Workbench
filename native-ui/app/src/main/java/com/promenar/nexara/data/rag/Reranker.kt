package com.promenar.nexara.data.rag

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class RerankClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelId: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun rerank(
        query: String,
        documents: List<SearchResult>,
        topK: Int
    ): List<SearchResult> {
        if (documents.isEmpty()) return documents

        return try {
            val cleanBase = baseUrl.trimEnd('/')
            val suffix = if (cleanBase.endsWith("/v1")) "/rerank" else "/v1/rerank"
            val endpoint = "$cleanBase$suffix"

            val requestBody = buildJsonObject {
                put("model", modelId)
                put("query", query)
                put("documents", buildJsonArray {
                    documents.forEach { doc -> add(doc.content) }
                })
                put("top_n", topK)
            }

            val response: HttpResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(json.encodeToString(JsonObject.serializer(), requestBody))
            }

            if (!response.status.isSuccess()) {
                return documents
            }

            val responseText = response.bodyAsText()
            val jsonResponse = json.parseToJsonElement(responseText).jsonObject
            val results = jsonResponse["results"]?.jsonArray ?: return documents

            val reorderedResults = mutableListOf<SearchResult>()
            for (resultItem in results) {
                val index = resultItem.jsonObject["index"]?.jsonPrimitive?.int ?: continue
                val score = resultItem.jsonObject["relevance_score"]?.jsonPrimitive?.float ?: continue
                if (index in documents.indices) {
                    val original = documents[index]
                    reorderedResults.add(
                        original.copy(
                            similarity = score,
                            originalSimilarity = original.similarity
                        )
                    )
                }
            }

            if (reorderedResults.isEmpty() && documents.isNotEmpty()) {
                documents.take(topK)
            } else {
                reorderedResults
            }
        } catch (e: Exception) {
            documents
        }
    }
}
