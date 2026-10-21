package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.workspace.isWorkspaceSwitchNotice
import com.promenar.nexara.data.agent.AgentMemoryPolicy
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagRetrievalTrace
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.data.model.json
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

data class ContextBuilderResult(
    val searchContext: String,
    val ragContext: String,
    val citations: List<com.promenar.nexara.data.model.Citation>,
    val ragReferences: List<RagReference>,
    val ragUsage: RagUsage?,
    val finalSystemPrompt: String,
    val kgPaths: List<com.promenar.nexara.data.model.KgPath> = emptyList(),
    /** 知识库检索过程记录；未执行检索时为 null。 */
    val ragTrace: RagRetrievalTrace? = null,
    /** 本次构建所用的检索结果；同一次生成的后续工具轮次以 [ContextBuilderParams.cachedRetrieval] 复用。 */
    val retrieval: ContextRetrieval = ContextRetrieval(
        searchContext = searchContext,
        searchCitations = citations,
        ragContext = ragContext,
        ragReferences = ragReferences,
        ragUsage = ragUsage,
        kgContext = "",
        kgPaths = kgPaths,
        ragTrace = ragTrace,
    ),
)

/** 依赖用户问题的检索结果（联网搜索、文档与记忆、知识图谱），与工具轮次无关。 */
data class ContextRetrieval(
    val searchContext: String,
    val searchCitations: List<com.promenar.nexara.data.model.Citation>,
    val ragContext: String,
    val ragReferences: List<RagReference>,
    val ragUsage: RagUsage?,
    val kgContext: String,
    val kgPaths: List<com.promenar.nexara.data.model.KgPath>,
    val ragTrace: RagRetrievalTrace? = null,
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
    /** 助手的记忆权限；会话档位超出上限时按上限执行。 */
    val agentMemoryPolicy: AgentMemoryPolicy = AgentMemoryPolicy(),
    /** 本轮实际广告给模型的工具名；null 表示调用方未提供，按会话开关输出通用工具说明。 */
    val availableToolNames: Set<String>? = null,
    /** 当前会话两级技能扫描结果；提示段落按状态、隐式策略和用户点名筛选。 */
    val availableSkills: List<com.promenar.nexara.data.skills.DiscoveredSkill> = emptyList(),
    /** 同一次生成中首轮的检索结果；非空时跳过联网搜索、RAG 与 KG 检索，只重建系统提示。 */
    val cachedRetrieval: ContextRetrieval? = null,
    /** 会话当前工作区；null 表示调用方未解析，按私有工作区说明。 */
    val workspace: com.promenar.nexara.data.workspace.SessionWorkspace? = null,
    /** 已路由模型的上下文窗口，未知时技能目录使用默认字符预算。 */
    val modelContextTokens: Int? = null,
)

interface WebSearchProvider {
    suspend fun search(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>>
}

/** 知识库检索结果；[trace] 为 null 表示提供方未记录检索过程。 */
data class RagRetrievalResult(
    val context: String,
    val references: List<RagReference>,
    val usage: RagUsage?,
    val trace: RagRetrievalTrace? = null,
)

interface RagProvider {
    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: com.promenar.nexara.data.model.RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null
    ): RagRetrievalResult
}

data class KgContextResult(
    val context: String,
    val paths: List<com.promenar.nexara.data.model.KgPath> = emptyList(),
    val status: com.promenar.nexara.data.rag.KgExtractionStatus = com.promenar.nexara.data.rag.KgExtractionStatus.SUCCESS,
    val error: String? = null,
    val canonicalPaths: Boolean = false,
    val maxContextTokens: Int = 2048,
    /** 本次是否调用了临时抽取（JIT）及其模型；持久图足够时为 0。 */
    val jitExtractionCount: Int = 0,
    val jitModel: String? = null,
)

