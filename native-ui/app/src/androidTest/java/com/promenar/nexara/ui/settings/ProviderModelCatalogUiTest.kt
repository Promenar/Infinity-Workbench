package com.promenar.nexara.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.catalog.CatalogUpdatePhase
import com.promenar.nexara.data.model.catalog.CatalogUpdateStatus
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ProviderModelCatalogUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun catalogUpdateRemainsAccessibleAndDisablesDuplicateClicks() {
        val phase = mutableStateOf(CatalogUpdatePhase.FAILED)
        var clicks = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                NexaraTheme {
                    ProviderModelsScreenContent(
                        ProviderModelsScreenState("Provider", "test", false, null, emptyList(), emptyMap(),
                            CatalogUpdateStatus(phase.value, "2026-09-22T00:00:00Z", 422, 20, true)),
                        ProviderModelsScreenActions({}, { _, _ -> false }, {}, {}, {}, {}, {}, {}, {}, {},
                            onRefreshCatalog = { clicks++; phase.value = CatalogUpdatePhase.UPDATING }),
                        {},
                    )
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.model_catalog_failed)).assertIsDisplayed()
        rule.onNodeWithTag("model_catalog_refresh").assertIsDisplayed().performClick()
        rule.onNodeWithTag("model_catalog_refresh").assertIsNotEnabled()
        rule.runOnIdle { assertThat(clicks).isEqualTo(1) }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "model-catalog-ui.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
