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
                ExecutionModeSelector(selected = ExecutionMode.SEMI, onSelect = {})
            }
        }

        composeRule.onNodeWithText("Auto")
            .assertIsSelectable()
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Semi")
            .assertIsSelectable()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Manual")
            .assertIsSelectable()
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun remainsSelectableAtTwoTimesFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                NexaraTheme {
                    ExecutionModeSelector(selected = ExecutionMode.AUTO, onSelect = {})
                }
            }
        }

        composeRule.onNodeWithText("Auto").assertIsSelected().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Semi").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Manual").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
    }
}
