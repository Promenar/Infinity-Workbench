package com.promenar.nexara.ui.chat.manager.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun skillArgs(vararg values: Pair<String, Any?>): JsonObject = JsonObject(
    values.associate { (key, value) -> key to value.toTestJsonElement() },
)

private fun Any?.toTestJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
        require(key is String)
        key to value.toTestJsonElement()
    })
    is Iterable<*> -> JsonArray(map { it.toTestJsonElement() })
    else -> error("不支持的测试 JSON 类型: ${this::class.java.name}")
}
