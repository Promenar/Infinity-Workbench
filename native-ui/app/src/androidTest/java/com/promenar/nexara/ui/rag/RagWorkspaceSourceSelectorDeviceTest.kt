package com.promenar.nexara.ui.rag

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class RagWorkspaceSourceSelectorDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun sameTitleSourcesRemainSelectableByStableSessionTag() {
        val selected = AtomicReference<String>()
        val sources = listOf(
            RagWorkspaceSource("__nexara_rag_workspace__", "current-root", "同名知识库"),
            RagWorkspaceSource("legacy-session", "legacy-root", "同名知识库"),
        )

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagWorkspaceSourceSelector(
                    sources = sources,
                    selectedSessionId = sources.first().sessionId,
                    onSelect = selected::set,
                )
            }
        }

        rule.onNodeWithTag(RecoveryRagUiTags.SOURCE_SELECTOR).performClick()
        rule.onNodeWithTag(RecoveryRagUiTags.sourceOption("legacy-session"))
            .assertIsDisplayed()
            .performClick()

        assertThat(selected.get()).isEqualTo("legacy-session")
    }

    @Test
    fun selectorIsHiddenForOneAvailableSource() {
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagWorkspaceSourceSelector(
                    sources = listOf(
                        RagWorkspaceSource("__nexara_rag_workspace__", "current-root", "当前知识库"),
                    ),
                    selectedSessionId = "__nexara_rag_workspace__",
                    onSelect = {},
                )
            }
        }

        rule.onNodeWithTag(RecoveryRagUiTags.SOURCE_SELECTOR).assertDoesNotExist()
    }
}
