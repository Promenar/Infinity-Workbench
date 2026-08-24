package com.promenar.nexara.data.remote.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Generic OpenAI-compatible `/models` 的唯一 envelope 解析合同。 */
internal object GenericModelsEnvelopeParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<String>? = try {
        val root = json.parseToJsonElement(body)
        val models = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: return null
            else -> return null
        }
        models.map { element ->
            val model = element as? JsonObject ?: return null
            val id = model["id"] as? JsonPrimitive ?: return null
            if (!id.isString || id.content.isBlank()) return null
            id.content
        }
    } catch (_: Exception) {
        null
    }
}
