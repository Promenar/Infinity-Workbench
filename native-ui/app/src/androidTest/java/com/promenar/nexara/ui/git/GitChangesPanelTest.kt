package com.promenar.nexara.ui.git

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.git.GitBranch
import com.promenar.nexara.data.git.GitBranchList
import com.promenar.nexara.data.git.GitChangeKind
import com.promenar.nexara.data.git.GitChangeList
import com.promenar.nexara.data.git.GitFileChange
import com.promenar.nexara.data.git.GitStatus
import com.promenar.nexara.data.git.GitTracking
import com.promenar.nexara.ui.chat.ChatTopBar
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class GitChangesPanelTest {
    @get:Rule val rule = createComposeRule()

    private fun status(changes: Int, tracking: GitTracking) = GitStatus(
        branch = "main", detached = false, head = "2222222222", unborn = false,
        staged = GitChangeList(emptyList()),
        unstaged = GitChangeList(List(changes) { GitFileChange("src/F$it.kt", GitChangeKind.MODIFIED, addedLines = 3, removedLines = 1) }),
        untracked = GitChangeList(emptyList()),
        tracking = tracking,
    )

    @Test fun footer_handsPullAndCommitToAgentWithUpstream() {
        val requests = mutableListOf<Pair<GitAgentRequest, String?>>()
        val state = GitPanelUiState(status = GitLoad.Ready(status(2, GitTracking("origin/main", 2, 1, false, false))))
        rule.setContent {
            NexaraTheme {
                GitChangesPanel(state, GitPanelActions(onAskAgent = { r, u -> requests += r to u }), Modifier.fillMaxSize())
            }
        }
        rule.onNodeWithTag(GitPanelTags.TRACKING).assertIsDisplayed()
        rule.onNodeWithTag(GitPanelTags.AGENT_PULL).assertIsEnabled().performClick()
        rule.onNodeWithTag(GitPanelTags.AGENT_COMMIT_PUSH).assertIsEnabled().performClick()
        assertThat(requests).containsExactly(
            GitAgentRequest.PULL_MERGE to "origin/main",
            GitAgentRequest.COMMIT_PUSH to "origin/main",
        ).inOrder()
    }

    @Test fun footer_disabledWhileAgentBusyOrNothingToDo() {
        var busy by mutableStateOf(true)
        val clean = GitPanelUiState(status = GitLoad.Ready(status(0, GitTracking())))
        rule.setContent {
            NexaraTheme {
                GitChangesPanel(clean, GitPanelActions(onAskAgent = { _, _ -> }), Modifier.fillMaxSize(), agentBusy = busy)
            }
        }
        rule.onNodeWithTag(GitPanelTags.AGENT_PULL).assertIsNotEnabled()
        rule.onNodeWithTag(GitPanelTags.AGENT_COMMIT_PUSH).assertIsNotEnabled()
        busy = false
        // 没有上游、没有改动也没有未推送提交时仍不可用。
        rule.onNodeWithTag(GitPanelTags.AGENT_PULL).assertIsNotEnabled()
        rule.onNodeWithTag(GitPanelTags.AGENT_COMMIT_PUSH).assertIsNotEnabled()
    }

    @Test fun footer_hiddenWithoutAgentHandoff() {
        val state = GitPanelUiState(status = GitLoad.Ready(status(1, GitTracking("origin/main", 0, 0, false, false))))
        rule.setContent { NexaraTheme { GitChangesPanel(state, GitPanelActions(), Modifier.fillMaxSize()) } }
        rule.onNodeWithTag(GitPanelTags.AGENT_PULL).assertDoesNotExist()
    }

    @Test fun branchesTab_listsLocalBranches() {
        val state = GitPanelUiState(
            tab = GitPanelTab.BRANCHES,
            status = GitLoad.Ready(status(0, GitTracking())),
            branches = GitLoad.Ready(GitBranchList(listOf(GitBranch("main", current = true, shortSha = "2222222", title = "提交"), GitBranch("topic")))),
        )
        rule.setContent { NexaraTheme { GitChangesPanel(state, GitPanelActions(), Modifier.fillMaxSize()) } }
        rule.onNodeWithTag(GitPanelTags.BRANCHES_LIST).assertIsDisplayed()
        rule.onNodeWithTag(GitPanelTags.branch("main")).assertIsDisplayed()
        rule.onNodeWithTag(GitPanelTags.branch("topic")).assertIsDisplayed()
    }

    @Test fun topBarGitLine_opensGitPanel() {
        var opened = false
        rule.setContent {
            NexaraTheme {
                ChatTopBar(
                    title = "会话", onBack = {}, onWorkspace = {}, onSettings = {},
                    onClearHistory = {}, onRename = {}, onDeleteSession = {},
                    git = GitEntrySummary.Repository("main", false, "2222222", false, 3, false, ahead = 2),
                    onOpenGit = { opened = true },
                )
            }
        }
        rule.onNodeWithText("main", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_GIT_LINE).performClick()
        assertThat(opened).isTrue()
    }
}
