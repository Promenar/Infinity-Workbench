package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import org.junit.jupiter.api.Test

class PipelineBubbleTest {

    @Test
    fun `历史错误只按持久化信封映射且旧自然语言安全回落`() {
        val timeoutEnvelope = GenerationFailureCodec.encode(
            GenerationFailure(
                com.promenar.nexara.domain.generation.GenerationFailureCode.TIMEOUT,
                technical = "must-not-display",
            ),
        )

        val decoded = historicalGenerationFailureNotice(timeoutEnvelope)
        val legacy = historicalGenerationFailureNotice("provider 原始错误：secret")

        assertThat(GenerationFailureNotice.template(decoded).resourceId)
            .isEqualTo(com.promenar.nexara.R.string.generation_failure_timeout)
        assertThat(GenerationFailureNotice.template(decoded).args).isEmpty()
        assertThat(GenerationFailureNotice.template(legacy).resourceId)
            .isEqualTo(com.promenar.nexara.R.string.generation_failure_unknown)
        assertThat(GenerationFailureNotice.template(legacy).args).isEmpty()
    }

    @Test
    fun `streaming reasoning preview keeps short reasoning unchanged`() {
        val reasoning = "1. 先确认输入\n2. 再组织输出"

        assertThat(streamingReasoningPreview(reasoning)).isEqualTo(reasoning)
    }

    @Test
    fun `streaming reasoning preview keeps recent tail for long reasoning`() {
        val reasoning = (1..40).joinToString("\n") { index ->
            "$index. 推理步骤 $index"
        }

        val result = streamingReasoningPreview(reasoning, maxChars = 240, maxLines = 6)

        assertThat(result).startsWith("...\n")
        assertThat(result).contains("40. 推理步骤 40")
        assertThat(result).doesNotContain("1. 推理步骤 1")
        assertThat(result.lines().size).isAtMost(7)
    }

    @Test
    fun `streaming body creates content step after thinking and tools for empty placeholder`() {
        val message = Message(
            id = "assistant-streaming",
            role = MessageRole.ASSISTANT,
            content = "",
            reasoning = "正在分析",
            executionSteps = listOf(
                ExecutionStep(
                    id = "tool-1",
                    type = "tool_result",
                    toolName = "search",
                    content = "完成"
                )
            )
        )

        val plan = buildPipelineRenderPlan(
            messages = listOf(message),
            isGenerating = true,
            hasStreamingContent = true
        )

        assertThat(plan.steps.map { it::class.simpleName })
            .containsExactly("Thinking", "ToolExec", "Content")
            .inOrder()
        assertThat(plan.showStandaloneCursor).isFalse()
        assertThat(plan.showContentCursor).isFalse()
    }

    @Test
    fun `empty TTFT keeps standalone cursor without creating content step`() {
        val message = Message(
            id = "assistant-waiting",
            role = MessageRole.ASSISTANT,
            content = ""
        )

        val plan = buildPipelineRenderPlan(
            messages = listOf(message),
            isGenerating = true,
            hasStreamingContent = false
        )

        assertThat(plan.steps).isEmpty()
        assertThat(plan.showStandaloneCursor).isTrue()
        assertThat(plan.showContentCursor).isFalse()
    }

    @Test
    fun `会话表面使用 M3 语义角色并保留思考轨迹`() {
        val moduleRoot = java.io.File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt",
        ).readText()

        assertThat(source).contains("internal fun ThinkingTrace(")
        assertThat(source).contains("MaterialTheme.colorScheme.surfaceContainerLow")
        assertThat(source).contains("MaterialTheme.colorScheme.secondaryContainer")
        assertThat(source).contains("MaterialTheme.colorScheme.onSecondaryContainer")
        val thinkingTraceSource = source
            .substringAfter("internal fun ThinkingTrace(")
            .substringBefore("//  InlineToolRow")
        assertThat(thinkingTraceSource).contains(".drawBehind")
        assertThat(thinkingTraceSource).doesNotContain(".fillMaxHeight()")
        assertThat(source).doesNotContain("private fun InlineThinkingRow(")
        assertThat(source).doesNotContain("import com.promenar.nexara.ui.common.NexaraGlassCard")
    }
}
