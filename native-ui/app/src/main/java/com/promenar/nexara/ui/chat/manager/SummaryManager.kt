package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.provider.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SummaryManager(private val llmProvider: LlmProvider) {

    /**
     * Summarizes the conversation history.
     * @param oldSummary The existing summary, if any.
     * @param overflowMessages The messages that have overflowed the active window.
     * @param summaryModelId The model to use for summarization (optional).
     * @param currentModelId The fallback model ID.
     */
    suspend fun summarize(
        oldSummary: String?,
        overflowMessages: List<Message>,
        summaryModelId: String?,
        currentModelId: String
    ): String = withContext(Dispatchers.IO) {
        if (overflowMessages.isEmpty()) return@withContext oldSummary ?: ""

        val modelToUse = if (!summaryModelId.isNullOrBlank()) summaryModelId else currentModelId
        
        val historyText = overflowMessages.joinToString("\n") { msg ->
            "${msg.role.name}: ${msg.content}"
        }

        val prompt = if (oldSummary.isNullOrBlank()) {
            "你是一个信息压缩助手。请将以下对话记录压缩成一段简短的摘要，确保保留关键事实、用户偏好和核心实体：\n\n$historyText"
        } else {
            "你是一个信息压缩助手。现有摘要如下：\n$oldSummary\n\n请将以下新产生的对话记录压缩并合并到已有摘要中，确保保留关键事实、用户偏好和核心实体：\n\n$historyText"
        }

        val request = PromptRequest(
            messages = listOf(ProtocolMessage(role = "user", content = prompt)),
            model = modelToUse,
            stream = false
        )

        try {
            val response = llmProvider.sendPromptSync(request)
            response.content
        } catch (e: Exception) {
            e.printStackTrace()
            oldSummary ?: "" // Fallback to old summary on error
        }
    }
}
