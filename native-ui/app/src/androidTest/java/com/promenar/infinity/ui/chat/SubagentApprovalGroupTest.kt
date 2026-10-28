package com.promenar.infinity.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.subagent.SubagentApproval
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

/** 会话流内的子代理审批：同一子代理的多项请求归在一张分组卡下，各自允许或拒绝，提交后按钮失效。 */
class SubagentApprovalGroupTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun approval(id: String, tool: String) = SubagentApproval(
        id = id,
        sessionId = "s",
        subagentIndex = 0,
        subagentPreset = null,
        subagentDescription = "检查 shell 与 python 是否可用",
        toolName = tool,
        risk = ToolRisk.PROCESS,
        target = null,
        preview = "echo SUBSHELL-OK; uname -m",
        requestedAt = 0L,
    )

    @Test
    fun eachRequestResolvesOnceWithItsOwnId() {
        val resolved = mutableListOf<Pair<String, Boolean>>()
        rule.setContent {
            InfinityTheme {
                SubagentApprovalGroup(
                    requests = listOf(approval("a", "shell_exec"), approval("b", "python_exec")),
                    onResolve = { id, ok -> resolved += id to ok },
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_SUBAGENT_APPROVAL).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.subagent_approval_waiting)).assertIsDisplayed()
        rule.onNodeWithText("检查 shell 与 python 是否可用").assertIsDisplayed()

        rule.onNode(hasTestTag(UiTags.CHAT_APPROVAL_APPROVE) and hasAnyAncestor(hasTestTag(UiTags.subagentApproval("a")))).performClick()
        rule.onNode(hasTestTag(UiTags.CHAT_APPROVAL_DECLINE) and hasAnyAncestor(hasTestTag(UiTags.subagentApproval("b")))).performClick()
        rule.runOnIdle { assertThat(resolved).containsExactly("a" to true, "b" to false).inOrder() }

        // 提交后等待审批代理移除该项，期间按钮不可再点。
        rule.onNode(hasTestTag(UiTags.CHAT_APPROVAL_DECLINE) and hasAnyAncestor(hasTestTag(UiTags.subagentApproval("a")))).assertIsNotEnabled()
        rule.onAllNodesWithTag(UiTags.CHAT_APPROVAL_KIND_COMMAND).assertCountEquals(2)
    }
}
