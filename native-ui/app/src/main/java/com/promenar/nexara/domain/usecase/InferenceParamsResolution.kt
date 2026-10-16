package com.promenar.nexara.domain.usecase

import com.promenar.nexara.data.model.InferenceParams

/** 未配置时发送给模型的活动上下文窗口（消息条数），与会话设置面板的默认显示一致。 */
val DEFAULT_ACTIVE_CONTEXT_WINDOW: Int = InferenceParams().activeContextWindow

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
