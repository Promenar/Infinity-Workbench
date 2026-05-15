package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.ITaskRepository

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
        val searchContext = if (params.session.options.webSearch) {
            performClientSideSearch(params.content)
        } else ""
        val ragResult = performRagRetrieval(params)
        val kgEnabled = params.session.ragOptions?.enableKnowledgeGraph ?: false
        val kgContext = if (kgProvider != null && ragResult.second.isNotEmpty() && kgEnabled) {
            try {
                kgProvider.extractContext(params.content, params.sessionId, ragResult.second) ?: ""
            } catch (_: Exception) { "" }
        } else ""

        // 预取任务计划（suspend 调用）
        val activePlan: TaskState? = try {
            taskRepository?.getPlan(params.sessionId)
        } catch (_: Exception) { null }

        val systemPrompt = buildSystemPrompt(params, ragResult.second, searchContext, kgContext, activePlan)

        return ContextBuilderResult(
            searchContext = searchContext,
            ragContext = ragResult.first,
            citations = emptyList(),
            ragReferences = ragResult.second,
            ragUsage = ragResult.third,
            finalSystemPrompt = systemPrompt
        )
    }

    private suspend fun performClientSideSearch(query: String): String {
        if (webSearchProvider == null) return ""
        return try {
            val (context, _) = webSearchProvider.search(query)
            context
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun performRagRetrieval(params: ContextBuilderParams): Triple<String, List<RagReference>, RagUsage?> {
        if (ragProvider == null) return Triple("", emptyList(), null)

        val sessionRagOptions = params.session.ragOptions ?: com.promenar.nexara.data.model.RagOptions()
        val tempRagOptions = params.ragOptions ?: com.promenar.nexara.data.model.RagOptions()

        val finalRagOptions = com.promenar.nexara.data.model.RagOptions(
            enableMemory = tempRagOptions.enableMemory && sessionRagOptions.enableMemory,
            enableDocs = tempRagOptions.enableDocs && sessionRagOptions.enableDocs,
            activeDocIds = tempRagOptions.activeDocIds.ifEmpty { sessionRagOptions.activeDocIds },
            activeFolderIds = tempRagOptions.activeFolderIds.ifEmpty { sessionRagOptions.activeFolderIds },
            isGlobal = tempRagOptions.isGlobal,
            enableRerank = sessionRagOptions.enableRerank
        )

        val isRagEnabled = finalRagOptions.enableMemory || finalRagOptions.enableDocs
        if (!isRagEnabled) return Triple("", emptyList(), null)

        return try {
            val (context, references, usage) = ragProvider.retrieveContext(
                params.content, params.sessionId, finalRagOptions, params.onRagProgress
            )
            Triple(context, references, usage)
        } catch (_: Exception) {
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
            sb.appendLine("[You have access to function calling tools. Use them when needed to provide accurate and up-to-date responses.]")
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
                sb.appendLine("$prefix   📝 ${step.note!!.take(120)}")
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
