package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.repository.WorkspaceTextPolicyException
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FileDiffSkill(
    private val fileOpRepo: IFileOperationRepository
) : SkillDefinition {
    override val id = "diff_file"
    override val name = "diff_file"
    override val description = "生成文件的差异报告（JSON格式）。可用于查看文件变更情况。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"basisHash":{"type":"string","description":"对比基准hash(可选，默认与上次已知版本对比)"}},"required":["uuid"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val uuid = args.stringArgument("uuid")
            ?: return ToolResult("err", "缺少 uuid", "error")
        val basisHash = args.stringArgument("basisHash")

        val result = try {
            fileOpRepo.diffFile(context.workspaceRootUuid, uuid, basisHash)
        } catch (failure: WorkspaceTextPolicyException) {
            return workspaceTextFailureResult("diff_file", failure)
        }

        val content = buildJsonObject {
            put("uuid", result.uuid)
            put("basisHash", result.basisHash)
            put("currentHash", result.currentHash)
            put("hunks", buildJsonArray {
                result.hunks.forEach { hunk ->
                    add(buildJsonObject {
                        put("oldStart", hunk.oldStart)
                        put("oldCount", hunk.oldCount)
                        put("newStart", hunk.newStart)
                        put("newCount", hunk.newCount)
                        put("lines", buildJsonArray {
                            hunk.lines.forEach { line ->
                                add(buildJsonObject {
                                    put("type", line.type)
                                    put("content", line.content)
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
        return enforceWorkspaceToolResultBudget("diff_file", ToolResult(
            "diff_file_${System.currentTimeMillis()}",
            content,
        ))
    }
}
