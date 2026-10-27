package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.ask.AskUserLimits
import org.junit.Test

class AskUserSystemPromptTest {
    @Test fun `只在提问工具可用时给出指引`() {
        assertThat(SystemPromptSections.toolGuidance(setOf(AskUserLimits.TOOL_NAME), false)).contains(AskUserLimits.TOOL_NAME)
        assertThat(SystemPromptSections.toolGuidance(setOf("calculator"), false)).doesNotContain(AskUserLimits.TOOL_NAME)
    }
}
