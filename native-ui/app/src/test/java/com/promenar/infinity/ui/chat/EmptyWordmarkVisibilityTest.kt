package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import org.junit.Test

class EmptyWordmarkVisibilityTest {
    private val session = Session("s", "assistant", title = "")
    private val loadedEmpty = ChatUiState(session = session)

    @Test
    fun `已载入且没有消息的会话显示字标`() {
        assertThat(shouldShowEmptyWordmark(loadedEmpty)).isTrue()
    }

    @Test
    fun `会话尚未载入的首帧不显示字标`() {
        assertThat(shouldShowEmptyWordmark(ChatUiState())).isFalse()
    }

    @Test
    fun `有消息、加载中、生成中或待审批时不显示字标`() {
        val message = Message("u", MessageRole.USER, "你好", createdAt = 1L)
        assertThat(shouldShowEmptyWordmark(loadedEmpty.copy(messages = listOf(message)))).isFalse()
        assertThat(shouldShowEmptyWordmark(loadedEmpty.copy(isLoading = true))).isFalse()
        assertThat(shouldShowEmptyWordmark(loadedEmpty.copy(isGenerating = true))).isFalse()
        assertThat(shouldShowEmptyWordmark(loadedEmpty.copy(approvalRequest = ApprovalRequest()))).isFalse()
    }
}
