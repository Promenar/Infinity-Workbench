package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.utils.NexaraLogger

data class ContextBuilderResult(
    val searchContext: String,
    val ragContext: String,
    val citations: List<com.promenar.nexara.data.model.Citation>,
    val ragReferences: List<RagReference>,
    val ragUsage: RagUsage?,
    val finalSystemPrompt: String
)

data class ContextBuilderParams(
    val sessionId: String,
    val content: String,
    val images: String? = null,
    val assistantMsgId: String,
    val session: Session,
    val ragOptions: com.promenar.nexara.data.model.RagOptions? = null,
    val onRagProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null,
    val agentSystemPrompt: String? = null,
    val sessionCustomPrompt: String? = null
)

interface WebSearchProvider {
    suspend fun search(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>>
}

interface RagProvider {
    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: com.promenar.nexara.data.model.RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null
    ): Triple<String, List<RagReference>, RagUsage?>
}

interface KgProvider {
    suspend fun extractContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>
    ): String?
}

class ContextBuilder(
    private val webSearchProvider: WebSearchProvider? = null,
    private val ragProvider: RagProvider? = null,
    private val kgProvider: KgProvider? = null,
    private val taskRepository: ITaskRepository? = null
) {
    suspend fun buildContext(params: ContextBuilderParams): ContextBuilderResult {
        val (searchContext, searchCitations) = if (params.session.options.webSearch) {
            val cleanedQuery = cleanSearchQuery(params.content)
            NexaraLogger.log("[ContextBuilder] 被动联网搜索 Query 提炼: \"${params.content}\" -> \"$cleanedQuery\"")
            performClientSideSearch(cleanedQuery)
        } else "" to emptyList()
        val ragResult = performRagRetrieval(params)
        val tempRagOptions = params.ragOptions ?: com.promenar.nexara.data.model.RagOptions()
        val kgEnabled = (params.session.ragOptions ?: tempRagOptions).enableKnowledgeGraph == true
        val kgContext = if (kgProvider != null && ragResult.second.isNotEmpty() && kgEnabled) {
            try {
                params.onRagProgress?.invoke("KG retrieval", 95, null)
                val result = kgProvider.extractContext(params.content, params.sessionId, ragResult.second) ?: ""
                params.onRagProgress?.invoke("Context ready", 100, null)
                result
            } catch (e: Exception) {
                NexaraLogger.logError("ContextBuilder.KGExtract", e)
                ""
            }
        } else ""

        // 预取任务计划（suspend 调用）
        val activePlan: TaskState? = try {
            taskRepository?.getPlan(params.sessionId)
        } catch (e: Exception) {
            NexaraLogger.log("ContextBuilder: Task plan fetch error: ${e.message?.take(80)}")
            null
        }

        val systemPrompt = buildSystemPrompt(params, ragResult.second, searchContext, kgContext, activePlan)

        return ContextBuilderResult(
            searchContext = searchContext,
            ragContext = ragResult.first,
            citations = searchCitations,
            ragReferences = ragResult.second,
            ragUsage = ragResult.third,
            finalSystemPrompt = systemPrompt
        )
    }

    private fun cleanSearchQuery(rawQuery: String): String {
        var query = rawQuery.trim()
        
        // 1. 过滤常见的口语化前缀和后缀指令
        val prefixes = listOf(
            "帮我搜索一下", "帮我搜索", "请帮我搜索", "请搜索", "查找关于", 
            "请查找关于", "帮我看看", "我想知道", "顺便帮我", "顺便", "你可以帮我",
            "帮我查一下", "查一下", "检索一下", "请检索"
        )
        for (prefix in prefixes) {
            if (query.startsWith(prefix, ignoreCase = true)) {
                query = query.substring(prefix.length).trim()
            }
        }
        
        val suffixes = listOf(
            "顺便写一个摘要", "写一个摘要", "并写个总结", "写个总结", 
            "并总结一下", "总结一下", "并写一段总结", "写一段总结", "谢谢", "并归纳"
        )
        for (suffix in suffixes) {
            if (query.endsWith(suffix, ignoreCase = true)) {
                query = query.substring(0, query.length - suffix.length).trim()
            }
        }
        
        // 2. 替换掉大部分标点符号为空格，让搜索引擎更易处理
        val punctuation = "[\\p{Punct}\\p{P}&&[^.]]".toRegex()
        query = query.replace(punctuation, " ").replace("\\s+".toRegex(), " ").trim()
        
        // 3. 如果处理后依然极长，则截取前 36 个字符以保证搜索精度
        if (query.length > 36) {
            query = query.take(36).trim()
        }
        
        return query.ifBlank { rawQuery }
    }

    private suspend fun performClientSideSearch(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>> {
        if (webSearchProvider == null) return "" to emptyList()
        return try {
            webSearchProvider.search(query)
        } catch (e: Exception) {
            NexaraLogger.logError("ContextBuilder.WebSearch", e)
            "" to emptyList()
        }
    }

    private suspend fun performRagRetrieval(params: ContextBuilderParams): Triple<String, List<RagReference>, RagUsage?> {
        if (ragProvider == null) {
            NexaraLogger.log("[ContextBuilder] ragProvider is null, skipping retrieval")
            return Triple("", emptyList(), null)
        }

        val sessionRagOptions = params.session.ragOptions ?: com.promenar.nexara.data.model.RagOptions()
        val tempRagOptions = params.ragOptions ?: com.promenar.nexara.data.model.RagOptions()

        val finalRagOptions = com.promenar.nexara.data.model.RagOptions(
            enableMemory = tempRagOptions.enableMemory && sessionRagOptions.enableMemory,
            enableDocs = tempRagOptions.enableDocs && sessionRagOptions.enableDocs,
            activeDocIds = tempRagOptions.activeDocIds.ifEmpty { sessionRagOptions.activeDocIds },
            activeFolderIds = tempRagOptions.activeFolderIds.ifEmpty { sessionRagOptions.activeFolderIds },
            isGlobal = tempRagOptions.isGlobal,
            enableRerank = if (params.session.ragOptions == null) tempRagOptions.enableRerank else sessionRagOptions.enableRerank,
            enableKnowledgeGraph = if (params.session.ragOptions == null) tempRagOptions.enableKnowledgeGraph else sessionRagOptions.enableKnowledgeGraph
        )

        NexaraLogger.log("[ContextBuilder] ragOptions: session=${sessionRagOptions.enableDocs}/${sessionRagOptions.enableMemory}, temp=${tempRagOptions.enableDocs}/${tempRagOptions.enableMemory}, final=${finalRagOptions.enableDocs}/${finalRagOptions.enableMemory}, isGlobal=${finalRagOptions.isGlobal}, rerank=${finalRagOptions.enableRerank}")

        val isRagEnabled = finalRagOptions.enableMemory || finalRagOptions.enableDocs
        if (!isRagEnabled) return Triple("", emptyList(), null)

        return try {
            val (context, references, usage) = ragProvider.retrieveContext(
                params.content, params.sessionId, finalRagOptions, params.onRagProgress
            )
            Triple(context, references, usage)
        } catch (e: Exception) {
            NexaraLogger.logError("ContextBuilder.RAGRetrieval", e)
            Triple("", emptyList(), null)
        }
    }

    private fun buildSystemPrompt(
        params: ContextBuilderParams,
        ragReferences: List<RagReference>,
        searchContext: String,
        kgContext: String = "",
        activePlan: TaskState? = null
    ): String {
        val sb = StringBuilder()

        val session = params.session

        // 1. System Time
        val enableTimeInjection = session.options.enableTimeInjection
        if (enableTimeInjection) {
            val now = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss EEEE",
                java.util.Locale.getDefault()
            ).format(java.util.Date())
            sb.appendLine("[System Time: $now]")
            sb.appendLine()
        }

        // 2. Tools Instructions
        if (session.options.toolsEnabled) {
            sb.appendLine("## Tool Usage Guidelines")
            sb.appendLine()
            sb.appendLine("You have access to function calling tools. Use them when you need real-time data, computation, file operations, or task planning.")
            sb.appendLine()
            sb.appendLine("### Calling Tools")
            sb.appendLine("- Use the native function calling mechanism provided by this API. Your tool calls will be automatically intercepted and executed.")
            sb.appendLine("- Each tool's parameters are defined by a JSON Schema. You MUST match the schema exactly — all required fields must be present with correct types.")
            sb.appendLine("- Tool call arguments MUST be valid JSON objects. Do NOT nest or escape the JSON unnecessarily.")
            sb.appendLine()
            sb.appendLine("### Handling Errors")
            sb.appendLine("- If a tool call returns an error, analyze the error message carefully. The most common causes are: missing required arguments, incorrect argument types, or malformed JSON.")
            sb.appendLine("- When you receive an error, DO NOT give up. Instead: (1) identify the specific issue from the error message, (2) correct the arguments, (3) retry the tool with the corrected arguments.")
            sb.appendLine("- You may retry a tool call up to 3 times with progressively corrected arguments before falling back to a text-only response.")
            sb.appendLine()
            sb.appendLine("### Important Constraints")
            sb.appendLine("- When describing or listing available tools for the user, use plain text descriptions only. Do NOT emit any JSON or XML tool call formats in teaching/demonstration scenarios.")
            sb.appendLine("- Only emit actual tool calls when you genuinely need to use the tool to fulfill the user's request.")
            sb.appendLine()
        }
        
        // 3. Task Plan Context
        if (activePlan != null && activePlan.status !in listOf("idle", "dropped")) {
            val economyMode = params.session.options.economyMode
            if (economyMode) {
                appendEconomyTaskContext(sb, activePlan)
            } else {
                appendFullTaskContext(sb, activePlan)
            }
        }

        // 4. Agent System Prompt
        params.agentSystemPrompt?.let { prompt ->
            if (prompt.isNotBlank()) {
                sb.appendLine(prompt)
            }
        }

        // 5. Session Custom Prompt
        val customPrompt = params.sessionCustomPrompt ?: session.customPrompt
        if (!customPrompt.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("## Session Instructions")
            sb.appendLine(customPrompt)
            sb.appendLine()
        }

        // 6. RAG Context (Memory & Docs)
        if (ragReferences.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Retrieved Context")
            ragReferences.forEach { ref ->
                sb.appendLine("- [${ref.source}] ${ref.content.take(400)}") // Increased preview slightly
            }
        }

        // 7. Knowledge Graph Context
        if (kgContext.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Knowledge Graph Relations")
            sb.append(kgContext)
        }

        // 8. Web Search Results
        if (searchContext.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Web Search Results")
            sb.append(searchContext)
        }

        // 9. History Summary
        session.summary?.let { summary ->
            if (summary.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("## History Summary")
                sb.appendLine("<history_summary>")
                sb.appendLine(summary)
                sb.appendLine("</history_summary>")
            }
        }

        return sb.toString()
    }

    /** 完整任务树上下文（economyMode=false） */
    private fun appendFullTaskContext(
        sb: StringBuilder,
        plan: TaskState
    ) {
        val (completed, total) = countLeaves(plan.steps)
        sb.appendLine("## Active Task Plan")
        sb.appendLine("- **Goal**: \"${plan.title}\"")
        sb.appendLine("- **Progress**: $completed/$total leaf steps done")
        plan.currentFocusStepId?.let { focusId ->
            val focus = findStepById(plan.steps, focusId)
            if (focus != null) {
                sb.appendLine("- **Current Focus**: \"${focus.title}\" — ${focus.description}")
            }
        }
        sb.appendLine()
        sb.appendLine("### Task Tree")
        sb.appendLine("```")
        renderTaskTree(sb, plan.steps, indent = 0)
        sb.appendLine("```")
        sb.appendLine()

        // 断点重连提示
        val doingLeaf = findDoingLeaf(plan.steps)
        if (doingLeaf != null) {
            sb.appendLine("[Resume Hint]: Step \"${doingLeaf.title}\" was in progress. Resume from where you left off.")
            sb.appendLine()
        }

        // 下一个待办步骤
        val nextTodos = findNextTodos(plan.steps, maxCount = 3)
        if (nextTodos.isNotEmpty()) {
            sb.appendLine("**Next**: ${nextTodos.joinToString(" → ") { "\"${it.title}\"" }}")
            sb.appendLine()
        }

        sb.appendLine("[Use update_plan to modify status, add/remove/move steps, or set notes.]")
        sb.appendLine("[Use get_plan to re-read the full task tree when context is truncated.]")
        sb.appendLine()
    }

    /** 精简任务上下文（economyMode=true） */
    private fun appendEconomyTaskContext(
        sb: StringBuilder,
        plan: TaskState
    ) {
        val (completed, total) = countLeaves(plan.steps)
        sb.appendLine("## Task Plan (Compact)")
        sb.appendLine("- Goal: \"${plan.title}\" | Progress: $completed/$total steps")
        plan.currentFocusStepId?.let { focusId ->
            val focus = findStepById(plan.steps, focusId)
            if (focus != null) {
                sb.appendLine("- Focus: \"${focus.title}\" — ${focus.description.take(80)}")
            }
        }
        val nextTodos = findNextTodos(plan.steps, maxCount = 1)
        if (nextTodos.isNotEmpty()) {
            sb.appendLine("- Next: \"${nextTodos.first().title}\"")
        }
        sb.appendLine("[Use get_plan to read the full task tree.]")
        sb.appendLine()
    }

    private fun renderTaskTree(sb: StringBuilder, steps: List<TaskStep>, indent: Int) {
        val prefix = "  ".repeat(indent)
        for (step in steps) {
            val icon = when (step.status) {
                "completed" -> "✅"
                "in_progress" -> "⟳"
                "failed" -> "✕"
                "dropped" -> "⊗"
                else -> "○"
            }
            sb.appendLine("$prefix$icon ${step.title}")
            if (step.note != null) {
                sb.appendLine("$prefix   📝 ${step.note.take(120)}")
            }
            if (step.children.isNotEmpty()) {
                renderTaskTree(sb, step.children, indent + 1)
            }
        }
    }

    private fun findStepById(steps: List<TaskStep>, targetId: String): TaskStep? {
        for (step in steps) {
            if (step.id == targetId) return step
            findStepById(step.children, targetId)?.let { return it }
        }
        return null
    }

    private fun findDoingLeaf(steps: List<TaskStep>): TaskStep? {
        for (step in steps) {
            if (step.status == "in_progress" && step.children.isEmpty()) return step
            findDoingLeaf(step.children)?.let { return it }
        }
        return null
    }

    private fun findNextTodos(steps: List<TaskStep>, maxCount: Int): List<TaskStep> {
        val result = mutableListOf<TaskStep>()
        for (step in steps) {
            if (result.size >= maxCount) break
            if (step.status == "pending" && step.children.isEmpty()) {
                result.add(step)
            }
            if (step.children.isNotEmpty()) {
                result.addAll(findNextTodos(step.children, maxCount - result.size))
            }
        }
        return result
    }

    private fun countLeaves(steps: List<TaskStep>): Pair<Int, Int> {
        var completed = 0
        var total = 0
        for (step in steps) {
            if (step.children.isEmpty()) {
                total++
                if (step.status == "completed") completed++
            } else {
                val (c, t) = countLeaves(step.children)
                completed += c
                total += t
            }
        }
        return Pair(completed, total)
    }
}
