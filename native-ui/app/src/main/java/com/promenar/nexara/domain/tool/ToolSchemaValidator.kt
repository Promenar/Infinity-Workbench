package com.promenar.nexara.domain.tool

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

enum class ToolSchemaErrorCode {
    MALFORMED_SCHEMA,
    SCHEMA_ROOT_NOT_OBJECT,
    UNSUPPORTED_KEYWORD,
    INVALID_SCHEMA,
    TYPE_MISMATCH,
    REQUIRED_PROPERTY_MISSING,
    ENUM_MISMATCH,
    ADDITIONAL_PROPERTY_NOT_ALLOWED,
}

data class ToolSchemaValidationError(
    val code: ToolSchemaErrorCode,
    val path: String,
    val message: String,
)

sealed interface ToolSchemaValidation {
    data class Valid(val canonicalSchema: String) : ToolSchemaValidation
    data class Invalid(val error: ToolSchemaValidationError) : ToolSchemaValidation
}

/**
 * 工具调用使用的窄范围 JSON Schema 门禁。
 *
 * 未明确实现的关键字一律拒绝，避免模型参数绕过客户端实际理解的约束。
 */
class ToolSchemaValidator {
    fun validateDefinition(schemaRaw: String): ToolSchemaValidation {
        val parsed = try {
            Json.parseToJsonElement(schemaRaw)
        } catch (_: SerializationException) {
            return invalid(ToolSchemaErrorCode.MALFORMED_SCHEMA, "$", "工具 schema 不是合法 JSON")
        } catch (_: IllegalArgumentException) {
            return invalid(ToolSchemaErrorCode.MALFORMED_SCHEMA, "$", "工具 schema 不是合法 JSON")
        }
        val schema = parsed as? JsonObject
            ?: return invalid(ToolSchemaErrorCode.SCHEMA_ROOT_NOT_OBJECT, "$", "工具 schema 根节点必须是 object")
        validateSchema(schema, "$", root = true)?.let { return ToolSchemaValidation.Invalid(it) }
        return ToolSchemaValidation.Valid(canonicalize(schema).toString())
    }

    fun validate(schemaRaw: String, arguments: JsonObject): ToolSchemaValidation {
        val definition = validateDefinition(schemaRaw)
        if (definition is ToolSchemaValidation.Invalid) return definition
        val schema = Json.parseToJsonElement(
            (definition as ToolSchemaValidation.Valid).canonicalSchema,
        ).let { it as JsonObject }
        validateValue(schema, arguments, "$")?.let { return ToolSchemaValidation.Invalid(it) }
        return definition
    }

