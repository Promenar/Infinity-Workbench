package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.model.params.ModelParameterProfile
import com.promenar.nexara.data.model.params.ReasoningDialect
import com.promenar.nexara.data.model.params.ReasoningEffort
import com.promenar.nexara.data.model.params.ReasoningSupport
import com.promenar.nexara.data.model.params.SamplingParam
import com.promenar.nexara.data.model.params.resolveModelParameterProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 按模型档案改写后的请求：剔除会报错或被忽略的采样参数，并把会话档位换成本模型可发送的值。
 * [effort] 为 null 时不发送任何推理字段，由模型按默认处理。
 */
internal class ModelAdaptedRequest(
    val request: PromptRequest,
    val reasoning: ReasoningSupport?,
    val effort: ReasoningEffort?,
)

internal fun PromptRequest.adaptedTo(protocolType: ProtocolType, modelId: String): ModelAdaptedRequest {
    val profile = resolveModelParameterProfile(protocolType, modelId)
    val support = profile.reasoning
    return ModelAdaptedRequest(
        request = withSupportedSampling(profile),
        reasoning = support,
        effort = support?.effective(reasoningEffort),
    )
}

internal fun PromptRequest.withSupportedSampling(profile: ModelParameterProfile): PromptRequest {
    val unsupported = profile.unsupportedParams(reasoningEffort)
    if (unsupported.isEmpty()) return this
    fun <T> T?.keep(param: SamplingParam): T? = takeUnless { param in unsupported }
    return copy(
        temperature = temperature.keep(SamplingParam.TEMPERATURE),
        topP = topP.keep(SamplingParam.TOP_P),
        topK = topK.keep(SamplingParam.TOP_K),
        frequencyPenalty = frequencyPenalty.keep(SamplingParam.FREQUENCY_PENALTY),
        presencePenalty = presencePenalty.keep(SamplingParam.PRESENCE_PENALTY),
        repetitionPenalty = repetitionPenalty.keep(SamplingParam.REPETITION_PENALTY),
    )
}

/** Chat Completions 外形：顶层 `reasoning_effort`，以及厂商私有的开关字段。 */
internal fun JsonObjectBuilder.putChatReasoning(adapted: ModelAdaptedRequest) {
    val support = adapted.reasoning ?: return
    val effort = adapted.effort ?: return
    when (support.dialect) {
        ReasoningDialect.EFFORT -> put("reasoning_effort", effort.wireName)
        ReasoningDialect.THINKING_TYPE -> {
            if (support.canDisable) put("thinking", thinkingType(effort != ReasoningEffort.NONE))
            if (effort != ReasoningEffort.NONE && !support.isToggleOnly) put("reasoning_effort", effort.wireName)
        }
        ReasoningDialect.ENABLE_THINKING -> put("enable_thinking", effort != ReasoningEffort.NONE)
        else -> Unit
    }
}

/** Responses 外形：`reasoning.effort`。 */
internal fun JsonObjectBuilder.putResponsesReasoning(adapted: ModelAdaptedRequest) {
    val support = adapted.reasoning ?: return
    val effort = adapted.effort ?: return
    if (support.isToggleOnly || support.dialect !in RESPONSES_DIALECTS) return
    put("reasoning", buildJsonObject { put("effort", effort.wireName) })
}

private val RESPONSES_DIALECTS = setOf(ReasoningDialect.EFFORT, ReasoningDialect.THINKING_TYPE)

internal class AnthropicReasoningFields(val thinking: JsonObject?, val effort: String?)

/**
 * Anthropic 外形：`thinking` 与 `output_config.effort`。
 * 未选择档位时沿用 [anthropicThinkingConfig] 的默认行为（Claude 5 请求摘要显示，其它模型不开启）。
 */
internal fun anthropicReasoningFields(
    model: String,
    adapted: ModelAdaptedRequest,
    maxTokens: Int,
): AnthropicReasoningFields {
    val support = adapted.reasoning
    val effort = adapted.effort
    if (support == null || effort == null) {
        return AnthropicReasoningFields(anthropicThinkingConfig(model, adapted.request.reasoning, maxTokens), null)
    }
    val off = effort == ReasoningEffort.NONE
    return when (support.dialect) {
        ReasoningDialect.CLAUDE -> when {
            // 默认不思考的模型省略 thinking 即可；其余可关闭的模型须显式 disabled。
            off -> AnthropicReasoningFields(thinkingType(false).takeUnless { support.defaultEffort == ReasoningEffort.NONE }, null)
            else -> AnthropicReasoningFields(anthropicThinkingConfig(model, true, maxTokens), effort.wireName)
        }
        ReasoningDialect.CLAUDE_BUDGET ->
            AnthropicReasoningFields(if (off) null else anthropicThinkingConfig(model, true, maxTokens), null)
        ReasoningDialect.THINKING_TYPE -> AnthropicReasoningFields(
            thinking = thinkingType(!off).takeIf { support.canDisable },
            effort = effort.wireName.takeUnless { off || support.isToggleOnly },
        )
        else -> AnthropicReasoningFields(null, null)
    }
}

/**
 * Gemini 外形：`generationConfig.thinkingConfig`。思考中的模型请求返回思考摘要；
 * 3.x 起用 thinkingLevel，2.5 用 thinkingBudget（0 为关闭）。
 */
internal fun geminiThinkingConfig(adapted: ModelAdaptedRequest): JsonObject? {
    val support = adapted.reasoning
    val effort = adapted.effort
    val includeThoughts = adapted.request.reasoning == true || support?.thinks(effort) == true
    val level = effort?.takeIf { support?.dialect == ReasoningDialect.GEMINI_LEVEL && it != ReasoningEffort.NONE }
    val budget = effort?.takeIf { support?.dialect == ReasoningDialect.GEMINI_BUDGET }?.let(::geminiThinkingBudget)
    if (!includeThoughts && level == null && budget == null) return null
    return buildJsonObject {
        if (includeThoughts) put("includeThoughts", true)
        level?.let { put("thinkingLevel", it.wireName) }
        budget?.let { put("thinkingBudget", it) }
    }
}

private fun geminiThinkingBudget(effort: ReasoningEffort): Int = when (effort) {
    ReasoningEffort.NONE -> 0
    ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 1024
    ReasoningEffort.MEDIUM -> 8192
    else -> 24_576
}

private fun thinkingType(enabled: Boolean): JsonObject =
    buildJsonObject { put("type", if (enabled) "enabled" else "disabled") }
