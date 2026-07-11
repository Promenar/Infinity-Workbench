package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PipelineBubbleTest {

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
}
