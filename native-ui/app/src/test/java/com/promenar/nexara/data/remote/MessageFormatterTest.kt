package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.parser.ToolCall
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MessageFormatter")
class MessageFormatterTest {

    private class TestFormatter : BaseMessageFormatter() {
        override fun formatHistory(
            messages: List<FormatterMessage>,
            contextWindow: Int?
        ): List<LlmChatMessage> {
            val window = contextWindow ?: messages.size
            val limited = messages.takeLast(window)
            return limited.map { convertMessage(it) }
        }
    }

    private val formatter = TestFormatter()

    @Nested
    @DisplayName("MessageRole enum")
    inner class MessageRoleTest {

        @Test
        @DisplayName("toApiString returns lowercase role name")
        fun toApiString() {
            assertThat(MessageRole.SYSTEM.toApiString()).isEqualTo("system")
            assertThat(MessageRole.USER.toApiString()).isEqualTo("user")
            assertThat(MessageRole.ASSISTANT.toApiString()).isEqualTo("assistant")
            assertThat(MessageRole.TOOL.toApiString()).isEqualTo("tool")
        }

        @Test
        @DisplayName("fromString parses case-insensitively")
        fun fromString() {
            assertThat(MessageRole.fromString("system")).isEqualTo(MessageRole.SYSTEM)
            assertThat(MessageRole.fromString("USER")).isEqualTo(MessageRole.USER)
            assertThat(MessageRole.fromString("Assistant")).isEqualTo(MessageRole.ASSISTANT)
            assertThat(MessageRole.fromString("tool")).isEqualTo(MessageRole.TOOL)
        }

        @Test
        @DisplayName("fromString throws on invalid value")
        fun fromStringInvalid() {
            try {
                MessageRole.fromString("invalid")
                assert(false) { "Should have thrown" }
            } catch (_: NoSuchElementException) {
            }
        }

        @Test
        @DisplayName("enum has exactly 4 values")
        fun enumSize() {
            assertThat(MessageRole.entries).hasSize(4)
        }
    }

    @Nested
    @DisplayName("FileAttachment")
    inner class FileAttachmentTest {

        @Test
        @DisplayName("creates attachment with all fields")
        fun fullAttachment() {
            val att = FileAttachment(uri = "file:///photo.jpg", mimeType = "image/jpeg", name = "photo.jpg")
            assertThat(att.uri).isEqualTo("file:///photo.jpg")
            assertThat(att.mimeType).isEqualTo("image/jpeg")
            assertThat(att.name).isEqualTo("photo.jpg")
        }

        @Test
        @DisplayName("name defaults to null")
        fun nameDefaultsNull() {
            val att = FileAttachment(uri = "file:///doc.pdf", mimeType = "application/pdf")
            assertThat(att.name).isNull()
        }
    }

    @Nested
    @DisplayName("FormatterMessage")
    inner class FormatterMessageTest {

        @Test
        @DisplayName("creates minimal message with role and content")
        fun minimalMessage() {
            val msg = FormatterMessage(role = MessageRole.USER, content = "Hello")
            assertThat(msg.role).isEqualTo(MessageRole.USER)
            assertThat(msg.content).isEqualTo("Hello")
            assertThat(msg.reasoning).isNull()
            assertThat(msg.name).isNull()
            assertThat(msg.toolCallId).isNull()
            assertThat(msg.toolCalls).isNull()
            assertThat(msg.thoughtSignature).isNull()
            assertThat(msg.files).isNull()
        }

        @Test
        @DisplayName("creates message with all optional fields")
        fun fullMessage() {
            val toolCalls = listOf(
                ToolCall(id = "call_1", name = "search", arguments = mapOf("q" to "test"))
            )
            val files = listOf(
                FileAttachment(uri = "file:///a.png", mimeType = "image/png", name = "a.png")
            )
            val msg = FormatterMessage(
                role = MessageRole.ASSISTANT,
                content = "result",
                reasoning = "let me think",
                name = "search_tool",
                toolCallId = "call_1",
                toolCalls = toolCalls,
                thoughtSignature = "sig_123",
                files = files
            )
            assertThat(msg.role).isEqualTo(MessageRole.ASSISTANT)
            assertThat(msg.content).isEqualTo("result")
            assertThat(msg.reasoning).isEqualTo("let me think")
            assertThat(msg.name).isEqualTo("search_tool")
            assertThat(msg.toolCallId).isEqualTo("call_1")
            assertThat(msg.toolCalls).hasSize(1)
            assertThat(msg.thoughtSignature).isEqualTo("sig_123")
            assertThat(msg.files).hasSize(1)
        }
    }

