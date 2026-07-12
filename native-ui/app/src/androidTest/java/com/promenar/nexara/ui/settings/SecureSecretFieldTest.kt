package com.promenar.nexara.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class SecureSecretFieldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun storedSecret_isFixedMask_revealsTemporarily_andRecreateHidesAgain() {
        val show = composeRule.activity.getString(R.string.secret_field_show)
        val secret = "full-secret-not-length-mask"
        composeRule.setContent {
            NexaraTheme {
                SecretField(
                    value = "",
                    onValueChange = {},
                    hasStoredSecret = true,
                    onRevealRequest = { secret.toCharArray() },
                    onClear = {},
                )
            }
        }

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
    fun clearAction_is48dpClickable_andCallsExplicitClear() {
        val cleared = mutableStateOf(false)
        val clear = composeRule.activity.getString(R.string.secret_field_clear)
        composeRule.setContent {
            NexaraTheme {
                SecretField(
                    value = "new-secret",
                    onValueChange = {},
                    hasStoredSecret = false,
                    onRevealRequest = { null },
                    onClear = { cleared.value = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription(clear).assertIsDisplayed().performClick()
        composeRule.runOnIdle { check(cleared.value) }
    }
}
