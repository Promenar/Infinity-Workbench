package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MessageFormatterTest {

    @Test
    fun `OpenAIFormatter should strip reasoning from history`() {
        val formatter = OpenAIFormatter()
        val messages = listOf(
            FormatterMessage(MessageRole.USER, "Hello"),
            FormatterMessage(MessageRole.ASSISTANT, "Hi", reasoning = "Thinking..."),
        )

        val formatted = formatter.formatHistory(messages)

        assertThat(formatted).hasSize(2)
        assertThat(formatted[1].reasoning).isNull()
    }

    @Test
    fun `OpenAIFormatter should support hanging tool calls stripping`() {
        val formatter = OpenAIFormatter()
        val message = FormatterMessage(MessageRole.ASSISTANT, "Call tool", toolCalls = listOf())
        assertThat(formatter.shouldStripHangingToolCalls(message)).isTrue()
    }

    @Test
    fun `DeepSeekFormatter should keep reasoning in history`() {
        val formatter = DeepSeekFormatter("deepseek-chat")
        val messages = listOf(
            FormatterMessage(MessageRole.ASSISTANT, "Hi", reasoning = "Thinking..."),
        )

        val formatted = formatter.formatHistory(messages)

        assertThat(formatted).hasSize(1)
        assertThat(formatted[0].reasoning).isEqualTo("Thinking...")
    }

    @Test
    fun `DeepSeekFormatter should enhance system prompt`() {
        val formatter = DeepSeekFormatter("deepseek-reasoner")
        val messages = listOf(
            FormatterMessage(MessageRole.SYSTEM, "You are a helpful assistant."),
        )

        val formatted = formatter.formatHistory(messages)

        assertThat(formatted).hasSize(1)
        assertThat(formatted[0].content).contains("工具调用规范")
        assertThat(formatted[0].content).contains("任务执行流程")
    }

    @Test
    fun `GeminiFormatter should enhance system prompt for Pro models`() {
        val formatter = GeminiFormatter("gemini-1.5-pro")
        val messages = listOf(
            FormatterMessage(MessageRole.SYSTEM, "Basic prompt"),
        )

        val formatted = formatter.formatHistory(messages)

        assertThat(formatted).hasSize(1)
        assertThat(formatted[0].content).contains("manage_task 工具使用规范")
    }

    @Test
    fun `GeminiFormatter should NOT enhance system prompt for non-Pro models`() {
        val formatter = GeminiFormatter("gemini-1.5-flash")
        val messages = listOf(
            FormatterMessage(MessageRole.SYSTEM, "Basic prompt"),
        )

        val formatted = formatter.formatHistory(messages)

        assertThat(formatted).hasSize(1)
        assertThat(formatted[0].content).isEqualTo("Basic prompt")
    }

    @Test
    fun `MessageFormatterFactory should return correct formatter and cache it`() {
        val provider = com.promenar.nexara.data.remote.parser.ProviderType.OPENAI_COMPATIBLE
        val formatter1 = MessageFormatterFactory.getFormatter(provider, "deepseek-v3")
        val formatter2 = MessageFormatterFactory.getFormatter(provider, "deepseek-v3")

        assertThat(formatter1).isInstanceOf(DeepSeekFormatter::class.java)
        assertThat(formatter1).isSameInstanceAs(formatter2)

        val formatter3 = MessageFormatterFactory.getFormatter(provider, "gpt-4o")
        assertThat(formatter3).isInstanceOf(OpenAIFormatter::class.java)
    }
}
