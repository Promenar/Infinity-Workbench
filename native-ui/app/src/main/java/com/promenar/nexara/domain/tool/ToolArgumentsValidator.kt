package com.promenar.nexara.domain.tool

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

enum class ToolArgumentsErrorCode {
    MALFORMED_JSON,
    ROOT_NOT_OBJECT,
}

data class ToolArgumentsValidationError(
    val code: ToolArgumentsErrorCode,
    val message: String,
)

sealed interface ToolArgumentsValidation {
    data class Valid(
        val arguments: JsonObject,
        val canonicalJson: String,
        val sha256: String,
    ) : ToolArgumentsValidation

    data class Invalid(val error: ToolArgumentsValidationError) : ToolArgumentsValidation
}

class ToolArgumentsValidator {
    fun validate(raw: String): ToolArgumentsValidation {
        val parsed = try {
            Json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            return invalid(ToolArgumentsErrorCode.MALFORMED_JSON, "工具参数不是合法 JSON")
        } catch (_: IllegalArgumentException) {
            return invalid(ToolArgumentsErrorCode.MALFORMED_JSON, "工具参数不是合法 JSON")
        }
        val arguments = parsed as? JsonObject
            ?: return invalid(ToolArgumentsErrorCode.ROOT_NOT_OBJECT, "工具参数根节点必须是 JSON object")
        val canonical = canonicalize(arguments) as JsonObject
        val canonicalJson = canonical.toString()
        return ToolArgumentsValidation.Valid(
            arguments = canonical,
            canonicalJson = canonicalJson,
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(canonicalJson.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
            .associate { (key, value) -> key to canonicalize(value) })
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun invalid(code: ToolArgumentsErrorCode, message: String): ToolArgumentsValidation.Invalid =
        ToolArgumentsValidation.Invalid(ToolArgumentsValidationError(code, message))
}
