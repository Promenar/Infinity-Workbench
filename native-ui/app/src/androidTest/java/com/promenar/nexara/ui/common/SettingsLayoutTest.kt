package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SettingsLayoutTest {
    @get:Rule val rule = createComposeRule()

    private fun content(block: @Composable () -> Unit) = rule.setContent {
        NexaraTheme { Column(Modifier.width(411.dp)) { block() } }
    }

    @Test fun singleAndTwoLineRowsHaveContractHeights() {
        content {
            NexaraSettingsItem(title = "Item", onClick = {}, modifier = Modifier.testTag("item"))
            NexaraSettingsItem(title = "Interactive tail", onClick = {}, modifier = Modifier.testTag("interactive"),
                trailingContent = { Switch(checked = true, onCheckedChange = {}) })
            NexaraSettingsItem(title = "Item two", subtitle = "Status", onClick = {}, modifier = Modifier.testTag("item2"))
            SettingsToggle(title = "Toggle", checked = true, onCheckedChange = {}, modifier = Modifier.testTag("toggle"))
            SettingsToggle(title = "Toggle two", description = "Status", checked = true, onCheckedChange = {}, modifier = Modifier.testTag("toggle2"))
            NexaraValueRow(title = "Value", value = "80%", onClick = {}, modifier = Modifier.testTag("value"))
            NexaraValueRow(title = "Value two", subtitle = "Status", value = "80%", onClick = {}, modifier = Modifier.testTag("value2"))
        }
        listOf("item", "toggle", "value", "interactive").forEach { assertHeight(it, 48f) }
        // 带副标题：默认字号下内容（含 12dp 内边距与 2dp 间距）低于 64dp，由最小高度决定。
        listOf("item2", "toggle2", "value2").forEach { assertHeight(it, 64f) }
    }

    @Test fun twoLineRowKeepsTwelveDpPaddingAndTwoDpGap() {
        content { NexaraSettingsItem(title = "Title", subtitle = "Subtitle", onClick = {}, modifier = Modifier.testTag("row")) }
        val row = boundsTag("row"); val title = boundsText("Title"); val subtitle = boundsText("Subtitle")
        fun dp(px: Float) = with(rule.density) { px.toDp().value }
        val top = dp(title.top - row.top); val bottom = dp(row.bottom - subtitle.bottom)
        assertEquals(2f, dp(subtitle.top - title.bottom), 0.5f)
        assertTrue("上内边距 $top", top >= 11.5f)
        assertEquals(top, bottom, 1f)
    }

    @Test fun controlsShareTextBlockCentre() {
        content {
            NexaraSettingsItem(title = "Item", subtitle = "Subtitle", onClick = {},
                trailingContent = { Box(Modifier.size(24.dp).testTag("item_tail")) })
            SettingsToggle(title = "Toggle", description = "Description", checked = true, onCheckedChange = {},
                switchModifier = Modifier.testTag("toggle_tail"))
            NexaraValueRow(title = "Value", value = "80%", onClick = {})
            NexaraStepperSetting(title = "Stepper", value = "3", onDecrease = {}, onIncrease = {})
        }
        val itemTitle = boundsText("Item"); val itemSubtitle = boundsText("Subtitle")
        assertCentre((itemTitle.top + itemSubtitle.bottom) / 2, boundsTag("item_tail").center.y)
        assertCentre((boundsText("Toggle").top + boundsText("Description").bottom) / 2, boundsTag("toggle_tail").center.y)
        assertCentre(boundsText("Value").center.y, boundsText("80%").center.y)
        assertCentre(boundsText("Stepper").center.y, rule.onNode(hasStateDescription("3"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y)
    }

    @Test fun longSubtitleIsExactlyOneEllipsizedLine() {
        val subtitle = "A very long subtitle that repeats ".repeat(12)
        content { NexaraSettingsItem(title = "Title", subtitle = subtitle, onClick = {}) }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(subtitle, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
    }

    @Test fun leadingSpacerAlignsToIconColumn() {
        content {
            NexaraSettingsSection(title = "Section") {
                NexaraSettingsItem(title = "With icon", icon = Icons.Rounded.Info, onClick = {})
                SettingsToggle(title = "With spacer", leadingSpacer = true, checked = true, onCheckedChange = {})
                NexaraValueRow(title = "Value spacer", leadingSpacer = true, value = "5", onClick = {})
            }
        }
        assertEquals(boundsText("With icon").left, boundsText("With spacer").left, 0.5f)
        assertEquals(boundsText("With icon").left, boundsText("Value spacer").left, 0.5f)
        assertEquals(52f, with(rule.density) { boundsText("With icon").left.toDp().value }, 0.5f)
    }

    @Test fun stepperCapsuleIs36DpAndBoundaryButtonsAreDisabled() {
        var decrease = ""; var increase = ""
        content {
            decrease = stringResource(R.string.p0_decrease_setting, "Count")
            increase = stringResource(R.string.p0_increase_setting, "Count")
            NexaraInlineStepper("Count", "2", {}, {}, canDecrease = false, modifier = Modifier.testTag("stepper"))
        }
        assertHeight("stepper", 36f)
        rule.onNodeWithContentDescription(decrease, useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithContentDescription(increase, useUnmergedTree = true).assertIsEnabled()
    }

    @Test fun upperBoundaryDisablesIncreaseAndAccessibleActionsUpdateValue() {
        var value = 20
        var decrease = ""; var increase = ""
        content {
            decrease = stringResource(R.string.p0_decrease_setting, "Count")
            increase = stringResource(R.string.p0_increase_setting, "Count")
            NexaraInlineStepper("Count", "20", { value-- }, { value++ }, canIncrease = false)
        }
        rule.onNodeWithContentDescription(increase, useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithContentDescription(decrease, useUnmergedTree = true).performClick()
        assertEquals(19, value)
    }

    @Test fun selectingPresetSubmitsItsValueAndDismisses() {
        var value = -1.0; var dismissed = false
        content {
            NexaraValuePickerContent("Budget", 50.0, turnSpec(), { value = it }, { dismissed = true })
        }
        rule.onNodeWithText("25").performClick()
        assertEquals(25.0, value, 0.0)
        assertTrue(dismissed)
    }

    @Test fun pickerTitleDescriptionPresetsAndInputShareThe24DpStart() {
        content {
            NexaraValuePickerContent(
                "Budget", 50.0, turnSpec(), {}, {},
                modifier = Modifier.padding(horizontal = 24.dp),
                description = "Choose the tool budget",
                initialCustomExpanded = true,
            )
        }
        val titleLeft = rule.onAllNodesWithText("Budget", useUnmergedTree = true)
            .fetchSemanticsNodes().minBy { it.boundsInRoot.top }.boundsInRoot.left
        assertEquals(with(rule.density) { 24.dp.toPx() }, titleLeft, 0.5f)
        listOf(boundsText("Choose the tool budget"), boundsText("10"),
            boundsTag("numeric_custom"), boundsTag("numeric_custom_input")).forEach {
            assertEquals(titleLeft, it.left, 0.5f)
        }
    }

    @Test fun narrowSettingsTitleIsFullyReadableWithoutEllipsis() {
        val title = "Very long settings title that must wrap naturally on a narrow phone layout"
        content {
            Box(Modifier.width(220.dp)) {
                NexaraSettingsItem(icon = Icons.Rounded.Info, title = title, onClick = {})
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 2)
        assertFalse(layouts.single().hasVisualOverflow)
        rule.onNodeWithText(title).assertHeightIsAtLeast(72.dp)
    }

    @Test fun constrainedEnumPickerKeepsItsLastOptionReachableAtTwoXFontScale() {
        var selected = 0
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, fontScale = 2f)) {
                NexaraTheme {
                    NexaraBottomSheet(show = true, onDismiss = {}, title = "Options",
                        modifier = Modifier.height(252.dp), scrollContent = true) {
                        (1..5).forEach { option ->
                            NexaraSettingsItem(title = "Option $option", horizontalPadding = 0.dp,
                                onClick = { selected = option })
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Option 5").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(5, selected)
    }

    @Test fun invalidCustomValueShowsErrorAndDoesNotSubmit() {
        var calls = 0
        content { NexaraValuePickerContent("Budget", 50.0, turnSpec(), { calls++ }, {}) }
        rule.onNodeWithTag("numeric_custom").performClick()
        rule.onNodeWithTag("numeric_custom_input").performTextReplacement("101")
        rule.onNodeWithTag("numeric_done").performClick()
        rule.onNodeWithTag("numeric_custom_error").assertExists()
        assertEquals(0, calls)
    }

    @Test fun validCustomValueSubmitsAlignedValue() {
        var value = -1.0
        content { NexaraValuePickerContent("Budget", 50.0, turnSpec(), { value = it }, {}) }
        rule.onNodeWithTag("numeric_custom").performClick()
        rule.onNodeWithTag("numeric_custom_input").performTextReplacement("71")
        rule.onNodeWithTag("numeric_done").performClick()
        assertEquals(71.0, value, 0.0)
    }

    @Test fun repeatedRagChangesPreserveOtherFieldsBeforeParentRecomposition() {
        var stored = com.promenar.nexara.data.model.RagOptions()
        var memory = ""; var documents = ""
        content {
            memory = stringResource(R.string.sheet_settings_memory)
            documents = stringResource(R.string.sheet_settings_docs)
            com.promenar.nexara.ui.chat.SessionContextPanelContent(
                onRagOptionsChange = { stored = it(stored) },
            )
        }
        rule.onNode(hasText(memory) and hasClickAction()).performClick()
        rule.onNode(hasText(documents) and hasClickAction()).performClick()
        assertFalse(stored.enableMemory)
        assertFalse(stored.enableDocs)
        assertTrue(stored.enableRerank)
    }

    @Test fun executionModePickerUsesNamedOptionsAndKeepsStoredString() {
        var mode = "semi"
        content { com.promenar.nexara.ui.chat.SessionToolsContent(executionMode = mode, onExecutionModeChange = { mode = it }) }
        rule.onNodeWithTag("session_execution_mode_picker").performScrollTo().performClick()
        rule.onNodeWithTag("numeric_custom").assertDoesNotExist()
        rule.onNodeWithTag("session_execution_mode_manual").performClick()
        assertEquals("manual", mode)
    }

    @Test fun languagePickerSubmitsOriginalCodeAndCloses() {
        var selected = "zh"
        var english = ""
        content {
            var show by remember { mutableStateOf(true) }
            english = stringResource(R.string.settings_language_en)
            if (show) com.promenar.nexara.ui.hub.LanguageSelectorSheet(
                currentLanguage = "zh",
                onSelect = { selected = it; show = false },
                onDismiss = { show = false },
            )
        }
        rule.onNodeWithText(english).performClick()
        assertEquals("en", selected)
        rule.onNodeWithText(english).assertDoesNotExist()
    }

    private fun turnSpec() = NumericSettingSpec(1.0, 100.0, presets = listOf(10.0, 25.0, 50.0, 100.0, 0.0).map { NumericSettingSpec.Preset(it) },
        defaultValue = 50.0, unlimitedValue = 0.0)
    private fun boundsText(text: String): Rect = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun boundsTag(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun assertHeight(tag: String, expected: Float) {
        val actual = with(rule.density) { boundsTag(tag).height.toDp().value }
        assertEquals(tag, expected, actual, 0.5f)
    }
    private fun assertCentre(first: Float, second: Float) {
        assertTrue("中心线偏差超出1dp: ${abs(first - second)}px", abs(first - second) <= with(rule.density) { 1.dp.toPx() })
    }
}
