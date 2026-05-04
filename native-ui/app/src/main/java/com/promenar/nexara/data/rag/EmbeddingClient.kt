package com.promenar.nexara.data.rag

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*

class EmbeddingClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = "text-embedding-3-small",
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun embedDocuments(texts: List<String>): EmbeddingResult {
        if (texts.isEmpty()) throw IllegalArgumentException("No texts provided for embedding")

        val batchSize = 50
        val allEmbeddings = mutableListOf<FloatArray>()
        var totalTokens = 0

        for (i in texts.indices step batchSize) {
            val batch = texts.subList(i, minOf(i + batchSize, texts.size))
            val result = embedBatch(batch)
            allEmbeddings.addAll(result.embeddings)
            totalTokens += result.usage?.totalTokens ?: 0
        }

        return EmbeddingResult(
            embeddings = allEmbeddings,
            usage = if (totalTokens > 0) EmbeddingUsage(totalTokens) else null
        )
    }

    suspend fun embedQuery(text: String): Pair<FloatArray, EmbeddingUsage?> {
        val result = embedDocuments(listOf(text))
        return Pair(result.embeddings.first(), result.usage)
    }

    private suspend fun embedBatch(texts: List<String>): EmbeddingResult {
        val cleanBase = baseUrl.trimEnd('/')
        val endpoint = if (cleanBase.endsWith("/v1")) {
            "$cleanBase/embeddings"
        } else {
            "$cleanBase/v1/embeddings"
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray { texts.forEach { add(it) } })
        }

        val response: HttpResponse = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val responseText = response.bodyAsText()
        val jsonResponse = json.parseToJsonElement(responseText).jsonObject

        val data = jsonResponse["data"]?.jsonArray ?: throw IllegalStateException("Missing data array in embedding response")
        val embeddings = data.map { item ->
            val embeddingArray = item.jsonObject["embedding"]?.jsonArray
                ?: throw IllegalStateException("Missing embedding array")
            FloatArray(embeddingArray.size) { i -> embeddingArray[i].jsonPrimitive.float }
        }

        val usage = jsonResponse["usage"]?.jsonObject?.let { usageObj ->
            EmbeddingUsage(usageObj["total_tokens"]?.jsonPrimitive?.int ?: 0)
        }

        return EmbeddingResult(embeddings = embeddings, usage = usage)
    }
}
