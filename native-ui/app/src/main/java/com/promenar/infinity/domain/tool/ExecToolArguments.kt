package com.promenar.infinity.domain.tool

import kotlinx.serialization.json.*

/** 保留原始审批摘要，只在校验与执行时做确定的标量转换。 */
object ExecToolArguments {
    data class Normalized(val arguments: JsonObject, val notes: List<String>) {
        fun result(fields: JsonObject): JsonObject = if (notes.isEmpty()) fields else
            JsonObject(fields + ("argument_notes" to JsonArray(notes.map(::JsonPrimitive))))
    }
    /** 命令类工具的短标题参数：由智能体概括命令用途，显示在运行记录与后台任务列表中。 */
    const val TITLE_SCHEMA = """"title":{"type":"string","description":"用不超过 20 个字概括这条命令在做什么，例如“克隆 Personal-Archive”；显示在运行记录与后台任务列表中，请每次填写"}"""
    private const val MAX_TITLE_CHARS = 40

    /** 读取短标题：空白折叠为单个空格、去掉首尾空白，过长截断；没有或为空时为 null。 */
    fun title(args: JsonObject): String? = (args["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotEmpty)

    fun supports(name: String) = name in setOf("shell_exec", "job_status", "job_wait", "job_stop", "job_list", "job_input")
    fun normalize(args: JsonObject): Normalized {
        val notes = mutableListOf<String>()
        val converted = args.mapValues { (key, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            val replacement = when {
                text == null -> null
                key in setOf("background", "newline", "eof") -> when (text.lowercase()) {
                    "true" -> JsonPrimitive(true); "false" -> JsonPrimitive(false); else -> null
                }
                key in setOf("timeout_seconds", "max_runtime_minutes", "yield_after_seconds") && text.matches(Regex("[0-9]+")) -> text.toLongOrNull()?.let(::JsonPrimitive)
                else -> null
            }
            replacement?.also { notes += "$key 已从字符串转换为${if (key in setOf("background", "newline", "eof")) "布尔值" else "整数"}。" } ?: value
        }
        return Normalized(JsonObject(converted), notes)
    }
    fun validate(schema: String, args: JsonObject) {
        val validation = ToolSchemaValidator().validate(schema, args)
        require(validation !is ToolSchemaValidation.Invalid) {
            validation as ToolSchemaValidation.Invalid
            "${validation.error.path}：${validation.error.message}"
        }
    }
    fun integer(args: JsonObject, key: String, default: Int, range: IntRange): Int {
        val value = args[key] ?: return default
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        require(number != null && number in range) { "$key 应为 ${range.first}–${range.last} 的整数，收到${typeOf(value)}。" }
        return number
    }
    fun typeOf(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonObject -> "对象"
        is JsonArray -> "数组"
        is JsonPrimitive -> when { value.isString -> "字符串"; value.booleanOrNull != null -> "布尔值"; else -> "数字" }
    }
}
