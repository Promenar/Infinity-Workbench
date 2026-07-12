package com.promenar.nexara.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class BackupSettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun includeKeysPasswordDialog_disablesConfirmUntilNonEmptyPasswordsMatch() {
        val confirm = composeRule.activity.getString(R.string.common_btn_confirm)
        val passwordHint = composeRule.activity.getString(R.string.backup_password_hint)
        val confirmationHint = composeRule.activity.getString(R.string.backup_password_confirm_hint)
        composeRule.setContent {
            NexaraTheme {
                var password by remember { mutableStateOf("") }
                var confirmation by remember { mutableStateOf("") }
                BackupPasswordDialog(
                    title = "test",
                    password = password,
                    passwordConfirmation = confirmation,
                    requireConfirmation = true,
                    onPasswordChange = { password = it },
                    onConfirmationChange = { confirmation = it },
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText(confirm).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(passwordHint).performTextInput("safe-password")
        composeRule.onNodeWithContentDescription(confirmationHint).performTextInput("different")
        composeRule.onNodeWithText(confirm).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(confirmationHint).performTextClearance()
        composeRule.onNodeWithContentDescription(confirmationHint).performTextInput("safe-password")
        composeRule.onNodeWithText(confirm).assertIsEnabled()
    }

    @Test
    fun restoreDialog_allowsEmptyPasswordForPackagesWithoutKeys() {
        val confirm = composeRule.activity.getString(R.string.common_btn_confirm)
        composeRule.setContent {
            NexaraTheme {
                BackupPasswordDialog(
                    title = "test",
                    password = "",
                    passwordConfirmation = "",
                    requireConfirmation = false,
                    onPasswordChange = {},
                    onConfirmationChange = {},
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText(confirm).assertIsEnabled()
    }
}
