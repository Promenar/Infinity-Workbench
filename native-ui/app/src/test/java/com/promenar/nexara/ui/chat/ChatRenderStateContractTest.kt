package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.ui.testing.UiTags
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
    fun `模型与上下文动作使用低对比 AssistChip 而非选择型 FilterChip`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
        ).readText()
        val inputActions = source
            .substringAfter("private fun ChatInputTopBar(")
            .substringBefore("private fun TokenDetailRow(")

        assertThat(inputActions).contains("AssistChip(")
        assertThat(inputActions).contains("AssistChipDefaults.assistChipColors(")
        assertThat(inputActions).contains("surfaceContainerLow")
        assertThat(inputActions).doesNotContain("FilterChip(")
    }

    @Test
    fun `生成中追尾应随输入区实测高度变化重新校正且尊重用户上滚`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
        ).readText()
        val streamingFollowEffect = source
            .substringAfter("// 生成中跟随当前 AI item 的尾部")
            .substringBefore("// IME 键盘避让")

        assertThat(streamingFollowEffect).contains("composerHeightPx,")
        assertThat(streamingFollowEffect).contains(
            "if (uiState.isGenerating && autoFollowEnabled)",
        )
        assertThat(streamingFollowEffect).doesNotContain("delay(16)")
        assertThat(streamingFollowEffect).doesNotContain("withFrameNanos { }")
        assertThat(streamingFollowEffect).contains("scrollToStreamingTail()")
    }

    @Test
    fun `横屏会话列表压缩顶部与条目间距且不依赖初始索引裁剪消息`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
        ).readText()
        val conversationList = source
            .substringAfter("BoxWithConstraints(")
            .substringBefore("// ── Skeleton")

        assertThat(conversationList).contains("val isLandscape = maxWidth > maxHeight")
        assertThat(conversationList).contains(
            "top = if (isLandscape) NexaraSpacing.Small else NexaraSpacing.Large",
        )
        assertThat(conversationList).contains(
            "if (isLandscape) NexaraSpacing.Small else NexaraSpacing.Medium",
        )
        assertThat(source).doesNotContain("initialStreamingIndex")
        assertThat(source).doesNotContain("LocalConfiguration")
        assertThat(source).doesNotContain("Configuration.ORIENTATION_LANDSCAPE")
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

    @Test
    fun `PostProcessChip与TaskFloatingPanel不包含旧Glass组件和硬编码圆角`() {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val inlineComponentsSource = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt",
        ).readText()
        val taskFloatingPanelSource = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/components/TaskFloatingPanel.kt",
        ).readText()
        val postProcessChipSource = inlineComponentsSource
            .substringAfter("fun PostProcessChip(")
            .substringBefore("\n@Composable\nfun SummaryCard(")

        assertThat(postProcessChipSource).doesNotContain("NexaraGlassCard")
        assertThat(postProcessChipSource).doesNotContain("GlassSurface")
        assertThat(postProcessChipSource).doesNotContain("GlassBorder")
        assertThat(postProcessChipSource).doesNotContain("RoundedCornerShape(50)")

        assertThat(taskFloatingPanelSource).doesNotContain("NexaraGlassCard")
        assertThat(taskFloatingPanelSource).doesNotContain("GlassSurface")
        assertThat(taskFloatingPanelSource).doesNotContain("GlassBorder")
        assertThat(taskFloatingPanelSource).doesNotContain("RoundedCornerShape(16.dp)")
    }
}
