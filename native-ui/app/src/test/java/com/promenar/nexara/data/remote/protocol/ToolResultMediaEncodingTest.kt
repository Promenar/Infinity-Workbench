package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class ToolResultMediaEncodingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val image = ImageInput(base64 = "aW1hZ2U=", mimeType = "image/png")
    private val calls = listOf(
        ProtocolToolCall("view-1", "view_media", "{}"),
        ProtocolToolCall("read-2", "read_file", "{}"),
    )

    private fun messages() = listOf(
        ProtocolMessage("user", "查看图片并读取说明"),
        ProtocolMessage("assistant", "", toolCalls = calls),
        ProtocolMessage("tool", "图片已加载", name = "view_media", toolCallId = "view-1", imageUrls = listOf(image)),
        ProtocolMessage("tool", "说明文字", name = "read_file", toolCallId = "read-2"),
    )

    @Test
    fun `Anthropic 图片嵌在 tool_result 内并位于文字后`() {
        val body = requestBody(AnthropicProtocol("https://example.invalid", "test", "claude-test"))
        val resultMessage = body.getValue("messages").jsonArray[2].jsonObject
        val blocks = resultMessage.getValue("content").jsonArray
        assertThat(blocks).hasSize(1)
        val result = blocks.single().jsonObject
        assertThat(result.getValue("type").jsonPrimitive.content).isEqualTo("tool_result")
        assertThat(result.getValue("tool_use_id").jsonPrimitive.content).isEqualTo("view-1")
        val nested = result.getValue("content").jsonArray
        assertThat(nested.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            .containsExactly("text", "image").inOrder()
        assertThat(nested[1].jsonObject.getValue("source").jsonObject.getValue("data").jsonPrimitive.content)
            .isEqualTo(image.base64)
    }

    @Test
    fun `Responses 图片位于 function_call_output 的原生 output 数组`() {
        val body = requestBody(OpenAIResponsesProtocol("https://example.invalid", "test", "gpt-test"))
        val output = body.getValue("input").jsonArray.map { it.jsonObject }
            .first { it["call_id"]?.jsonPrimitive?.content == "view-1" && it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertThat(output.getValue("output")).isInstanceOf(JsonArray::class.java)
        val parts = output.getValue("output").jsonArray
        assertThat(parts.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            .containsExactly("input_text", "input_image").inOrder()
        assertThat(parts[1].jsonObject.getValue("image_url").jsonPrimitive.content)
            .isEqualTo("data:image/png;base64,aW1hZ2U=")
    }

    @Test
    fun `Gemini 3 图片位于 functionResponse parts`() {
        val body = geminiBody("gemini-3-flash-preview")
        val parts = body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray
        val response = parts.first().jsonObject.getValue("functionResponse").jsonObject
        assertThat(response.containsKey("parts")).isTrue()
        val nativeParts = response.getValue("parts").jsonArray
        assertThat(nativeParts.single().jsonObject.getValue("inlineData").jsonObject["data"]?.jsonPrimitive?.content)
            .isEqualTo(image.base64)
        assertThat(parts).hasSize(2)
    }

    @Test
    fun `Gemini 旧模型媒体在整组 functionResponse 之后`() {
        val body = geminiBody("gemini-2.5-flash")
        val parts = body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray
        assertThat(parts).hasSize(4)
        assertThat(parts.take(2).map { it.jsonObject.containsKey("functionResponse") })
            .containsExactly(true, true)
        assertThat(parts[0].jsonObject.getValue("functionResponse").jsonObject.containsKey("parts")).isFalse()
        assertThat(parts.last().jsonObject.getValue("inlineData").jsonObject["data"]?.jsonPrimitive?.content)
            .isEqualTo(image.base64)
    }

    @Test
    fun `Chat 图片通过整组 tool 之后唯一合成 user 发送`() {
        assertGroupedChat(requestBody(OpenAIProtocol("https://example.invalid", "test", "gpt-test")))
    }

    @Test
    fun `通用兼容图片通过整组 tool 之后唯一合成 user 发送`() {
        assertGroupedChat(requestBody(GenericOpenAICompatProtocol("https://example.invalid", "test", "gpt-test")))
    }

    @Test
    fun `无媒体 Chat 消息保留字符串内容且不增加消息`() {
        val plain = messages().map { it.copy(imageUrls = null) }
        val expected = json.parseToJsonElement(
            """[{"role":"user","content":"查看图片并读取说明"},{"role":"assistant","content":"","tool_calls":[{"id":"view-1","type":"function","function":{"name":"view_media","arguments":"{}"}},{"id":"read-2","type":"function","function":{"name":"read_file","arguments":"{}"}}]},{"role":"tool","content":"图片已加载","name":"view_media","tool_call_id":"view-1"},{"role":"tool","content":"说明文字","name":"read_file","tool_call_id":"read-2"}]""",
        )
        listOf(
            OpenAIProtocol("https://example.invalid", "test", "gpt-test"),
            GenericOpenAICompatProtocol("https://example.invalid", "test", "gpt-test"),
        ).forEach { protocol -> assertThat(requestBody(protocol, plain)["messages"]).isEqualTo(expected) }
    }

    @Test
    fun `无媒体原生协议保留工具结果的原有结构`() {
        val plain = messages().map { it.copy(imageUrls = null) }
        val anthropic = requestBody(AnthropicProtocol("https://example.invalid", "test", "claude-test"), plain)
        assertThat(anthropic.getValue("messages").jsonArray[2])
            .isEqualTo(json.parseToJsonElement("""{"role":"user","content":[{"type":"tool_result","tool_use_id":"view-1","content":"图片已加载"}]}"""))
        val responses = requestBody(OpenAIResponsesProtocol("https://example.invalid", "test", "gpt-test"), plain)
        assertThat(responses.getValue("input").jsonArray[3])
            .isEqualTo(json.parseToJsonElement("""{"type":"function_call_output","call_id":"view-1","output":"图片已加载"}"""))
        val gemini = json.parseToJsonElement(
            GeminiContentCodec.buildRequestBody(PromptRequest(plain, "gemini-3-flash-preview"), json),
        ).jsonObject
        assertThat(gemini.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray.first())
            .isEqualTo(json.parseToJsonElement("""{"functionResponse":{"id":"view-1","name":"view_media","response":{"content":"图片已加载"}}}"""))
    }

    @Test
    fun `媒体契约保留视频和来源路径且旧消息仍可解码`() {
        val encoded = json.encodeToString(mediaTool())
        val payload = json.parseToJsonElement(encoded).jsonObject
        val restored = json.decodeFromString<ProtocolMessage>(encoded)
        assertThat(restored.videoData?.single()?.mimeType).isEqualTo("video/mp4")
        assertThat(payload.getValue("mediaPaths").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("/workspace/clip.mp4", "/workspace/sound.wav").inOrder()
        val old = json.decodeFromString<ProtocolMessage>("""{"role":"tool","content":"旧结果"}""")
        assertThat(json.encodeToString(old)).isEqualTo("""{"role":"tool","content":"旧结果"}""")
    }

    @Test
    fun `媒体来源路径在所有远端协议中保留`() {
        val sourced = messages().toMutableList().apply { this[2] = mediaTool().copy(imageUrls = listOf(image)) }
        listOf<LlmProtocol>(
            AnthropicProtocol("https://example.invalid", "test", "claude-test"),
            OpenAIResponsesProtocol("https://example.invalid", "test", "gpt-test"),
            OpenAIProtocol("https://example.invalid", "test", "gpt-test"),
            GenericOpenAICompatProtocol("https://example.invalid", "test", "gpt-test"),
        ).forEach { protocol ->
            val encoded = requestBody(protocol, sourced).toString()
            assertThat(encoded).contains("/workspace/clip.mp4")
            assertThat(encoded).contains("/workspace/sound.wav")
        }
        val gemini = GeminiContentCodec.buildRequestBody(PromptRequest(sourced, "gemini-3.1-pro"), json)
        assertThat(gemini).contains("/workspace/clip.mp4")
        assertThat(gemini).contains("/workspace/sound.wav")
    }

    @Test
    fun `Chat 与通用兼容不把未知音频当成 mp3且视频用中文降级`() {
        val unsupported = messages().toMutableList().apply {
            this[2] = mediaTool().copy(audioData = listOf(AudioInput("ZmxhYw==", "audio/flac")))
        }
        listOf<LlmProtocol>(
            OpenAIProtocol("https://example.invalid", "test", "gpt-test"),
            GenericOpenAICompatProtocol("https://example.invalid", "test", "gpt-test"),
        ).forEach { protocol ->
            val encoded = requestBody(protocol, unsupported).toString()
            assertThat(encoded).contains("不支持音频")
            assertThat(encoded).contains("不支持视频")
            assertThat(encoded).contains("audio/flac")
            assertThat(encoded).doesNotContain("ZmxhYw==")
            assertThat(encoded).doesNotContain("dmlkZW8=")
            assertThat(encoded).doesNotContain("input_audio")
        }
    }

    @Test
    fun `Chat 输入音频只发送 wav和mp3且不请求音频输出`() {
        val audio = messages().toMutableList().apply {
            this[2] = this[2].copy(imageUrls = null, audioData = listOf(
                AudioInput("d2F2", "audio/wav"), AudioInput("bXAz", "audio/mpeg"),
            ))
        }
        listOf<LlmProtocol>(
            OpenAIProtocol("https://example.invalid", "test", "gpt-test"),
            GenericOpenAICompatProtocol("https://example.invalid", "test", "gpt-test"),
        ).forEach { protocol ->
            val body = requestBody(protocol, audio)
            val parts = body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
            assertThat(parts.drop(1).map { it.jsonObject.getValue("input_audio").jsonObject.getValue("format").jsonPrimitive.content })
                .containsExactly("wav", "mp3").inOrder()
            assertThat(body.getValue("modalities").jsonArray.map { it.jsonPrimitive.content }).containsExactly("text")
        }
    }

    @Test
    fun `图片输入不发送无效的 image 输出模态`() {
        listOf<LlmProtocol>(
            OpenAIProtocol("https://example.invalid", "test", "gpt-test"),
            GenericOpenAICompatProtocol("https://example.invalid", "test", "gpt-test"),
        ).forEach { assertThat(requestBody(it).containsKey("modalities")).isFalse() }
    }

    @Test
    fun `原生工具结果不发送不支持的音视频字节`() {
        val media = messages().toMutableList().apply { this[2] = mediaTool() }
        listOf<LlmProtocol>(
            AnthropicProtocol("https://example.invalid", "test", "claude-test"),
            OpenAIResponsesProtocol("https://example.invalid", "test", "gpt-test"),
        ).forEach { protocol ->
            val encoded = requestBody(protocol, media).toString()
            assertThat(encoded).contains("不支持音频")
            assertThat(encoded).contains("不支持视频")
            assertThat(encoded).doesNotContain("YXVkaW8=")
            assertThat(encoded).doesNotContain("dmlkZW8=")
        }
    }

    @Test
    fun `Gemini 3 原生图片与音视频回退保持完整工具配对`() {
        val media = messages().toMutableList().apply { this[2] = mediaTool().copy(imageUrls = listOf(image)) }
        val body = json.parseToJsonElement(GeminiContentCodec.buildRequestBody(PromptRequest(media, "models/gemini-3.1-pro"), json)).jsonObject
        val parts = body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray
        assertThat(parts.take(2).all { it.jsonObject.containsKey("functionResponse") }).isTrue()
        val response = parts[0].jsonObject.getValue("functionResponse").jsonObject
        assertThat(response.getValue("parts").jsonArray.single().jsonObject.getValue("inlineData").jsonObject.getValue("mimeType").jsonPrimitive.content)
            .isEqualTo("image/png")
        assertThat(parts.drop(2).mapNotNull { it.jsonObject["inlineData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.content })
            .containsExactly("audio/wav", "video/mp4").inOrder()
    }

    @Test
    fun `Gemini 媒体合成 user 不改变当前轮函数调用的签名要求`() {
        val media = messages().toMutableList().apply { this[2] = mediaTool() }
        val body = json.parseToJsonElement(
            GeminiContentCodec.buildRequestBody(PromptRequest(media, "gemini-3-flash-preview"), json),
        ).jsonObject
        val modelParts = body.getValue("contents").jsonArray[1].jsonObject.getValue("parts").jsonArray
        assertThat(modelParts.first().jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
            .isEqualTo("skip_thought_signature_validator")
        val oldModelBody = geminiBody("gemini-2.5-flash")
        val oldModelParts = oldModelBody.getValue("contents").jsonArray[1].jsonObject.getValue("parts").jsonArray
        assertThat(oldModelParts.first().jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
            .isEqualTo("skip_thought_signature_validator")
    }

    @Test
    fun `Vertex 以实际接口 MIME 列表原生返回图片并回退音视频`() {
        val media = messages().toMutableList().apply {
            this[2] = mediaTool().copy(
                imageUrls = listOf(image),
                audioData = listOf(AudioInput("dmVydGV4", "audio/x-aac")),
            )
        }
        val protocol = VertexAIProtocol(serviceAccountJson = "{}", projectId = "test-project", model = "gemini-3-flash-preview")
        val method = protocol.javaClass.getDeclaredMethod("buildRequestBody", PromptRequest::class.java).apply { isAccessible = true }
        val body = json.parseToJsonElement(method.invoke(protocol, PromptRequest(media, "gemini-3-flash-preview")) as String).jsonObject
        val parts = body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray
        assertThat(parts.take(2).all { it.jsonObject.containsKey("functionResponse") }).isTrue()
        assertThat(parts.first().jsonObject.getValue("functionResponse").jsonObject.getValue("parts").jsonArray).hasSize(1)
        assertThat(parts.drop(2).mapNotNull { it.jsonObject["inlineData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.content })
            .containsExactly("audio/x-aac", "video/mp4").inOrder()
        val geminiBody = json.parseToJsonElement(GeminiContentCodec.buildRequestBody(PromptRequest(media, "gemini-3-flash-preview"), json)).jsonObject
        assertThat(geminiBody.toString()).contains("不支持音频")
        assertThat(geminiBody.toString()).doesNotContain("dmVydGV4")
    }

    @Test
    fun `Gemini 未知模型别名用普通图片回退且未知音视频不发送`() {
        val unknown = messages().toMutableList().apply {
            this[2] = this[2].copy(audioData = listOf(AudioInput("dW5rbm93bg==", "audio/unknown")))
        }
        val body = json.parseToJsonElement(GeminiContentCodec.buildRequestBody(PromptRequest(unknown, "custom-gemini-alias"), json)).jsonObject
        val parts = body.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray
        assertThat(parts.first().jsonObject.getValue("functionResponse").jsonObject.containsKey("parts")).isFalse()
        assertThat(parts.last().jsonObject.getValue("inlineData").jsonObject.getValue("mimeType").jsonPrimitive.content)
            .isEqualTo("image/png")
        assertThat(body.toString()).contains("不支持音频")
        assertThat(body.toString()).doesNotContain("dW5rbm93bg==")
    }

    @Test
    fun `多个媒体工具结果合并且不同 assistant 组分别收束`() {
        val firstGroup = messages().toMutableList().apply { this[3] = this[3].copy(imageUrls = listOf(image)) }
        val all = firstGroup + listOf(
            ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("view-3", "view_media", "{}"))),
            ProtocolMessage("tool", "另一图片", toolCallId = "view-3", imageUrls = listOf(image)),
        )
        val body = requestBody(OpenAIProtocol("https://example.invalid", "test", "gpt-test"), all)
        val encoded = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertThat(encoded.map { it.getValue("role").jsonPrimitive.content })
            .containsExactly("user", "assistant", "tool", "tool", "user", "assistant", "tool", "user").inOrder()
        assertThat(encoded[4].getValue("content").jsonArray).hasSize(3)
    }

    @Test
    fun `本地流式与同步引擎收到明确降级文字且没有原始媒体字节`() = runBlocking {
        val prompts = mutableListOf<String>()
        val engine = mockk<LocalInferenceEngine>()
        every { engine.generate(capture(prompts), any()) } returns flowOf("完成")
        val protocol = LocalProtocol(engine, "qwen")
        val media = messages().toMutableList().apply { this[2] = mediaTool().copy(imageUrls = listOf(image)) }
        val request = PromptRequest(media, "local")
        protocol.sendPromptSync(request)
        protocol.sendPrompt(request).toList()
        assertThat(prompts).hasSize(2)
        assertThat(prompts[0]).isEqualTo(prompts[1])
        val prompt = prompts[0]
        assertThat(prompt).contains("不支持图片")
        assertThat(prompt).contains("不支持音频")
        assertThat(prompt).contains("不支持视频")
        assertThat(prompt).contains("/workspace/clip.mp4")
        assertThat(prompt.indexOf("<|im_start|>user", prompt.indexOf("<|im_start|>tool")))
            .isGreaterThan(prompt.lastIndexOf("<|im_start|>tool"))
        assertThat(prompt).doesNotContain("aW1hZ2U=")
        assertThat(prompt).doesNotContain("YXVkaW8=")
        assertThat(prompt).doesNotContain("dmlkZW8=")
    }

    private fun mediaTool(): ProtocolMessage = json.decodeFromString(
        """{"role":"tool","content":"媒体已加载","name":"view_media","toolCallId":"view-1","audioData":[{"base64":"YXVkaW8=","mimeType":"audio/wav"}],"videoData":[{"base64":"dmlkZW8=","mimeType":"video/mp4"}],"mediaPaths":["/workspace/clip.mp4","/workspace/sound.wav"]}""",
    )

    private fun assertGroupedChat(body: JsonObject) {
        val encoded = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertThat(encoded.map { it.getValue("role").jsonPrimitive.content })
            .containsExactly("user", "assistant", "tool", "tool", "user").inOrder()
        assertThat(encoded[2].getValue("content").jsonPrimitive.content).isEqualTo("图片已加载")
        assertThat(encoded[3].getValue("tool_call_id").jsonPrimitive.content).isEqualTo("read-2")
        val parts = encoded.last().getValue("content").jsonArray
        assertThat(parts.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            .containsExactly("text", "image_url").inOrder()
    }

    private fun geminiBody(model: String): JsonObject = json.parseToJsonElement(
        GeminiContentCodec.buildRequestBody(PromptRequest(messages(), model), json),
    ).jsonObject

    private fun requestBody(protocol: LlmProtocol, messages: List<ProtocolMessage> = messages()): JsonObject {
        val method = protocol.javaClass.getDeclaredMethod("buildRequestBody", PromptRequest::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return json.parseToJsonElement(method.invoke(protocol, PromptRequest(messages, "test-model"), false) as String).jsonObject
    }
}
