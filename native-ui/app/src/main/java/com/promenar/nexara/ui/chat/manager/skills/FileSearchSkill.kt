package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject

class FileSearchSkill(
    private val workspaceRepo: IWorkspaceRepository,
    private val fileOpRepo: IFileOperationRepository? = null,
) : SkillDefinition {
    override val id = "search_files"
    override val name = "search_files"
    override val description = "在工作区中搜索。mode=name 按文件名匹配（默认）；mode=content 在文本文件内容中逐行查找关键词，返回路径与行号。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词（不区分大小写）"},"mode":{"type":"string","enum":["name","content"],"default":"name"}},"required":["query"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val query = args.stringArgument("query")?.trim()?.takeIf(String::isNotEmpty)
            ?: return workspaceToolError(id, "缺少 query")
        val mode = args.stringArgument("mode") ?: "name"
        return try {
            when (mode) {
                "name" -> searchNames(query, context)
                "content" -> searchContent(query, context)
                else -> workspaceToolError(id, "不支持的 mode：$mode，可用 name 或 content")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            workspaceToolError(id, "搜索失败")
        }
    }

    private suspend fun searchNames(query: String, context: SkillExecutionContext): ToolResult {
        val matches = workspaceRepo.searchByName(context.workspaceRootUuid, query).firstOrNull().orEmpty()
            .filter { it.uuid != context.workspaceRootUuid && !it.isSystemEntry() }
            .sortedBy { it.materializedPath }
        if (matches.isEmpty()) return ToolResult(resultId(), "未找到名称包含 '$query' 的条目。")
        val shown = matches.take(MAX_NAME_RESULTS)
        return ToolResult(
            resultId(),
            buildString {
                appendLine("找到 ${matches.size} 个条目${if (matches.size > shown.size) "（显示前 ${shown.size} 个）" else ""}:")
                shown.forEach { appendLine("  ${it.describeForTool()}") }
            },
        )
    }

    private suspend fun searchContent(query: String, context: SkillExecutionContext): ToolResult {
        val operations = fileOpRepo ?: return workspaceToolError(id, "当前不支持内容搜索")
        val files = collectFiles(context)
        val hits = mutableListOf<String>()
        var scanned = 0
        var skipped = 0
        for (file in files) {
            if (hits.size >= MAX_CONTENT_HITS) break
            if (file.sizeBytes > MAX_SCAN_BYTES) {
                skipped++
                continue
            }
            val content = try {
                operations.readFileRange(context.workspaceRootUuid, file.uuid).content
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 二进制、超预算或读取失败的文件不阻断整体搜索。
                skipped++
                continue
            }
            scanned++
            content.lineSequence().forEachIndexed { index, line ->
                if (hits.size < MAX_CONTENT_HITS && line.contains(query, ignoreCase = true)) {
                    hits += "${file.materializedPath}:${index + 1}: ${line.trim().take(MAX_LINE_CHARS)}"
                }
            }
        }
        val summary = "扫描 $scanned 个文本文件" + if (skipped > 0) "，跳过 $skipped 个过大或非文本文件" else ""
        if (hits.isEmpty()) return ToolResult(resultId(), "未在内容中找到 '$query'（$summary）。")
        return ToolResult(
            resultId(),
            buildString {
                appendLine("找到 ${hits.size} 处匹配${if (hits.size >= MAX_CONTENT_HITS) "（已达上限）" else ""}，$summary:")
                hits.forEach { appendLine("  $it") }
                append("可用 read_file(path=..., mode=\"range\") 查看上下文。")
            },
        )
    }

    private suspend fun collectFiles(context: SkillExecutionContext): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        val pending = ArrayDeque(listOf(context.workspaceRootUuid))
        while (pending.isNotEmpty() && result.size < MAX_SCAN_FILES) {
            val parent = pending.removeFirst()
            workspaceRepo.observeChildren(context.workspaceRootUuid, parent).firstOrNull().orEmpty()
                .filterNot { it.isSystemEntry() }
                .forEach { entry -> if (entry.isDirectory) pending += entry.uuid else result += entry }
        }
        return result.take(MAX_SCAN_FILES)
    }

    private fun resultId() = "search_files_${System.currentTimeMillis()}"

    private companion object {
        const val MAX_NAME_RESULTS = 100
        const val MAX_CONTENT_HITS = 50
        const val MAX_SCAN_FILES = 200
        const val MAX_SCAN_BYTES = 1024L * 1024L
        const val MAX_LINE_CHARS = 200
    }
}
