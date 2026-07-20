package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.document.ContextBudgetDecision
import com.promenar.nexara.data.document.ContextBudgetGate
import com.promenar.nexara.data.document.ContextBudgetRequest
import com.promenar.nexara.data.document.ConservativeContextTokenEstimator
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.findModelSpec
import com.promenar.nexara.data.remote.protocol.PromptRequest

object PreparedPromptBudgetGate {
    fun evaluate(
        session: Session,
        prompt: PromptRequest,
        settings: SharedPreferences,
        hasFullContextDocuments: Boolean,
        stableModelId: String? = null,
    ): ContextBudgetDecision? {
        if (!hasFullContextDocuments) return null

        val remoteModelId = prompt.model.takeIf(String::isNotBlank) ?: session.modelId.orEmpty()
        val persistedModelId = stableModelId?.takeIf(String::isNotBlank)
            ?: session.modelId?.takeIf(String::isNotBlank)
            ?: remoteModelId
        val modelSpec = findModelSpec(remoteModelId)
        val savedContext = settings.getInt("model_info_${persistedModelId}_context", 0)
        val modelContext = savedContext.takeIf { it > 0 }
            ?: modelSpec?.contextLength?.takeIf { it > 0 }
        val outputReserve = prompt.maxTokens?.takeIf { it > 0 }
            ?: session.inferenceParams?.maxTokens?.takeIf { it > 0 }
            ?: modelSpec?.maxOutputTokens?.takeIf { it > 0 }
            ?: 4096
        val messageTokens = prompt.messages.sumOf { message ->
            ConservativeContextTokenEstimator.estimate(
                buildString {
                    append(message.role)
                    append(message.content)
                    append(message.reasoning.orEmpty())
                    append(message.name.orEmpty())
                    append(message.toolCallId.orEmpty())
                    message.toolCalls.orEmpty().forEach { call ->
                        append(call.id)
                        append(call.name)
                        append(call.arguments)
                    }
                    message.imageUrls.orEmpty().forEach { image ->
                        append(image.mimeType)
                        append(image.base64)
                        append(image.url)
                    }
                },
            )
        }
        val toolTokens = prompt.tools.orEmpty().sumOf { tool ->
            ConservativeContextTokenEstimator.estimate(
                tool.function.name + tool.function.description + tool.function.parameters,
            )
        }
        return ContextBudgetGate.evaluate(
            ContextBudgetRequest(
                modelContextTokens = modelContext,
                outputReserveTokens = outputReserve,
                safetyMarginTokens = modelContext?.let { maxOf(1024, it / 50) } ?: 0,
                historyTokens = messageTokens,
                promptTokens = toolTokens,
                attachmentTokens = 0,
            ),
        )
    }

}
