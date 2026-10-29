package com.promenar.infinity.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.model.catalog.CatalogUpdatePhase
import com.promenar.infinity.data.model.catalog.CatalogUpdateStatus
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

class ProviderModelCatalogUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun specLibraryRetryRemainsAccessibleAndIgnoresDuplicateClicks() {
        val phase = mutableStateOf(CatalogUpdatePhase.FAILED)
        var clicks = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                InfinityTheme {
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
        rule.onNodeWithText(rule.activity.getString(R.string.model_spec_failed_update)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.model_spec_kept, "09-22")).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_SPEC_RETRY).assertIsDisplayed().performClick()
        // 更新中失败提示收起；⋮ 菜单里的“更新规格库”显示进度，重复点按不再触发。
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_SPEC_FAILURE).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_OVERFLOW).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.model_spec_updating)).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_SPEC_LIBRARY).performClick()
        rule.runOnIdle { assertThat(clicks).isEqualTo(1) }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "model-catalog-ui.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
