package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.params.MODEL_DEFAULT_SCHEME_ID
import com.promenar.infinity.data.model.params.ParameterScheme
import com.promenar.infinity.data.model.params.SamplingParam
import com.promenar.infinity.data.model.params.SamplingValues
import com.promenar.infinity.data.model.params.sampling
import org.junit.Test

class SessionSamplingEditsTest {
    private val agent = SamplingValues(temperature = 0.3, topK = 40)
    private val base = InferenceParams(temperature = 0.8, streamTimeout = 90, reasoningEffort = "low")

    @Test
    fun `选用模型默认或方案时整组替换采样参数并保留其它会话设置`() {
        val modelDefault = base.withModelDefaultSampling()
        assertThat(modelDefault.sampling.isModelDefault).isTrue()
        assertThat(modelDefault.parameterSchemeId).isEqualTo(MODEL_DEFAULT_SCHEME_ID)
        assertThat(listOf(modelDefault.streamTimeout, modelDefault.reasoningEffort)).containsExactly(90, "low").inOrder()

        val scheme = ParameterScheme("s1", "p::m", "写作", SamplingValues(topP = 0.95), 1, 1)
        val applied = base.withScheme(scheme)
        assertThat(applied.sampling).isEqualTo(SamplingValues(topP = 0.95))
        assertThat(applied.parameterSchemeId).isEqualTo("s1")
    }

    @Test
    fun `改动单项只动这一项，清空沿用中的项时固化其余沿用值`() {
        assertThat(base.withSamplingParam(SamplingParam.TOP_K, 20.0, agent).sampling)
            .isEqualTo(SamplingValues(temperature = 0.8, topK = 20))
        // 本会话自己设置的项清空后回到沿用助手。
        val cleared = base.withSamplingParam(SamplingParam.TEMPERATURE, null, agent)
        assertThat(cleared.temperature).isNull()
        assertThat(cleared.parameterSchemeId).isNull()

        val stopInheriting = base.withSamplingParam(SamplingParam.TOP_K, null, agent)
        assertThat(stopInheriting.sampling).isEqualTo(SamplingValues(temperature = 0.8))
        assertThat(stopInheriting.parameterSchemeId).isEqualTo(MODEL_DEFAULT_SCHEME_ID)
    }
}
