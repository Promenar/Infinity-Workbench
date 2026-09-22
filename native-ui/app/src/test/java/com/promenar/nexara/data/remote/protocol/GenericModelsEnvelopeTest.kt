package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.SupportState

class GenericModelsEnvelopeTest {
    @Test
    fun `Generic模型列表同时接受裸数组与data对象envelope`() = runTest {
        val bare = listModels("""[{"id":"bare-a"},{"id":"bare-b"}]""")
        val wrapped = listModels("""{"data":[{"id":"wrapped-a"},{"id":"wrapped-b"}]}""")

        assertThat(bare).containsExactly("bare-a", "bare-b").inOrder()
        assertThat(wrapped).containsExactly("wrapped-a", "wrapped-b").inOrder()
    }

    @Test
    fun `Generic模型列表任一元素畸形时整包失败关闭`() = runTest {
        listOf(
            """[{"id":"valid"},{}]""",
            """{"data":[{"id":"valid"},{"id":7}]}""",
            """{"data":{}}""",
        ).forEach { body ->
            assertThat(listModels(body)).isEmpty()
        }
    }

    @Test
    fun `富模型列表保留raw id ownedBy scope额度和明确能力三态`() = runTest {
        val body = """{"data":[{
          "id":"Tenant::Model-X",
          "owned_by":"NEWAPI",
          "source_provider_id":"openrouter",
          "display_name":"Remote X",
          "context_length":128000,
          "max_input_tokens":120000,
          "max_output_tokens":8000,
          "capabilities":{"tool_calling":true,"reasoning":false}
        }]}"""

        val descriptor = descriptors(body).single()
        assertThat(descriptor.id).isEqualTo("Tenant::Model-X")
        assertThat(descriptor.ownedBy).isEqualTo("NEWAPI")
        assertThat(descriptor.sourceProviderId).isEqualTo("openrouter")
        assertThat(descriptor.metadata.displayName).isEqualTo("Remote X")
        assertThat(descriptor.metadata.inputTokens).isEqualTo(120000)
        assertThat(descriptor.metadata.capabilities[ModelCapability.TOOL_CALLING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(descriptor.metadata.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.UNSUPPORTED)
        assertThat(descriptor.metadata.capabilities[ModelCapability.VISION_INPUT]).isNull()
    }

    @Test
    fun `Claude官方nested capabilities和零额度保持可解析未知`() = runTest {
        val descriptor = descriptors(
            """{"data":[{
              "id":"claude-opus-5",
              "display_name":"Claude Opus 5",
              "capabilities":{
                "image_input":{"supported":true},
                "structured_outputs":{"supported":true},
                "thinking":{"supported":true,"types":{"adaptive":{"supported":true}}},
                "effort":{"supported":true,"high":{"supported":true}}
              },
              "max_input_tokens":0,
              "max_tokens":0
            }]}"""
        ).single()

        assertThat(descriptor.id).isEqualTo("claude-opus-5")
        assertThat(descriptor.metadata.displayName).isEqualTo("Claude Opus 5")
        assertThat(descriptor.metadata.inputTokens).isNull()
        assertThat(descriptor.metadata.outputTokens).isNull()
        assertThat(descriptor.metadata.capabilities[ModelCapability.VISION_INPUT])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(descriptor.metadata.capabilities[ModelCapability.STRUCTURED_OUTPUT])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(descriptor.metadata.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `Claude capability null和null额度保持未知但负值及非数字拒绝`() = runTest {
        val nullable = descriptors(
            """{"data":[{"id":"claude-x","capabilities":null,"max_input_tokens":null,"max_tokens":null}]}"""
        ).single()

        assertThat(nullable.metadata.capabilities).isEmpty()
        assertThat(nullable.metadata.inputTokens).isNull()
        assertThat(nullable.metadata.outputTokens).isNull()
        assertThat(descriptors("""{"data":[{"id":"claude-x","max_tokens":-1}]}""")).isEmpty()
        assertThat(descriptors("""{"data":[{"id":"claude-x","max_tokens":"8192"}]}""")).isEmpty()
    }

    @Test
    fun `Gemini官方models envelope以name作wire id并读取额度thinking和generation methods`() = runTest {
        val parsed = descriptors(
            """{"models":[{
              "name":"models/gemini-3.8-flash",
              "displayName":"Gemini 3.8 Flash",
              "inputTokenLimit":1048576,
              "outputTokenLimit":65536,
              "supportedGenerationMethods":["generateContent","countTokens"],
              "thinking":true
            },{
              "name":"models/text-embedding-005",
              "supportedGenerationMethods":["embedContent","batchEmbedContents"]
            }]}"""
        )
        val descriptor = parsed.first()

        assertThat(descriptor.id).isEqualTo("models/gemini-3.8-flash")
        assertThat(descriptor.metadata.displayName).isEqualTo("Gemini 3.8 Flash")
        assertThat(descriptor.metadata.inputTokens).isEqualTo(1048576)
        assertThat(descriptor.metadata.outputTokens).isEqualTo(65536)
        assertThat(descriptor.metadata.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(descriptor.metadata.capabilities[ModelCapability.CHAT_ENDPOINT]).isNull()
        assertThat(parsed.last().metadata.workload)
            .isEqualTo(com.promenar.nexara.data.model.catalog.ModelWorkload.EMBEDDING)
    }

    @Test
    fun `OpenAI形状name仅为显示字段不能替代raw id`() = runTest {
        val descriptor = descriptors("""{"data":[{"id":"wire-id","name":"Display Name"}]}""").single()
        assertThat(descriptor.id).isEqualTo("wire-id")
        assertThat(descriptor.metadata.displayName).isEqualTo("Display Name")
        assertThat(descriptors("""{"data":[{"name":"display-only"}]}""")).isEmpty()
    }

    @Test
    fun `重复raw id完全相同去重但元数据冲突整包失败且大小写不合并`() = runTest {
        val identical = descriptors("""[{"id":"Model-X","owned_by":"Vendor"},{"id":"Model-X","owned_by":"Vendor"}]""")
        val conflicting = descriptors("""[{"id":"Model-X","owned_by":"A"},{"id":"Model-X","owned_by":"B"}]""")
        val caseDistinct = descriptors("""[{"id":"Model-X"},{"id":"model-x"}]""")

        assertThat(identical.map { it.id }).containsExactly("Model-X")
        assertThat(conflicting).isEmpty()
        assertThat(caseDistinct.map { it.id }).containsExactly("Model-X", "model-x").inOrder()
    }

    @Test
    fun `模型列表截断超记录数和超字节均失败关闭`() = runTest {
        assertThat(descriptors("""{"data":[{"id":"x"}]""")).isEmpty()
        val tooMany = buildString {
            append('[')
            repeat(MAX_REMOTE_MODEL_COUNT + 1) { index ->
                if (index > 0) append(',')
                append("{\"id\":\"m").append(index).append("\"}")
            }
            append(']')
        }
        assertThat(descriptors(tooMany)).isEmpty()

        val oversized = """[{"id":"${"x".repeat(MAX_MODEL_LIST_BYTES)}"}]"""
        assertThat(descriptors(oversized)).isEmpty()
    }

    @Test
    fun `模型列表拒绝非法负值和超限ContentLength`() = runTest {
        val valid = """[{"id":"model-x"}]"""

        assertThat(protocol(valid, contentLength = "invalid").listModelDescriptors()).isEmpty()
        assertThat(protocol(valid, contentLength = "-1").listModelDescriptors()).isEmpty()
        assertThat(
            protocol(valid, contentLength = (MAX_MODEL_LIST_BYTES + 1).toString()).listModelDescriptors()
        ).isEmpty()
    }

    private suspend fun listModels(body: String): List<String> {
        return protocol(body).listModels()
    }

    private suspend fun descriptors(body: String): List<RemoteModelDescriptor> {
        return protocol(body).listModelDescriptors()
    }

    private fun protocol(body: String, contentLength: String? = null): GenericOpenAICompatProtocol {
        val headers = if (contentLength == null) {
            headersOf(HttpHeaders.ContentType, "application/json")
        } else {
            headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.ContentLength to listOf(contentLength),
            )
        }
        val client = HttpClient(MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headers,
            )
        })
        return GenericOpenAICompatProtocol(
            baseUrl = "https://generic.example.test/v1/chat/completions",
            apiKey = "fake-key",
            model = "",
            httpClient = client,
        )
    }
}
