package com.promenar.nexara.ui.common.params

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.params.MODEL_DEFAULT_SCHEME_ID
import com.promenar.nexara.data.model.params.ParamAvailability
import com.promenar.nexara.data.model.params.ParameterScheme
import com.promenar.nexara.data.model.params.ReasoningEffort
import com.promenar.nexara.data.model.params.SamplingParam
import com.promenar.nexara.data.model.params.SamplingValues
import com.promenar.nexara.data.model.params.resolveModelParameterProfile
import com.promenar.nexara.data.remote.protocol.ProtocolType
import org.junit.Test

class SamplingEditorStateTest {
    private val coding = ParameterScheme("s1", "p::m", "写代码", SamplingValues(temperature = 0.2, topP = 0.9), 1, 1)
    private val all = SAMPLING_PARAM_ORDER

    private fun state(
        own: SamplingValues,
        inherited: SamplingValues = SamplingValues(),
        base: String? = null,
        visible: List<SamplingParam> = all,
    ) = SamplingEditorState(own, inherited, followsScheme = base != null, baseSchemeId = base,
        layout = SamplingParamLayout(visible), schemes = listOf(coding))

    @Test
    fun `未选过方案时按当前取值匹配模型默认或已存方案`() {
        assertThat(state(SamplingValues()).selection).isEqualTo(SchemeSelection(MODEL_DEFAULT_SCHEME_ID, false))
        assertThat(state(SamplingValues(temperature = 0.2, topP = 0.9)).selection).isEqualTo(SchemeSelection("s1", false))
        // 沿用助手的值也计入当前取值。
        assertThat(state(SamplingValues(topP = 0.9), inherited = SamplingValues(temperature = 0.2)).selection)
            .isEqualTo(SchemeSelection("s1", false))
        assertThat(state(SamplingValues(temperature = 1.3)).selection).isEqualTo(SchemeSelection(null, false))
    }

    @Test
    fun `选用方案后改动任一项标为已修改，只比较当前模型会发送的参数`() {
        assertThat(state(SamplingValues(temperature = 0.5, topP = 0.9), base = "s1").selection).isEqualTo(SchemeSelection("s1", true))
        assertThat(state(SamplingValues(temperature = 0.4), base = MODEL_DEFAULT_SCHEME_ID).selection)
            .isEqualTo(SchemeSelection(MODEL_DEFAULT_SCHEME_ID, true))
        // 温度对当前模型不生效时，残留的温度不算修改。
        val noTemperature = all - SamplingParam.TEMPERATURE
        assertThat(state(SamplingValues(temperature = 0.5, topP = 0.9), base = "s1", visible = noTemperature).selection)
            .isEqualTo(SchemeSelection("s1", false))
        // 基准方案已删除时回到按取值匹配。
        assertThat(state(SamplingValues(temperature = 0.2, topP = 0.9), base = "deleted").selection).isEqualTo(SchemeSelection("s1", false))
    }

    @Test
    fun `参数来源区分本层、沿用上一层与模型默认，方案只保存会发送的参数`() {
        val following = state(SamplingValues(topK = 20), inherited = SamplingValues(temperature = 0.3), base = "s1")
        assertThat(following.source(SamplingParam.TEMPERATURE)).isEqualTo(ParamSource.DEFAULT)
        val inheriting = state(SamplingValues(topK = 20), inherited = SamplingValues(temperature = 0.3))
        assertThat(inheriting.source(SamplingParam.TEMPERATURE)).isEqualTo(ParamSource.INHERITED)
        assertThat(inheriting.source(SamplingParam.TOP_K)).isEqualTo(ParamSource.OWN)
        assertThat(inheriting.source(SamplingParam.TOP_P)).isEqualTo(ParamSource.DEFAULT)
        assertThat(state(SamplingValues(topK = 20, temperature = 0.5), visible = listOf(SamplingParam.TOP_K)).savableValues)
            .isEqualTo(SamplingValues(topK = 20))
    }

    @Test
    fun `方案被修改时列出与方案不同的参数`() {
        assertThat(state(SamplingValues(temperature = 0.5, topP = 0.9, topK = 20), base = "s1").changedParams)
            .containsExactly(SamplingParam.TEMPERATURE, SamplingParam.TOP_K).inOrder()
        assertThat(state(SamplingValues(maxTokens = 8192), base = MODEL_DEFAULT_SCHEME_ID).changedParams)
            .containsExactly(SamplingParam.MAX_TOKENS)
        assertThat(state(SamplingValues(temperature = 0.2, topP = 0.9), base = "s1").changedParams).isEmpty()
    }

    @Test
    fun `官方接口不接受的参数收进不适用分组，第三方接口全部可编辑`() {
        val official = samplingParamLayout(
            resolveModelParameterProfile(ProtocolType.Moonshot_Kimi, "kimi-k3", "https://api.moonshot.cn/v1"), null,
        )
        assertThat(official.visible).containsExactly(SamplingParam.MAX_TOKENS)
        assertThat(official.notApplicable).contains(SamplingParam.TEMPERATURE)

        val local = samplingParamLayout(
            resolveModelParameterProfile(ProtocolType.Moonshot_Kimi, "kimi-k3", "http://10.0.0.2:8000/v1"), null,
        )
        assertThat(local.visible).containsExactlyElementsIn(SAMPLING_PARAM_ORDER).inOrder()
        assertThat(local.notApplicable).isEmpty()
        assertThat(local.availability[SamplingParam.TEMPERATURE]).isEqualTo(ParamAvailability.MAY_BE_IGNORED)

        // 思考时不生效的参数仍可编辑，只是不发送。
        val deepseek = samplingParamLayout(
            resolveModelParameterProfile(ProtocolType.DeepSeek, "deepseek-v4-pro", "https://api.deepseek.com"), ReasoningEffort.HIGH,
        )
        assertThat(deepseek.visible).contains(SamplingParam.TEMPERATURE)
        assertThat(deepseek.availability[SamplingParam.TEMPERATURE]).isEqualTo(ParamAvailability.NOT_SENT_WHILE_THINKING)

        assertThat(samplingParamLayout(null, null).visible).containsExactlyElementsIn(SAMPLING_PARAM_ORDER)
    }
}
