package com.promenar.nexara.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 目录页头：子目录内一个返回按钮回上级，路径可点跳级，根目录按需回到外层。 */
class FolderNavigationHeaderTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backToParent = context.getString(R.string.rag_back_to_parent)
    private val back = context.getString(R.string.common_cd_back)

    @Test
    fun singleBackButtonWalksUpAndTrailJumpsToAncestor() {
        var stack by mutableStateOf(listOf(FolderCrumb("/a", "alpha"), FolderCrumb("/a/b", "beta")))
        var exited = false
        rule.setContent {
            NexaraTheme {
                FolderBreadcrumbsBar(
                    folderStack = stack,
                    rootTitle = "root",
                    onNavigateBack = { stack = stack.dropLast(1) },
                    onNavigateToCrumb = { index -> stack = stack.navigateToCrumb(index) },
                    onExitRoot = { exited = true },
                )
            }
        }
        rule.onNodeWithText("beta").assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(backToParent)).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription(back)).assertCountEquals(0)

        rule.onNodeWithContentDescription(backToParent).performClick()
        rule.waitForIdle()
        assertThat(stack.map { it.name }).containsExactly("alpha")

        stack = listOf(FolderCrumb("/a", "alpha"), FolderCrumb("/a/b", "beta"))
        rule.waitForIdle()
        rule.onNodeWithText("root").performClick()
        rule.waitForIdle()
        assertThat(stack).isEmpty()

        // 根目录的返回按钮回到外层列表。
        rule.onNodeWithContentDescription(back).performClick()
        assertThat(exited).isTrue()
    }

    @Test
    fun rootWithoutOuterLevelHasNoBackButton() {
        rule.setContent {
            NexaraTheme {
                FolderBreadcrumbsBar(folderStack = emptyList(), rootTitle = "root", onNavigateBack = {}, onNavigateToCrumb = {})
            }
        }
        rule.onNodeWithText("root").assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(back)).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription(backToParent)).assertCountEquals(0)
    }
}
