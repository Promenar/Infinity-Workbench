package com.promenar.nexara.onboarding

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.welcome.WelcomeScreen
import com.promenar.nexara.ui.settings.ModelInfo
import org.junit.Rule
import org.junit.Test

class WelcomeScreenLayoutTest {
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
                NexaraTheme {
                    WelcomeScreen(onLanguageSelected = {})
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
            NexaraTheme {
                WelcomeScreen(
                    onLanguageSelected = {},
                    state = OnboardingState(
                        step = OnboardingStep.MODEL,
                        providerId = "provider",
                    ),
                    models = models,
                )
            }
        }

        rule.onNodeWithTag("onboarding_step_model").performScrollToIndex(500)
        rule.onNodeWithText("Model 499", substring = true).assertIsDisplayed()
    }
}
