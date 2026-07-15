package com.promenar.nexara.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ThinkingTraceTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun restoreComposeClock() {
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun completedThinkingTraceCanExpandAndCollapse() {
        rule.setContent {
            NexaraTheme {
                ThinkingTrace(
                    reasoning = "先检查输入，再组织答案。",
                    isGenerating = false,
                    fontSize = 14,
                )
            }
        }

        // 验证 toggle 可见，且具有点击动作和至少 48dp 高度
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        assertExpansionState(R.string.common_state_collapsed)

        // 默认折叠，内容不可见
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsNotDisplayed()

        // 点击展开
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        assertExpansionState(R.string.common_state_expanded)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()

        // 再次点击折叠
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        assertExpansionState(R.string.common_state_collapsed)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsNotDisplayed()
    }

    @Test
    fun generatingThinkingTraceDefaultsToExpanded() {
        rule.setContent {
            NexaraTheme {
                ThinkingTrace(
                    reasoning = "正在分析中...",
                    isGenerating = true,
                    fontSize = 14,
                )
            }
        }

        // 生成态默认展开，内容可见
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)

        assertExpansionState(R.string.common_state_expanded)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()
    }

    @Test
    fun completedGenerationCollapsesOnlyAfterThreeHundredMilliseconds() {
        val isGenerating = mutableStateOf(true)
        rule.setContent {
            NexaraTheme {
                ThinkingTrace(
                    reasoning = "正在分析中...",
                    isGenerating = isGenerating.value,
                    fontSize = 14,
                )
            }
        }
        assertExpansionState(R.string.common_state_expanded)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { isGenerating.value = false }
        rule.mainClock.advanceTimeByFrame()
        assertExpansionState(R.string.common_state_expanded)

        rule.mainClock.advanceTimeBy(299L, ignoreFrameDuration = true)
        assertExpansionState(R.string.common_state_expanded)

        rule.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
        rule.mainClock.advanceTimeByFrame()
        assertExpansionState(R.string.common_state_collapsed)
    }

    @Test
    fun reopeningDuringCompletionDelayCancelsPendingCollapse() {
        val isGenerating = mutableStateOf(true)
        rule.setContent {
            NexaraTheme {
                ThinkingTrace(
                    reasoning = "正在分析中...",
                    isGenerating = isGenerating.value,
                    fontSize = 14,
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { isGenerating.value = false }
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        rule.mainClock.advanceTimeByFrame()
        assertExpansionState(R.string.common_state_collapsed)

        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        rule.mainClock.advanceTimeByFrame()
        assertExpansionState(R.string.common_state_expanded)

        rule.mainClock.advanceTimeBy(300L, ignoreFrameDuration = true)
        rule.mainClock.advanceTimeByFrame()
        assertExpansionState(R.string.common_state_expanded)
    }

    private fun assertExpansionState(stringRes: Int) {
        val expected = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.getString(stringRes)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                expected,
            ),
        )
    }
}
