package com.promenar.nexara.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ThemeSettingsInteractionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    object TestTags {
        const val MODE_SYSTEM = "theme_mode_system"
        const val MODE_LIGHT = "theme_mode_light"
        const val MODE_DARK = "theme_mode_dark"
        const val DYNAMIC_COLOR_SWITCH = "theme_dynamic_color_switch"
    }

    @Test
    fun themeScreenContent_rendersModesAndTriggersCallbacks() {
        val selectedMode = AtomicReference<NexaraThemeMode?>(null)
        val selectedSource = AtomicReference<NexaraColorSource?>(null)

        composeRule.setContent {
            ThemeScreenContent(
                state = ThemeUiState(
                    preferences = NexaraThemePreferences(
                        mode = NexaraThemeMode.DARK,
                        colorSource = NexaraColorSource.NEXARA
                    ),
                    dynamicColorAvailable = true
                ),
                onModeSelect = { selectedMode.set(it) },
                onColorSourceSelect = { selectedSource.set(it) },
                onNavigateBack = {}
            )
        }

        // 验证三个模式选项存在，且都具有点击动作，同时触摸目标尺寸至少为 48dp
        listOf(
            TestTags.MODE_SYSTEM to NexaraThemeMode.SYSTEM,
            TestTags.MODE_LIGHT to NexaraThemeMode.LIGHT,
            TestTags.MODE_DARK to NexaraThemeMode.DARK
        ).forEach { (tag, mode) ->
            composeRule.onNodeWithTag(tag)
                .assertExists()
                .assert(hasClickAction())
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            assertThat(selectedMode.getAndSet(null)).isEqualTo(mode)
        }

        composeRule.onNodeWithTag(TestTags.MODE_DARK).assertIsSelected()
        composeRule.onNodeWithTag(TestTags.MODE_SYSTEM).assertIsNotSelected()

        // 验证动态取色开关存在，且可以点击
        composeRule.onNodeWithTag(TestTags.DYNAMIC_COLOR_SWITCH)
            .assertExists()
            .assert(hasClickAction())
            .assertIsOff()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        // 原始状态为 NEXARA，点击后应该触发切换到 DYNAMIC
        assertThat(selectedSource.getAndSet(null)).isEqualTo(NexaraColorSource.DYNAMIC)
    }

    @Test
    fun themeScreenContent_dynamicColorUnavailable_disablesSwitchButKeepsTouchTarget() {
        val selectedSource = AtomicReference<NexaraColorSource?>(null)

        composeRule.setContent {
            ThemeScreenContent(
                state = ThemeUiState(
                    preferences = NexaraThemePreferences(
                        mode = NexaraThemeMode.DARK,
                        colorSource = NexaraColorSource.DYNAMIC
                    ),
                    dynamicColorAvailable = false
                ),
                onModeSelect = {},
                onColorSourceSelect = { selectedSource.set(it) },
                onNavigateBack = {}
            )
        }

        // 验证不可用时，动态颜色开关为禁用状态，但仍保持 48dp 触摸无障碍目标
        composeRule.onNodeWithTag(TestTags.DYNAMIC_COLOR_SWITCH)
            .assertExists()
            .assertIsNotEnabled()
            .assertIsOff()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun themeScreenContent_rendersCorrectlyAt2xFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                ThemeScreenContent(
                    state = ThemeUiState(
                        preferences = NexaraThemePreferences(
                            mode = NexaraThemeMode.SYSTEM,
                            colorSource = NexaraColorSource.DYNAMIC
                        ),
                        dynamicColorAvailable = true
                    ),
                    onModeSelect = {},
                    onColorSourceSelect = {},
                    onNavigateBack = {}
                )
            }
        }

        // 在 2.0x 字号下，验证主要元素和模式仍然正常显示并且无障碍语义可查询
        composeRule.onNodeWithTag(TestTags.MODE_SYSTEM).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.MODE_LIGHT).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.MODE_DARK).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.DYNAMIC_COLOR_SWITCH).assertIsDisplayed()
    }
}
