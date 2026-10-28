package com.promenar.infinity.domain.usecase

import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.params.ReasoningEffort
import kotlin.math.ceil

/** 显式保留轮数优先，旧消息窗口按每三条一轮迁移，未配置时保留五轮。 */
fun resolveKeepRecentTurns(keepRecentTurns: Int?, activeContextWindow: Int?): Int =
    keepRecentTurns?.coerceIn(1, 20)
        ?: activeContextWindow?.let { ceil(it / 3.0).toInt().coerceIn(1, 20) }
        ?: 5

val InferenceParams?.resolvedKeepRecentTurns: Int
    get() = resolveKeepRecentTurns(this?.keepRecentTurns, this?.activeContextWindow)

/** 写入新的会话配置时固化轮数，不延续仅供解码的旧消息窗口。 */
fun InferenceParams.withResolvedContextTurns(): InferenceParams = copy(
    keepRecentTurns = resolvedKeepRecentTurns,
    activeContextWindow = null,
)

/** 助手为会话提供的默认推理参数。 */
fun AgentConfigResolver.ResolvedConfig.inferenceDefaults(): InferenceParams = inferenceDefaults

/**
 * 会话推理参数逐字段回退到 [defaults]：会话只覆盖用户显式设置过的项，
 * 其余沿用助手设置；两者都未设置的项保持为空，请求中不发送，由模型按默认处理。
 * 会话选用了参数方案或“模型默认”时，采样参数不再沿用助手，推理强度仍逐项回退。
 */
fun InferenceParams?.withDefaults(defaults: InferenceParams?): InferenceParams {
    val base = this ?: InferenceParams()
    if (defaults == null) return base
    if (base.parameterSchemeId != null) {
        return base.copy(reasoningEffort = base.reasoningEffort ?: defaults.reasoningEffort)
    }
    return base.copy(
        temperature = base.temperature ?: defaults.temperature,
        topP = base.topP ?: defaults.topP,
        maxTokens = base.maxTokens ?: defaults.maxTokens,
        frequencyPenalty = base.frequencyPenalty ?: defaults.frequencyPenalty,
        presencePenalty = base.presencePenalty ?: defaults.presencePenalty,
        topK = base.topK ?: defaults.topK,
        repetitionPenalty = base.repetitionPenalty ?: defaults.repetitionPenalty,
        reasoningEffort = base.reasoningEffort ?: defaults.reasoningEffort,
    )
}

/** 解析后的推理强度；未知取值按模型默认处理。 */
val InferenceParams.resolvedReasoningEffort: ReasoningEffort?
    get() = ReasoningEffort.fromWireName(reasoningEffort)
