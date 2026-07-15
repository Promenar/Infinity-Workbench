package com.promenar.nexara.ui.settings

import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.promenar.nexara.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SecureSecretFieldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<SecretFieldTestActivity>()

    @Before fun reset() = composeRule.runOnIdle { composeRule.activity.resetHarness() }

    @Test
    fun storedSecret_isFixedMask_revealsTemporarily_andRealRecreateHidesAgain() {
        val show = composeRule.activity.getString(R.string.secret_field_show)
        val secret = "full-secret-not-length-mask"
        composeRule.activity.revealProvider = { secret.toCharArray() }

        composeRule.onNodeWithText("****").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(secret).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("****").assertIsDisplayed()
        composeRule.onAllNodesWithText(secret).assertCountEquals(0)
    }

    @Test
    fun revealAndClearExposeOneAccessibleTouchTargetEach() {
        val show = composeRule.activity.getString(R.string.secret_field_show)
        val clear = composeRule.activity.getString(R.string.secret_field_clear)

        composeRule.onAllNodesWithContentDescription(show).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(show)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithContentDescription(clear).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(clear)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun stableLabelOverridesExamplePlaceholderAsAccessibleName() {
        val label = "Stable credential label"
        val placeholder = composeRule.activity.getString(R.string.secret_field_placeholder)
        composeRule.runOnIdle { composeRule.activity.label.value = label }

        composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(placeholder).assertCountEquals(0)
    }

    @Test
    fun storedMaskIsReadOnlyUntilExplicitClearStartsANewBuffer() {
        val placeholder = composeRule.activity.getString(R.string.secret_field_placeholder)
        val clear = composeRule.activity.getString(R.string.secret_field_clear)

        composeRule.onNodeWithContentDescription(placeholder).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false),
        )
        composeRule.runOnIdle { check(composeRule.activity.edit.value.isEmpty()) }
        composeRule.onNodeWithText("****").assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.clearAction = { composeRule.activity.hasStored.value = false }
        }
        composeRule.onNodeWithContentDescription(clear).performClick()
        composeRule.onNodeWithContentDescription(placeholder).performTextReplacement("new*fake")
        composeRule.runOnIdle { check(composeRule.activity.edit.value == "new*fake") }
    }

    @Test
    fun revealCompletedAfterFocusLoss_isRejectedAndWiped() {
        val gate = CompletableDeferred<CharArray?>()
        val returned = "late-secret".toCharArray()
        composeRule.activity.revealProvider = { gate.await() }
        val show = composeRule.activity.getString(R.string.secret_field_show)
        val placeholder = composeRule.activity.getString(R.string.secret_field_placeholder)

        composeRule.onNodeWithContentDescription(placeholder).performClick()
        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.onNodeWithText("outside").performClick()
        gate.complete(returned)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("late-secret").assertCountEquals(0)
        composeRule.runOnIdle { check(returned.contentEquals(CharArray(returned.size))) }
    }

    @Test
    fun revealClickedAfterAnEarlierFocusLoss_isAccepted() {
        val placeholder = composeRule.activity.getString(R.string.secret_field_placeholder)
        val show = composeRule.activity.getString(R.string.secret_field_show)
        composeRule.activity.revealProvider = { "allowed-after-loss".toCharArray() }

        composeRule.onNodeWithContentDescription(placeholder).performClick()
        composeRule.onNodeWithText("outside").performClick()
        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("allowed-after-loss").assertIsDisplayed()
    }

    @Test
    fun directEye_pendingRevealThenOutside_rejectsAndWipesResult() {
        val gate = CompletableDeferred<CharArray?>()
        val returned = "direct-late-secret".toCharArray()
        composeRule.activity.revealProvider = { gate.await() }
        val show = composeRule.activity.getString(R.string.secret_field_show)

        composeRule.onNodeWithText("outside").performClick()
        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.onNodeWithText("outside").performClick()
        gate.complete(returned)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("direct-late-secret").assertCountEquals(0)
        composeRule.runOnIdle { check(returned.contentEquals(CharArray(returned.size))) }
    }

    @Test
    fun directEye_visibleRevealThenOutside_returnsToFixedMask() {
        val secret = "direct-visible-secret"
        composeRule.activity.revealProvider = { secret.toCharArray() }
        val show = composeRule.activity.getString(R.string.secret_field_show)

        composeRule.onNodeWithText("outside").performClick()
        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(secret).assertIsDisplayed()

        composeRule.onNodeWithText("outside").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("****").assertIsDisplayed()
        composeRule.onAllNodesWithText(secret).assertCountEquals(0)
    }

    @Test
    fun visibleReveal_expiresAndLeavesOnlyFixedMaskInSemantics() {
        val secret = "short-lived-fake-secret"
        composeRule.runOnIdle {
            composeRule.activity.revealTimeoutMillis.value = 1_000L
            composeRule.activity.revealProvider = { secret.toCharArray() }
        }
        val show = composeRule.activity.getString(R.string.secret_field_show)

        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.onNodeWithText(secret).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithText(secret).fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithText("****").assertIsDisplayed()
        composeRule.onAllNodesWithText(secret).assertCountEquals(0)
    }

    @Test
    fun backgroundingActivityHidesRevealAndWipesTransientArray() {
        val returned = "background-fake-secret".toCharArray()
        composeRule.activity.revealProvider = { returned }
        val show = composeRule.activity.getString(R.string.secret_field_show)

        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.onNodeWithText("background-fake-secret").assertIsDisplayed()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("****").assertIsDisplayed()
        composeRule.onAllNodesWithText("background-fake-secret").assertCountEquals(0)
        composeRule.runOnIdle { check(returned.contentEquals(CharArray(returned.size))) }
    }

    @Test
    fun directEye_doesNotOpenIme_andOutsideStillHidesReveal() {
        val secret = "direct-no-ime-secret"
        composeRule.activity.revealProvider = { secret.toCharArray() }
        val show = composeRule.activity.getString(R.string.secret_field_show)

        composeRule.onNodeWithText("outside").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { check(!composeRule.activity.isImeVisible()) }

        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.waitForIdle()
        SystemClock.sleep(750)
        composeRule.onNodeWithText(secret).assertIsDisplayed()
        composeRule.runOnIdle { check(!composeRule.activity.isImeVisible()) }

        composeRule.onNodeWithText("outside").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("****").assertIsDisplayed()
        composeRule.onAllNodesWithText(secret).assertCountEquals(0)
    }

    @Test
    fun hasStoredChangeAndDispose_cancelLateRevealAndWipeResult() {
        val firstGate = CompletableDeferred<CharArray?>()
        val first = "changed-secret".toCharArray()
        composeRule.activity.revealProvider = { firstGate.await() }
        val show = composeRule.activity.getString(R.string.secret_field_show)

        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.runOnIdle { composeRule.activity.hasStored.value = false }
        firstGate.complete(first)
        composeRule.waitForIdle()
        composeRule.runOnIdle { check(first.contentEquals(CharArray(first.size))) }

        val secondGate = CompletableDeferred<CharArray?>()
        val second = "disposed-secret".toCharArray()
        composeRule.runOnIdle {
            composeRule.activity.hasStored.value = true
            composeRule.activity.revealProvider = { secondGate.await() }
        }
        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.runOnIdle { composeRule.activity.showField.value = false }
        secondGate.complete(second)
        composeRule.waitForIdle()
        composeRule.runOnIdle { check(second.contentEquals(CharArray(second.size))) }
    }
}
