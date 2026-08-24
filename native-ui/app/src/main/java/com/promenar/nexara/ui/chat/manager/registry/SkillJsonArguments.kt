package com.promenar.nexara.ui.chat.manager.registry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

fun JsonObject.stringArgument(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.intArgument(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

fun JsonObject.objectArgument(name: String): JsonObject? = this[name] as? JsonObject

fun JsonObject.arrayArgument(name: String): JsonArray? = this[name] as? JsonArray
