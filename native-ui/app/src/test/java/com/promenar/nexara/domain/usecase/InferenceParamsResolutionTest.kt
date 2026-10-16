package com.promenar.nexara.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.InferenceParams
import org.junit.Test

class InferenceParamsResolutionTest {
    private val agent = InferenceParams(temperature = 0.2, topP = 0.8, maxTokens = 2048)

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
        assertThat(effective.activeContextWindow).isEqualTo(DEFAULT_ACTIVE_CONTEXT_WINDOW)
    }
}
