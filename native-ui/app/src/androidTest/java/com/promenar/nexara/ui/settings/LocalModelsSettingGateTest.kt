package com.promenar.nexara.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.nexara.data.local.inference.StoredModel
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelsSettingGateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun modelNameCannotLoadWhileEngineIsDisabled() {
        var loads = 0; var unloads = 0
        rule.setContent {
            NexaraTheme {
                LocalModelsScreenContent(
                    availableModels = listOf(StoredModel("guard", "guard-test.gguf", "/fixture/guard-test.gguf", 1024, "GGUF", 0)),
                    engineEnabled = false,
                    onLoadModel = { _, _ -> loads++ }, onUnloadModel = { unloads++ },
                )
            }
        }
        rule.onNodeWithText("guard-test.gguf").performScrollTo().performClick()
        assertEquals(0, loads)
        assertEquals(0, unloads)
    }

    @Test fun emptyModelEntryCannotOpenAnotherImporterWhileBusy() {
        var imports = 0
        var emptyTitle = ""
        rule.setContent {
            NexaraTheme {
                emptyTitle = stringResource(R.string.local_models_none_imported)
                LocalModelsScreenContent(isImporting = true, onImport = { imports++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(emptyTitle).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, imports)
    }
}
