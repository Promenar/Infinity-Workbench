package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.promenar.nexara.data.remote.protocol.ProtocolUsage

class LlmProtocolSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Nested
    @DisplayName("ProtocolMessage serialization")
    inner class ProtocolMessageTests {

        @Test
        fun `serialize and deserialize basic message`() {
            val msg = ProtocolMessage(
                role = "user",
                content = "Hello, world!"
            )
            val encoded = json.encodeToString(msg)
            val decoded = json.decodeFromString<ProtocolMessage>(encoded)

            assertThat(decoded.role).isEqualTo("user")
            assertThat(decoded.content).isEqualTo("Hello, world!")
            assertThat(decoded.reasoning).isNull()
            assertThat(decoded.toolCalls).isNull()
        }

        @Test
        fun `serialize message with all fields`() {
            val msg = ProtocolMessage(
                role = "assistant",
                content = "The result is 42",
                reasoning = "I need to think about this...",
                name = "calculator",
                toolCallId = "call_123",
                toolCalls = listOf(
                    ProtocolToolCall(id = "call_123", name = "calc", arguments = """{"expr":"6*7"}""")
                ),
                thoughtSignature = "sig_abc",
                files = listOf(
                    ProtocolFileAttachment(uri = "file:///tmp/a.png", mimeType = "image/png", name = "a.png")
                )
            )
            val encoded = json.encodeToString(msg)
            val decoded = json.decodeFromString<ProtocolMessage>(encoded)

            assertThat(decoded.role).isEqualTo("assistant")
            assertThat(decoded.content).isEqualTo("The result is 42")
            assertThat(decoded.reasoning).isEqualTo("I need to think about this...")
            assertThat(decoded.toolCalls).hasSize(1)
            assertThat(decoded.toolCalls!![0].id).isEqualTo("call_123")
            assertThat(decoded.files).hasSize(1)
            assertThat(decoded.files!![0].mimeType).isEqualTo("image/png")
        }

        @Test
        fun `round-trip preserves nulls`() {
            val msg = ProtocolMessage(role = "system", content = "You are helpful")
            val encoded = json.encodeToString(msg)
            val decoded = json.decodeFromString<ProtocolMessage>(encoded)

            assertThat(decoded.name).isNull()
            assertThat(decoded.toolCallId).isNull()
            assertThat(decoded.toolCalls).isNull()
            assertThat(decoded.files).isNull()
            assertThat(decoded.thoughtSignature).isNull()
        }
    }

    @Nested
    @DisplayName("ProtocolToolCall serialization")
    inner class ProtocolToolCallTests {

        @Test
        fun `serialize and deserialize tool call`() {
            val tc = ProtocolToolCall(
                id = "call_abc",
                name = "search",
                arguments = """{"query":"kotlin"}"""
            )
            val encoded = json.encodeToString(tc)
            val decoded = json.decodeFromString<ProtocolToolCall>(encoded)

            assertThat(decoded.id).isEqualTo("call_abc")
            assertThat(decoded.name).isEqualTo("search")
            assertThat(decoded.arguments).contains("kotlin")
        }
    }

    @Nested
    @DisplayName("ProtocolFileAttachment serialization")
    inner class ProtocolFileAttachmentTests {

        @Test
        fun `serialize and deserialize file attachment`() {
            val fa = ProtocolFileAttachment(
                uri = "content://com.app/file/1",
                mimeType = "application/pdf",
                name = "report.pdf"
            )
            val encoded = json.encodeToString(fa)
            val decoded = json.decodeFromString<ProtocolFileAttachment>(encoded)

            assertThat(decoded.uri).isEqualTo("content://com.app/file/1")
            assertThat(decoded.mimeType).isEqualTo("application/pdf")
            assertThat(decoded.name).isEqualTo("report.pdf")
        }

        @Test
        fun `null name is preserved`() {
            val fa = ProtocolFileAttachment(uri = "file:///x", mimeType = "text/plain")
            val encoded = json.encodeToString(fa)
            val decoded = json.decodeFromString<ProtocolFileAttachment>(encoded)

            assertThat(decoded.name).isNull()
        }
    }

    @Nested
    @DisplayName("PromptRequest serialization")
    inner class PromptRequestTests {

        @Test
        fun `serialize and deserialize minimal request`() {
            val req = PromptRequest(
                messages = listOf(
                    ProtocolMessage(role = "user", content = "ping")
                ),
                model = "gpt-4o"
            )
            val encoded = json.encodeToString(req)
            val decoded = json.decodeFromString<PromptRequest>(encoded)

            assertThat(decoded.messages).hasSize(1)
            assertThat(decoded.messages[0].role).isEqualTo("user")
            assertThat(decoded.model).isEqualTo("gpt-4o")
            assertThat(decoded.stream).isTrue()
            assertThat(decoded.temperature).isNull()
            assertThat(decoded.maxTokens).isNull()
            assertThat(decoded.tools).isNull()
        }

        @Test
        fun `serialize and deserialize full request`() {
            val req = PromptRequest(
                messages = listOf(
                    ProtocolMessage(role = "system", content = "Be concise"),
                    ProtocolMessage(role = "user", content = "What is 2+2?")
                ),
                model = "deepseek-chat",
                temperature = 0.7,
                topP = 0.9,
                maxTokens = 1024,
                frequencyPenalty = 0.1,
                presencePenalty = 0.2,
                tools = listOf(
                    ProtocolTool(
                        function = ProtocolToolFunction(
                            name = "calculator",
                            description = "Performs calculations",
                            parameters = """{"type":"object","properties":{"expr":{"type":"string"}}}"""
                        )
                    )
                ),
                stream = false,
                reasoning = true,
                webSearch = false
            )
            val encoded = json.encodeToString(req)
            val decoded = json.decodeFromString<PromptRequest>(encoded)

            assertThat(decoded.messages).hasSize(2)
            assertThat(decoded.model).isEqualTo("deepseek-chat")
            assertThat(decoded.temperature).isEqualTo(0.7)
            assertThat(decoded.topP).isEqualTo(0.9)
            assertThat(decoded.maxTokens).isEqualTo(1024)
            assertThat(decoded.tools).hasSize(1)
            assertThat(decoded.tools!![0].function.name).isEqualTo("calculator")
            assertThat(decoded.stream).isFalse()
            assertThat(decoded.reasoning).isTrue()
        }
    }

    @Nested
    @DisplayName("PromptResponse serialization")
    inner class PromptResponseTests {

        @Test
        fun `serialize and deserialize text response`() {
            val resp = PromptResponse(
                content = "The answer is 4"
            )
            val encoded = json.encodeToString(resp)
            val decoded = json.decodeFromString<PromptResponse>(encoded)

            assertThat(decoded.content).isEqualTo("The answer is 4")
            assertThat(decoded.reasoning).isNull()
            assertThat(decoded.toolCalls).isNull()
            assertThat(decoded.usage).isNull()
        }

        @Test
        fun `serialize and deserialize full response`() {
            val resp = PromptResponse(
                content = "Result",
                reasoning = "I thought about it",
                usage = ProtocolUsage(input = 10, output = 20, total = 30),
                citations = listOf(
                    ProtocolCitation(title = "Source", url = "https://example.com", source = "Google")
                )
            )
            val encoded = json.encodeToString(resp)
            val decoded = json.decodeFromString<PromptResponse>(encoded)

            assertThat(decoded.content).isEqualTo("Result")
            assertThat(decoded.reasoning).isEqualTo("I thought about it")
            assertThat(decoded.usage).isNotNull()
            assertThat(decoded.usage!!.total).isEqualTo(30)
            assertThat(decoded.citations).hasSize(1)
            assertThat(decoded.citations!![0].url).isEqualTo("https://example.com")
        }
    }

    @Nested
    @DisplayName("ProtocolCitation serialization")
    inner class ProtocolCitationTests {

        @Test
        fun `serialize and deserialize citation`() {
            val c = ProtocolCitation(
                title = "Wiki",
                url = "https://wiki.org",
                source = "Web"
            )
            val encoded = json.encodeToString(c)
            val decoded = json.decodeFromString<ProtocolCitation>(encoded)

            assertThat(decoded.title).isEqualTo("Wiki")
            assertThat(decoded.url).isEqualTo("https://wiki.org")
            assertThat(decoded.source).isEqualTo("Web")
        }
    }

    @Nested
    @DisplayName("StreamChunk sealed class")
    inner class StreamChunkTests {

        @Test
        fun `TextDelta holds content and optional reasoning`() {
            val chunk = StreamChunk.TextDelta(content = "hello", reasoning = "thinking...")
            assertThat(chunk.content).isEqualTo("hello")
            assertThat(chunk.reasoning).isEqualTo("thinking...")
        }

        @Test
        fun `TextDelta reasoning defaults to null`() {
            val chunk = StreamChunk.TextDelta(content = "hello")
            assertThat(chunk.reasoning).isNull()
        }

        @Test
        fun `ToolCallDelta holds tool call data`() {
            val chunk = StreamChunk.ToolCallDelta(
                id = "call_1",
                name = "search",
                arguments = """{"q":"test"}""",
                index = 0
            )
            assertThat(chunk.id).isEqualTo("call_1")
            assertThat(chunk.name).isEqualTo("search")
            assertThat(chunk.index).isEqualTo(0)
        }

        @Test
        fun `Thinking holds content`() {
            val chunk = StreamChunk.Thinking(content = "Let me think...")
            assertThat(chunk.content).isEqualTo("Let me think...")
        }

        @Test
        fun `Usage holds token data`() {
            val usage = ProtocolUsage(input = 5, output = 10, total = 15)
            val chunk = StreamChunk.Usage(usage = usage)
            assertThat(chunk.usage.total).isEqualTo(15)
        }

        @Test
        fun `Citations holds list`() {
            val chunk = StreamChunk.Citations(
                citations = listOf(
                    ProtocolCitation(title = "A", url = "https://a.com")
                )
            )
            assertThat(chunk.citations).hasSize(1)
        }

        @Test
        fun `Error holds message and retryable`() {
            val chunk = StreamChunk.Error(message = "Rate limited", retryable = true, category = "RATE_LIMIT")
            assertThat(chunk.message).isEqualTo("Rate limited")
            assertThat(chunk.retryable).isTrue()
            assertThat(chunk.category).isEqualTo("RATE_LIMIT")
        }

        @Test
        fun `Done is singleton`() {
            val a = StreamChunk.Done
            val b = StreamChunk.Done
            assertThat(a).isSameInstanceAs(b)
        }
    }

    @Nested
    @DisplayName("ProtocolId enum")
    inner class ProtocolIdTests {

        @Test
        fun `all protocol IDs exist`() {
            assertThat(ProtocolId.OPENAI.name).isEqualTo("OPENAI")
            assertThat(ProtocolId.ANTHROPIC.name).isEqualTo("ANTHROPIC")
            assertThat(ProtocolId.VERTEX_AI.name).isEqualTo("VERTEX_AI")
        }

        @Test
        fun `values returns all three`() {
            assertThat(ProtocolId.entries).hasSize(3)
        }
    }

    @Nested
    @DisplayName("JSON wire format compatibility")
    inner class WireFormatTests {

        @Test
        fun `PromptRequest matches OpenAI API structure`() {
            val req = PromptRequest(
                messages = listOf(
                    ProtocolMessage(role = "user", content = "Hello")
                ),
                model = "gpt-4o",
                temperature = 0.7,
                stream = true
            )
            val jsonStr = json.encodeToString(req)

            assertThat(jsonStr).contains("\"model\":\"gpt-4o\"")
            assertThat(jsonStr).contains("\"role\":\"user\"")
            assertThat(jsonStr).contains("\"content\":\"Hello\"")
            assertThat(jsonStr).contains("\"temperature\":0.7")
            assertThat(jsonStr).contains("\"stream\":true")
        }

        @Test
        fun `ProtocolMessage tool_calls serialize as array`() {
            val msg = ProtocolMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    ProtocolToolCall(id = "call_1", name = "fn", arguments = "{}"),
                    ProtocolToolCall(id = "call_2", name = "fn2", arguments = """{"a":1}""")
                )
            )
            val jsonStr = json.encodeToString(msg)

            assertThat(jsonStr).contains("\"toolCalls\":[")
            assertThat(jsonStr).contains("\"call_1\"")
            assertThat(jsonStr).contains("\"call_2\"")
        }

        @Test
        fun `deserialize PromptRequest from JSON string`() {
            val jsonStr = """{"messages":[{"role":"user","content":"test"}],"model":"gpt-4","stream":false}"""
            val req = json.decodeFromString<PromptRequest>(jsonStr)

            assertThat(req.model).isEqualTo("gpt-4")
            assertThat(req.stream).isFalse()
            assertThat(req.messages[0].content).isEqualTo("test")
        }
    }
}
