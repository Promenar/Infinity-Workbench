package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

/** 全站胶囊页签：任何窗口与字号下，单个页签都必须保留 48dp 触控高度。 */
class InfinityCapsuleTabRowTouchTargetTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun phoneWithDoubleFontKeepsEveryTabAtMinimumTouchHeight() {
        assertTabsReachable(windowSize = DpSize(360.dp, 800.dp), fontScale = 2f)
    }

    @Test
    fun landscapeKeepsEveryTabAtMinimumTouchHeight() {
        assertTabsReachable(windowSize = DpSize(800.dp, 360.dp), fontScale = 1f)
    }

    private fun assertTabsReachable(windowSize: DpSize, fontScale: Float) {
        var selected = 0
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(windowSize) then
                    DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                InfinityTheme(dynamicColor = false) {
                    Box(Modifier.requiredSize(windowSize.width, windowSize.height)) {
                        InfinityCapsuleTabRow(
                            tabs = TAB_TAGS.mapIndexed { index, tag ->
                                InfinityTabItem(title = "页签 $index", testTag = tag)
                            },
                            selectedIndex = selected,
                            onTabSelected = { selected = it },
                        )
                    }
                }
            }
        }

        TAB_TAGS.forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag(TAB_TAGS.last()).performClick()
        assertThat(selected).isEqualTo(TAB_TAGS.lastIndex)
    }

    private companion object {
        val TAB_TAGS = listOf("capsule_tab_0", "capsule_tab_1", "capsule_tab_2", "capsule_tab_3")
    }
}