    private fun validateSchema(
        schema: JsonObject,
        path: String,
        root: Boolean = false,
    ): ToolSchemaValidationError? {
        if (schema.isEmpty()) return null
        schema.keys.firstOrNull { it !in SUPPORTED_KEYWORDS }?.let { keyword ->
            return error(
                ToolSchemaErrorCode.UNSUPPORTED_KEYWORD,
                child(path, keyword),
                "工具 schema 包含客户端不支持的关键字",
            )
        }
        schema["type"]?.let { type ->
            val types = when (type) {
                is JsonPrimitive -> type.takeIf { it.isString }?.let { listOf(it.content) }
                is JsonArray -> type.mapNotNull { item ->
                    (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                }.takeIf { it.size == type.size && it.isNotEmpty() }
                else -> null
            } ?: return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "type"), "工具 schema 的 type 无效")
            if (types.distinct().size != types.size || types.any { it !in SUPPORTED_TYPES }) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "type"), "工具 schema 的 type 无效")
            }
            if (root && "object" !in types) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "type"), "工具 schema 根类型必须包含 object")
            }
        }
        schema["properties"]?.let { propertiesElement ->
            val properties = propertiesElement as? JsonObject
                ?: return error(
                    ToolSchemaErrorCode.INVALID_SCHEMA,
                    child(path, "properties"),
                    "工具 schema 的 properties 无效",
                )
            properties.forEach { (name, nested) ->
                val nestedSchema = nested as? JsonObject
                    ?: return error(
                        ToolSchemaErrorCode.INVALID_SCHEMA,
                        child(child(path, "properties"), name),
                        "工具 schema 的属性定义无效",
                    )
                validateSchema(nestedSchema, child(child(path, "properties"), name))?.let { return it }
            }
        }
        schema["required"]?.let { requiredElement ->
            val required = requiredElement as? JsonArray
                ?: return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "required"), "工具 schema 的 required 无效")
            val names = required.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            if (names.size != required.size || names.distinct().size != names.size) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "required"), "工具 schema 的 required 无效")
            }
            val properties = schema["properties"] as? JsonObject
            if (properties == null || names.any { it !in properties }) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "required"), "工具 schema 的 required 无效")
            }
        }
        schema["items"]?.let { items ->
            val itemsSchema = items as? JsonObject
                ?: return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "items"), "工具 schema 的 items 无效")
            validateSchema(itemsSchema, child(path, "items"))?.let { return it }
        }
        schema["enum"]?.let { enum ->
            if (enum !is JsonArray || enum.isEmpty() || enum.distinct().size != enum.size) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "enum"), "工具 schema 的 enum 无效")
            }
        }
        schema["additionalProperties"]?.let { additional ->
            when (additional) {
                is JsonPrimitive -> if (additional.booleanOrNull == null) {
                    return error(
                        ToolSchemaErrorCode.INVALID_SCHEMA,
                        child(path, "additionalProperties"),
                        "工具 schema 的 additionalProperties 无效",
                    )
                }
                is JsonObject -> validateSchema(additional, child(path, "additionalProperties"))?.let { return it }
                else -> return error(
                    ToolSchemaErrorCode.INVALID_SCHEMA,
                    child(path, "additionalProperties"),
                    "工具 schema 的 additionalProperties 无效",
                )
            }
        }
        schema["description"]?.let {
            if (it !is JsonPrimitive || !it.isString) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "description"), "工具 schema 的描述无效")
            }
        }
        schema["title"]?.let {
            if (it !is JsonPrimitive || !it.isString) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "title"), "工具 schema 的标题无效")
            }
        }
        schema["\$schema"]?.let {
            if (it !is JsonPrimitive || !it.isString) {
                return error(ToolSchemaErrorCode.INVALID_SCHEMA, child(path, "\$schema"), "工具 schema 的版本声明无效")
            }
        }
        return null
    }

    private fun validateValue(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ): ToolSchemaValidationError? {
        val types = schema["type"]?.let(::schemaTypes).orEmpty()
        if (types.isNotEmpty() && types.none { matchesType(it, value) }) {
            return error(ToolSchemaErrorCode.TYPE_MISMATCH, path, "工具参数类型不符合 schema")
        }
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) {
                return error(ToolSchemaErrorCode.ENUM_MISMATCH, path, "工具参数不在允许的枚举范围")
            }
        }
        if (value is JsonObject) {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray).orEmpty().forEach { required ->
                val name = (required as JsonPrimitive).content
                if (name !in value) {
                    return error(
                        ToolSchemaErrorCode.REQUIRED_PROPERTY_MISSING,
                        child(path, name),
                        "工具参数缺少 schema 要求的字段",
                    )
                }
            }
            value.forEach { (name, nestedValue) ->
                val nestedSchema = properties[name] as? JsonObject
                if (nestedSchema != null) {
                    validateValue(nestedSchema, nestedValue, child(path, name))?.let { return it }
                } else {
                    when (val additional = schema["additionalProperties"]) {
                        is JsonPrimitive -> if (additional.booleanOrNull == false) {
                            return error(
                                ToolSchemaErrorCode.ADDITIONAL_PROPERTY_NOT_ALLOWED,
                                child(path, name),
                                "工具参数包含 schema 未允许的字段",
                            )
                        }
                        is JsonObject -> validateValue(additional, nestedValue, child(path, name))?.let { return it }
                        else -> Unit
                    }
                }
            }
        }
        if (value is JsonArray) {
            val itemSchema = schema["items"] as? JsonObject
            if (itemSchema != null) {
                value.forEachIndexed { index, element ->
                    validateValue(itemSchema, element, "$path[$index]")?.let { return it }
                }
            }
        }
        return null
    }

    private fun schemaTypes(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> listOf(element.content)
        is JsonArray -> element.map { (it as JsonPrimitive).content }
        else -> emptyList()
    }

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value === JsonNull
        else -> false
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun child(path: String, name: String): String = "$path.$name"

    private fun invalid(code: ToolSchemaErrorCode, path: String, message: String) =
        ToolSchemaValidation.Invalid(error(code, path, message))

    private fun error(code: ToolSchemaErrorCode, path: String, message: String) =
        ToolSchemaValidationError(code, path, message)

    private companion object {
        val SUPPORTED_KEYWORDS = setOf(
            "type",
            "properties",
            "required",
            "items",
            "enum",
            "additionalProperties",
            "description",
            "title",
            "default",
            "\$schema",
        )
        val SUPPORTED_TYPES = setOf("object", "array", "string", "integer", "number", "boolean", "null")
    }
}
