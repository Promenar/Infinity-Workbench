package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.intArgument
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject

class FileListSkill(
    private val workspaceRepo: IWorkspaceRepository
) : SkillDefinition {
    override val id = "list_files"
    override val name = "list_files"
    override val description = "列出工作区目录内容，返回每项的路径、uuid 与文件 hash（可直接作为 write_file/patch_file 的 expectedHash）。用 parentUuid 或 path 指定目录，缺省为根目录；depth>1 时递归列出子目录。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"parentUuid":{"type":"string","description":"目录UUID(不传则为根目录)"},"path":{"type":"string","description":"目录路径，如 /docs；根目录为 /"},"depth":{"type":"integer","description":"递归深度，默认1，最大5"}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val hasTarget = args.stringArgument("parentUuid")?.isNotBlank() == true ||
            args.stringArgument("path")?.isNotBlank() == true
        val directory = if (hasTarget) {
            when (
                val resolved = resolveWorkspaceEntry(
                    workspaceRepo, context.workspaceRootUuid, args, id, "parentUuid", "path",
                )
            ) {
                is WorkspaceEntryResolution.Failed -> return resolved.result
                is WorkspaceEntryResolution.Found -> resolved.entry
            }
        } else {
            null
        }
        if (directory != null && !directory.isDirectory) {
            return workspaceToolError(id, "不是目录：${directory.materializedPath}")
        }
        val parentUuid = directory?.uuid ?: context.workspaceRootUuid
        val depth = (args.intArgument("depth") ?: 1).coerceIn(1, MAX_DEPTH)
        val lines = mutableListOf<String>()
        var truncated = false
        suspend fun walk(uuid: String, prefix: String, level: Int) {
            val children = workspaceRepo.observeChildren(context.workspaceRootUuid, uuid).firstOrNull().orEmpty()
                .filterNot(FileEntry::isSystemEntry)
            for (entry in children) {
                if (lines.size >= MAX_ENTRIES) {
                    truncated = true
                    return
                }
                val size = if (!entry.isDirectory) " (${formatSize(entry.sizeBytes)})" else ""
                val hash = if (!entry.isDirectory) " [hash=${entry.hash}]" else ""
                lines += "$prefix${if (entry.isDirectory) "[DIR]" else "[FILE]"} ${entry.materializedPath}$size [uuid=${entry.uuid}]$hash"
                if (entry.isDirectory && level < depth) walk(entry.uuid, "$prefix  ", level + 1)
            }
        }
        walk(parentUuid, "", 1)

        val label = directory?.materializedPath ?: "/"
        if (lines.isEmpty()) {
            return ToolResult("list_files_${System.currentTimeMillis()}", "目录 $label 为空")
        }
        return ToolResult(
            "list_files_${System.currentTimeMillis()}",
            buildString {
                appendLine("目录 $label（${lines.size} 项${if (truncated) "，已截断至 $MAX_ENTRIES 项" else ""}）:")
                lines.forEach(::appendLine)
            },
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }

    private companion object {
        const val MAX_DEPTH = 5
        const val MAX_ENTRIES = 300
    }
}

/** 回收站与系统保留目录不向模型展示。 */
internal fun FileEntry.isSystemEntry(): Boolean {
    val first = materializedPath.trimStart('/').substringBefore('/')
    return first == ".recycle_bin" || first.startsWith(".nexara", ignoreCase = true)
}
