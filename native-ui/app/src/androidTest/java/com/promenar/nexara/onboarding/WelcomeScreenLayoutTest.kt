package com.promenar.nexara.onboarding

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.welcome.WelcomeScreen
import com.promenar.nexara.ui.settings.ModelInfo
import org.junit.Rule
import org.junit.Test

class WelcomeScreenLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun languageScreen_landscapeAtDoubleFontScale_keepsBothChoicesReachable() {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val english = rule.activity.getString(R.string.welcome_lang_english)
        val chinese = rule.activity.getString(R.string.welcome_lang_chinese)

        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
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