    @Nested
    @DisplayName("LlmChatMessage")
    inner class LlmChatMessageTest {

        @Test
        @DisplayName("creates wire message with role and content")
        fun basicWireMessage() {
            val msg = LlmChatMessage(role = MessageRole.SYSTEM, content = "You are helpful")
            assertThat(msg.role).isEqualTo(MessageRole.SYSTEM)
            assertThat(msg.content).isEqualTo("You are helpful")
        }
    }

    @Nested
    @DisplayName("BaseMessageFormatter defaults")
    inner class BaseDefaultsTest {

        @Test
        @DisplayName("shouldStripHangingToolCalls returns false by default")
        fun shouldStripDefault() {
            val msg = FormatterMessage(role = MessageRole.ASSISTANT, content = "")
            assertThat(formatter.shouldStripHangingToolCalls(msg)).isFalse()
        }

        @Test
        @DisplayName("supportsReasoningInHistory returns false by default")
        fun supportsReasoningDefault() {
            assertThat(formatter.supportsReasoningInHistory()).isFalse()
        }
    }

    @Nested
    @DisplayName("convertMessage")
    inner class ConvertMessageTest {

        @Test
        @DisplayName("maps all fields from FormatterMessage to LlmChatMessage")
        fun mapsAllFields() {
            val toolCalls = listOf(
                ToolCall(id = "c1", name = "calc", arguments = mapOf("x" to 1))
            )
            val files = listOf(
                FileAttachment(uri = "file:///img.png", mimeType = "image/png")
            )
            val input = FormatterMessage(
                role = MessageRole.ASSISTANT,
                content = "Here is the answer",
                reasoning = "step by step",
                name = "tool_name",
                toolCallId = "c1",
                toolCalls = toolCalls,
                thoughtSignature = "sig",
                files = files
            )
            val result = formatter.formatHistory(listOf(input))
            assertThat(result).hasSize(1)
            val out = result[0]
            assertThat(out.role).isEqualTo(MessageRole.ASSISTANT)
            assertThat(out.content).isEqualTo("Here is the answer")
            assertThat(out.reasoning).isEqualTo("step by step")
            assertThat(out.name).isEqualTo("tool_name")
            assertThat(out.toolCallId).isEqualTo("c1")
            assertThat(out.toolCalls).isEqualTo(toolCalls)
            assertThat(out.thoughtSignature).isEqualTo("sig")
            assertThat(out.files).isEqualTo(files)
        }

        @Test
        @DisplayName("nullable fields default to null when absent")
        fun nullableDefaults() {
            val input = FormatterMessage(role = MessageRole.USER, content = "Hi")
            val result = formatter.formatHistory(listOf(input))
            val out = result[0]
            assertThat(out.reasoning).isNull()
            assertThat(out.name).isNull()
            assertThat(out.toolCallId).isNull()
            assertThat(out.toolCalls).isNull()
            assertThat(out.thoughtSignature).isNull()
            assertThat(out.files).isNull()
        }
    }

