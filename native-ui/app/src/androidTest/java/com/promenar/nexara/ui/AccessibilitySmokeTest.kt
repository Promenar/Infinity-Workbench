package com.promenar.nexara.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.content.res.Configuration
import com.promenar.nexara.R
import java.util.Locale
import com.promenar.nexara.ui.chat.ChatApprovalLiveRegion
import com.promenar.nexara.ui.chat.ChatInputBar
import com.promenar.nexara.ui.chat.GenerationStatus
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilitySmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun criticalChatActionsMeetTouchTargetAndExposeLiveStatus() {
        var status by mutableStateOf(GenerationStatus.THINKING)
        composeRule.setContent {
            NexaraTheme {
                Column {
                    ChatInputBar(
                        text = "hello",
                        onTextChange = {},
                        onSend = {},
                        status = status,
                    )
                    ChatApprovalLiveRegion { Text("approval") }
                }
            }
        }

        val action = composeRule.onNodeWithTag("chat_generation_action")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                )
            )
        val bounds = action.fetchSemanticsNode().boundsInRoot
        val minimumPx = 48f * InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        assertTrue(bounds.width >= minimumPx)
        assertTrue(bounds.height >= minimumPx)

        composeRule.onNodeWithTag("chat_approval_live_region").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            )
        )

        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        composeRule.runOnIdle { status = GenerationStatus.ERROR }
        composeRule.onNodeWithTag("chat_generation_action")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(resources.getString(R.string.chat_status_error)),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                )
            )

        composeRule.runOnIdle { status = GenerationStatus.COMPLETED }
        composeRule.onNodeWithTag("chat_generation_action").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(resources.getString(R.string.chat_status_completed)),
            )
        )
    }

    @Test
    fun pageRemainsScrollableAtTwoTimesFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                NexaraTheme {
                    NexaraPageLayout(title = "A deliberately long accessible page title") {
                        Text("Body content remains available in landscape and large text layouts")
                    }
                }
            }
        }

        composeRule.onNodeWithTag("nexara_page_body").assertIsDisplayed()
    }

    @Test
    fun defaultResourcesContainNoChineseAndChineseLocaleResolvesTranslatedKeys() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun resourcesFor(tag: String) = base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
        ).resources

        val english = resourcesFor("en")
        val chinesePattern = Regex("[\\u4E00-\\u9FFF]")
        R.string::class.java.fields.forEach { field ->
            val value = english.getText(field.getInt(null)).toString()
            assertTrue("Default English resource contains Chinese: ${field.name}", !chinesePattern.containsMatchIn(value))
        }

        val chinese = resourcesFor("zh-CN")
        assertTrue(chinese.getString(R.string.nav_tab_chat).contains("对话"))
        assertTrue(chinese.getString(R.string.chat_status_thinking).contains("思考"))
    }
}
