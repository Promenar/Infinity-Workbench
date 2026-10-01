package com.promenar.nexara.data.skills

/** 已安装技能包的元数据；正文按需读取，不常驻内存。 */
data class AgentSkillMetadata(
    val name: String,
    val description: String,
    val license: String? = null,
    val allowedTools: List<String> = emptyList(),
)

/** 解析后的 SKILL.md。 */
data class ParsedSkillDocument(
    val metadata: AgentSkillMetadata,
    val body: String,
)

class AgentSkillFormatException(message: String) : IllegalArgumentException(message)

/**
 * SKILL.md 解析器：只支持 Agent Skills frontmatter 所需的 YAML 子集
 * （`key: value` 标量、引号字符串、`>`/`|` 折叠块与 `- item` 列表），未知键忽略。
 */
object SkillMarkdownParser {
    const val MAX_NAME_LENGTH = 64
    const val MAX_DESCRIPTION_LENGTH = 1024
    private val NAME_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    fun parse(text: String): ParsedSkillDocument {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val lines = normalized.split('\n')
        if (lines.firstOrNull()?.trim() != "---") {
            throw AgentSkillFormatException("SKILL.md 必须以 --- 开头的 frontmatter 开始")
        }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) throw AgentSkillFormatException("SKILL.md frontmatter 缺少结束的 ---")
        val fields = parseFrontmatter(lines.subList(1, end + 1))
        val body = lines.drop(end + 2).joinToString("\n").trim()
        val name = (fields["name"] as? String)?.trim().orEmpty()
        val description = (fields["description"] as? String)?.trim().orEmpty()
        validateName(name)
        if (description.isEmpty()) throw AgentSkillFormatException("description 不能为空")
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            throw AgentSkillFormatException("description 不能超过 $MAX_DESCRIPTION_LENGTH 个字符")
        }
        val allowedTools = when (val raw = fields["allowed-tools"]) {
            is String -> raw.split(' ', ',').map(String::trim).filter(String::isNotEmpty)
            is List<*> -> raw.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            else -> emptyList()
        }
        return ParsedSkillDocument(
            AgentSkillMetadata(
                name = name,
                description = description,
                license = (fields["license"] as? String)?.trim()?.takeIf(String::isNotEmpty),
                allowedTools = allowedTools,
            ),
            body,
        )
    }

    fun validateName(name: String) {
        if (name.isEmpty()) throw AgentSkillFormatException("name 不能为空")
        if (name.length > MAX_NAME_LENGTH) throw AgentSkillFormatException("name 不能超过 $MAX_NAME_LENGTH 个字符")
        if (!NAME_PATTERN.matches(name)) {
            throw AgentSkillFormatException("name 只能包含小写字母、数字和单个连字符，且不能以连字符开头或结尾")
        }
    }

    /** 生成规范的 SKILL.md 文本，供应用内新建与编辑使用。 */
    fun render(metadata: AgentSkillMetadata, body: String): String = buildString {
        appendLine("---")
        appendLine("name: ${metadata.name}")
        appendLine("description: ${quote(metadata.description)}")
        metadata.license?.let { appendLine("license: ${quote(it)}") }
        if (metadata.allowedTools.isNotEmpty()) appendLine("allowed-tools: ${metadata.allowedTools.joinToString(" ")}")
        appendLine("---")
        appendLine()
        append(body.trim())
        appendLine()
    }

    private fun quote(value: String): String {
        val singleLine = value.replace('\n', ' ').trim()
        return "\"" + singleLine.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun parseFrontmatter(lines: List<String>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank() || line.trimStart().startsWith("#") || line.first().isWhitespace()) {
                index++
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw AgentSkillFormatException("frontmatter 第 ${index + 2} 行格式无效")
            val key = line.substring(0, colon).trim()
            val rawValue = line.substring(colon + 1).trim()
            index++
            when {
                rawValue == ">" || rawValue == ">-" || rawValue == "|" || rawValue == "|-" -> {
                    val block = mutableListOf<String>()
                    while (index < lines.size && (lines[index].isBlank() || lines[index].first().isWhitespace())) {
                        block += lines[index].trim()
                        index++
                    }
                    val separator = if (rawValue.startsWith("|")) "\n" else " "
                    result[key] = block.joinToString(separator).trim()
                }
                rawValue.isEmpty() -> {
                    val items = mutableListOf<String>()
                    while (index < lines.size && (lines[index].isBlank() || lines[index].first().isWhitespace())) {
                        val item = lines[index].trim()
                        if (item.startsWith("- ")) items += unquote(item.removePrefix("- ").trim())
                        index++
                    }
                    if (items.isNotEmpty()) result[key] = items
                }
                rawValue.startsWith("[") && rawValue.endsWith("]") -> {
                    result[key] = rawValue.removeSurrounding("[", "]").split(',')
                        .map { unquote(it.trim()) }.filter(String::isNotEmpty)
                }
                else -> result[key] = unquote(rawValue)
            }
        }
        return result
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
            value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') ->
            value.substring(1, value.length - 1).replace("''", "'")
        else -> value
    }
}
