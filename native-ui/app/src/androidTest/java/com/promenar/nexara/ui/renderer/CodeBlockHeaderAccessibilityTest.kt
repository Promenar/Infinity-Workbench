package com.promenar.nexara.ui.renderer

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class CodeBlockHeaderAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun htmlToolbarActionsKeep48DpTouchTargetsAcrossStateChanges() {
        val context = localizedContext("en")
        val code = "<html>\n<body>Preview</body>\n</html>"
        val edited = AtomicReference<String?>(null)

        showCodeBlock(
            context = context,
            code = code,
            language = "html",
            onCodeChange =(edited::set),
        )

        listOf(
            R.string.code_block_full_screen,
            R.string.code_block_export_png,
            R.string.code_block_edit,
            R.string.code_block_copy,
        ).forEach { resourceId ->
            rule.onNodeWithContentDescription(context.getString(resourceId))
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        rule.onNodeWithContentDescription(context.getString(R.string.code_block_edit)).performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.code_block_save))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.runOnIdle { check(edited.get() == code) }

        rule.onNodeWithContentDescription(context.getString(R.string.code_block_copy)).performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.code_block_copied))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun blockDescriptionIsLocalizedAndDecorativeLanguageAndLineNumbersStaySilent() {
        val context = localizedContext("zh-CN")
        val code = "第一行\n第二行"
        val description = context.resources.getQuantityString(
            R.plurals.code_block_description,
            2,
            context.getString(R.string.code_block_plain_text),
            2,
        )

        showCodeBlock(context = context, code = code, language = null)

        rule.onNodeWithContentDescription(description).assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.code_block_copy))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Copy code").assertDoesNotExist()
        rule.onAllNodesWithText("1").assertCountEquals(0)
        rule.onAllNodesWithText("2").assertCountEquals(0)
        rule.onAllNodesWithText(context.getString(R.string.code_block_language_fallback))
            .assertCountEquals(0)
    }

    private fun showCodeBlock(
        context: Context,
        code: String,
        language: String?,
        onCodeChange: ((String) -> Unit)? = null,
    ) {
        val configuration = Configuration(context.resources.configuration)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides configuration,
                ) {
                    NexaraTheme(dynamicColor = false) {
                        CodeBlockWithHeader(
                            code = code,
                            language = language,
                            onCodeChange = onCodeChange,
                        ) {
                            Text(code)
                        }
                    }
                }
            }
        }
    }

    private fun localizedContext(languageTag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            },
        )
    }
}
