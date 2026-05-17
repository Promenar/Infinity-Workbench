package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.inference.LocalInferenceEngine
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
    },
    private val localEngine: LocalInferenceEngine? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 远程 Embedding 是否已配置（baseUrl 和 apiKey 均非空） */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    /** 是否有本地引擎可用作降级 */
    val hasLocalFallback: Boolean
        get() = localEngine != null

    /** 诊断信息：用于向用户解释当前配置状态 */
    fun diagnosticMessage(): String = buildString {
        if (baseUrl.isBlank()) {
            append("Embedding 服务未配置")
            if (model.isNotBlank()) append("（当前已选模型: \"$model\"）")
            append("\n\n可能的原因：\n")
            append("1. 未在 设置 → 通用 中选择向量模型。\n")
            append("2. 所选模型属于一个无效或未配置地址的提供商。\n")
            append("3. 主提供商配置已失效且未设置备选 Embedding 模型。\n")
            append("\n请前往 设置 检查提供商配置及模型选择。")
        } else if (apiKey.isBlank()) {
            append("Embedding API Key 未配置（Base URL: $baseUrl）")
            append("\n请检查对应提供商的 API Key 设置。")
        } else if (model.isBlank()) {
            append("Embedding 模型未指定（Base URL: $baseUrl）")
            append("\n请前往 设置 → 通用 选择一个向量模型。")
        }
    }

    suspend fun embedDocuments(texts: List<String>): EmbeddingResult {
        if (texts.isEmpty()) throw IllegalArgumentException("No texts provided for embedding")
        // 如果远程未配置但有本地引擎，直接走本地通道
        if (!isConfigured && localEngine != null) {
            return localEngine.embedBatch(texts).fold(
                onSuccess = { EmbeddingResult(embeddings = it, usage = null) },
                onFailure = { throw IllegalStateException(diagnosticMessage()) }
            )
        }
        return try {
            embedViaRemote(texts)
        } catch (e: Exception) {
            if (localEngine != null) {
                localEngine.embedBatch(texts).fold(
                    onSuccess = { EmbeddingResult(embeddings = it, usage = null) },
                    onFailure = { throw e }
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun embedViaRemote(texts: List<String>): EmbeddingResult {
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
        // 如果远程未配置但有本地引擎，直接走本地通道
        if (!isConfigured && localEngine != null) {
            return localEngine.embed(text).fold(
                onSuccess = { Pair(it, null) },
                onFailure = { throw IllegalStateException(diagnosticMessage()) }
            )
        }
        return try {
            val result = embedDocuments(listOf(text))
            Pair(result.embeddings.first(), result.usage)
        } catch (e: Exception) {
            if (localEngine != null) {
                localEngine.embed(text).fold(
                    onSuccess = { Pair(it, null) },
                    onFailure = { throw e }
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun embedBatch(texts: List<String>): EmbeddingResult {
        if (!isConfigured) throw IllegalStateException(diagnosticMessage())

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

    suspend fun embedLocal(text: String): Result<FloatArray> {
        val engine = localEngine
            ?: return Result.failure(IllegalStateException("Local engine not configured"))
        return engine.embed(text)
    }

    suspend fun embedLocalBatch(texts: List<String>): Result<List<FloatArray>> {
        val engine = localEngine
            ?: return Result.failure(IllegalStateException("Local engine not configured"))
        return engine.embedBatch(texts)
    }
}
