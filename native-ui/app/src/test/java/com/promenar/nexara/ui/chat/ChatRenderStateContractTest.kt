package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import java.io.File
import org.junit.Test

class ChatRenderStateContractTest {
    @Test
    fun `five representative states have stable render anchors`() {
        assertThat(
            listOf(
                UiTags.CHAT_STATE_EMPTY,
                UiTags.CHAT_STATE_LOADING,
                UiTags.CHAT_STATE_GENERATING,
                UiTags.CHAT_STATE_APPROVAL,
                UiTags.CHAT_STATE_ERROR,
            ),
        ).containsExactly(
            "chat_state_empty",
            "chat_state_loading",
            "chat_state_generating",
            "chat_state_approval",
            "chat_state_error",
        ).inOrder()
    }

    @Test
    fun `状态锚点附着在真实会话表面且加载骨架可独立观测`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
        ).readText()
        val conversationSurface = source.substringAfter("LazyColumn(").substringBefore(") {")

        assertThat(conversationSurface).contains(".testTag(renderStateTag)")
        assertThat(source).doesNotContain("Spacer(Modifier.size(1.dp).testTag(renderStateTag))")
        assertThat(source).contains(".testTag(UiTags.CHAT_LOADING_SKELETON)")
    }

    @Test
    fun `已有消息的稳定态不会被误标为空态`() {
        val state = ChatUiState(
            messages = listOf(Message("m1", MessageRole.ASSISTANT, "ready")),
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_READY)
        assertThat(chatRenderStateTag(ChatUiState())).isEqualTo(UiTags.CHAT_STATE_EMPTY)
    }
}
