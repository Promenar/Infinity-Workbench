package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

class ChatPipelineLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun stepRowIs44DpAndArrowSharesTextCenter() {
        rule.setContent {
            NexaraTheme {
                Box(Modifier.width(371.dp)) {
                    CompactStepRow(listOf("运行了 2 条命令"), Icons.Rounded.CheckCircle, {}, tag = "STEP")
                }
            }
        }
        rule.onNodeWithTag("STEP").assertHeightIsEqualTo(44.dp).assertHasClickAction()
        val text = rule.onNode(hasTestTag("CHAT_STEP_SUMMARY") and hasAnyAncestor(hasTestTag("STEP")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val arrow = rule.onNode(hasTestTag("CHAT_STEP_ARROW") and hasAnyAncestor(hasTestTag("STEP")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(abs(text.center.y - arrow.center.y)).isAtMost(with(rule.density) { 1.dp.toPx() })
    }

    @Test
    fun runningSummaryKeepsItsLeftEdgeAndThinkingPreviewFollowsAfterTwelveDp() {
        var active by mutableStateOf(false)
        rule.setContent {
            NexaraTheme {
                Column(Modifier.width(371.dp)) {
                    CompactStepRow(listOf("正在运行命令…"), Icons.Rounded.CheckCircle, {}, active = active, tag = "STEP")
                    ThinkingTrace("这是用于验证推理预览位置的较长文本。".repeat(8), true, 15, elapsedMs = 6_000)
                }
            }
        }
        fun summaryLeft() = rule.onNode(hasTestTag("CHAT_STEP_SUMMARY") and hasAnyAncestor(hasTestTag("STEP")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        val before = summaryLeft()
        rule.runOnIdle { active = true }
        rule.onNodeWithTag("STEP").assertHeightIsEqualTo(44.dp)
        assertThat(abs(summaryLeft() - before)).isAtMost(with(rule.density) { 0.5.dp.toPx() })
        val label = rule.onNode(hasTestTag("CHAT_STEP_SUMMARY") and hasAnyAncestor(hasTestTag(UiTags.CHAT_THINKING_TOGGLE)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val preview = rule.onNodeWithTag("CHAT_THINKING_PREVIEW", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val arrow = rule.onNode(hasTestTag("CHAT_STEP_ARROW") and hasAnyAncestor(hasTestTag(UiTags.CHAT_THINKING_TOGGLE)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(abs(preview.left - label.right - with(rule.density) { 12.dp.toPx() })).isAtMost(with(rule.density) { 0.5.dp.toPx() })
        assertThat(preview.right).isAtMost(arrow.left)
    }

    @Test
    fun collapsedToolsContainNaturalLanguageWithoutInternalIdsOrArguments() {
        rule.setContent {
            NexaraTheme { InlineToolRow(toolSteps(), isExecuting = false, fontSize = 15) }
        }
        listOf("ssh_exec", "read_file", "private-path", "token-marker").forEach { internalValue ->
            rule.onNodeWithText(internalValue, substring = true, useUnmergedTree = true).assertDoesNotExist()
            val description = rule.onNodeWithTag("CHAT_TOOL_TOGGLE").fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription].joinToString()
            assertThat(description).doesNotContain(internalValue)
        }
        rule.onNodeWithTag("CHAT_TOOL_TOGGLE").performClick()
        rule.onNodeWithText("ssh_exec", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("CHAT_TOOL_DETAILS").assertIsDisplayed()
    }

    @Test
    fun expandedToolAndThinkingContentKeepEightDpBottomGap() {
        rule.setContent {
            NexaraTheme {
                Column(Modifier.width(371.dp)) {
                    Box(Modifier.testTag("TOOL_ROW")) { InlineToolRow(toolSteps(), isExecuting = false, fontSize = 15, initiallyExpanded = true) }
                    Box(Modifier.testTag("THINKING_ROW")) { ThinkingTrace("推理内容", false, 15, initiallyExpanded = true) }
                }
            }
        }
        val tolerance = with(rule.density) { 0.5.dp.toPx() }
        val eight = with(rule.density) { 8.dp.toPx() }
        val toolRow = rule.onNodeWithTag("TOOL_ROW").fetchSemanticsNode().boundsInRoot
        val details = rule.onNodeWithTag("CHAT_TOOL_DETAILS").fetchSemanticsNode().boundsInRoot
        // 展开内容与下一块之间：自身 8dp + 流水线块间 8dp = 16dp，与段落间距一致。
        assertThat(abs(toolRow.bottom - details.bottom - eight)).isAtMost(tolerance)
        val thinkingRow = rule.onNodeWithTag("THINKING_ROW").fetchSemanticsNode().boundsInRoot
        val thinking = rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(abs(thinkingRow.bottom - thinking.bottom - eight)).isAtMost(tolerance)
    }

    @Test
    fun userBubbleUsesAtMost82PercentAndAttachmentsAreAboveWithSixDpGap() {
        rule.setContent {
            NexaraTheme {
                UserMessageBubble(
                    message = Message("user", MessageRole.USER, "这是用来验证长消息宽度的内容。".repeat(8), userDocuments = listOf(document())),
                    fontSize = 15,
                    modifier = Modifier.width(360.dp).testTag("USER_AVAILABLE"),
                )
            }
        }
        val available = rule.onNodeWithTag("USER_AVAILABLE").fetchSemanticsNode().boundsInRoot
        val bubble = rule.onNodeWithTag("CHAT_USER_BUBBLE", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val attachment = rule.onNodeWithTag(UiTags.CHAT_MESSAGE_DOCUMENTS, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 0.5.dp.toPx() }
        assertThat(bubble.width).isAtMost(available.width * 0.82f + tolerance)
        assertThat(abs(bubble.right - available.right)).isAtMost(tolerance)
        assertThat(abs(bubble.top - attachment.bottom - with(rule.density) { 6.dp.toPx() })).isAtMost(tolerance)
    }

    @Test
    fun userTimeIsOnlyInLongPressMenu() {
        val createdAt = 1_700_000_000_000L
        val timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(createdAt))
        rule.setContent {
            NexaraTheme { UserMessageBubble(Message("user", MessageRole.USER, "用户消息", createdAt = createdAt), 15) }
        }
        rule.onNodeWithText(timestamp).assertDoesNotExist()
        rule.onNodeWithText("用户消息").performTouchInput { longClick() }
        rule.onNodeWithText(timestamp).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun completedActionRowHas36DpVisualHeightAnd48DpTouchTargetsAndCallbacks() {
        var copied = 0
        var regenerated = 0
        var branched = 0
        rule.setContent {
            NexaraTheme {
                Box(Modifier.width(371.dp).padding(vertical = 8.dp)) {
                    AssistantActionRow("超长模型名称用于检查截断和时间可见性".repeat(4), "14:35", false,
                        onCopy = { copied++ }, onRegenerate = { regenerated++ }, onBranch = { branched++ }, onDelete = {})
                }
            }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_ACTIONS").assertHeightIsEqualTo(36.dp)
        listOf("CHAT_ASSISTANT_COPY", "CHAT_ASSISTANT_REGENERATE", "CHAT_ASSISTANT_MORE").forEach { tag ->
            val node = rule.onNodeWithTag(tag).assertHasClickAction().fetchSemanticsNode()
            val minimum = with(rule.density) { 48.dp.toPx() }
            assertThat(node.touchBoundsInRoot.width).isAtLeast(minimum)
            assertThat(node.touchBoundsInRoot.height).isAtLeast(minimum)
        }
        val model = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA).fetchSemanticsNode().boundsInRoot
        val time = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_TIME_METADATA).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertThat(model.right).isAtMost(time.left)
        assertThat(abs(model.center.y - time.center.y)).isAtMost(with(rule.density) { 0.5.dp.toPx() })
        rule.onNodeWithTag("CHAT_ASSISTANT_COPY").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_REGENERATE").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").performClick()
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_BRANCH).performClick()
        rule.runOnIdle {
            assertThat(copied).isEqualTo(1)
            assertThat(regenerated).isEqualTo(1)
            assertThat(branched).isEqualTo(1)
        }
    }

    @Test
    fun generationHidesActionRowAndUnavailableRegenerationStaysHidden() {
        rule.setContent {
            NexaraTheme { AssistantActionRow("模型", "14:35", true, onCopy = {}) }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_ACTIONS").assertDoesNotExist()
    }

    @Test
    fun noRegenerationOrBranchCallbackDoesNotExposeThoseActions() {
        rule.setContent {
            NexaraTheme { AssistantActionRow("模型", "14:35", false, onCopy = {}) }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_REGENERATE").assertDoesNotExist()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").assertIsNotEnabled()
    }

    @Test
    fun shortAssistantBodyKeeps48DpTouchAreaWithoutAddingVisualPadding() {
        rule.setContent {
            NexaraTheme {
                Box(Modifier.width(371.dp).padding(vertical = 24.dp)) {
                    ContentSegment("结论", false, false, 15, onContentChange = null)
                }
            }
        }
        val body = rule.onNodeWithTag("CHAT_ASSISTANT_BODY").assertHasClickAction().fetchSemanticsNode()
        assertThat(body.touchBoundsInRoot.height).isAtLeast(with(rule.density) { 48.dp.toPx() })
        assertThat(body.boundsInRoot.height).isLessThan(with(rule.density) { 48.dp.toPx() })
    }

    @Test
    fun stepRowAt130PercentFontGrowsWithoutClipping() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                NexaraTheme {
                    Box(Modifier.width(371.dp)) {
                        CompactStepRow(listOf("查看了 2 个文件"), Icons.Rounded.CheckCircle, {}, tag = "LARGE_STEP")
                    }
                }
            }
        }
        rule.onNodeWithTag("LARGE_STEP").assertHeightIsAtLeast(44.dp)
        val row = rule.onNodeWithTag("LARGE_STEP").fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithTag("CHAT_STEP_SUMMARY", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(text.top).isAtLeast(row.top)
        assertThat(text.bottom).isAtMost(row.bottom)
    }

    @Test
    fun longArgumentsAndResultsCanEachExpandWithoutTruncation() {
        rule.setContent {
            NexaraTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InlineToolRow(listOf(
                        ExecutionStep("a", "tool_call", "exec_js", (1..9).joinToString("\n") { "parameter_$it" }, "call", timestamp = 1_000),
                        ExecutionStep("r", "tool_result", "exec_js", toolCallId = "call", content = (1..15).joinToString("\n") { "result_$it" }, timestamp = 1_400),
                    ), false, 15, initiallyExpanded = true)
                }
            }
        }
        assertThat(textLayout("CHAT_TOOL_ARGUMENTS").lineCount).isEqualTo(6)
        assertThat(textLayout("CHAT_TOOL_ARGUMENTS").hasVisualOverflow).isTrue()
        assertThat(textLayout("CHAT_TOOL_RESULT").lineCount).isEqualTo(12)
        rule.onAllNodes(hasTestTag("CHAT_TOOL_EXPAND_ALL"))[0].performScrollTo().performClick()
        assertThat(textLayout("CHAT_TOOL_ARGUMENTS").lineCount).isEqualTo(9)
        assertThat(textLayout("CHAT_TOOL_ARGUMENTS").hasVisualOverflow).isFalse()
        rule.onAllNodes(hasTestTag("CHAT_TOOL_EXPAND_ALL"))[1].performScrollTo().performClick()
        assertThat(textLayout("CHAT_TOOL_RESULT").lineCount).isEqualTo(15)
        assertThat(textLayout("CHAT_TOOL_RESULT").hasVisualOverflow).isFalse()
    }

    @Test
    fun historicalUnfinishedToolShowsPendingWithoutInternalIds() {
        val pending = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.chat_step_pending_count, 1, 1)
        rule.setContent {
            NexaraTheme {
                InlineToolRow(listOf(ExecutionStep("a", "tool_call", "ssh_exec", "{}", "a", timestamp = 1_000)), false, 15)
            }
        }
        rule.onNodeWithText(pending, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("ssh_exec", substring = true, useUnmergedTree = true).assertDoesNotExist()
        val description = rule.onNodeWithTag("CHAT_TOOL_TOGGLE").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertThat(description).containsExactly(pending)
    }

    @Test
    fun actionRowBindsCopyAndMessageCallbacksToVisibleBodyBeforeEmptyTail() {
        val copied = mutableListOf<String>()
        val regenerated = mutableListOf<String>()
        val branched = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val answer = Message("body-source", MessageRole.ASSISTANT, "实际可见的正文", modelId = "source-model")
        val emptyTail = Message("empty-tail", MessageRole.ASSISTANT, "", modelId = "tail-model", executionSteps = toolSteps())
        rule.setContent {
            NexaraTheme {
                PipelineBubble(PipelineGroup(listOf(answer, emptyTail), false), false,
                    streamingContent = "", fontSize = 15, onCopy = { copied += it },
                    onRegenerate = { regenerated += it }, onBranch = { branched += it }, onDelete = { deleted += it })
            }
        }
        rule.onNodeWithTag("CHAT_ASSISTANT_COPY").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_REGENERATE").performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").performClick()
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_BRANCH).performClick()
        rule.onNodeWithTag("CHAT_ASSISTANT_MORE").performClick()
        val deleteLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_action_delete)
        rule.onNodeWithText(deleteLabel).performClick()
        rule.runOnIdle {
            assertThat(copied).containsExactly("实际可见的正文")
            assertThat(regenerated).containsExactly("body-source")
            assertThat(branched).containsExactly("body-source")
            assertThat(deleted).containsExactly("body-source")
        }
        rule.onNodeWithText("source-model").assertIsDisplayed()
        rule.onNodeWithText("tail-model").assertDoesNotExist()
    }

    @Test
    fun longPressActionsUseEachContentSegmentOwner() {
        val regenerated = mutableListOf<String>()
        val branched = mutableListOf<String>()
        rule.setContent {
            NexaraTheme {
                PipelineBubble(PipelineGroup(listOf(
                    Message("first", MessageRole.ASSISTANT, "前段正文"),
                    Message("second", MessageRole.ASSISTANT, "最终正文"),
                ), false), false, streamingContent = "", fontSize = 15,
                    onRegenerate = { regenerated += it }, onBranch = { branched += it })
            }
        }
        val regenerateLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_action_regenerate)
        rule.onNodeWithText("前段正文").performTouchInput { longClick() }
        rule.onNodeWithText(regenerateLabel).performClick()
        rule.onNodeWithText("最终正文").performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_BRANCH).performClick()
        rule.runOnIdle {
            assertThat(regenerated).containsExactly("first")
            assertThat(branched).containsExactly("second")
        }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun toolSteps() = listOf(
        ExecutionStep("a", "tool_call", "ssh_exec", "private-path", "a", timestamp = 1_000),
        ExecutionStep("ar", "tool_result", "ssh_exec", toolCallId = "a", content = "完成", timestamp = 1_400),
        ExecutionStep("b", "tool_call", "read_file", "token-marker", "b", timestamp = 1_500),
        ExecutionStep("br", "tool_result", "read_file", toolCallId = "b", content = "完成", timestamp = 1_800),
    )

    private fun document() = MessageDocumentAttachment("document", "需求说明.md", "text/markdown", "内容", 1_024, "fixture", 10)
}
