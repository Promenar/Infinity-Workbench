package com.promenar.nexara.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UnifiedPromptEditorE2ETest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun saveClosesEditorAndActionsMeetAccessibleContract() {
        var show by mutableStateOf(true)
        var saved: String? = null
        rule.setContent {
            NexaraTheme {
                UnifiedPromptEditor(
                    show = show,
                    onDismiss = { show = false },
                    onSave = {
                        saved = it
                        Result.success(Unit)
                    },
                    initialText = "before",
                )
            }
        }

        val save = rule.onNodeWithContentDescription(
            resources.getString(R.string.prompt_editor_cd_save),
        ).assertHasClickAction()
        val bounds = save.fetchSemanticsNode().boundsInRoot
        val minimumPx = 48f * resources.displayMetrics.density
        assertTrue(bounds.width >= minimumPx)
        assertTrue(bounds.height >= minimumPx)

        rule.onNodeWithText(resources.getString(R.string.prompt_editor_tab_edit))
            .assertIsSelected()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

        save.performClick()
        rule.runOnIdle { assertThat(saved).isEqualTo("before") }
        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_save))
            .assertDoesNotExist()
    }

    @Test
    fun dirtyCloseRequiresExplicitConfirmation() {
        var show by mutableStateOf(true)
        rule.setContent {
            NexaraTheme {
                UnifiedPromptEditor(
                    show = show,
                    onDismiss = { show = false },
                    onSave = { Result.success(Unit) },
                    initialText = "before",
                )
            }
        }

        rule.onNodeWithText("before").performTextReplacement("after")
        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_close))
            .performClick()

        rule.onNodeWithText(resources.getString(R.string.shared_action_cannot_undo))
            .assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.shared_btn_close)).performClick()
        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_save))
            .assertDoesNotExist()
    }

    @Test
    fun longContentAtTwoTimesFontScaleKeepsSaveReachable() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                NexaraTheme {
                    UnifiedPromptEditor(
                        show = true,
                        onDismiss = {},
                        onSave = { Result.success(Unit) },
                        initialText = (1..200).joinToString("\n") { "第 $it 行 long prompt content" },
                    )
                }
            }
        }

        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_save))
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun slowSavePreventsDuplicateSubmissionAndClosesOnlyAfterSuccess() {
        var show by mutableStateOf(true)
        var calls = 0
        var dismissals = 0
        val persistence = CompletableDeferred<Result<Unit>>()
        rule.setContent {
            NexaraTheme {
                UnifiedPromptEditor(
                    show = show,
                    onDismiss = {
                        dismissals++
                        show = false
                    },
                    onSave = {
                        calls++
                        persistence.await()
                    },
                    initialText = "slow",
                )
            }
        }

        val saveDescription = resources.getString(R.string.prompt_editor_cd_save)
        rule.onNodeWithContentDescription(saveDescription).performClick()
        rule.onNodeWithContentDescription(saveDescription).performClick()
        rule.runOnIdle {
            assertThat(calls).isEqualTo(1)
            assertThat(dismissals).isEqualTo(0)
        }

        persistence.complete(Result.success(Unit))
        rule.waitForIdle()
        rule.runOnIdle {
            assertThat(calls).isEqualTo(1)
            assertThat(dismissals).isEqualTo(1)
        }
    }

    @Test
    fun failedSaveKeepsEditorAndEditedTextVisible() {
        var show by mutableStateOf(true)
        rule.setContent {
            NexaraTheme {
                UnifiedPromptEditor(
                    show = show,
                    onDismiss = { show = false },
                    onSave = { Result.failure(IllegalStateException("repository failed")) },
                    initialText = "before",
                )
            }
        }

        rule.onNodeWithText("before").performTextReplacement("after")
        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_save))
            .performClick()
        rule.waitForIdle()

        rule.onNodeWithText("after").assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.shared_error_generic)).assertIsDisplayed()
        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_save))
            .assertIsDisplayed()
    }

    @Test
    fun closeDuringSaveCancelsPersistenceAndKeepsEditorOpen() {
        var show by mutableStateOf(true)
        var cancelled = false
        rule.setContent {
            NexaraTheme {
                UnifiedPromptEditor(
                    show = show,
                    onDismiss = { show = false },
                    onSave = {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                    initialText = "draft",
                )
            }
        }

        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_save))
            .performClick()
        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_close))
            .performClick()
        rule.waitForIdle()

        rule.runOnIdle { assertThat(cancelled).isTrue() }
        rule.onNodeWithText("draft").assertIsDisplayed()
        rule.onNodeWithContentDescription(resources.getString(R.string.prompt_editor_cd_save))
            .assertIsDisplayed()
    }
}
