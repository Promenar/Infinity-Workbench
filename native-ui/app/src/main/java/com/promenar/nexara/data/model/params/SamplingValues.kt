package com.promenar.nexara.data.model.params

import com.promenar.nexara.data.model.InferenceParams
import kotlinx.serialization.Serializable

/** 会话选用“模型默认”时保存的方案标记：采样参数全部不发送，也不沿用助手设置。 */
const val MODEL_DEFAULT_SCHEME_ID = "model-default"

/** 一组采样参数；null 字段表示该项按模型默认，不发送。参数方案与助手默认参数都以此保存。 */
@Serializable
data class SamplingValues(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val topK: Int? = null,
    val repetitionPenalty: Double? = null,
) {
    val isModelDefault: Boolean get() = this == SamplingValues()

    fun valueOf(param: SamplingParam): Number? = when (param) {
        SamplingParam.TEMPERATURE -> temperature
        SamplingParam.TOP_P -> topP
        SamplingParam.TOP_K -> topK
        SamplingParam.MAX_TOKENS -> maxTokens
        SamplingParam.FREQUENCY_PENALTY -> frequencyPenalty
        SamplingParam.PRESENCE_PENALTY -> presencePenalty
        SamplingParam.REPETITION_PENALTY -> repetitionPenalty
    }

    /** 把 [param] 换成 [value]，null 恢复模型默认；整数参数四舍五入。 */
    fun with(param: SamplingParam, value: Double?): SamplingValues = when (param) {
        SamplingParam.TEMPERATURE -> copy(temperature = value)
        SamplingParam.TOP_P -> copy(topP = value)
        SamplingParam.TOP_K -> copy(topK = value?.let { kotlin.math.round(it).toInt() })
        SamplingParam.MAX_TOKENS -> copy(maxTokens = value?.let { kotlin.math.round(it).toInt() })
        SamplingParam.FREQUENCY_PENALTY -> copy(frequencyPenalty = value)
        SamplingParam.PRESENCE_PENALTY -> copy(presencePenalty = value)
        SamplingParam.REPETITION_PENALTY -> copy(repetitionPenalty = value)
    }

    /** 逐项取本组的值，缺省项取 [fallback]。 */
    fun orElse(fallback: SamplingValues): SamplingValues = SamplingValues(
        temperature = temperature ?: fallback.temperature,
        topP = topP ?: fallback.topP,
        maxTokens = maxTokens ?: fallback.maxTokens,
        frequencyPenalty = frequencyPenalty ?: fallback.frequencyPenalty,
        presencePenalty = presencePenalty ?: fallback.presencePenalty,
        topK = topK ?: fallback.topK,
        repetitionPenalty = repetitionPenalty ?: fallback.repetitionPenalty,
    )

    /** 只保留 [params] 中的项，其余恢复模型默认。 */
    fun onlyFor(params: Set<SamplingParam>): SamplingValues = SamplingValues(
        temperature = temperature.takeIf { SamplingParam.TEMPERATURE in params },
        topP = topP.takeIf { SamplingParam.TOP_P in params },
        maxTokens = maxTokens.takeIf { SamplingParam.MAX_TOKENS in params },
        frequencyPenalty = frequencyPenalty.takeIf { SamplingParam.FREQUENCY_PENALTY in params },
        presencePenalty = presencePenalty.takeIf { SamplingParam.PRESENCE_PENALTY in params },
        topK = topK.takeIf { SamplingParam.TOP_K in params },
        repetitionPenalty = repetitionPenalty.takeIf { SamplingParam.REPETITION_PENALTY in params },
    )
}

val InferenceParams.sampling: SamplingValues
    get() = SamplingValues(temperature, topP, maxTokens, frequencyPenalty, presencePenalty, topK, repetitionPenalty)

fun InferenceParams.withSampling(values: SamplingValues): InferenceParams = copy(
    temperature = values.temperature,
    topP = values.topP,
    maxTokens = values.maxTokens,
    frequencyPenalty = values.frequencyPenalty,
    presencePenalty = values.presencePenalty,
    topK = values.topK,
    repetitionPenalty = values.repetitionPenalty,
)
