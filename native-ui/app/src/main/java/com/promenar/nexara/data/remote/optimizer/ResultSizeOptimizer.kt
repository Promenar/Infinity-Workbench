package com.promenar.nexara.data.remote.optimizer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MCPContentItem(
    val type: String,
    val text: String? = null,
    val mimeType: String? = null,
    val data: String? = null,
    val resource: MCPResource? = null
)

@Serializable
data class MCPResource(
    val uri: String? = null,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)

@Serializable
data class MCPCallToolResult(
    val content: List<MCPContentItem>? = null,
    val isError: Boolean = false
)

object ResultSizeOptimizer {
    private const val MAX_CONTENT_SIZE = 4 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun optimize(rawResult: Any?): String {
        if (rawResult == null) return ""
        if (rawResult is String) return rawResult.take(MAX_CONTENT_SIZE)

        val result = tryParseResult(rawResult) ?: return rawResult.toString().take(MAX_CONTENT_SIZE)

        if (result.content == null) return rawResult.toString().take(MAX_CONTENT_SIZE)

        if (hasMultimodalContent(result)) {
            return summarizeMultimodalResult(result)
        }

        return result.content
            .map { item ->
                when (item.type) {
                    "text" -> item.text ?: ""
                    else -> json.encodeToString(MCPContentItem.serializer(), item)
                }
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_CONTENT_SIZE)
    }

    fun hasMultimodalContent(result: MCPCallToolResult): Boolean {
        val content = result.content ?: return false
        return content.any { item ->
            item.type == "image" ||
                item.type == "audio" ||
                (item.type == "resource" && item.resource?.blob != null)
        }
    }

    fun extractImages(result: MCPCallToolResult): List<String> {
        val content = result.content ?: return emptyList()
        return content
            .filter { it.type == "image" && it.data != null }
            .map { item ->
                "data:${item.mimeType ?: "image/png"};base64,${item.data}"
            }
    }

    private fun summarizeMultimodalResult(result: MCPCallToolResult): String {
        val content = result.content ?: return ""
        return content.joinToString("\n") { item ->
            when (item.type) {
                "text" -> item.text ?: ""
                "image" -> "[Image: ${item.mimeType ?: "image/png"}, delivered to user]"
                "audio" -> "[Audio: ${item.mimeType ?: "audio/mp3"}, delivered to user]"
                "resource" -> {
                    val res = item.resource
                    if (res?.blob != null) {
                        "[Resource: ${res.mimeType ?: "application/octet-stream"}, uri=${res.uri ?: "unknown"}, delivered to user]"
                    } else {
                        res?.text ?: json.encodeToString(MCPContentItem.serializer(), item)
                    }
                }
                else -> json.encodeToString(MCPContentItem.serializer(), item)
            }
        }.take(MAX_CONTENT_SIZE)
    }

    private fun tryParseResult(rawResult: Any?): MCPCallToolResult? {
        return try {
            when (rawResult) {
                is MCPCallToolResult -> rawResult
                is String -> json.decodeFromString<MCPCallToolResult>(rawResult)
                else -> json.decodeFromString<MCPCallToolResult>(rawResult.toString())
            }
        } catch (_: Exception) {
            null
        }
    }
}
