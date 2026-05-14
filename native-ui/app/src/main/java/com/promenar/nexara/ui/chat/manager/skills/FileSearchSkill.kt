package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.File

class FileSearchSkill : SkillDefinition {
    override val id = "file_search"
    override val name = "search_file"
    override val description = "Search for files by name pattern in the workspace."
    override val mcpServerId: String? = null

    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "pattern":{"type":"string","description":"File name pattern, supports wildcards (e.g. *.kt, test*.txt)"}
        },
        "required":["pattern"]
    }""".trimIndent()

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val pattern = args["pattern"]?.toString()
            ?: return ToolResult("err", "Missing required parameter: pattern", "error")

        val wsPath = context.workspacePath
            ?: return ToolResult("err", "No workspace path configured", "error")

        return try {
            val root = File(wsPath).canonicalFile
            val regex = globToRegex(pattern)
            val results = mutableListOf<String>()
            root.walkTopDown().maxDepth(20).forEach { file ->
                val relative = file.relativeTo(root).path
                if (file.isFile && regex.matches(file.name)) {
                    results.add(relative)
                }
            }

            if (results.isEmpty()) {
                ToolResult("file_search_${System.currentTimeMillis()}", "No files matching '$pattern' found.")
            } else {
                ToolResult(
                    "file_search_${System.currentTimeMillis()}",
                    "Found ${results.size} file(s):\n${results.joinToString("\n") { "  $it" }}"
                )
            }
        } catch (e: Exception) {
            ToolResult("file_search_${System.currentTimeMillis()}", "Search failed: ${e.message}", "error")
        }
    }

    private fun globToRegex(glob: String): Regex {
        val escaped = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^$escaped$", RegexOption.IGNORE_CASE)
    }
}
