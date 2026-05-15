package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext

class FileDiffSkill(
    private val fileOpRepo: IFileOperationRepository
) : SkillDefinition {
    override val id = "diff_file"
    override val name = "diff_file"
    override val description = "生成文件的差异报告（JSON格式）。可用于查看文件变更情况。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"basisHash":{"type":"string","description":"对比基准hash(可选，默认与上次已知版本对比)"}},"required":["uuid"]}"""

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val uuid = args["uuid"] as? String
            ?: return ToolResult("err", "缺少 uuid", "error")
        val basisHash = args["basisHash"] as? String

        val result = fileOpRepo.diffFile(uuid, basisHash)

        return ToolResult(
            "diff_file_${System.currentTimeMillis()}",
            buildString {
                appendLine("{")
                appendLine("  \"uuid\": \"${result.uuid}\",")
                appendLine("  \"basisHash\": \"${result.basisHash}\",")
                appendLine("  \"currentHash\": \"${result.currentHash}\",")
                appendLine("  \"hunks\": [")
                result.hunks.forEachIndexed { i, hunk ->
                    appendLine("    {")
                    appendLine("      \"oldStart\": ${hunk.oldStart}, \"oldCount\": ${hunk.oldCount},")
                    appendLine("      \"newStart\": ${hunk.newStart}, \"newCount\": ${hunk.newCount},")
                    appendLine("      \"lines\": [")
                    hunk.lines.forEach { line ->
                        appendLine("        {\"type\": \"${line.type}\", \"content\": ${escapeJson(line.content)}},")
                    }
                    appendLine("      ]")
                    append("    }")
                    if (i < result.hunks.lastIndex) append(",")
                    appendLine()
                }
                appendLine("  ]")
                append("}")
            }
        )
    }

    private fun escapeJson(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
}
