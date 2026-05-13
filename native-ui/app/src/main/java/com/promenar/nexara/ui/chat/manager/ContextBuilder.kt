package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session

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
    val agentSystemPrompt: String? = null
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
    private val kgProvider: KgProvider? = null
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
        val systemPrompt = buildSystemPrompt(params, ragResult.second, searchContext, kgContext)

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
        kgContext: String = ""
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
        
        // 3. Task Information
        if (session.activeTask != null && session.activeTask?.status == "in-progress") {
            val task = session.activeTask!!
            val currentStepIndex = task.steps.indexOfFirst { it.status == "pending" }
            val currentStep = task.steps.getOrNull(currentStepIndex)
            sb.appendLine("## Active Task")
            sb.appendLine("- **Current Task**: \"${task.title}\"")
            sb.appendLine("- **Immediate Goal**: ${currentStep?.description ?: "Review and Complete Task"}")
            sb.appendLine()
        }

        // 4. Agent System Prompt
        params.agentSystemPrompt?.let { prompt ->
            if (prompt.isNotBlank()) {
                sb.appendLine(prompt)
            }
        }

        // 5. Session Custom Prompt
        if (session.customPrompt != null) {
            sb.appendLine()
            sb.appendLine(session.customPrompt)
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
}
