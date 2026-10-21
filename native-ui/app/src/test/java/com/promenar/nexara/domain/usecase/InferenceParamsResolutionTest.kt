package com.promenar.nexara.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.InferenceParams
import kotlinx.serialization.json.Json
import org.junit.Test

class InferenceParamsResolutionTest {
    private val agent = InferenceParams(temperature = 0.2, topP = 0.8, maxTokens = 2048)

    @Test
    fun `编辑旧配置固化轮数且即使编码默认值也不写旧窗口`() {
        val legacy = Json.decodeFromString<InferenceParams>("""{"activeContextWindow":6,"temperature":0.2}""")
        val current = legacy.withResolvedContextTurns()
        val encoded = com.promenar.nexara.data.model.json.encodeToString(InferenceParams.serializer(), current)

        assertThat(current.keepRecentTurns).isEqualTo(2)
        assertThat(current.temperature).isEqualTo(0.2)
        assertThat(encoded).doesNotContain("activeContextWindow")
        assertThat(Json.decodeFromString<InferenceParams>(encoded).resolvedKeepRecentTurns).isEqualTo(2)
    }

    @Test
    fun `未设置旧窗口的参数不再隐式写入十五条窗口`() {
        val decoded = Json.decodeFromString<InferenceParams>("{}")

        assertThat(decoded.activeContextWindow).isNull()
        assertThat(InferenceParams().activeContextWindow).isNull()
        assertThat(decoded.keepRecentTurns).isNull()
        assertThat(decoded.resolvedKeepRecentTurns).isEqualTo(5)
        assertThat(decoded.autoSummaryThreshold).isEqualTo(0.8)
    }

    @Test
    fun `只含旧窗口的推理参数JSON仍可解码`() {
        val decoded = Json.decodeFromString<InferenceParams>("""{"activeContextWindow":15}""")

        assertThat(decoded.activeContextWindow).isEqualTo(15)
        assertThat(decoded.keepRecentTurns).isNull()
        assertThat(decoded.resolvedKeepRecentTurns).isEqualTo(5)
        assertThat(decoded.autoSummaryThreshold).isEqualTo(0.8)
    }

    @Test
    fun `旧消息窗口按每三条折算一轮并向上取整`() {
        assertThat(resolveKeepRecentTurns(null, 15)).isEqualTo(5)
        assertThat(resolveKeepRecentTurns(null, 6)).isEqualTo(2)
        assertThat(resolveKeepRecentTurns(null, 4)).isEqualTo(2)
        assertThat(resolveKeepRecentTurns(null, 2)).isEqualTo(1)
    }

    @Test
    fun `新保留轮数优先于旧窗口且限制在一到二十轮`() {
        assertThat(resolveKeepRecentTurns(2, 60)).isEqualTo(2)
        assertThat(resolveKeepRecentTurns(1, 15)).isEqualTo(1)
        assertThat(resolveKeepRecentTurns(20, 15)).isEqualTo(20)
        assertThat(resolveKeepRecentTurns(0, 15)).isEqualTo(1)
        assertThat(resolveKeepRecentTurns(Int.MIN_VALUE, 15)).isEqualTo(1)
        assertThat(resolveKeepRecentTurns(21, 15)).isEqualTo(20)
        assertThat(resolveKeepRecentTurns(Int.MAX_VALUE, 15)).isEqualTo(20)
    }

    @Test
    fun `旧窗口折算时限制边界且极值不溢出`() {
        assertThat(resolveKeepRecentTurns(null, 0)).isEqualTo(1)
        assertThat(resolveKeepRecentTurns(null, Int.MIN_VALUE)).isEqualTo(1)
        assertThat(resolveKeepRecentTurns(null, 60)).isEqualTo(20)
        assertThat(resolveKeepRecentTurns(null, 61)).isEqualTo(20)
        assertThat(resolveKeepRecentTurns(null, Int.MAX_VALUE)).isEqualTo(20)
    }

    @Test
    fun `参数与旧窗口都未设置时保留五轮`() {
        assertThat(resolveKeepRecentTurns(null, null)).isEqualTo(5)
        assertThat((null as InferenceParams?).resolvedKeepRecentTurns).isEqualTo(5)
    }

    @Test
    fun `含新旧字段的JSON优先使用新字段而显式空值可回退旧窗口`() {
        val explicit = Json.decodeFromString<InferenceParams>(
            """{"keepRecentTurns":9,"activeContextWindow":6,"autoSummaryThreshold":0.65}"""
        )
        val fallback = Json.decodeFromString<InferenceParams>(
            """{"keepRecentTurns":null,"activeContextWindow":6}"""
        )

        assertThat(explicit.resolvedKeepRecentTurns).isEqualTo(9)
        assertThat(explicit.autoSummaryThreshold).isEqualTo(0.65)
        assertThat(fallback.resolvedKeepRecentTurns).isEqualTo(2)
    }

    @Test
    fun `会话只改超时时温度Top P与最大输出仍沿用智能体预设`() {
        val session = InferenceParams(streamTimeout = 300)

        val effective = session.withDefaults(agent)

        assertThat(effective.temperature).isEqualTo(0.2)
        assertThat(effective.topP).isEqualTo(0.8)
        assertThat(effective.maxTokens).isEqualTo(2048)
        assertThat(effective.streamTimeout).isEqualTo(300)
    }

    @Test
    fun `会话显式覆盖的字段优先于智能体预设`() {
        val session = InferenceParams(temperature = 1.0, maxTokens = 512, topK = 40)

        val effective = session.withDefaults(agent)

        assertThat(effective.temperature).isEqualTo(1.0)
        assertThat(effective.maxTokens).isEqualTo(512)
        assertThat(effective.topP).isEqualTo(0.8)
        assertThat(effective.topK).isEqualTo(40)
    }

    @Test
    fun `会话未设置任何参数时完全采用智能体预设`() {
        val effective = (null as InferenceParams?).withDefaults(agent)

        assertThat(effective.temperature).isEqualTo(0.2)
        assertThat(effective.topP).isEqualTo(0.8)
        assertThat(effective.maxTokens).isEqualTo(2048)
        assertThat(effective.resolvedKeepRecentTurns).isEqualTo(5)
        assertThat(effective.activeContextWindow).isNull()
    }

    @Test
    fun `智能体推理缺省不覆盖会话上下文保留轮数`() {
        val session = InferenceParams(keepRecentTurns = 7, autoSummaryThreshold = 0.65)

        val effective = session.withDefaults(agent.copy(keepRecentTurns = 2, autoSummaryThreshold = 0.9))

        assertThat(effective.resolvedKeepRecentTurns).isEqualTo(7)
        assertThat(effective.autoSummaryThreshold).isEqualTo(0.65)
        assertThat(effective.temperature).isEqualTo(0.2)
    }

    @Test
    fun `会话选用方案或模型默认后采样参数不再沿用助手而推理强度仍回退`() {
        val withEffort = agent.copy(reasoningEffort = "high")
        val modelDefault = InferenceParams(parameterSchemeId = com.promenar.nexara.data.model.params.MODEL_DEFAULT_SCHEME_ID)
            .withDefaults(withEffort)
        assertThat(listOf(modelDefault.temperature, modelDefault.topP, modelDefault.maxTokens?.toDouble())).containsExactly(null, null, null)
        assertThat(modelDefault.reasoningEffort).isEqualTo("high")

        val scheme = InferenceParams(temperature = 0.9, parameterSchemeId = "scheme-1").withDefaults(withEffort)
        assertThat(scheme.temperature).isEqualTo(0.9)
        assertThat(scheme.topP).isNull()
    }
}
