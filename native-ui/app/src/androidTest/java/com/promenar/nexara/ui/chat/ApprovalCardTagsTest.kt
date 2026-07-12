package com.promenar.nexara.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ApprovalCardTagsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun pendingApproval_exposesStableStateAndActionTags() {
        var approved = false
        var declined = false
        rule.setContent {
            NexaraTheme {
                ApprovalCard(
                    toolName = "write_file",
                    description = "test approval",
                    onApprove = { approved = true },
                    onDecline = { declined = true },
                )
            }
        }

        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_CARD).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_REQUIRED).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_DECLINE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE).performClick()
        rule.runOnIdle {
            assertThat(declined).isTrue()
            assertThat(approved).isTrue()
        }
    }

    @Test
    fun executedApproval_exposesExecutedStateWithoutActionTags() {
        rule.setContent {
            NexaraTheme {
                ApprovalCard(
                    toolName = "write_file",
                    description = "test approval",
                    isExecuted = true,
                )
            }
        }

        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_CARD).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_EXECUTED).assertIsDisplayed()
        rule.onAllNodesWithTag(UiTags.CHAT_APPROVAL_APPROVE).assertCountEquals(0)
        rule.onAllNodesWithTag(UiTags.CHAT_APPROVAL_DECLINE).assertCountEquals(0)
    }
}
