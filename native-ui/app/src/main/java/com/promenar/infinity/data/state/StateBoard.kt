package com.promenar.infinity.data.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class StateField(
    val key: String,
    val label: String,
    val type: String,
    val value: JsonElement = JsonNull,
    val unit: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class StateBoard(val title: String, val fields: List<StateField>, val manualKeys: List<String> = emptyList(), val manualRevisions: Map<String, Long> = emptyMap())

@Serializable
data class StateOperation(val key: String, val op: String, val value: JsonElement = JsonNull)

@Serializable
data class StateChange(val key: String, val label: String, val before: JsonElement, val after: JsonElement)

data class StateMutation(val board: StateBoard, val changes: List<StateChange>)

data class StateBoardSnapshot(val revision: Long, val board: StateBoard?)

object StateBoardEngine {
    const val MAX_FIELDS = 20
    const val MAX_TEXT = 2000
    const val MAX_ITEMS = 50
    const val MAX_ITEM_TEXT = 200
    const val MAX_TITLE = 80
    const val MAX_LABEL = 40
    const val MAX_OPERATIONS = 50
    private val keyPattern = Regex("[A-Za-z][A-Za-z0-9_-]{0,39}")

    fun define(previous: StateBoard?, title: String, fields: List<StateField>, now: Long): StateBoard {
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "title 需为 1～$MAX_TITLE 字。" }
        require(fields.size in 1..MAX_FIELDS) { "fields 需为 1～$MAX_FIELDS 个字段。" }
        require(fields.map { it.key }.distinct().size == fields.size) { "字段 key 不能重复。" }
        val old = previous?.fields?.associateBy { it.key }.orEmpty()
        val defined = fields.map { field ->
            require(keyPattern.matches(field.key)) { "key ${field.key.take(40)} 需以字母开头，仅含字母、数字、下划线、短横线，最多 40 字。" }
            require(field.label.isNotBlank() && field.label.length <= MAX_LABEL) { "${field.key} 的 label 需为 1～$MAX_LABEL 字。" }
            require(field.unit == null || field.unit.length <= 16 && field.type in setOf("number", "meter")) { "${field.key} 的单位仅用于 number/meter，最多 16 字。" }
            val existing = old[field.key]
            require(existing == null || existing.type == field.type) { "${field.key} 已为 ${existing?.type} 类型；改变类型请使用新的 key。" }
            val value = normalizeValue(field.type, existing?.value ?: field.value.takeUnless { it == JsonNull } ?: defaultValue(field.type))
            validateValue(field.type, value)
            field.copy(value = value, updatedAt = existing?.updatedAt ?: now)
        }
        val manualKeys = previous?.manualKeys.orEmpty().filter { key -> defined.any { it.key == key } }
        return StateBoard(title, defined, manualKeys, previous?.manualRevisions.orEmpty().filterKeys { it in manualKeys })
    }

    fun update(board: StateBoard, operations: List<StateOperation>, now: Long, user: Boolean = false): StateMutation {
        require(operations.size in 1..MAX_OPERATIONS) { "operations 需为 1～$MAX_OPERATIONS 项。" }
        val fields = board.fields.toMutableList()
        operations.forEach { operation ->
            val index = fields.indexOfFirst { it.key == operation.key }
            require(index >= 0) { "字段 ${operation.key.take(40)} 不存在；先用 state_get 读取有效 key。" }
            val field = fields[index]
            val value = when (operation.op) {
                "set" -> normalizeValue(field.type, operation.value)
                "increment" -> {
                    val delta = numeric(operation.value)
                    when (field.type) {
                        "number" -> numberValue(numeric(field.value) + delta)
                        "meter" -> JsonObject(field.value.jsonObject + ("current" to numberValue(numeric(field.value.jsonObject.getValue("current")) + delta)))
                        else -> throw IllegalArgumentException("${field.key} 仅 number/meter 支持 increment。")
                    }
                }
                "append", "remove" -> {
                    require(field.type == "list") { "${field.key} 仅 list 支持 ${operation.op}。" }
                    val items = normalizeValue("list", operation.value)
                    validateValue("list", items)
                    JsonArray(if (operation.op == "append") field.value.jsonArray + items.jsonArray else field.value.jsonArray.filterNot { it in items.jsonArray })
                }
                "clear" -> if (field.type == "meter") JsonObject(field.value.jsonObject + ("current" to JsonPrimitive(0))) else defaultValue(field.type)
                else -> throw IllegalArgumentException("op 仅支持 set、increment、append、remove、clear。")
            }
            validateValue(field.type, value)
            fields[index] = field.copy(value = value, updatedAt = if (value != field.value) now else field.updatedAt)
        }
        val changes = fields.mapNotNull { after ->
            val before = board.fields.first { it.key == after.key }
            if (before.value == after.value) null else StateChange(after.key, after.label, before.value, after.value)
        }
        val manual = if (user) (board.manualKeys + changes.map { it.key }).distinct() else board.manualKeys.filterNot { key -> operations.any { it.key == key } }
        return StateMutation(board.copy(fields = fields, manualKeys = manual, manualRevisions = board.manualRevisions.filterKeys { it in manual }), changes)
    }

    fun validateValue(type: String, value: JsonElement) {
        when (type) {
            "text" -> require(value is JsonPrimitive && value.isString && value.content.length <= MAX_TEXT) { "text 的 value 必须是最多 $MAX_TEXT 字的字符串。" }
            "number" -> numeric(value)
            "meter" -> {
                require(value is JsonObject && value.keys == setOf("current", "max")) { "meter 的 value 必须是 {current:数值,max:上限}。" }
                val current = numeric(value.getValue("current"))
                val max = numeric(value.getValue("max"))
                require(max > 0 && current in 0.0..max) { "meter 需满足 0 ≤ current ≤ max，max > 0。" }
            }
            "list" -> {
                val items = normalizeValue(type, value)
                require(items is JsonArray && items.size <= MAX_ITEMS && items.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= MAX_ITEM_TEXT }) { "list 的 value 必须是非空字符串或最多 $MAX_ITEMS 项的字符串数组，每项 1～$MAX_ITEM_TEXT 字。" }
            }
            else -> throw IllegalArgumentException("type 仅支持 text、number、meter、list。")
        }
    }

    private fun normalizeValue(type: String, value: JsonElement): JsonElement =
        if (type == "list" && value is JsonPrimitive && value.isString) JsonArray(listOf(value)) else value

    private fun numberValue(value: Double): JsonElement {
        require(value.isFinite()) { "运算结果必须是有限数值。" }
        return Json.parseToJsonElement(java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
    }

    private fun numeric(value: JsonElement): Double {
        require(value is JsonPrimitive && !value.isString) { "数值必须是 JSON number，不能是字符串。" }
        val number = value.doubleOrNull
        require(number != null && number.isFinite() && kotlin.math.abs(number) <= 1e12) { "数值必须有限且绝对值不超过 10^12。" }
        return number
    }

    private fun defaultValue(type: String): JsonElement = when (type) {
        "text" -> JsonPrimitive("")
        "number" -> JsonPrimitive(0)
        "meter" -> buildJsonObject { put("current", 0); put("max", 100) }
        "list" -> JsonArray(emptyList())
        else -> throw IllegalArgumentException("type 仅支持 text、number、meter、list。")
    }
}
