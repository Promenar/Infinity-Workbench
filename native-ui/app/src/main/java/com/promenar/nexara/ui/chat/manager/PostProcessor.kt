package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.BillingUsage
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionStats
import com.promenar.nexara.data.model.TokenMetric
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.ui.chat.ChatStore

data class PostProcessorParams(
    val sessionId: String,
    val assistantMsgId: String,
    val userMsgId: String,
    val userContent: String,
    val assistantContent: String,
    val agent: com.promenar.nexara.data.model.Agent,
    val session: Session,
    val ragEnabled: Boolean,
    val ragUsage: RagUsage? = null,
    val accumulatedUsage: TokenUsage? = null,
    val totalContextTokens: Int = 0,
    val modelId: String,
    val providerId: String? = null,
    val providerName: String? = null
)

class PostProcessor(
    private val store: ChatStore,
    private val sessionManager: SessionManager,
    private val messageManager: MessageManager
) {
    private var onGenerateTitle: (suspend (sessionId: String) -> String?)? = null

    fun setTitleGenerator(generator: (suspend (sessionId: String) -> String?)?) {
        onGenerateTitle = generator
    }

    suspend fun updateStats(params: PostProcessorParams) {
        val finalUsage = params.accumulatedUsage ?: TokenUsage(
            input = params.totalContextTokens,
            output = estimateTokens(params.assistantContent)
        )
        val total = finalUsage.input + finalUsage.output
        val usage = finalUsage.copy(total = total)

        val billingUsage = BillingUsage(
            chatInput = TokenMetric(usage.input, params.accumulatedUsage == null),
            chatOutput = TokenMetric(usage.output, params.accumulatedUsage == null),
            ragSystem = params.ragUsage?.let { TokenMetric(it.ragSystem, it.isEstimated) } ?: TokenMetric(),
            total = total + (params.ragUsage?.ragSystem ?: 0),
            costUSD = 0.0
        )

        sessionManager.updateSession(
            params.sessionId,
            mapOf("stats" to SessionStats(totalTokens = billingUsage.total, billing = billingUsage))
        )

        val isSuperAssistant = params.sessionId == "super_assistant" ||
                params.session.agentId == "super_assistant"

        val isDefaultTitle = params.session.title == params.agent.name ||
                params.session.title == "New Conversation" ||
                params.session.title == "New Chat" ||
                params.session.title.startsWith("New Conversation") ||
                params.session.title.startsWith("New Chat")

        if (!isSuperAssistant && (params.session.messages.size <= 2 || isDefaultTitle)) {
            val titleGenerator = onGenerateTitle
            if (titleGenerator != null) {
                try {
                    val title = titleGenerator(params.sessionId)
                    if (title != null) {
                        sessionManager.updateSessionTitle(params.sessionId, title)
                    }
                } catch (_: Exception) {
                    val titleLimit = 15
                    val title = params.userContent.take(titleLimit) +
                            if (params.userContent.length > titleLimit) "..." else ""
                    sessionManager.updateSessionTitle(params.sessionId, title)
                }
            } else {
                val titleLimit = 15
                val title = params.userContent.take(titleLimit) +
                        if (params.userContent.length > titleLimit) "..." else ""
                sessionManager.updateSessionTitle(params.sessionId, title)
            }
        }
    }

    suspend fun archiveToRag(params: PostProcessorParams) {
        messageManager.setVectorizationStatus(
            params.sessionId,
            listOf(params.userMsgId, params.assistantMsgId),
            "processing"
        )

        try {
            messageManager.setVectorizationStatus(
                params.sessionId,
                listOf(params.userMsgId, params.assistantMsgId),
                "success"
            )
        } catch (_: Exception) {
            messageManager.setVectorizationStatus(
                params.sessionId,
                listOf(params.userMsgId, params.assistantMsgId),
                "error"
            )
        }
    }

    companion object {
        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            return (text.length / 4.0).toInt().coerceAtLeast(1)
        }
    }
}
