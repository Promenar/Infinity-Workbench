package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.inference.GenerateConfig
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class ProtocolParamAdapterTest {

    @Test
    fun `mapCommonParams puts all standard params`() {
        val body = buildJsonObject {
            ProtocolParamAdapter.mapCommonParams(this, PromptRequest(
                messages = emptyList(),
                model = "test",
                temperature = 0.5,
                topP = 0.8,
                maxTokens = 100
            ))
        }
        assertThat(body["temperature"]?.jsonPrimitive?.double).isEqualTo(0.5)
        assertThat(body["top_p"]?.jsonPrimitive?.double).isEqualTo(0.8)
        assertThat(body["max_tokens"]?.jsonPrimitive?.int).isEqualTo(100)
    }

    @Test
    fun `topP equals 1_0 is skipped`() {
        val body = buildJsonObject {
            ProtocolParamAdapter.mapCommonParams(this, PromptRequest(
                messages = emptyList(),
                model = "test",
                topP = 1.0
            ))
        }
        assertThat(body.containsKey("top_p")).isFalse()
    }

    @Test
    fun `mapPenaltyParams for OpenAI puts all penalties`() {
        val body = buildJsonObject {
            ProtocolParamAdapter.mapPenaltyParams(this, PromptRequest(
                messages = emptyList(),
                model = "test",
                frequencyPenalty = 0.3,
                presencePenalty = 0.5,
                repetitionPenalty = 1.2
            ), ProtocolType.OpenAI_ChatCompletions)
        }
        assertThat(body["frequency_penalty"]?.jsonPrimitive?.double).isEqualTo(0.3)
        assertThat(body["presence_penalty"]?.jsonPrimitive?.double).isEqualTo(0.5)
        assertThat(body["repetition_penalty"]?.jsonPrimitive?.double).isEqualTo(1.2)
    }

    @Test
    fun `mapPenaltyParams for Anthropic skips all penalties`() {
        val body = buildJsonObject {
            ProtocolParamAdapter.mapPenaltyParams(this, PromptRequest(
                messages = emptyList(),
                model = "test",
                frequencyPenalty = 0.3,
                presencePenalty = 0.5,
                repetitionPenalty = 1.2
            ), ProtocolType.Anthropic_Messages)
        }
        assertThat(body.containsKey("frequency_penalty")).isFalse()
        assertThat(body.containsKey("presence_penalty")).isFalse()
        assertThat(body.containsKey("repetition_penalty")).isFalse()
    }

    @Test
    fun `mapPenaltyParams for Local skips freq and presence penalties`() {
        val body = buildJsonObject {
            ProtocolParamAdapter.mapPenaltyParams(this, PromptRequest(
                messages = emptyList(),
                model = "test",
                frequencyPenalty = 0.3,
                presencePenalty = 0.5,
                repetitionPenalty = 1.2
            ), ProtocolType.Local)
        }
        assertThat(body.containsKey("frequency_penalty")).isFalse()
        assertThat(body.containsKey("presence_penalty")).isFalse()
        assertThat(body.containsKey("repetition_penalty")).isTrue()
        assertThat(body["repetition_penalty"]?.jsonPrimitive?.double).isEqualTo(1.2)
    }

    @Test
    fun `mapSamplingParams puts topK`() {
        val body = buildJsonObject {
            ProtocolParamAdapter.mapSamplingParams(this, PromptRequest(
                messages = emptyList(),
                model = "test",
                topK = 40
            ))
        }
        assertThat(body["top_k"]?.jsonPrimitive?.int).isEqualTo(40)
    }

    @Test
    fun `clampGenerateConfig prevents extreme values`() {
        val config = ProtocolParamAdapter.clampGenerateConfig(GenerateConfig(
            temperature = 3.0f,
            topK = 0,
            repeatPenalty = 3.0f
        ))
        assertThat(config.temperature).isWithin(0.001f).of(2.0f)
        assertThat(config.topK).isEqualTo(1)
        assertThat(config.repeatPenalty).isWithin(0.001f).of(1.5f)
    }

    @Test
    fun `clampGenerateConfig leaves valid values unchanged`() {
        val config = ProtocolParamAdapter.clampGenerateConfig(GenerateConfig(
            temperature = 0.7f,
            topK = 40,
            repeatPenalty = 1.0f
        ))
        assertThat(config.temperature).isWithin(0.001f).of(0.7f)
        assertThat(config.topK).isEqualTo(40)
        assertThat(config.repeatPenalty).isWithin(0.001f).of(1.0f)
    }
}