    @Nested
    @DisplayName("formatHistory")
    inner class FormatHistoryTest {

        @Test
        @DisplayName("formats multi-turn conversation in order")
        fun multiTurn() {
            val messages = listOf(
                FormatterMessage(role = MessageRole.SYSTEM, content = "Be helpful"),
                FormatterMessage(role = MessageRole.USER, content = "What is 2+2?"),
                FormatterMessage(role = MessageRole.ASSISTANT, content = "4"),
                FormatterMessage(role = MessageRole.USER, content = "And 3+3?"),
                FormatterMessage(role = MessageRole.ASSISTANT, content = "6")
            )
            val result = formatter.formatHistory(messages)
            assertThat(result).hasSize(5)
            assertThat(result[0].role).isEqualTo(MessageRole.SYSTEM)
            assertThat(result[1].role).isEqualTo(MessageRole.USER)
            assertThat(result[2].role).isEqualTo(MessageRole.ASSISTANT)
            assertThat(result[3].role).isEqualTo(MessageRole.USER)
            assertThat(result[4].role).isEqualTo(MessageRole.ASSISTANT)
        }

        @Test
        @DisplayName("respects contextWindow by taking last N messages")
        fun contextWindow() {
            val messages = (1..10).map { i ->
                FormatterMessage(
                    role = if (i % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                    content = "msg$i"
                )
            }
            val result = formatter.formatHistory(messages, contextWindow = 3)
            assertThat(result).hasSize(3)
            assertThat(result[0].content).isEqualTo("msg8")
            assertThat(result[1].content).isEqualTo("msg9")
            assertThat(result[2].content).isEqualTo("msg10")
        }

        @Test
        @DisplayName("contextWindow larger than messages returns all")
        fun contextWindowOversize() {
            val messages = listOf(
                FormatterMessage(role = MessageRole.USER, content = "hi")
            )
            val result = formatter.formatHistory(messages, contextWindow = 100)
            assertThat(result).hasSize(1)
        }

        @Test
        @DisplayName("empty input returns empty list")
        fun emptyInput() {
            val result = formatter.formatHistory(emptyList())
            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("tool role message with toolCallId and name")
        fun toolMessage() {
            val messages = listOf(
                FormatterMessage(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCall(id = "call_abc", name = "get_weather", arguments = mapOf("city" to "Tokyo"))
                    )
                ),
                FormatterMessage(
                    role = MessageRole.TOOL,
                    content = "{\"temp\": 25}",
                    toolCallId = "call_abc",
                    name = "get_weather"
                )
            )
            val result = formatter.formatHistory(messages)
            assertThat(result).hasSize(2)
            assertThat(result[0].role).isEqualTo(MessageRole.ASSISTANT)
            assertThat(result[0].toolCalls).hasSize(1)
            assertThat(result[1].role).isEqualTo(MessageRole.TOOL)
            assertThat(result[1].toolCallId).isEqualTo("call_abc")
            assertThat(result[1].name).isEqualTo("get_weather")
        }

        @Test
        @DisplayName("assistant message with reasoning content")
        fun reasoningContent() {
            val messages = listOf(
                FormatterMessage(
                    role = MessageRole.ASSISTANT,
                    content = "The answer is 42.",
                    reasoning = "I need to think about this...",
                    thoughtSignature = "sig_xyz"
                )
            )
            val result = formatter.formatHistory(messages)
            assertThat(result).hasSize(1)
            assertThat(result[0].reasoning).isEqualTo("I need to think about this...")
            assertThat(result[0].thoughtSignature).isEqualTo("sig_xyz")
        }

        @Test
        @DisplayName("user message with file attachments")
        fun fileAttachments() {
            val files = listOf(
                FileAttachment(uri = "file:///photo1.jpg", mimeType = "image/jpeg", name = "photo1.jpg"),
                FileAttachment(uri = "file:///doc.pdf", mimeType = "application/pdf")
            )
            val messages = listOf(
                FormatterMessage(
                    role = MessageRole.USER,
                    content = "Describe these files",
                    files = files
                )
            )
            val result = formatter.formatHistory(messages)
            assertThat(result).hasSize(1)
            assertThat(result[0].files).hasSize(2)
            assertThat(result[0].files!![0].uri).isEqualTo("file:///photo1.jpg")
            assertThat(result[0].files!![1].mimeType).isEqualTo("application/pdf")
            assertThat(result[0].files!![1].name).isNull()
        }
    }
}
