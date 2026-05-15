package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext

class FileReadSkill(
    private val fileOpRepo: IFileOperationRepository
) : SkillDefinition {
    override val id = "read_file"
    override val name = "read_file"
    override val description = "读取工作区文件内容。支持分页（offset/limit）和行号范围（startLine/endLine）两种模式。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"mode":{"type":"string","enum":["page","range"],"default":"page"},"offset":{"type":"integer","description":"分页偏移(行号，0-based)"},"limit":{"type":"integer","description":"分页大小(行数)","default":200},"startLine":{"type":"integer","description":"起始行号(1-based)"},"endLine":{"type":"integer","description":"结束行号(1-based)"}},"required":["uuid"]}"""

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val uuid = args["uuid"] as? String
            ?: return ToolResult("err", "缺少 uuid 参数", "error")
        val mode = args["mode"] as? String ?: "page"

        val startLine: Int?
        val endLine: Int?

        when (mode) {
            "range" -> {
                startLine = (args["startLine"] as? Number)?.toInt()
                endLine = (args["endLine"] as? Number)?.toInt()
            }
            else -> {
                val offset = (args["offset"] as? Number)?.toInt() ?: 0
                val limit = (args["limit"] as? Number)?.toInt() ?: 200
                startLine = offset + 1
                endLine = offset + limit
            }
        }

        val result = fileOpRepo.readFileRange(uuid, startLine, endLine)

        return ToolResult(
            "read_file_${System.currentTimeMillis()}",
            buildString {
                appendLine("文件: ${result.name}")
                appendLine("行数: ${result.totalLines} (返回 ${result.startLine}-${result.endLine})")
                appendLine("Hash: ${result.hash}")
                appendLine("---")
                append(result.content)
            }
        )
    }
}
