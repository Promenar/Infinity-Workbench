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
    val agentRetrievalConfig: AgentRetrievalConfig? = null
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
        var query = rawQuery.trim()
        
        // 1. 替换大部分标点符号为空格，并合并连续的空白字符
        val punctuation = "[\\p{Punct}\\p{P}&&[^.]]".toRegex()
        query = query.replace(punctuation, " ").replace("\\s+".toRegex(), " ").trim()
        
        // 2. 剥离中英文最常见的“请求/意图”式前缀（多次循环剥离，确保极度干净）
        val prefixes = listOf(
            "帮我搜索一下", "帮我搜索", "请帮我搜索", "请搜索", "查找关于", 
            "请查找关于", "帮我看看", "我想知道", "顺便帮我", "顺便", "你可以帮我",
            "帮我查一下", "查一下", "检索一下", "请检索", "请教一下", "你知道",
            "告诉我关于", "告诉我", "我想了解", "我想了解关于", "我想问", "我想问一下",
            "请告诉我", "谁能告诉我", "怎么查询", "我想查询", "查询一下", "请问一下", "请问",
            "能不能帮我", "能不能", "帮我看一下", "帮我检索一下", "帮我检索",
            "你能帮我", "你能", "帮我科普一下", "科普一下", "科普", "你能科普一下",
            "please search about", "please search for", "please search", "search for", 
            "search about", "find out about", "look up", "can you search", "help me search",
            "tell me about", "tell me", "do you know", "could you tell me", "i want to know about",
            "i want to know", "find information about", "give me information about"
        )
        
        var oldQuery: String
        do {
            oldQuery = query
            for (prefix in prefixes) {
                if (query.startsWith(prefix, ignoreCase = true)) {
                    query = query.substring(prefix.length).trim()
                }
                // 处理可能被标点符号替换出的首部空格，例如 " 帮我搜索" 或是 "prefix " 情况
                val prefixWithSpace = "$prefix "
                if (query.startsWith(prefixWithSpace, ignoreCase = true)) {
                    query = query.substring(prefixWithSpace.length).trim()
                }
            }
        } while (query != oldQuery)

        // 3. 剥离中英文常见的“口语提问”疑问/指令式前缀（例如：“什么是”、“how to”）
        val questionPrefixes = listOf(
            "什么是", "什么叫", "关于", "介绍一下", "科普一下", "简单说说", 
            "解释一下", "详细解释", "如何理解", "怎么理解", "怎么做", "为什么", "如何", 
            "how to", "what is", "what are", "who is", "who are", "why does", "why is",
            "how does", "define", "definition of", "explain", "briefly explain", "introduction to",
            "details of", "about", "the difference between", "difference between", "the differences between", 
            "differences between"
        )
        
        do {
            oldQuery = query
            for (qp in questionPrefixes) {
                if (query.startsWith(qp, ignoreCase = true)) {
                    query = query.substring(qp.length).trim()
                }
                val qpWithSpace = "$qp "
                if (query.startsWith(qpWithSpace, ignoreCase = true)) {
                    query = query.substring(qpWithSpace.length).trim()
                }
            }
        } while (query != oldQuery)

        // 4. 剥离中英文常见的口语化/礼貌性或干扰性后缀（多次循环剥离）
        val suffixes = listOf(
            "顺便写一个摘要", "写一个摘要", "并写个总结", "写个总结", 
            "并总结一下", "总结一下", "并写一段总结", "写一段总结", "谢谢你", "谢谢您", "谢谢", "并归纳",
            "到底是什么意思", "是什么意思", "是什么", "什么意思", "怎么回事", "是怎么回事", 
            "有哪些", "有什么区别", "的区别", "吗", "呢", "吧", "啊",
            "thank you very much", "thank you", "thanks", "please", "summarize it", "make a summary",
            "and write a summary", "and summarize", "mean", "what does it mean",
            "difference between", "definition", "meaning"
        )
        
        do {
            oldQuery = query
            for (suffix in suffixes) {
                if (query.endsWith(suffix, ignoreCase = true)) {
                    query = query.substring(0, query.length - suffix.length).trim()
                }
                val suffixWithSpace = " $suffix"
                if (query.endsWith(suffixWithSpace, ignoreCase = true)) {
                    query = query.substring(0, query.length - suffixWithSpace.length).trim()
                }
            }
        } while (query != oldQuery)

        // 5. 过滤掉单纯的语气助词和高频无关连词（仅针对搜索词最开头或最末尾，去除词网粘连）
        val grammarParticles = listOf("的", "了", "和", "与", "及", "或", "之", "about", "and", "or", "of", "with")
        do {
            oldQuery = query
            for (gp in grammarParticles) {
                val gpWithSpaceStart = "$gp "
                if (query.startsWith(gpWithSpaceStart, ignoreCase = true)) {
                    query = query.substring(gpWithSpaceStart.length).trim()
                } else if (query.equals(gp, ignoreCase = true)) {
                    query = ""
                }
                
                val gpWithSpaceEnd = " $gp"
                if (query.endsWith(gpWithSpaceEnd, ignoreCase = true)) {
                    query = query.substring(0, query.length - gpWithSpaceEnd.length).trim()
                } else if (query.equals(gp, ignoreCase = true)) {
                    query = ""
                }
            }
        } while (query != oldQuery && query.isNotEmpty())

        // 6. 智能多国语动态长度截断（防止英文单词被拦腰折断）
        val hasLatin = query.any { it in 'a'..'z' || it in 'A'..'Z' }
        val maxLen = if (hasLatin) 80 else 36
        
        if (query.length > maxLen) {
            val truncated = query.take(maxLen)
            query = if (hasLatin && truncated.contains(" ")) {
                truncated.substringBeforeLast(" ").trim()
            } else {
                truncated.trim()
            }
        }
        
        // 7. 若清洗后完全为空，则安全降级回退到去除多余空白的最原始查询
        val finalResult = query.trim()
        return if (finalResult.isBlank()) rawQuery.trim().replace("\\s+".toRegex(), " ") else finalResult
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
        if (ragContext.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("## Retrieved Context")
            sb.appendLine(ragContext)
        } else if (ragReferences.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Retrieved Context")
            ragReferences.forEach { ref ->
                sb.appendLine("- [${ref.source}] ${ref.content.take(400)}")
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
