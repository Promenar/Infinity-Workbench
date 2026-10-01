package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.document.ContextBudgetDecision
import com.promenar.nexara.data.document.ContextBudgetGate
import com.promenar.nexara.data.document.ContextBudgetRequest
import com.promenar.nexara.data.document.ConservativeContextTokenEstimator
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.findRemoteModelSpec
import com.promenar.nexara.data.remote.protocol.PromptRequest

/** 当前请求的模型上下文与输出预留；上下文未知时为 null。 */
data class PromptWindow(val modelContextTokens: Int?, val outputReserveTokens: Int)

object PreparedPromptBudgetGate {
    fun resolveWindow(
        session: Session,
        prompt: PromptRequest,
        settings: SharedPreferences,
        stableModelId: String? = null,
    ): PromptWindow {
        val remoteModelId = prompt.model.takeIf(String::isNotBlank) ?: session.modelId.orEmpty()
        val persistedModelId = stableModelId?.takeIf(String::isNotBlank)
            ?: session.modelId?.takeIf(String::isNotBlank)
            ?: remoteModelId
        val modelSpec = findRemoteModelSpec(remoteModelId)
        val savedContext = settings.getInt("model_info_${persistedModelId}_context", 0)
        val modelContext = savedContext.takeIf { it > 0 }
            ?: modelSpec?.contextLength?.takeIf { it > 0 }
        val outputReserve = prompt.maxTokens?.takeIf { it > 0 }
            ?: session.inferenceParams?.maxTokens?.takeIf { it > 0 }
            ?: modelSpec?.maxOutputTokens?.takeIf { it > 0 }
            ?: 4096
        return PromptWindow(modelContext, outputReserve)
    }

    /** 工具循环超出上下文时省略早期工具结果；上下文未知时保持原样。 */
    fun compactToolResults(
        session: Session,
        prompt: PromptRequest,
        settings: SharedPreferences,
        stableModelId: String? = null,
    ): ToolResultCompactor.Result? {
        val window = resolveWindow(session, prompt, settings, stableModelId)
        val context = window.modelContextTokens ?: return null
        val budget = ((context - window.outputReserveTokens) * COMPACTION_TARGET_RATIO).toInt()
        if (budget <= 0) return null
        val toolTokens = prompt.tools.orEmpty().sumOf { tool ->
            ApproximateTokenEstimator.estimate(
                tool.function.name + tool.function.description + tool.function.parameters,
            )
        }
        return ToolResultCompactor.compact(prompt.messages, budget, extraTokens = toolTokens)
    }

    private const val COMPACTION_TARGET_RATIO = 0.85

    fun evaluate(
        session: Session,
        prompt: PromptRequest,
        settings: SharedPreferences,
        hasFullContextDocuments: Boolean,
        stableModelId: String? = null,
    ): ContextBudgetDecision? {
        if (!hasFullContextDocuments) return null

        val window = resolveWindow(session, prompt, settings, stableModelId)
        val modelContext = window.modelContextTokens
        val outputReserve = window.outputReserveTokens
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
