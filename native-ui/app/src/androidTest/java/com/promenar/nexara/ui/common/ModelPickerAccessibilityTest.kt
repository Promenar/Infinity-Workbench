package com.promenar.nexara.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.SupportState
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ModelPickerAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectionRow_atTwoXFontScale_keepsRadioSemanticsAndTouchTarget() {
        var clicks = 0
        val model = ModelSelectionUiModel(
            selectionId = "provider::long-model",
            remoteModelId = "long-model",
            displayName = "Extremely Long Official Model Name Preview With Reasoning",
            providerName = "Provider With A Long Display Name",
            contextTokens = 1_048_576,
            workload = ModelWorkload.GENERATIVE_TEXT,
            capabilityStates = mapOf(
                ModelCapability.REASONING to SupportState.SUPPORTED,
                ModelCapability.VISION_INPUT to SupportState.SUPPORTED,
                ModelCapability.TOOL_CALLING to SupportState.UNKNOWN,
            ),
            chatEndpointCompatible = SupportState.UNKNOWN,
        )

        rule.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                NexaraTheme {
                    ModelSelectionListItem(
                        model = model,
                        selected = true,
                        onClick = { clicks += 1 },
                        modifier = Modifier.testTag("model-row"),
                    )
                }
            }
        }

        val row = rule.onNodeWithTag("model-row")
        row.assertIsDisplayed()
            .assertIsSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithText(model.displayName).assertIsDisplayed()
        row.performClick()
        rule.runOnIdle { assertThat(clicks).isEqualTo(1) }
    }
}
