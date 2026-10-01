package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.repository.WorkspaceTextPolicyException
import com.promenar.nexara.data.repository.WorkspaceTextContentPolicy
import com.promenar.nexara.data.repository.WorkspaceTextErrorCode
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.intArgument
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FileReadSkill(
    private val fileOpRepo: IFileOperationRepository,
    private val workspaceRepo: IWorkspaceRepository? = null,
) : SkillDefinition {
    override val id = "read_file"
    override val name = "read_file"
    override val description = "读取工作区文件内容，每行带 `行号| ` 前缀（前缀不属于文件内容，patch_file 的行号即以此为准）。用 uuid 或 path 指定文件；支持分页（offset/limit）和行号范围（startLine/endLine）两种模式。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"path":{"type":"string","description":"文件路径，如 /docs/a.md"},"mode":{"type":"string","enum":["page","range"],"default":"page"},"offset":{"type":"integer","description":"分页偏移(行号，0-based)"},"limit":{"type":"integer","description":"分页大小(行数)","default":200},"startLine":{"type":"integer","description":"起始行号(1-based)"},"endLine":{"type":"integer","description":"结束行号(1-based)"}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val (resolvedUuid, resolveError) = resolveWorkspaceFileUuid(workspaceRepo, context.workspaceRootUuid, args, id)
        if (resolveError != null) return resolveError
        val uuid = resolvedUuid!!
        val mode = args.stringArgument("mode") ?: "page"

        val startLine: Int?
        val endLine: Int?

        when (mode) {
            "range" -> {
                startLine = args.intArgument("startLine")
                endLine = args.intArgument("endLine")
                if (startLine != null && endLine != null && startLine > endLine) {
                    return workspaceToolError(id, "startLine($startLine) 不能大于 endLine($endLine)")
                }
            }
            else -> {
                val offset = (args.intArgument("offset") ?: 0).coerceAtLeast(0)
                val limit = (args.intArgument("limit") ?: 200).coerceIn(1, MAX_PAGE_LINES)
                startLine = offset + 1
                endLine = offset + limit
            }
        }

        val result = try {
            fileOpRepo.readFileRange(context.workspaceRootUuid, uuid, startLine, endLine)
        } catch (failure: WorkspaceTextPolicyException) {
            return workspaceTextFailureResult("read_file", failure)
        }

        return enforceWorkspaceToolResultBudget("read_file", ToolResult(
            "read_file_${System.currentTimeMillis()}",
            buildString {
                appendLine("文件: ${result.name} [uuid=${result.uuid}]")
                appendLine("行数: ${result.totalLines} (返回 ${result.startLine}-${result.endLine})")
                appendLine("Hash: ${result.hash}")
                appendLine("---")
                append(numberLines(result.content, result.startLine, result.totalLines))
            }
        ))
    }
}

private const val MAX_PAGE_LINES = 2000

/** 为模型输出加行号前缀，宽度按全文行数对齐。 */
internal fun numberLines(content: String, firstLine: Int, totalLines: Int): String {
    if (content.isEmpty()) return ""
    val width = maxOf(totalLines, 1).toString().length
    return content.split('\n').mapIndexed { index, line ->
        (firstLine + index).toString().padStart(width) + "| " + line
    }.joinToString("\n")
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

internal fun enforceWorkspaceToolResultBudget(operation: String, result: ToolResult): ToolResult {
    val serializedBytes = Json.encodeToString(result).toByteArray(Charsets.UTF_8).size.toLong()
    return if (serializedBytes <= WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxOutputBytes) {
        result
    } else {
        workspaceTextFailureResult(
            operation,
            WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.OUTPUT_TOO_LARGE,
                "结果超过输出上限，请缩小读取或差异范围后重试。",
            ),
        )
    }
}
