package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.WriteResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FileWriteSkill(
    private val fileOpRepo: IFileOperationRepository
) : SkillDefinition {
    override val id = "write_file"
    override val name = "write_file"
    override val description = "将内容写入工作区文件（全量覆盖）。自动进行乐观锁冲突检测。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"目标文件UUID"},"content":{"type":"string","description":"要写入的完整内容"},"expectedHash":{"type":"string","description":"文件的当前hash(乐观锁)"}},"required":["uuid","content","expectedHash"]}"""

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val uuid = args["uuid"] as? String
            ?: return ToolResult("err", "缺少 uuid", "error")
        val content = args["content"] as? String
            ?: return ToolResult("err", "缺少 content", "error")
        val expectedHash = args["expectedHash"] as? String
            ?: return ToolResult("err", "缺少 expectedHash", "error")

        return when (val result = fileOpRepo.writeFileAtomic(context.workspaceRootUuid, uuid, content, context.sessionId, expectedHash)) {
            is WriteResult.Success -> ToolResult(
                "write_file_${System.currentTimeMillis()}",
                if (result.indexQueued) {
                    "写入成功，索引已入队。新 Hash: ${result.newHash}"
                } else if (result.targetEpoch == null) {
                    "写入成功，但索引尚未入队且缺少 targetEpoch，无法生成安全的补偿重试指令。"
                } else {
                    "写入成功，但索引尚未入队。请按返回契约依次调用 read_file 与 write_file 原样写回补偿。"
                },
                status = if (result.indexQueued) "success" else "error",
                data = indexResultData(
                    operation = "write_file",
                    workspaceRootUuid = context.workspaceRootUuid,
                    fileUuid = uuid,
                    contentHash = result.newHash,
                    targetEpoch = result.targetEpoch,
                    indexQueued = result.indexQueued,
                ),
            )
            is WriteResult.Conflict -> ToolResult(
                "write_file_${System.currentTimeMillis()}",
                "写入冲突！文件已被其他会话修改。当前 Hash: ${result.currentHash}，你的基准: ${result.expectedHash}。" +
                    "请先调用 read_file 获取最新内容，或调用 diff_file 查看差异后重新写入。",
                "error"
            )
            is WriteResult.NotFound -> ToolResult(
                "write_file_${System.currentTimeMillis()}",
                "文件不存在: $uuid",
                "error"
            )
        }
    }

    private fun indexResultData(
        operation: String,
        workspaceRootUuid: String,
        fileUuid: String,
        contentHash: String,
        targetEpoch: Long?,
        indexQueued: Boolean,
    ): String = buildJsonObject {
        put("operation", operation)
        put("fileUuid", fileUuid)
        put("contentHash", contentHash)
        put("targetEpoch", targetEpoch?.let(::JsonPrimitive) ?: JsonNull)
        put("indexQueued", indexQueued)
        if (!indexQueued && targetEpoch != null) {
            put("indexRetry", buildJsonObject {
                put("strategy", "read_then_rewrite_same_content")
                put("workspaceRootUuid", workspaceRootUuid)
                put("fileUuid", fileUuid)
                put("contentHash", contentHash)
                put("targetEpoch", targetEpoch)
                put("steps", buildJsonArray {
                    add(buildJsonObject {
                        put("action", "read_file")
                        put("arguments", buildJsonObject {
                            put("uuid", fileUuid)
                            put("mode", "range")
                            put("startLine", 1)
                            put("endLine", Int.MAX_VALUE)
                        })
                    })
                    add(buildJsonObject {
                        put("action", "write_file")
                        put("arguments", buildJsonObject {
                            put("uuid", fileUuid)
                            put("content", "{{read_file.content}}")
                            put("expectedHash", contentHash)
                        })
                    })
                })
            })
        }
    }.toString()
}
