package com.promenar.nexara.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.promenar.nexara.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupRealScreenTest {
    @get:Rule val rule = createAndroidComposeRule<BackupTestActivity>()

    @Before fun resetGate() {
        rule.activity.listGate = CompletableDeferred(Unit)
    }

    @Test
    fun realScreen_refreshesSelectsExactRemoteAndOpensRestorePassword() {
        val refresh = rule.activity.getString(R.string.backup_remote_refresh)
        val restore = rule.activity.getString(R.string.backup_restore_cloud)
        rule.onNodeWithText(refresh).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("remote-full-id.nexara").performScrollTo().performClick()
        rule.onNodeWithText(restore).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.backup_password_remote_restore_title)).assertIsDisplayed()
    }

    @Test
    fun realScreen_showsCancelOnlyWhileRemoteListIsActuallyCancellable() {
        val gate = CompletableDeferred<Unit>()
        rule.activity.listGate = gate
        rule.onNodeWithText(rule.activity.getString(R.string.backup_remote_refresh)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.common_btn_cancel)).performScrollTo().assertIsDisplayed()
        gate.complete(Unit)
        rule.waitForIdle()
    }

    @Test
    fun realScreen_webDavSheetKeepsSaveReachable() {
        rule.onNodeWithText(rule.activity.getString(R.string.backup_config_webdav)).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.backup_webdav_config_title)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.backup_save_config)).performScrollTo().assertIsDisplayed()
    }
}
