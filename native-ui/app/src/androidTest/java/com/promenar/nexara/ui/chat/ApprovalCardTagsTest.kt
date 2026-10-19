package com.promenar.nexara.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
        rule.onNodeWithText("write_file").assertIsDisplayed()
        rule.onNodeWithText("Approve").assertIsDisplayed()
        rule.onNodeWithText("Reject").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_DECLINE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE).performClick()
        rule.runOnIdle {
            assertThat(declined).isTrue()
            assertThat(approved).isTrue()
        }
    }

    @Test
    fun exactQueueLabelsCurrentAndRemainingAndDisablesActionsInFlight() {
        rule.setContent {
            NexaraTheme {
                ApprovalCard(
                    toolName = "write_file",
                    description = "test approval",
                    calls = listOf(
                        ApprovalDisplayCall("write_file", "{path:a}", "file write"),
                        ApprovalDisplayCall("delete_file", "{path:b}", "delete"),
                    ),
                    enabled = false,
                )
            }
        }

        rule.onNodeWithText("Current approval").assertDoesNotExist()
        rule.onNodeWithText("Up next: 1").assertIsDisplayed()
        rule.onNodeWithText("Approve current").assertIsDisplayed()
        rule.onNodeWithText("Reject current").assertIsDisplayed()
        rule.onNodeWithText("write_file").assertIsDisplayed()
        rule.onNodeWithText("delete_file").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_DECLINE).assertIsNotEnabled()
        rule.onNodeWithContentDescription("Approve current write_file, risk file write, 1 remaining")
            .assertIsDisplayed()
    }

    @Test
    fun mcpAndUnknownCallsKeepTheirExactVisibleIds() {
        rule.setContent {
            NexaraTheme {
                ApprovalCard(
                    toolName = "mcp__files__remove", description = "Review the request",
                    calls = listOf(
                        ApprovalDisplayCall("mcp__files__remove", "{}", "External write"),
                        ApprovalDisplayCall("custom_unknown_tool", "{}", "Unknown risk"),
                    ),
                )
            }
        }
        rule.onNodeWithText("mcp__files__remove").assertIsDisplayed()
        rule.onNodeWithText("custom_unknown_tool").assertIsDisplayed()
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
        rule.onNodeWithText("write_file").assertIsDisplayed()
        rule.onAllNodesWithTag(UiTags.CHAT_APPROVAL_APPROVE).assertCountEquals(0)
        rule.onAllNodesWithTag(UiTags.CHAT_APPROVAL_DECLINE).assertCountEquals(0)
    }
}