interface KgProvider {
    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>,
        options: com.promenar.nexara.data.model.RagOptions,
    ): KgContextResult? = extractContext(query, sessionId, topKResults)

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
    private val taskRepository: ITaskRepository? = null,
    /** 记忆或文档检索已开启但未配置嵌入模型时调用，用于给出一次性配置引导。 */
    private val onEmbeddingNotConfigured: () -> Unit = {},
) {
    suspend fun buildContext(params: ContextBuilderParams): ContextBuilderResult {
        val retrieval = params.cachedRetrieval ?: retrieve(params)

        // 预取任务计划（suspend 调用）
        val activePlan: TaskState? = try {
            taskRepository?.getPlan(params.sessionId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            NexaraLogger.logError("ContextBuilder.TaskPlanFetch", e)
            null
        }

        val workspaceSkillsShadowed = (params.workspace as? com.promenar.nexara.data.workspace.SessionWorkspace.Directory)?.let {
            withContext(Dispatchers.IO) {
                java.nio.file.Files.exists(it.root.resolve("skills").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }
        } ?: false
        val systemPrompt = buildSystemPrompt(
            params = params,
            ragContext = retrieval.ragContext,
            ragReferences = retrieval.ragReferences,
            searchContext = retrieval.searchContext,
            kgContext = retrieval.kgContext,
            activePlan = activePlan,
            workspaceSkillsShadowed = workspaceSkillsShadowed,
        )

        return ContextBuilderResult(
            searchContext = retrieval.searchContext,
            ragContext = retrieval.ragContext,
            citations = retrieval.searchCitations,
            ragReferences = retrieval.ragReferences,
            ragUsage = retrieval.ragUsage,
            finalSystemPrompt = systemPrompt,
            kgPaths = retrieval.kgPaths,
            ragTrace = retrieval.ragTrace,
            retrieval = retrieval,
        )
    }

    private suspend fun retrieve(params: ContextBuilderParams): ContextRetrieval {
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
        var jitExtractionCount = 0
        var jitModel: String? = null
        val (kgContext, kgPaths) = if (kgProvider != null && kgEnabled) {
            try {
                params.onRagProgress?.invoke("KG retrieval", 95, null)
                val kgResult = kgProvider.retrieveContext(params.content, params.sessionId, ragResult.references, effectiveRagOptions)
                if (kgResult == null || kgResult.status != com.promenar.nexara.data.rag.KgExtractionStatus.SUCCESS || kgResult.error != null) {
                    params.onRagProgress?.invoke("KG failed", 95, kgResult?.status?.name ?: "PROVIDER_ERROR")
                    "" to emptyList<com.promenar.nexara.data.model.KgPath>()
                } else {
                    params.onRagProgress?.invoke("Context ready", 100, null)
                    jitExtractionCount = kgResult.jitExtractionCount
                    jitModel = kgResult.jitModel
                    val paths = snapshotKgPaths(kgResult.paths)
                    if (kgResult.canonicalPaths) {
                        val fitted = com.promenar.nexara.data.rag.KgContextBudget.fit(paths, kgResult.maxContextTokens)
                        fitted.context to fitted.paths
                    } else kgResult.context to paths
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                NexaraLogger.logError("ContextBuilder.KGExtract", e)
                params.onRagProgress?.invoke("KG failed", 95, "PROVIDER_ERROR")
                "" to emptyList<com.promenar.nexara.data.model.KgPath>()
            }
        } else "" to emptyList<com.promenar.nexara.data.model.KgPath>()

        return ContextRetrieval(
            searchContext = searchContext,
            searchCitations = searchCitations,
            ragContext = ragResult.context,
            ragReferences = ragResult.references,
            ragUsage = ragResult.usage,
            kgContext = kgContext,
            kgPaths = kgPaths,
            // 未配置嵌入模型或没有检索范围时检索并未执行：不记录轨迹，回复中不出现检索步骤。
            ragTrace = ragResult.trace?.takeUnless { it.isSkipped }?.copy(
                memoryScope = effectiveRagOptions.effectiveMemoryScope.takeIf { effectiveRagOptions.enableMemory },
                graphScope = effectiveRagOptions.effectiveGraphScope.takeIf { kgEnabled },
                documentScope = effectiveRagOptions.effectiveDocumentScope.takeIf { effectiveRagOptions.enableDocs },
                jitExtractionCount = jitExtractionCount,
                jitModel = jitModel,
            ),
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
            memoryScope = (params.ragOptions ?: sessionRagOptions).effectiveMemoryScope,
            graphScope = (params.ragOptions ?: sessionRagOptions).effectiveGraphScope,
            documentScope = (params.ragOptions ?: sessionRagOptions).effectiveDocumentScope,
            enableRerank = if (params.session.ragOptions == null) tempRagOptions.enableRerank else sessionRagOptions.enableRerank,
            enableKnowledgeGraph = if (params.session.ragOptions == null) tempRagOptions.enableKnowledgeGraph else sessionRagOptions.enableKnowledgeGraph
        )
        return params.agentMemoryPolicy.clamp(applyAgentRetrievalConfig(mergedRagOptions, params.agentRetrievalConfig))
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
        val omitted = paths.size - accepted.size
        return when {
            omitted == 0 -> accepted
            accepted.isEmpty() -> listOf(com.promenar.nexara.data.model.KgPath(
                origins = emptyList(), truncated = true, omittedPathCount = omitted,
            ))
            else -> accepted.map { path -> path.copy(truncated = true, omittedPathCount = path.omittedPathCount + omitted) }
        }
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
    ): RagRetrievalResult {
        val skipped = RagRetrievalResult("", emptyList(), null)
        val isRagEnabled = effectiveRagOptions.enableMemory || effectiveRagOptions.enableDocs
        if (!isRagEnabled) return skipped
        if (ragProvider == null) {
            NexaraLogger.log("[ContextBuilder] 检索 Provider 不可用，跳过检索")
            return skipped
        }

        NexaraLogger.log("[ContextBuilder] effective ragOptions: docs=${effectiveRagOptions.enableDocs}/memory=${effectiveRagOptions.enableMemory}, scope=${effectiveRagOptions.memoryScope}/${effectiveRagOptions.graphScope}/${effectiveRagOptions.documentScope}, rerank=${effectiveRagOptions.enableRerank}, kg=${effectiveRagOptions.enableKnowledgeGraph}")

        return try {
            val result = ragProvider.retrieveContext(
                params.content, params.sessionId, effectiveRagOptions, params.onRagProgress
            )
            if (result.trace?.outcome == RagRetrievalTrace.OUTCOME_UNAVAILABLE) onEmbeddingNotConfigured()
            if (result.trace != null) result else result.copy(
                trace = RagRetrievalTrace(
                    outcome = if (result.context.isNotBlank() || result.references.isNotEmpty()) {
                        RagRetrievalTrace.OUTCOME_HIT
                    } else RagRetrievalTrace.OUTCOME_EMPTY,
                    selectedCount = result.references.size,
                ),
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            NexaraLogger.logError("ContextBuilder.RAGRetrieval", e)
            skipped.copy(trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED))
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
        activePlan: TaskState? = null,
        workspaceSkillsShadowed: Boolean = false,
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
            else -> if (names.isEmpty()) "" else SystemPromptSections.toolGuidance(
                names,
                generic = false,
                workspace = params.workspace,
                workspaceChanged = session.messages.any { it.isWorkspaceSwitchNotice },
                workspaceSkillsShadowed = workspaceSkillsShadowed,
            )
        }
        if (toolGuidance.isNotEmpty()) {
            sb.appendLine()
            sb.append(toolGuidance)
        }

        // 3.1 两级技能目录：只在模型有读取入口时披露元数据，正文按需加载。
        if (SystemPromptSections.canReadSkills(params.availableToolNames)) {
            SystemPromptSections.skillCatalog(params.availableSkills, params.content, params.modelContextTokens)
                .takeIf(String::isNotEmpty)?.let {
                    sb.appendLine()
                    sb.append(it)
                }
        }

        // 4. 任务计划
        if (activePlan != null && activePlan.status !in listOf("idle", "dropped")) {
            sb.appendLine()
            appendTaskContext(sb, activePlan)
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

    /** 已完成步骤仅保留标题，当前与未完成步骤保留完整说明。 */
    private fun appendTaskContext(
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
                val description = if (focus.status in setOf("completed", "done")) "" else " — ${focus.description}"
                sb.appendLine("- **Current Focus**: \"${focus.title}\"$description")
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
            if (step.status !in setOf("completed", "done")) {
                if (step.description.isNotBlank()) sb.appendLine("$prefix   ${step.description}")
                step.note?.let { sb.appendLine("$prefix   📝 $it") }
                step.artifactFileUuids?.takeIf(List<String>::isNotEmpty)?.let {
                    sb.appendLine("$prefix   Artifacts: ${it.joinToString(", ")}")
                }
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
