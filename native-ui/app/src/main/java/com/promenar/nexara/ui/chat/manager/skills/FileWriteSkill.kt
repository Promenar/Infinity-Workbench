package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.File

class FileWriteSkill : SkillDefinition {
    override val id = "file_write"
    override val name = "write_file"
    override val description = "Write or overwrite content to a file in the workspace. Creates parent directories if needed."
    override val mcpServerId: String? = null

    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"Relative path to the file within the workspace"},
            "content":{"type":"string","description":"The content to write to the file"}
        },
        "required":["path","content"]
    }""".trimIndent()

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val path = args["path"]?.toString()
            ?: return ToolResult("err", "Missing required parameter: path", "error")
        val content = args["content"]?.toString()
            ?: return ToolResult("err", "Missing required parameter: content", "error")

        val wsPath = context.workspacePath
            ?: return ToolResult("err", "No workspace path configured", "error")

        return try {
            val file = File(wsPath, path).canonicalFile
            val canonicalWs = File(wsPath).canonicalPath
            if (!file.path.startsWith(canonicalWs)) {
                return ToolResult("err", "Security: path escapes workspace", "error")
            }
            file.parentFile?.mkdirs()
            file.writeText(content)
            val size = file.length()
            ToolResult(
                "file_write_${System.currentTimeMillis()}",
                "Successfully wrote ${content.length} chars ($size bytes) to $path"
            )
        } catch (e: Exception) {
            ToolResult("file_write_${System.currentTimeMillis()}", "Write failed: ${e.message}", "error")
        }
    }
}
