package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import java.io.File
import org.junit.Test

class ChatRenderStateContractTest {
    @Test
    fun `会话输入区使用语义化 M3 控件和实测高度`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
        ).readText()
        val tags = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/testing/UiTags.kt",
        ).readText()
        assertThat(source).doesNotContain("CompactInputChip(")
        assertThat(source).doesNotContain("visualHeight = 34.dp")
        assertThat(source).doesNotContain("NexaraGlassCard(")
        assertThat(source).doesNotContain("NexaraColors.GlassSurface")
        assertThat(source).doesNotContain("NexaraColors.GlassBorder")
        assertThat(source).contains("onSizeChanged")
        assertThat(source).contains("chatComposerInsets(")
        assertThat(tags).contains("chat_composer")
        assertThat(tags).contains("chat_thinking_trace")
    }

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

    @Test
    fun `结构化服务错误映射应输出错误锚点`() {
        val state = ChatUiState(
            generationNotice = GenerationFailureNotice.from(
                GenerationFailure.of(GenerationFailureCode.SERVER),
            ),
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_ERROR)
    }

    @Test
    fun `旧版错误字段应独立输出错误锚点`() {
        val state = ChatUiState(
            error = "legacy provider error",
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_ERROR)
    }

    @Test
    fun `状态错误不应被短暂无状态覆盖`() {
        val state = ChatUiState(
            status = GenerationStatus.ERROR,
        )

        assertThat(chatRenderStateTag(state)).isEqualTo(UiTags.CHAT_STATE_ERROR)
    }
}
