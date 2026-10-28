package com.promenar.infinity.ui.common

import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import org.junit.Rule
import org.junit.Test

/** 全屏对话框自己的窗口不继承主窗口的系统栏图标设置，按顶栏底色明暗显式设置。 */
class InfinityFullscreenDialogTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun showAndCapture(dark: Boolean): Pair<Window, View> {
        var captured: Pair<Window, View>? = null
        rule.setContent {
            InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT)) {
                InfinityFullscreenDialog(onDismissRequest = {}) {
                    val view = LocalView.current
                    SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { captured = it to view } }
                    Box(Modifier.fillMaxSize().testTag("dialog_body"))
                }
            }
        }
        rule.onNodeWithTag("dialog_body").assertIsDisplayed()
        rule.waitForIdle()
        return checkNotNull(captured)
    }

    @Test fun lightThemeUsesDarkStatusBarIcons_andDoesNotDimBehind() {
        val (window, view) = showAndCapture(dark = false)
        rule.runOnIdle {
            val controller = WindowCompat.getInsetsController(window, view)
            assertThat(controller.isAppearanceLightStatusBars).isTrue()
            assertThat(controller.isAppearanceLightNavigationBars).isTrue()
            assertThat(window.attributes.dimAmount).isEqualTo(0f)
        }
    }

    @Test fun darkThemeUsesLightStatusBarIcons() {
        val (window, view) = showAndCapture(dark = true)
        rule.runOnIdle {
            val controller = WindowCompat.getInsetsController(window, view)
            assertThat(controller.isAppearanceLightStatusBars).isFalse()
            assertThat(controller.isAppearanceLightNavigationBars).isFalse()
        }
    }
}
