package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.File

class FileReadSkill : SkillDefinition {
    override val id = "file_read"
    override val name = "read_file"
    override val description = "Read the contents of a file in the workspace."
    override val mcpServerId: String? = null

    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"Relative path to the file within the workspace"}
        },
        "required":["path"]
    }""".trimIndent()

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val path = args["path"]?.toString()
            ?: return ToolResult("err", "Missing required parameter: path", "error")

        val wsPath = context.workspacePath
            ?: return ToolResult("err", "No workspace path configured", "error")

        return try {
            val file = File(wsPath, path).canonicalFile
            val canonicalWs = File(wsPath).canonicalPath
            if (!file.path.startsWith(canonicalWs)) {
                return ToolResult("err", "Security: path escapes workspace", "error")
            }
            if (!file.exists()) {
                return ToolResult("err", "File not found: $path", "error")
            }
            if (file.length() > 1_000_000) {
                return ToolResult("err", "File too large (>1MB), use offset/limit parameters", "error")
            }
            val content = file.readText()
            ToolResult("file_read_${System.currentTimeMillis()}", content)
        } catch (e: Exception) {
            ToolResult("file_read_${System.currentTimeMillis()}", "Read failed: ${e.message}", "error")
        }
    }
}
