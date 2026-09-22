package com.promenar.nexara.data.remote.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 仅在显式参数启用且专用模拟器通过 adb reverse 连接网关时执行。 */
@RunWith(Parameterized::class)
class LocalGatewayDeviceTest(private val model: String) {
    @Test
    fun `模拟器真实网关流式响应完成`() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nexaraLocalGateway") == "true")
        val text = StringBuilder()
        val errors = mutableListOf<String>()
        var completed = false
        val client = UnifiedLlmClient(providerConfigResolver = {
            UnifiedProviderConfig(
                protocolType = ProtocolType.Generic_OpenAI_Compat,
                baseUrl = "http://127.0.0.1:1337/v1/chat/completions",
                apiKey = "",
                defaultModel = model,
            )
        })
        withTimeout(120_000) {
            client.sendStream(StreamTextParams(
                model = model,
                messages = listOf(ProtocolMessage("user", "Reply with exactly the number 42.")),
                maxOutputTokens = 1024,
                enableGeminiSearch = false,
                streamTimeout = 90_000,
            ), StreamConfig()).collect { chunk ->
                when (chunk) {
                    is StreamChunk.TextDelta -> text.append(chunk.content)
                    is StreamChunk.Completed -> completed = true
                    is StreamChunk.Error -> errors += chunk.code.name
                    else -> Unit
                }
            }
        }
        assertThat(errors).isEmpty()
        assertThat(completed).isTrue()
        assertThat(text.toString()).contains("42")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<Array<String>> = listOf(
            "newapi/deepseek-v4-flash",
            "newapi/gemini-3.8-flash",
            "newapi/sensenova-6.8-flash-lite",
            "openai-chatgpt/gpt-5.6-luna",
            "newapi/MiniMax-M3",
        ).map { arrayOf(it) }
    }
}
