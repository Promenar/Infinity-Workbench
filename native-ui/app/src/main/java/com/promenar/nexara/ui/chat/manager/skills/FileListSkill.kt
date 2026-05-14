package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.File

class FileListSkill : SkillDefinition {
    override val id = "file_list"
    override val name = "list_dir"
    override val description = "List files and directories in the workspace."
    override val mcpServerId: String? = null

    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"Relative path to directory. Default: workspace root"}
        },
        "required":[]
    }""".trimIndent()

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val relativePath = args["path"]?.toString() ?: ""

        val wsPath = context.workspacePath
            ?: return ToolResult("err", "No workspace path configured", "error")

        return try {
            val dir = File(wsPath, relativePath).canonicalFile
            val canonicalWs = File(wsPath).canonicalPath
            if (!dir.path.startsWith(canonicalWs)) {
                return ToolResult("err", "Security: path escapes workspace", "error")
            }
            if (!dir.exists() || !dir.isDirectory) {
                return ToolResult("err", "Directory not found: $relativePath", "error")
            }

            val sb = StringBuilder()
            sb.appendLine("Contents of ${relativePath.ifEmpty { "/" }}:")
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                val type = if (f.isDirectory) "[DIR] " else "[FILE]"
                val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                sb.appendLine("  $type${f.name}$size")
            } ?: sb.appendLine("  (empty)")

            ToolResult("file_list_${System.currentTimeMillis()}", sb.toString())
        } catch (e: Exception) {
            ToolResult("file_list_${System.currentTimeMillis()}", "List failed: ${e.message}", "error")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}
