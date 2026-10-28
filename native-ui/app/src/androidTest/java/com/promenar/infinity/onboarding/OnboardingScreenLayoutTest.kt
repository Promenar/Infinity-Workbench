package com.promenar.infinity.onboarding

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.welcome.OnboardingActions
import com.promenar.infinity.ui.welcome.OnboardingScreen
import com.promenar.infinity.ui.welcome.OnboardingTags
import com.promenar.infinity.ui.welcome.OnboardingUiState
import org.junit.Rule
import org.junit.Test

class OnboardingScreenLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun languageScreen_landscapeAtDoubleFontScale_keepsBothChoicesReachable() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val english = resources.getString(R.string.welcome_lang_english)
        val chinese = resources.getString(R.string.welcome_lang_chinese)

        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                InfinityTheme {
                    OnboardingScreen(OnboardingUiState(OnboardingStep.LANGUAGE), OnboardingActions())
                }
            }
        }

        rule.onNodeWithText(english).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(chinese).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun modelScreen_hundredsOfCandidates_keepsLastModelReachable() {
        val models = (0 until 500).map { index ->
            ModelInfo(
                name = "Model $index",
                id = "provider::model-$index",
                remoteModelId = "model-$index",
                description = "",
                enabled = false,
                type = "unknown",
                providerId = "provider",
            )
        }
        rule.setContent {
            InfinityTheme {
                OnboardingScreen(OnboardingUiState(OnboardingStep.MODEL, models = models), OnboardingActions())
            }
        }

        // 第 0 项是标题，模型从第 1 项开始。
        rule.onNodeWithTag(OnboardingTags.MODEL_LIST).performScrollToIndex(500)
        rule.onNodeWithText("Model 499", substring = true).assertIsDisplayed()
    }
}
