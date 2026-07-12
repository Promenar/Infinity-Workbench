package com.promenar.nexara.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactWidthUsesBottomBarAndExpandedWidthUsesRail() {
        assertFalse(shouldUseNavigationRail(599.dp))
        assertTrue(shouldUseNavigationRail(600.dp))

        var expanded by mutableStateOf(false)
        composeRule.setContent {
            AdaptiveNavigationSurface(
                expanded = expanded,
                selectedTab = AppTab.CHAT,
                onTabSelected = {},
            ) { Text("content") }
        }
        composeRule.onAllNodesWithTag("main_bottom_navigation").assertCountEquals(1)
        composeRule.onAllNodesWithTag("main_navigation_rail").assertCountEquals(0)

        composeRule.runOnIdle { expanded = true }
        composeRule.onAllNodesWithTag("main_navigation_rail").assertCountEquals(1)
        composeRule.onAllNodesWithTag("main_bottom_navigation").assertCountEquals(0)
    }

    @Test
    fun bodyWidthNeverExceeds960Dp() {
        assertEquals(960.dp, constrainedBodyWidth(1_280.dp))
        assertEquals(720.dp, constrainedBodyWidth(720.dp))
    }
}
