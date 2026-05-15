package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.flow.firstOrNull

class FileSearchSkill(
    private val workspaceRepo: IWorkspaceRepository
) : SkillDefinition {
    override val id = "search_files"
    override val name = "search_files"
    override val description = "在工作区中搜索文件（文件名匹配 + 全文 FTS5 搜索）。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"},"mode":{"type":"string","enum":["name","fts"],"default":"name"}},"required":["query"]}"""

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val query = args["query"] as? String
            ?: return ToolResult("err", "缺少 query", "error")
        val mode = args["mode"] as? String ?: "name"

        return try {
            val roots = workspaceRepo.observeRoots().firstOrNull()
                ?: return ToolResult("search_files_${System.currentTimeMillis()}", "未找到工作区")

            val results = mutableListOf<String>()
            for (root in roots) {
                searchTree(workspaceRepo, root.uuid, query, mode, results, "")
            }

            if (results.isEmpty()) {
                ToolResult("search_files_${System.currentTimeMillis()}", "未找到匹配 '$query' 的文件。")
            } else {
                ToolResult(
                    "search_files_${System.currentTimeMillis()}",
                    "找到 ${results.size} 个结果:\n${results.joinToString("\n") { "  $it" }}"
                )
            }
        } catch (e: Exception) {
            ToolResult("search_files_${System.currentTimeMillis()}", "搜索失败: ${e.message}", "error")
        }
    }

    private suspend fun searchTree(
        repo: IWorkspaceRepository,
        parentUuid: String,
        query: String,
        mode: String,
        results: MutableList<String>,
        prefix: String
    ) {
        val children = repo.observeChildren(parentUuid).firstOrNull() ?: return
        for (child in children) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (mode == "name") {
                if (child.name.contains(query, ignoreCase = true) && !child.isDirectory) {
                    results.add("[${child.uuid}] $path")
                }
            }
            if (child.isDirectory) {
                searchTree(repo, child.uuid, query, mode, results, path)
            }
        }
    }
}
