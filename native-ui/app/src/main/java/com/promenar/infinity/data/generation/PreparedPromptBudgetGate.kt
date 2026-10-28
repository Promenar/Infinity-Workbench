package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.promenar.infinity.data.document.ContextBudgetDecision
import com.promenar.infinity.data.document.ContextBudgetGate
import com.promenar.infinity.data.document.ContextBudgetRequest
import com.promenar.infinity.data.document.ConservativeContextTokenEstimator
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.findRemoteModelSpec
import com.promenar.infinity.data.remote.protocol.PromptRequest

/** 当前请求的模型上下文与输出预留；上下文未知时为 null。 */
data class PromptWindow(val modelContextTokens: Int?, val outputReserveTokens: Int) {
    /** 安全余量：窗口的 2%，至少 1024，吸收估算与提供商实际计数之间的偏差。 */
    val safetyMarginTokens: Int
        get() = modelContextTokens?.takeIf { it > 0 }?.let { maxOf(1024, it / 50) } ?: 0

    /** 扣除输出预留与安全余量后可放入输入的 token 数；发送门禁与自动压缩共用这一上限。窗口未知时为 null。 */
    val inputBudgetTokens: Long?
        get() = modelContextTokens?.takeIf { it > 0 }?.let {
            (it.toLong() - outputReserveTokens.coerceAtLeast(0) - safetyMarginTokens).coerceAtLeast(0)
        }
}

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
        // 显式输出上限会随请求发送，必须完整预留；未发送时由提供商决定实际输出，
        // 只按模型最大输出与 IMPLICIT_OUTPUT_RESERVE_CAP 的较小值预留，避免长输出模型把窗口占去一半。
        val outputReserve = prompt.maxTokens?.takeIf { it > 0 }
            ?: session.inferenceParams?.maxTokens?.takeIf { it > 0 }
            ?: (modelSpec?.maxOutputTokens?.takeIf { it > 0 } ?: 4096).coerceAtMost(IMPLICIT_OUTPUT_RESERVE_CAP)
        return PromptWindow(modelContext, outputReserve)
    }

    fun evaluate(
        session: Session,
        prompt: PromptRequest,
        settings: SharedPreferences,
        hasFullContextDocuments: Boolean,
        stableModelId: String? = null,
    ): ContextBudgetDecision? {
        val window = resolveWindow(session, prompt, settings, stableModelId)
        val modelContext = window.modelContextTokens
        // 未知模型仍须为完整文档失败关闭；其它请求没有可信窗口时不虚构容量。
        if (modelContext == null && !hasFullContextDocuments) return null
        // 完整文档按 UTF-8 字节保守计量、失败关闭；其它请求与自动压缩使用同一近似估算，
        // 否则压缩认为尚有余量而门禁按字节（中文每字 3 个）判定超限，会在远未用满时误拒。
        val requiredTokens = if (hasFullContextDocuments) conservativeTokens(prompt) else ApproximateTokenEstimator.estimate(prompt)
        return ContextBudgetGate.evaluate(
            ContextBudgetRequest(
                modelContextTokens = modelContext,
                outputReserveTokens = window.outputReserveTokens,
                safetyMarginTokens = window.safetyMarginTokens,
                historyTokens = requiredTokens,
                promptTokens = 0,
                attachmentTokens = 0,
            ),
        )
    }

    private fun conservativeTokens(prompt: PromptRequest): Int {
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
                },
            ).toLong() + message.imageUrls.orEmpty().size * IMAGE_TOKEN_RESERVE.toLong()
        }
        val toolTokens = prompt.tools.orEmpty().sumOf { tool ->
            ConservativeContextTokenEstimator.estimate(
                tool.function.name + tool.function.description + tool.function.parameters,
            ).toLong()
        }
        return (messageTokens + toolTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // 图像使用每张固定预留估算；传输编码与 URL 都不是模型文本 token，实际视觉用量由提供商决定。
    private const val IMAGE_TOKEN_RESERVE = 4096

    // 未显式限制输出时的预留上限：足够容纳常规回答与思考，又不让长输出模型挤占大半窗口。
    private const val IMPLICIT_OUTPUT_RESERVE_CAP = 32_768

}
