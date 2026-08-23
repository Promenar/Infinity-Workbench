package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderEndpointResolverTest {
    @Test
    fun `host 与版本前缀只补齐缺失的推理路径`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://api.openai.com",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://api.openai.com/v1/chat/completions")
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://api.openai.com/v1/",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://api.openai.com/v1/chat/completions")
    }

    @Test
    fun `完整协议路径规范化后保持原样且不产生双路径`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_Responses,
                "https://example.test/v1/responses/",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://example.test/v1/responses")
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Anthropic_Messages,
                "https://example.test/v1/messages",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://example.test/v1/messages")
    }

    @Test
    fun `厂商前缀保留并拼接各自版本路径`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Qwen_DashScope,
                "https://dashscope.aliyuncs.com/compatible-mode",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.Zhipu_GLM,
                "https://open.bigmodel.cn/api/paas/",
                ProviderEndpointOperation.INFERENCE,
            ),
        ).isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions")
    }

    @Test
    fun `模型清单从完整推理路径回到相同协议前缀`() {
        assertThat(
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://proxy.test/provider/v1/chat/completions",
                ProviderEndpointOperation.MODELS,
            ),
        ).isEqualTo("https://proxy.test/provider/v1/models")
    }

    @Test
    fun `模型清单拒绝从歧义的完整推理资源路径继续向下拼接`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointResolver.resolve(
                ProtocolType.OpenAI_ChatCompletions,
                "https://proxy.test/vendor/chat/completions",
                ProviderEndpointOperation.MODELS,
            )
        }

        assertThat(error).hasMessageThat().isEqualTo(
            "Provider base URL 的完整推理路径无法无歧义转换为模型列表端点",
        )
    }

    @Test
    fun `Anthropic 连接探测在专用 probe 接线前失败关闭`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointResolver.resolve(
                ProtocolType.Anthropic_Messages,
                "https://api.anthropic.com",
                ProviderEndpointOperation.CONNECTION_PROBE,
            )
        }

        assertThat(error).hasMessageThat().isEqualTo(
            "Anthropic 连接探测尚未接线，拒绝回退模型列表",
        )
    }
}
