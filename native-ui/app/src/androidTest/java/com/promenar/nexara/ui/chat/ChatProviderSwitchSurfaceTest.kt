package com.promenar.nexara.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ChatProviderSwitchSurfaceTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectingAnotherProviderModel_dispatchesStableCompositeId() {
        val first = model("provider-a::alpha", "Alpha", "Provider A")
        val second = model("provider-b::beta", "Beta", "Provider B")
        var selected: String? = null

        rule.setContent {
            NexaraTheme {
                ModelPanel(
                    selectedModelId = first.id,
                    onSelect = { selected = it },
                    allModels = listOf(first, second),
                )
            }
        }

        rule.onNodeWithTag(UiTags.CHAT_MODEL_LIST).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.chatModelOption(first.id)).assertIsSelected()
        rule.onNodeWithTag(UiTags.chatModelOption(second.id)).assertIsNotSelected()
        rule.onNodeWithText("Provider A • 8K Context").assertIsDisplayed()
        rule.onNodeWithText("Provider B • 8K Context").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.chatModelOption(second.id)).performClick()
        rule.runOnIdle { assertThat(selected).isEqualTo(second.id) }
    }

    private fun model(id: String, name: String, provider: String) = ModelInfo(
        name = name,
        id = id,
        description = "E2E model",
        enabled = true,
        type = "chat",
        contextLength = 8192,
        capabilities = listOf("chat"),
        providerName = provider,
        providerId = id.substringBefore("::"),
    )
}
