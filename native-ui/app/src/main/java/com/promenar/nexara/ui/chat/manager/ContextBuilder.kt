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
    val onRagProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null
)

interface WebSearchProvider {
    suspend fun search(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>>
}

interface RagProvider {
    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: com.promenar.nexara.data.model.RagOptions
    ): Triple<String, List<RagReference>, RagUsage?>
}

class ContextBuilder(
    private val webSearchProvider: WebSearchProvider? = null,
    private val ragProvider: RagProvider? = null
) {
    suspend fun buildContext(params: ContextBuilderParams): ContextBuilderResult {
        val searchContext = performClientSideSearch(params.content)
        val ragResult = performRagRetrieval(params)
        val systemPrompt = buildSystemPrompt(params, ragResult.second, searchContext)

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
            isGlobal = tempRagOptions.isGlobal
        )

        val isRagEnabled = finalRagOptions.enableMemory || finalRagOptions.enableDocs
        if (!isRagEnabled) return Triple("", emptyList(), null)

        return try {
            val (context, references, usage) = ragProvider.retrieveContext(
                params.content, params.sessionId, finalRagOptions
            )
            Triple(context, references, usage)
        } catch (_: Exception) {
            Triple("", emptyList(), null)
        }
    }

    private fun buildSystemPrompt(
        params: ContextBuilderParams,
        ragReferences: List<RagReference>,
        searchContext: String
    ): String {
        val sb = StringBuilder()

        val session = params.session

        val enableTimeInjection = session.options?.enableTimeInjection ?: true
        if (enableTimeInjection) {
            val now = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss EEEE",
                java.util.Locale.getDefault()
            ).format(java.util.Date())
            sb.appendLine("[System Time: $now]")
            sb.appendLine()
        }

        if (session.activeTask != null && session.activeTask?.status == "in-progress") {
            val task = session.activeTask!!
            val currentStepIndex = task.steps.indexOfFirst { it.status == "pending" }
            val currentStep = task.steps.getOrNull(currentStepIndex)
            sb.appendLine("## Active Task")
            sb.appendLine("- **Current Task**: \"${task.title}\"")
            sb.appendLine("- **Immediate Goal**: ${currentStep?.description ?: "Review and Complete Task"}")
            sb.appendLine()
        }

        sb.append(session.agentId)

        if (session.customPrompt != null) {
            sb.appendLine()
            sb.appendLine(session.customPrompt)
        }

        if (ragReferences.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Retrieved Context")
            ragReferences.forEach { ref ->
                sb.appendLine("- [${ref.source}] ${ref.content.take(200)}")
            }
        }

        if (searchContext.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Web Search Results")
            sb.append(searchContext)
        }

        return sb.toString()
    }
}
