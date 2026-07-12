package com.promenar.nexara.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
