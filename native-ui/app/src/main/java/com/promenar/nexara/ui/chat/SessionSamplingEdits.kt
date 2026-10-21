package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.params.MODEL_DEFAULT_SCHEME_ID
import com.promenar.nexara.data.model.params.ParameterScheme
import com.promenar.nexara.data.model.params.SamplingParam
import com.promenar.nexara.data.model.params.SamplingValues
import com.promenar.nexara.data.model.params.sampling
import com.promenar.nexara.data.model.params.withSampling

/** 会话选用“模型默认”：采样参数全部不发送，也不再沿用助手设置。 */
internal fun InferenceParams.withModelDefaultSampling(): InferenceParams =
    withSampling(SamplingValues()).copy(parameterSchemeId = MODEL_DEFAULT_SCHEME_ID)

/** 会话选用参数方案：方案里未设置的项按模型默认，不沿用助手设置。 */
internal fun InferenceParams.withScheme(scheme: ParameterScheme): InferenceParams =
    withSampling(scheme.values).copy(parameterSchemeId = scheme.id)

/**
 * 改动单项参数。正在沿用助手值的项被清空时，会话改为自行决定采样参数：
 * 其余沿用中的值固化进会话，这一项按模型默认。
 */
internal fun InferenceParams.withSamplingParam(
    param: SamplingParam,
    value: Double?,
    inherited: SamplingValues,
): InferenceParams {
    val inheritingThis = parameterSchemeId == null && sampling.valueOf(param) == null && inherited.valueOf(param) != null
    if (value == null && inheritingThis) {
        return withSampling(sampling.orElse(inherited).with(param, null)).copy(parameterSchemeId = MODEL_DEFAULT_SCHEME_ID)
    }
    return withSampling(sampling.with(param, value))
}
