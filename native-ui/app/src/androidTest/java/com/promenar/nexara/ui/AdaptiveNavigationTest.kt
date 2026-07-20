package com.promenar.nexara.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class AdaptiveNavigationTest {
    private class TestMotionDurationScale : MotionDurationScale {
        var value = 1f
        override val scaleFactor: Float
            get() = value
    }

    private val motionDurationScale = TestMotionDurationScale()

    @get:Rule
    val composeRule = createComposeRule(effectContext = motionDurationScale)

    @Before
    fun resetMotionDurationScale() {
        motionDurationScale.value = 1f
    }

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
        composeRule.onNodeWithTag("main_bottom_navigation").assertHeightIsEqualTo(64.dp)
        composeRule.onAllNodesWithTag("main_navigation_selected_indicator").assertCountEquals(1)
        composeRule.onNodeWithTag("main_navigation_selected_indicator")
            .assertHeightIsEqualTo(48.dp)
            .assertWidthIsAtLeast(88.dp)
        composeRule.onAllNodesWithTag("main_navigation_rail").assertCountEquals(0)

        composeRule.runOnIdle { expanded = true }
        composeRule.onAllNodesWithTag("main_navigation_rail").assertCountEquals(1)
        composeRule.onAllNodesWithTag("main_bottom_navigation").assertCountEquals(0)
    }

    @Test
    fun compactNavigationExposesThreeTabsSelectionAndContentClearance() {
        var selectedTab by mutableStateOf(AppTab.CHAT)
        composeRule.setContent {
            AdaptiveNavigationSurface(
                expanded = false,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("navigation_test_content"),
                ) {
                    items((1..50).toList()) { index ->
                        Text(
                            text = "Navigation content item $index",
                            modifier = if (index == 50) {
                                Modifier.testTag("navigation_test_last_item")
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }

        AppTab.entries.forEach { tab ->
            composeRule.onNodeWithTag("main_navigation_tab_${tab.name.lowercase()}")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
        val initialIndicatorLeft = composeRule
            .onNodeWithTag("main_navigation_selected_indicator")
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        composeRule.onNodeWithTag("main_navigation_tab_chat").assertIsSelected()
        composeRule.onNodeWithTag("main_navigation_tab_settings").performClick()
        composeRule.onNodeWithTag("main_navigation_tab_settings").assertIsSelected()
        val settingsIndicatorLeft = composeRule
            .onNodeWithTag("main_navigation_selected_indicator")
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        assertTrue(settingsIndicatorLeft > initialIndicatorLeft)

        composeRule.onNodeWithTag("main_navigation_tab_library").performClick()
        composeRule.onNodeWithTag("main_navigation_tab_chat").performClick()
        composeRule.onNodeWithTag("main_navigation_tab_chat").assertIsSelected()
        composeRule.onAllNodesWithTag("main_navigation_selected_indicator").assertCountEquals(1)

        composeRule.onNodeWithTag("navigation_test_content")
            .performScrollToNode(hasTestTag("navigation_test_last_item"))
        val contentBottom = composeRule.onNodeWithTag("navigation_test_last_item")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val navigationTop = composeRule.onNodeWithTag("main_bottom_navigation")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue(contentBottom <= navigationTop)
    }

    @Test
    fun expandedNavigationKeepsThreeAccessibleRailTabs() {
        composeRule.setContent {
            AdaptiveNavigationSurface(
                expanded = true,
                selectedTab = AppTab.LIBRARY,
                onTabSelected = {},
            ) { Text("content") }
        }

        composeRule.onAllNodesWithTag("main_navigation_rail").assertCountEquals(1)
        AppTab.entries.forEach { tab ->
            composeRule.onNodeWithTag("main_navigation_tab_${tab.name.lowercase()}")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
        composeRule.onNodeWithTag("main_navigation_tab_library").assertIsSelected()
    }

    @Test
    fun fluidIndicatorStretchesAndSqueezesDuringTabTransition() {
        var selectedTab by mutableStateOf(AppTab.CHAT)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AdaptiveNavigationSurface(
                expanded = false,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            ) { Text("content") }
        }

        composeRule.mainClock.advanceTimeByFrame()
        val restingBounds = composeRule
            .onNodeWithTag("main_navigation_selected_indicator")
            .fetchSemanticsNode()
            .boundsInRoot

        composeRule.runOnIdle { selectedTab = AppTab.SETTINGS }
        composeRule.mainClock.advanceTimeBy(170L)
        val midpointBounds = composeRule
            .onNodeWithTag("main_navigation_selected_indicator")
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(midpointBounds.width > restingBounds.width)
        assertTrue(midpointBounds.height < restingBounds.height)

        composeRule.runOnIdle { selectedTab = AppTab.LIBRARY }
        composeRule.mainClock.advanceTimeByFrame()
        val redirectedBounds = composeRule
            .onNodeWithTag("main_navigation_selected_indicator")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(abs(redirectedBounds.width - midpointBounds.width) < restingBounds.width * 0.15f)
        assertTrue(abs(redirectedBounds.height - midpointBounds.height) < restingBounds.height * 0.15f)

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("main_navigation_tab_library").assertIsSelected()
        composeRule.onAllNodesWithTag("main_navigation_selected_indicator").assertCountEquals(1)
    }

    @Test
    fun zeroDurationScaleMovesIndicatorDirectlyToFinalSelection() {
        var selectedTab by mutableStateOf(AppTab.CHAT)
        motionDurationScale.value = 0f
        composeRule.setContent {
            AdaptiveNavigationSurface(
                expanded = false,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            ) { Text("content") }
        }

        val initialLeft = composeRule
            .onNodeWithTag("main_navigation_selected_indicator")
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        composeRule.runOnIdle { selectedTab = AppTab.SETTINGS }
        composeRule.waitForIdle()
        val finalLeft = composeRule
            .onNodeWithTag("main_navigation_selected_indicator")
            .fetchSemanticsNode()
            .boundsInRoot
            .left

        assertTrue(finalLeft > initialLeft)
        composeRule.onNodeWithTag("main_navigation_tab_settings").assertIsSelected()
        composeRule.onAllNodesWithTag("main_navigation_selected_indicator").assertCountEquals(1)
    }

    @Test
    fun bodyWidthNeverExceeds960Dp() {
        assertEquals(960.dp, constrainedBodyWidth(1_280.dp))
        assertEquals(720.dp, constrainedBodyWidth(720.dp))
    }
}
