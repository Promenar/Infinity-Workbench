package com.promenar.infinity.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.state.*
import com.promenar.infinity.ui.chat.components.StateBoardContent
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test

class StateBoardPanelTest {
    @get:Rule val rule = createComposeRule()
    private val snapshot = StateBoardSnapshot(1, StateBoard("冒险", listOf(StateField("hp", "生命值", "number", JsonPrimitive(80)))))
    @Test fun detailsShowTitleAndFieldCard() {
        rule.setContent { InfinityTheme { StateBoardContent(snapshot, false, { _, _ -> null }, { null }) } }
        rule.onNodeWithText("冒险").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.stateField("hp")).assertIsDisplayed().assert(hasText("80", substring = true))
    }
    @Test fun expandedEditorReturnsRevisionAndTypedValue() {
        var changed: StateOperation? = null
        var revision = 0L
        rule.setContent { InfinityTheme { StateBoardContent(snapshot, false, { id, value -> revision = id; changed = value; null }, { null }) } }
        rule.onNodeWithTag(UiTags.stateField("hp")).performClick()
        rule.onNodeWithTag(UiTags.CHAT_STATE_VALUE).performTextReplacement("65")
        rule.onNodeWithTag(UiTags.CHAT_STATE_SAVE).performClick()
        rule.runOnIdle {
            assertThat(revision).isEqualTo(1)
            assertThat(changed).isEqualTo(StateOperation("hp", "set", JsonPrimitive(65)))
        }
    }
    @Test fun removeRequiresSecondConfirmation() {
        var removed = false
        rule.setContent { InfinityTheme { StateBoardContent(snapshot, false, { _, _ -> null }, { removed = true; null }) } }
        rule.onNodeWithTag(UiTags.CHAT_STATE_MENU).performClick()
        rule.onNodeWithTag(UiTags.CHAT_STATE_REMOVE).performClick()
        rule.runOnIdle { assertThat(removed).isFalse() }
        rule.onNodeWithTag(UiTags.CHAT_STATE_REMOVE_CONFIRM).performClick()
        rule.runOnIdle { assertThat(removed).isTrue() }
    }
    @Test fun timelineDisplaysStateChangeSummary() {
        val steps = listOf(
            ExecutionStep("call", "tool_call", toolName = "state_update"),
            ExecutionStep("result", "tool_result", toolName = "state_update", content = """{"changes":[{"key":"hp","label":"生命值","before":80,"after":65}]}"""),
        )
        rule.setContent { InfinityTheme { InlineToolRow(steps, false, 14) } }
        rule.onNodeWithTag(UiTags.CHAT_STATE_SUMMARY).assertIsDisplayed()
        rule.onNodeWithText("生命值 80→65", substring = true).assertIsDisplayed()
    }
}
