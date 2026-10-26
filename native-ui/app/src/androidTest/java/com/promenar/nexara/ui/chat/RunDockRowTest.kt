package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.ui.chat.components.RunDetailContent
import com.promenar.nexara.ui.chat.components.RunDetailTab
import com.promenar.nexara.ui.chat.components.RunDockRow
import com.promenar.nexara.ui.chat.components.runDockState
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 运行坞：一句话读出当前步骤与进度，入口芯片各自打开对应页；待批准时只留跳转审批的芯片。 */
class RunDockRowTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val plan = requireNotNull(
        taskPanelUiState(
            listOf(
                TaskStep(id = "a", title = "列出工作区", status = "done", sortOrder = 0),
                TaskStep(id = "b", title = "查找 Markdown 文件", status = "doing", sortOrder = 1),
            ),
            isGenerating = true,
        ),
    )

    @Test
    fun readsCurrentStepAndOpensMatchingTab() {
        val opened = mutableListOf<RunDetailTab?>()
        rule.setContent {
            NexaraTheme {
                RunDockRow(
                    state = requireNotNull(runDockState(plan, true, 0, 1, 0, "进度", 3 * 60_000L)),
                    onOpen = { opened += it },
                    onJumpToApproval = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_MAIN)
            .assertContentDescriptionContains("查找 Markdown 文件", substring = true)
            .assertContentDescriptionContains("1/2", substring = true)
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_JOBS).performClick()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_STATE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_APPROVAL).assertDoesNotExist()
        rule.runOnIdle { assertThat(opened).containsExactly(null, RunDetailTab.BACKGROUND, RunDetailTab.STATE).inOrder() }
    }

    @Test
    fun pendingApprovalsReplaceOtherChipsAndJump() {
        var jumps = 0
        rule.setContent {
            NexaraTheme {
                RunDockRow(
                    state = requireNotNull(runDockState(plan, true, 2, 1, 0, "进度", null)),
                    onOpen = {},
                    onJumpToApproval = { jumps++ },
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_JOBS).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_STATE).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_APPROVAL)
            .assertContentDescriptionContains(context.getString(R.string.run_dock_jump_approval_cd, 2))
            .performClick()
        rule.runOnIdle { assertThat(jumps).isEqualTo(1) }
    }

    @Test
    fun touchLockButtonReportsTapAndHidesWhenNull() {
        var onLock by mutableStateOf<(() -> Unit)?>(null)
        var locks = 0
        onLock = { locks++ }
        rule.setContent {
            NexaraTheme {
                RunDockRow(
                    state = requireNotNull(runDockState(null, true, 0, 0, 0, null, null)),
                    onOpen = {},
                    onJumpToApproval = {},
                    onLockTouch = onLock,
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_TOUCH_LOCK_BUTTON).performClick()
        rule.runOnIdle {
            assertThat(locks).isEqualTo(1)
            onLock = null
        }
        rule.onNodeWithTag(UiTags.CHAT_TOUCH_LOCK_BUTTON).assertDoesNotExist()
    }

    @Test
    fun planFooterKeepsActionsWithoutRunStats() {
        rule.setContent {
            NexaraTheme {
                Column {
                    RunDetailContent(
                        initialTab = RunDetailTab.PLAN,
                        plan = plan,
                        isGenerating = true,
                        turnStartedAt = null,
                        activity = null,
                        stateBoard = null,
                        onDismiss = {},
                        onContinue = {},
                        onComplete = {},
                        onHidePlan = {},
                        onClearPlan = {},
                        onEditState = { _, _ -> null },
                        onRemoveState = { null },
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                        nowMillis = 0L,
                    )
                }
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_TASK_CONTINUE).assertExists()
    }
}
