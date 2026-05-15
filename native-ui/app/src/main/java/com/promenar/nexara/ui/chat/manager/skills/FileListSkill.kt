package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.flow.firstOrNull

class FileListSkill(
    private val workspaceRepo: IWorkspaceRepository
) : SkillDefinition {
    override val id = "list_files"
    override val name = "list_files"
    override val description = "列出工作区指定目录下的文件和子目录。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"parentUuid":{"type":"string","description":"父目录UUID(不传则列出根目录)"}}}"""

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val parentUuid = args["parentUuid"] as? String

        val children = if (parentUuid != null) {
            workspaceRepo.observeChildren(parentUuid).firstOrNull()
        } else {
            workspaceRepo.observeRoots().firstOrNull()
        }

        if (children == null || children.isEmpty()) {
            return ToolResult(
                "list_files_${System.currentTimeMillis()}",
                if (parentUuid != null) "目录为空或不存在" else "工作区为空"
            )
        }

        val sb = StringBuilder()
        sb.appendLine("目录内容 (${children.size} 项):")
        for (entry in children) {
            val type = if (entry.isDirectory) "[DIR]" else "[FILE]"
            val size = if (!entry.isDirectory && entry.sizeBytes > 0) " (${formatSize(entry.sizeBytes)})" else ""
            sb.appendLine("  $type ${entry.name}$size [uuid=${entry.uuid}]")
        }

        return ToolResult("list_files_${System.currentTimeMillis()}", sb.toString())
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}
