package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.repository.WorkspaceTextPolicyException
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.intArgument
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FileReadSkill(
    private val fileOpRepo: IFileOperationRepository
) : SkillDefinition {
    override val id = "read_file"
    override val name = "read_file"
    override val description = "读取工作区文件内容。支持分页（offset/limit）和行号范围（startLine/endLine）两种模式。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"mode":{"type":"string","enum":["page","range"],"default":"page"},"offset":{"type":"integer","description":"分页偏移(行号，0-based)"},"limit":{"type":"integer","description":"分页大小(行数)","default":200},"startLine":{"type":"integer","description":"起始行号(1-based)"},"endLine":{"type":"integer","description":"结束行号(1-based)"}},"required":["uuid"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val uuid = args.stringArgument("uuid")
            ?: return ToolResult("err", "缺少 uuid 参数", "error")
        val mode = args.stringArgument("mode") ?: "page"

        val startLine: Int?
        val endLine: Int?

        when (mode) {
            "range" -> {
                startLine = args.intArgument("startLine")
                endLine = args.intArgument("endLine")
            }
            else -> {
                val offset = args.intArgument("offset") ?: 0
                val limit = args.intArgument("limit") ?: 200
                startLine = offset + 1
                endLine = offset + limit
            }
        }

        val result = try {
            fileOpRepo.readFileRange(context.workspaceRootUuid, uuid, startLine, endLine)
        } catch (failure: WorkspaceTextPolicyException) {
            return workspaceTextFailureResult("read_file", failure)
        }

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

internal fun workspaceTextFailureResult(
    operation: String,
    failure: WorkspaceTextPolicyException,
): ToolResult = ToolResult(
    id = "${operation}_error_${System.currentTimeMillis()}",
    content = failure.safeMessage.take(512),
    status = "error",
    data = buildJsonObject {
        put("operation", operation)
        put("errorCode", failure.code.name)
        put("retrySuggestion", "请缩小读取或差异范围，或使用兼容该文件类型的专用工具。")
    }.toString(),
)
