package com.promenar.nexara.data.remote.parser

import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.GeneratedImageData
import com.promenar.nexara.data.model.TokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC_COMPATIBLE,
    VERTEX_AI,
    GENERIC
}

sealed class NormalizedResponse {
    abstract val content: String

    data class Empty(override val content: String = "") : NormalizedResponse()

    data class Text(override val content: String) : NormalizedResponse()

    data class WithReasoning(
        override val content: String,
        val reasoning: String
    ) : NormalizedResponse()

    data class Rich(
        override val content: String,
        val reasoning: String? = null,
        val citations: List<Citation>? = null,
        val images: List<GeneratedImageData>? = null,
        val tokens: TokenUsage? = null
    ) : NormalizedResponse()
}

object ResponseNormalizer {

    fun normalize(raw: JsonObject, providerType: ProviderType): NormalizedResponse =
        when (providerType) {
            ProviderType.VERTEX_AI -> normalizeVertex(raw)
            ProviderType.OPENAI_COMPATIBLE -> normalizeOpenAI(raw)
            ProviderType.ANTHROPIC_COMPATIBLE -> normalizeAnthropic(raw)
            ProviderType.GENERIC -> normalizeGeneric(raw)
        }

    private fun normalizeVertex(raw: JsonObject): NormalizedResponse {
        val candidate = raw["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: return NormalizedResponse.Empty()

        var content = ""
        var reasoning = ""
        val images = mutableListOf<GeneratedImageData>()

        val parts = candidate["content"]
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray

        if (parts != null) {
            val chunkHasThought = parts.any { part ->
                part.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull == true
            }

            for (partElement in parts) {
                val part = partElement.jsonObject

                val isThoughtPart =
                    part["thought"]?.jsonPrimitive?.booleanOrNull == true ||
                        part["thought"]?.jsonPrimitive?.isString == true

                when {
                    isThoughtPart -> {
                        part["thought"]?.jsonPrimitive?.let { p ->
                            if (p.isString) reasoning += p.content
                        }
                        part["text"]?.jsonPrimitive?.contentOrNull?.let { reasoning += it }
                    }
                    part.containsKey("text") -> {
                        val text = part["text"]?.jsonPrimitive?.content ?: ""
                        if (chunkHasThought) reasoning += text else content += text
                    }
                    else -> {
                        val inlineData = part["inline_data"]?.jsonObject
                            ?: part["inlineData"]?.jsonObject
                        if (inlineData != null) {
                            val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                            val mime = inlineData["mime_type"]?.jsonPrimitive?.content
                                ?: inlineData["mimeType"]?.jsonPrimitive?.content
                                ?: "image/png"
                            images += GeneratedImageData(
                                thumbnail = data,
                                original = data,
                                mime = mime
                            )
                        }
                    }
                }
            }
        }

        val citations = extractVertexCitations(candidate, raw)
        val tokens = extractVertexTokens(raw)

        return when {
            citations != null || images.isNotEmpty() || tokens != null ->
                NormalizedResponse.Rich(
                    content = content,
                    reasoning = reasoning.takeIf { it.isNotBlank() },
                    citations = citations,
                    images = images.takeIf { it.isNotEmpty() },
                    tokens = tokens
                )
            reasoning.isNotBlank() -> NormalizedResponse.WithReasoning(content, reasoning)
            content.isNotBlank() -> NormalizedResponse.Text(content)
            else -> NormalizedResponse.Empty()
        }
    }

    private fun normalizeOpenAI(raw: JsonObject): NormalizedResponse {
        val delta = raw["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("delta")
            ?.jsonObject
            ?: return NormalizedResponse.Empty()

        val content = delta["content"]?.jsonPrimitive?.content ?: ""
        val reasoning = delta["reasoning_content"]
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotEmpty() }

        return when {
            reasoning != null -> NormalizedResponse.WithReasoning(content, reasoning)
            content.isNotEmpty() -> NormalizedResponse.Text(content)
            else -> NormalizedResponse.Empty()
        }
    }

    private fun normalizeAnthropic(raw: JsonObject): NormalizedResponse {
        val type = raw["type"]?.jsonPrimitive?.contentOrNull
        val delta = raw["delta"]?.jsonObject ?: return NormalizedResponse.Empty()

        var content = ""
        var reasoning: String? = null

        if (type == "content_block_delta") {
            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                "text_delta" ->
                    content = delta["text"]?.jsonPrimitive?.content ?: ""
                "thinking_delta" ->
                    reasoning = delta["thinking"]?.jsonPrimitive?.content
                        ?.takeIf { it.isNotEmpty() }
            }
        }

        return when {
            reasoning != null -> NormalizedResponse.WithReasoning(content, reasoning)
            content.isNotEmpty() -> NormalizedResponse.Text(content)
            else -> NormalizedResponse.Empty()
        }
    }

    private fun normalizeGeneric(raw: JsonObject): NormalizedResponse {
        val content = raw["content"]?.jsonPrimitive?.content
            ?: raw["text"]?.jsonPrimitive?.content
            ?: raw["message"]?.jsonPrimitive?.content
            ?: ""
        return NormalizedResponse.Text(content)
    }

    private fun extractVertexCitations(
        candidate: JsonObject,
        response: JsonObject
    ): List<Citation>? {
        val metadata = candidate["groundingMetadata"]?.jsonObject
            ?: response["groundingMetadata"]?.jsonObject
            ?: return null

        val chunks = metadata["groundingChunks"]?.jsonArray ?: return null

        val citations = chunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            Citation(
                title = web["title"]?.jsonPrimitive?.content ?: "Web Source",
                url = web["uri"]?.jsonPrimitive?.content ?: "",
                source = "Google"
            )
        }

        return citations.takeIf { it.isNotEmpty() }
    }

    private fun extractVertexTokens(response: JsonObject): TokenUsage? {
        val usage = response["usageMetadata"]?.jsonObject ?: return null
        return TokenUsage(
            input = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
            output = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
            total = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }
}
