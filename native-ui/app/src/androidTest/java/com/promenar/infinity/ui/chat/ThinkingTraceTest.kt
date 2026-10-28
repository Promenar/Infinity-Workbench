package com.promenar.infinity.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
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
    fun expandedThinkingRendersMarkdownListsAndCode() {
        rule.setContent {
            InfinityTheme {
                ThinkingTrace(
                    reasoning = "**核对重点**\n\n- 检查输入\n\n```kotlin\nprintln(1)\n```",
                    isGenerating = false, fontSize = 15, initiallyExpanded = true,
                )
            }
        }
        rule.onNodeWithText("核对重点").assertIsDisplayed()
        rule.onNodeWithText("检查输入").assertIsDisplayed()
        rule.onNodeWithText("println(1)").assertIsDisplayed()
        rule.onNodeWithText("**核对重点**").assertDoesNotExist()
    }

    @Test
    fun expandedThinkingRendersFormulaWithoutRawMarkdownDelimiters() {
        rule.setContent {
            InfinityTheme {
                ThinkingTrace(
                    reasoning = "公式前说明。\n\n\$x+y=z\$\n\n公式后说明。",
                    isGenerating = false, fontSize = 15, initiallyExpanded = true,
                )
            }
        }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription("Math: x+y=z", useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        val formula = rule.onNodeWithContentDescription("Math: x+y=z", useUnmergedTree = true)
            .assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .fetchSemanticsNode()
        org.junit.Assert.assertTrue(formula.boundsInRoot.width > 0f && formula.boundsInRoot.height > 0f)
        rule.onNodeWithText("公式前说明。").assertIsDisplayed()
        rule.onNodeWithText("公式后说明。").assertIsDisplayed()
        rule.onAllNodes(hasText("\$", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun completedThinkingTraceCanExpandAndCollapse() {
        rule.setContent {
            InfinityTheme {
                ThinkingTrace(
                    reasoning = "先检查输入，再组织答案。",
                    isGenerating = false,
                    fontSize = 14,
                )
            }
        }

        // 验证 toggle 可见，且具有点击动作和步骤行高度
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(CompactStepRowHeight)
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
    fun generatingThinkingTraceDefaultsToCollapsed() {
        rule.setContent {
            InfinityTheme {
                ThinkingTrace(reasoning = "正在分析中...", isGenerating = true, fontSize = 14)
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE)
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(CompactStepRowHeight)
        assertExpansionState(R.string.common_state_collapsed)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsNotDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()
    }

    @Test
    fun completionKeepsDefaultCollapsed() {
        val generating = mutableStateOf(true)
        rule.setContent {
            InfinityTheme {
                ThinkingTrace(reasoning = "正在分析中...", isGenerating = generating.value, fontSize = 14)
            }
        }
        assertExpansionState(R.string.common_state_collapsed)
        rule.runOnIdle { generating.value = false }
        rule.waitForIdle()
        assertExpansionState(R.string.common_state_collapsed)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsNotDisplayed()
    }

    @Test
    fun explicitExpansionSurvivesCompletion() {
        val generating = mutableStateOf(true)
        rule.setContent {
            InfinityTheme {
                ThinkingTrace(reasoning = "正在分析中...", isGenerating = generating.value, fontSize = 14)
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()
        rule.runOnIdle { generating.value = false }
        rule.waitForIdle()
        assertExpansionState(R.string.common_state_expanded)
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsNotDisplayed()
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
