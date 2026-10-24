package com.promenar.nexara.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ExecutionModeSelectorSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesRadioSelectionAndMinimumTouchTarget() {
        composeRule.setContent {
            NexaraTheme {
                ExecutionModeSelector(selected = ExecutionMode.WORKSPACE, onSelect = {})
            }
        }

        composeRule.onNodeWithText("Workspace")
            .assertIsSelectable()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Full access")
            .assertIsSelectable()
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun remainsSelectableAtTwoTimesFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                NexaraTheme {
                    ExecutionModeSelector(selected = ExecutionMode.FULL_ACCESS, onSelect = {})
                }
            }
        }

        composeRule.onNodeWithText("Full access").assertIsSelected().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Workspace").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
    }
}
