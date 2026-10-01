package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.data.model.json
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.serialization.encodeToString

data class ContextBuilderResult(
    val searchContext: String,
    val ragContext: String,
    val citations: List<com.promenar.nexara.data.model.Citation>,
    val ragReferences: List<RagReference>,
    val ragUsage: RagUsage?,
    val finalSystemPrompt: String,
    val kgPaths: List<com.promenar.nexara.data.model.KgPath> = emptyList()
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
    val sessionCustomPrompt: String? = null,
    val agentRetrievalConfig: AgentRetrievalConfig? = null,
    /** 本轮实际广告给模型的工具名；null 表示调用方未提供，按会话开关输出通用工具说明。 */
    val availableToolNames: Set<String>? = null,
    /** 已启用且可通过 activate_skill 加载的技能（ADR-022）。 */
    val availableSkills: List<com.promenar.nexara.data.skills.AgentSkillMetadata> = emptyList(),
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

data class KgContextResult(
    val context: String,
    val paths: List<com.promenar.nexara.data.model.KgPath> = emptyList()
)

interface KgProvider {
    suspend fun extractContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>
    ): KgContextResult?
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
            NexaraLogger.log("[ContextBuilder] 被动联网搜索 Query 已提炼: inputChars=${params.content.length}, outputChars=${cleanedQuery.length}")
            performClientSideSearch(cleanedQuery)
        } else "" to emptyList()

        // effective RagOptions 只计算一次：RAG 与 KG 共享同一份配置（含 agent 覆盖）
        val effectiveRagOptions = computeEffectiveRagOptions(params)

        val ragResult = performRagRetrieval(params, effectiveRagOptions)

        // KG 启用判定与 RAG 共享 effective 配置：agent enableKnowledgeGraph=false 可覆盖 session true
        val kgEnabled = effectiveRagOptions.enableKnowledgeGraph == true
        val (kgContext, kgPaths) = if (kgProvider != null && ragResult.second.isNotEmpty() && kgEnabled) {
            try {
                params.onRagProgress?.invoke("KG retrieval", 95, null)
                val kgResult = kgProvider.extractContext(params.content, params.sessionId, ragResult.second)
                params.onRagProgress?.invoke("Context ready", 100, null)
                (kgResult?.context ?: "") to snapshotKgPaths(kgResult?.paths)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                NexaraLogger.logError("ContextBuilder.KGExtract", e)
                "" to emptyList<com.promenar.nexara.data.model.KgPath>()
            }
        } else "" to emptyList<com.promenar.nexara.data.model.KgPath>()

        // 预取任务计划（suspend 调用）
        val activePlan: TaskState? = try {
            taskRepository?.getPlan(params.sessionId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            NexaraLogger.logError("ContextBuilder.TaskPlanFetch", e)
            null
        }

        val systemPrompt = buildSystemPrompt(
            params = params,
            ragContext = ragResult.first,
            ragReferences = ragResult.second,
            searchContext = searchContext,
            kgContext = kgContext,
            activePlan = activePlan
        )

        return ContextBuilderResult(
            searchContext = searchContext,
            ragContext = ragResult.first,
            citations = searchCitations,
            ragReferences = ragResult.second,
            ragUsage = ragResult.third,
            finalSystemPrompt = systemPrompt,
            kgPaths = kgPaths
        )
    }

    /**
     * 合并 session/temp RagOptions 后应用 agent 检索配置，作为 RAG 与 KG 共用的唯一 effective 配置。
     * agent.enableKnowledgeGraph=false 可强制关闭 KG（覆盖 session 的 true）。
     */
    private fun computeEffectiveRagOptions(params: ContextBuilderParams): com.promenar.nexara.data.model.RagOptions {
        val sessionRagOptions = params.session.ragOptions ?: com.promenar.nexara.data.model.RagOptions()
        val tempRagOptions = params.ragOptions ?: com.promenar.nexara.data.model.RagOptions()

        val mergedRagOptions = com.promenar.nexara.data.model.RagOptions(
            enableMemory = tempRagOptions.enableMemory && sessionRagOptions.enableMemory,
            enableDocs = tempRagOptions.enableDocs && sessionRagOptions.enableDocs,
            activeDocIds = tempRagOptions.activeDocIds.ifEmpty { sessionRagOptions.activeDocIds },
            activeFolderIds = tempRagOptions.activeFolderIds.ifEmpty { sessionRagOptions.activeFolderIds },
            isGlobal = tempRagOptions.isGlobal,
            enableRerank = if (params.session.ragOptions == null) tempRagOptions.enableRerank else sessionRagOptions.enableRerank,
            enableKnowledgeGraph = if (params.session.ragOptions == null) tempRagOptions.enableKnowledgeGraph else sessionRagOptions.enableKnowledgeGraph
        )
        return applyAgentRetrievalConfig(mergedRagOptions, params.agentRetrievalConfig)
    }

    /**
     * 对 provider 返回的 paths 做嵌套防御性快照：复制每条 path 及其内部列表，
     * 使 provider 后续对内部可变集合的修改不影响 [ContextBuilderResult]。
     */
    internal fun snapshotKgPaths(
        paths: List<com.promenar.nexara.data.model.KgPath>?
    ): List<com.promenar.nexara.data.model.KgPath> {
        if (paths.isNullOrEmpty()) return emptyList()
        val accepted = mutableListOf<com.promenar.nexara.data.model.KgPath>()
        for (path in paths.take(MAX_KG_PATHS)) {
            val nodes = path.nodes.asSequence()
                .take(MAX_KG_NODES_PER_PATH)
                .map { node ->
                    node.copy(
                        id = node.id.bounded(MAX_KG_TEXT_CHARS),
                        label = node.label.bounded(MAX_KG_TEXT_CHARS),
                        type = node.type.bounded(MAX_KG_TEXT_CHARS),
                        metadata = node.metadata?.bounded(MAX_KG_LONG_TEXT_CHARS),
                    )
                }
                .toList()
            val nodeIds = nodes.mapTo(hashSetOf()) { it.id }
            val candidate = path.copy(
                queryKeywords = path.queryKeywords.asSequence()
                    .take(MAX_KG_KEYWORDS_PER_PATH)
                    .map { it.bounded(MAX_KG_TEXT_CHARS) }
                    .toList(),
                nodes = nodes,
                edges = path.edges.asSequence()
                    .map { edge ->
                        edge.copy(
                            sourceId = edge.sourceId.bounded(MAX_KG_TEXT_CHARS),
                            targetId = edge.targetId.bounded(MAX_KG_TEXT_CHARS),
                            relation = edge.relation.bounded(MAX_KG_TEXT_CHARS),
                        )
                    }
                    .filter { it.sourceId in nodeIds && it.targetId in nodeIds }
                    .take(MAX_KG_EDGES_PER_PATH)
                    .toList(),
                reasoning = path.reasoning?.bounded(MAX_KG_LONG_TEXT_CHARS),
            )
            val candidateSnapshot = accepted + candidate
            if (json.encodeToString(candidateSnapshot).toByteArray(Charsets.UTF_8).size > MAX_KG_SERIALIZED_BYTES) {
                break
            }
            accepted += candidate
        }
        return accepted
    }

    private fun String.bounded(maxChars: Int): String {
        if (length <= maxChars) return this
        val safeEnd = if (
            maxChars > 0 && this[maxChars - 1].isHighSurrogate() && this[maxChars].isLowSurrogate()
        ) maxChars - 1 else maxChars
        return substring(0, safeEnd)
    }

    private companion object {
        const val MAX_KG_PATHS = 32
        const val MAX_KG_KEYWORDS_PER_PATH = 32
        const val MAX_KG_NODES_PER_PATH = 128
        const val MAX_KG_EDGES_PER_PATH = 256
        const val MAX_KG_TEXT_CHARS = 512
        const val MAX_KG_LONG_TEXT_CHARS = 4096
        const val MAX_KG_SERIALIZED_BYTES = 512 * 1024
    }

    private fun cleanSearchQuery(rawQuery: String): String {
        return rawQuery.trim().replace("\\s+".toRegex(), " ")
    }

    private suspend fun performClientSideSearch(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>> {
        if (webSearchProvider == null) return "" to emptyList()
        return try {
            webSearchProvider.search(query)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            NexaraLogger.logError("ContextBuilder.WebSearch", e)
            "" to emptyList()
        }
    }

    private suspend fun performRagRetrieval(
        params: ContextBuilderParams,
        effectiveRagOptions: com.promenar.nexara.data.model.RagOptions
    ): Triple<String, List<RagReference>, RagUsage?> {
        if (ragProvider == null) {
            NexaraLogger.log("[ContextBuilder] ragProvider is null, skipping retrieval")
            return Triple("", emptyList(), null)
        }

        NexaraLogger.log("[ContextBuilder] effective ragOptions: docs=${effectiveRagOptions.enableDocs}/memory=${effectiveRagOptions.enableMemory}, isGlobal=${effectiveRagOptions.isGlobal}, rerank=${effectiveRagOptions.enableRerank}, kg=${effectiveRagOptions.enableKnowledgeGraph}")

        val isRagEnabled = effectiveRagOptions.enableMemory || effectiveRagOptions.enableDocs
        if (!isRagEnabled) return Triple("", emptyList(), null)

        return try {
            val (context, references, usage) = ragProvider.retrieveContext(
                params.content, params.sessionId, effectiveRagOptions, params.onRagProgress
            )
            Triple(context, references, usage)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            NexaraLogger.logError("ContextBuilder.RAGRetrieval", e)
            Triple("", emptyList(), null)
        }
    }

    private fun applyAgentRetrievalConfig(
        base: com.promenar.nexara.data.model.RagOptions,
        agentConfig: AgentRetrievalConfig?
    ): com.promenar.nexara.data.model.RagOptions {
        if (agentConfig == null) return base
        return base.copy(
            enableMemory = base.enableMemory && agentConfig.enableMemory,
            enableDocs = base.enableDocs && agentConfig.enableDocs,
            // agent 的 KG 关闭可强制覆盖 session 的开启；session 未显式开启时仍保持关闭
            enableKnowledgeGraph = base.enableKnowledgeGraph == true && agentConfig.enableKnowledgeGraph,
            enableRerank = base.enableRerank && agentConfig.enableRerank,
            memoryLimit = agentConfig.memoryLimit,
            memoryThreshold = agentConfig.memoryThreshold,
            docLimit = agentConfig.docLimit,
            docThreshold = agentConfig.docThreshold,
            rerankTopK = agentConfig.rerankTopK,
            rerankFinalK = agentConfig.rerankFinalK,
            enableQueryRewrite = agentConfig.enableQueryRewrite,
            queryRewriteStrategy = agentConfig.queryRewriteStrategy,
            queryRewriteCount = agentConfig.queryRewriteCount,
            enableHybridSearch = agentConfig.enableHybridSearch,
            hybridAlpha = agentConfig.hybridAlpha,
            hybridBM25Boost = agentConfig.hybridBM25Boost
        )
    }

    private fun buildSystemPrompt(
        params: ContextBuilderParams,
        ragContext: String,
        ragReferences: List<RagReference>,
        searchContext: String,
        kgContext: String = "",
        activePlan: TaskState? = null
    ): String {
        val sb = StringBuilder()
        val session = params.session

        // 1. 身份：Agent 提示词优先，未配置时使用基础身份。
        val agentPrompt = params.agentSystemPrompt?.takeIf(String::isNotBlank)
        sb.appendLine(agentPrompt?.trim() ?: SystemPromptSections.DEFAULT_IDENTITY)

        // 2. 会话级指令
        val customPrompt = params.sessionCustomPrompt ?: session.customPrompt
        if (!customPrompt.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("## Session Instructions")
            sb.appendLine(customPrompt.trim())
        }

        // 3. 工具与工作区运行时契约
        val toolGuidance = when (val names = params.availableToolNames) {
            null -> if (session.options.toolsEnabled) SystemPromptSections.toolGuidance(emptySet(), generic = true) else ""
            else -> if (names.isEmpty()) "" else SystemPromptSections.toolGuidance(names, generic = false)
        }
        if (toolGuidance.isNotEmpty()) {
            sb.appendLine()
            sb.append(toolGuidance)
        }

        // 3.1 技能目录：只给出名称与描述，正文由 activate_skill 按需加载。
        SystemPromptSections.skillCatalog(params.availableSkills).takeIf(String::isNotEmpty)?.let {
            sb.appendLine()
            sb.append(it)
        }

        // 4. 任务计划
        if (activePlan != null && activePlan.status !in listOf("idle", "dropped")) {
            sb.appendLine()
            if (params.session.options.economyMode) {
                appendEconomyTaskContext(sb, activePlan)
            } else {
                appendFullTaskContext(sb, activePlan)
            }
        }

        // 5. 外部检索数据：只作为资料，不作为指令。
        val references = buildString {
            if (ragContext.isNotBlank()) {
                appendLine("### Retrieved Context")
                appendLine(ragContext.trim())
            } else if (ragReferences.isNotEmpty()) {
                appendLine("### Retrieved Context")
                ragReferences.forEach { ref -> appendLine("- [${ref.source}] ${ref.content.take(400)}") }
            }
            if (kgContext.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Knowledge Graph Relations")
                appendLine(kgContext.trim())
            }
            if (searchContext.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Web Search Results")
                appendLine(searchContext.trim())
            }
        }
        if (references.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Reference Material")
            sb.appendLine(SystemPromptSections.DATA_BOUNDARY)
            sb.appendLine("<reference_material>")
            sb.append(references)
            sb.appendLine("</reference_material>")
        }

        // 6. 历史摘要
        session.summary?.takeIf(String::isNotBlank)?.let { summary ->
            sb.appendLine()
            sb.appendLine("## History Summary")
            sb.appendLine("Earlier turns of this conversation were condensed into the summary below.")
            sb.appendLine("<history_summary>")
            sb.appendLine(summary.trim())
            sb.appendLine("</history_summary>")
        }

        // 7. 易变环境信息放在末尾，减少对前缀缓存的破坏。
        if (session.options.enableTimeInjection) {
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.US).format(java.util.Date())
            sb.appendLine()
            sb.appendLine("[System Time: $now (${java.util.TimeZone.getDefault().id})]")
        }

        return sb.toString().trimEnd() + "\n"
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
                "completed", "done" -> "✅"
                "in_progress", "doing" -> "⟳"
                "failed", "error" -> "✕"
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
            if (step.status in setOf("in_progress", "doing") && step.children.isEmpty()) return step
            findDoingLeaf(step.children)?.let { return it }
        }
        return null
    }

    private fun findNextTodos(steps: List<TaskStep>, maxCount: Int): List<TaskStep> {
        val result = mutableListOf<TaskStep>()
        for (step in steps) {
            if (result.size >= maxCount) break
            if (step.status in setOf("pending", "todo") && step.children.isEmpty()) {
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
                if (step.status in setOf("completed", "done")) completed++
            } else {
                val (c, t) = countLeaves(step.children)
                completed += c
                total += t
            }
        }
        return Pair(completed, total)
    }
}
