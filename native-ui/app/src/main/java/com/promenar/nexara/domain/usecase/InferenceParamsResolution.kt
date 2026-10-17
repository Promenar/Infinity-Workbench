package com.promenar.nexara.domain.usecase

import com.promenar.nexara.data.model.InferenceParams
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

/** 智能体预设可提供默认值的推理参数。 */
fun AgentConfigResolver.ResolvedConfig.inferenceDefaults(): InferenceParams = InferenceParams(
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
)

/**
 * 会话推理参数逐字段回退到 [defaults]：会话只覆盖用户显式设置过的项，
 * 其余（温度、Top P、最大输出）继续沿用智能体预设，而不是整体失效后交给 Provider 默认值。
 */
fun InferenceParams?.withDefaults(defaults: InferenceParams?): InferenceParams {
    val base = this ?: InferenceParams()
    if (defaults == null) return base
    return base.copy(
        temperature = base.temperature ?: defaults.temperature,
        topP = base.topP ?: defaults.topP,
        maxTokens = base.maxTokens ?: defaults.maxTokens,
    )
}
