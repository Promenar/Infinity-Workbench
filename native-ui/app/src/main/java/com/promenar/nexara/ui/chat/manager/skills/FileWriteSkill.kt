package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.WriteResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext

class FileWriteSkill(
    private val fileOpRepo: IFileOperationRepository
) : SkillDefinition {
    override val id = "write_file"
    override val name = "write_file"
    override val description = "将内容写入工作区文件（全量覆盖）。自动进行乐观锁冲突检测。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"目标文件UUID"},"content":{"type":"string","description":"要写入的完整内容"},"expectedHash":{"type":"string","description":"文件的当前hash(乐观锁)"}},"required":["uuid","content","expectedHash"]}"""

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val uuid = args["uuid"] as? String
            ?: return ToolResult("err", "缺少 uuid", "error")
        val content = args["content"] as? String
            ?: return ToolResult("err", "缺少 content", "error")
        val expectedHash = args["expectedHash"] as? String
            ?: return ToolResult("err", "缺少 expectedHash", "error")

        return when (val result = fileOpRepo.writeFileAtomic(uuid, content, context.sessionId, expectedHash)) {
            is WriteResult.Success -> ToolResult(
                "write_file_${System.currentTimeMillis()}",
                "写入成功。新 Hash: ${result.newHash}"
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
}
