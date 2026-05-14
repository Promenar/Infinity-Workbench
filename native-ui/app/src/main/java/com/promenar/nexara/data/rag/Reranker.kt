package com.promenar.nexara.data.rag

import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
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
    private val modelId: String,
    private val llmProtocol: LlmProtocol? = null,
    private val llmModelId: String? = null
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
        if (documents.size <= 1) return documents

        val apiResult = rerankViaApi(query, documents, topK)
        if (apiResult != null) return apiResult

        return rerankViaLlm(query, documents, topK)
    }

    private suspend fun rerankViaApi(
        query: String,
        documents: List<SearchResult>,
        topK: Int
    ): List<SearchResult>? {
        if (baseUrl.isBlank() || apiKey.isBlank()) return null

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

            if (!response.status.isSuccess()) return null

            val responseText = response.bodyAsText()
            val jsonResponse = json.parseToJsonElement(responseText).jsonObject
            val results = jsonResponse["results"]?.jsonArray ?: return null

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

            if (reorderedResults.isEmpty()) null else reorderedResults
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun rerankViaLlm(
        query: String,
        documents: List<SearchResult>,
        topK: Int
    ): List<SearchResult> {
        val protocol = llmProtocol ?: return documents.take(topK)

        return try {
            val prompt = buildRerankPrompt(query, documents)
            val request = PromptRequest(
                messages = listOf(ProtocolMessage(role = "user", content = prompt)),
                model = llmModelId ?: "default",
                temperature = 0.0,
                stream = false
            )

            val response = protocol.sendPromptSync(request)
            parseRerankScores(response.content, documents, topK)
        } catch (e: Exception) {
            documents.take(topK)
        }
    }

    private fun buildRerankPrompt(query: String, candidates: List<SearchResult>): String {
        val sb = StringBuilder()
        sb.appendLine("对以下文档片断与查询「$query」的相关性进行评分（0-10分），")
        sb.appendLine("返回 JSON 数组 [{ \"index\": 序号, \"score\": 分数 }]，仅返回 JSON：")
        sb.appendLine()
        candidates.forEachIndexed { i, c ->
            sb.appendLine("[${i}] ${c.content.take(300)}")
        }
        return sb.toString()
    }

    private fun parseRerankScores(
        response: String,
        candidates: List<SearchResult>,
        topK: Int
    ): List<SearchResult> {
        val jsonRegex = Regex("""\[\s*\{.*?\}\s*\]""", RegexOption.DOT_MATCHES_ALL)
        val match = jsonRegex.find(response) ?: return candidates.take(topK)

        val scores = try {
            json.decodeFromString<List<RerankScore>>(match.value)
        } catch (e: Exception) {
            return candidates.take(topK)
        }

        return candidates.mapIndexed { i, c ->
            val score = scores.find { it.index == i }?.score ?: 5.0
            c.copy(
                similarity = (score.toFloat() / 10f).coerceIn(0f, 1f),
                originalSimilarity = c.similarity
            )
        }.sortedByDescending { it.similarity }.take(topK)
    }

    @Serializable
    data class RerankScore(val index: Int, val score: Double)
}
