package com.promenar.infinity.data.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal data class SkillInterface(
    val displayName: String? = null,
    val shortDescription: String? = null,
    val allowImplicitInvocation: Boolean = true,
)

/** 仅解释 interface 与 policy 的两层标量映射，未知字段不执行任何行为。 */
internal object SkillInterfaceParser {
    fun parse(text: String): SkillInterface = runCatching {
        var section: String? = null
        var indentation: Int? = null
        val values = mutableMapOf<String, String>()
        text.removePrefix("\uFEFF").replace("\r\n", "\n").lineSequence().forEach { raw ->
            check('\t' !in raw) { "不支持制表符缩进" }
            val line = stripComment(raw).trimEnd()
            if (line.isBlank() || line == "---" || line == "...") return@forEach
            val indent = line.takeWhile(Char::isWhitespace).length
            val content = line.trimStart()
            if (indent == 0) {
                val colon = content.indexOf(':')
                check(colon > 0) { "缺少映射键" }
                section = content.substring(0, colon).trim()
                indentation = null
                if (section == "interface" || section == "policy") {
                    check(content.substring(colon + 1).isBlank()) { "需要两层映射" }
                }
            } else if (section == "interface" || section == "policy") {
                val childIndent = indentation ?: indent.also { indentation = it }
                check(indent == childIndent) { "只支持两层映射" }
                val colon = content.indexOf(':')
                check(colon > 0) { "缺少映射键" }
                val key = "$section.${content.substring(0, colon).trim()}"
                if (key in setOf("interface.display_name", "interface.short_description", "policy.allow_implicit_invocation")) {
                    check(key !in values) { "重复映射键" }
                    values[key] = content.substring(colon + 1).trim()
                }
            } else {
                check(section != null) { "缺少顶层映射" }
            }
        }
        SkillInterface(
            values["interface.display_name"]?.let(::scalar),
            values["interface.short_description"]?.let(::scalar),
            when (values["policy.allow_implicit_invocation"]) {
                null, "true" -> true
                "false" -> false
                else -> error("需要布尔值")
            },
        )
    }.getOrElse { SkillInterface() }

    private fun scalar(value: String): String? {
        val decoded = when {
            value.startsWith('"') -> {
                val primitive = Json.parseToJsonElement(value) as? JsonPrimitive ?: error("无效字符串")
                check(primitive.isString)
                primitive.content
            }
            value.startsWith('\'') -> {
                check(value.length >= 2 && value.endsWith('\''))
                val inner = value.substring(1, value.length - 1)
                check('\'' !in inner.replace("''", ""))
                inner.replace("''", "'")
            }
            else -> {
                check(value.none { it in "[]{}" } && value !in setOf("|", ">", "|-", ">-"))
                check(": " !in value)
                value
            }
        }
        return decoded.takeIf(String::isNotBlank)
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        val colon = line.indexOf(':')
        val scalarStart = if (colon < 0) -1 else (colon + 1 until line.length).firstOrNull { !line[it].isWhitespace() } ?: -1
        var index = 0
        while (index < line.length) {
            val character = line[index]
            if (escaped) {
                escaped = false
            } else if (quote == '"' && character == '\\') {
                escaped = true
            } else if (quote == '\'' && character == '\'' && line.getOrNull(index + 1) == '\'') {
                index += 2
                continue
            } else if (character == quote) {
                quote = null
            } else if (quote == null && index == scalarStart && (character == '\'' || character == '"')) {
                quote = character
            } else if (quote == null && character == '#' && (index == 0 || line[index - 1].isWhitespace())) {
                return line.substring(0, index)
            }
            index++
        }
        check(quote == null) { "引号未闭合" }
        return line
    }
}
