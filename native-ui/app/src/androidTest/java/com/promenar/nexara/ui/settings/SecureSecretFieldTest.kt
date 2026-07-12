package com.promenar.nexara.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SecretFieldTestActivity : ComponentActivity() {
    companion object {
        val hasStored = mutableStateOf(true)
        val showField = mutableStateOf(true)
        val edit = mutableStateOf("")
        var reveal: suspend () -> CharArray? = { "default-secret".toCharArray() }
        var cleared: () -> Unit = {}

        fun reset() {
            hasStored.value = true
            showField.value = true
            edit.value = ""
            reveal = { "default-secret".toCharArray() }
            cleared = {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexaraTheme {
                val focus = LocalFocusManager.current
                Column {
                    if (showField.value) {
                        SecretField(
                            value = edit.value,
                            onValueChange = { edit.value = it },
                            hasStoredSecret = hasStored.value,
                            onRevealRequest = reveal,
                            onClear = cleared,
                        )
                    }
                    Button(onClick = { focus.clearFocus() }) { Text("outside") }
                }
            }
        }
    }
}

class SecureSecretFieldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<SecretFieldTestActivity>()

    @Before fun reset() = SecretFieldTestActivity.reset()

    @Test
    fun storedSecret_isFixedMask_revealsTemporarily_andRealRecreateHidesAgain() {
        val show = composeRule.activity.getString(R.string.secret_field_show)
        val secret = "full-secret-not-length-mask"
        SecretFieldTestActivity.reveal = { secret.toCharArray() }

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
        SecretFieldTestActivity.reveal = { gate.await() }
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
    fun hasStoredChangeAndDispose_cancelLateRevealAndWipeResult() {
        val firstGate = CompletableDeferred<CharArray?>()
        val first = "changed-secret".toCharArray()
        SecretFieldTestActivity.reveal = { firstGate.await() }
        val show = composeRule.activity.getString(R.string.secret_field_show)

        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.runOnIdle { SecretFieldTestActivity.hasStored.value = false }
        firstGate.complete(first)
        composeRule.waitForIdle()
        composeRule.runOnIdle { check(first.contentEquals(CharArray(first.size))) }

        val secondGate = CompletableDeferred<CharArray?>()
        val second = "disposed-secret".toCharArray()
        composeRule.runOnIdle {
            SecretFieldTestActivity.hasStored.value = true
            SecretFieldTestActivity.reveal = { secondGate.await() }
        }
        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.runOnIdle { SecretFieldTestActivity.showField.value = false }
        secondGate.complete(second)
        composeRule.waitForIdle()
        composeRule.runOnIdle { check(second.contentEquals(CharArray(second.size))) }
    }
}
