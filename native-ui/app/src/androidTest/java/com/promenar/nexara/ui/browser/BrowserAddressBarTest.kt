package com.promenar.nexara.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.browser.BrowserAvailability
import com.promenar.nexara.data.browser.BrowserUiState
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class BrowserAddressBarTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: BrowserUiState?, onNavigate: ((String) -> Unit)?) = rule.setContent {
        NexaraTheme {
            BrowserPageContent(
                state = state,
                availability = BrowserAvailability.Supported,
                onBack = {},
                onTakeOver = {},
                onHandBack = {},
                onReload = {},
                onPageBack = {},
                onPageForward = {},
                onUpdateWebView = {},
                modifier = Modifier.fillMaxSize(),
                onNavigate = onNavigate,
            ) { Box(Modifier.fillMaxSize()) }
        }
    }

    @Test fun tappingAddressEditsAndGoSendsResolvedTarget() {
        val opened = mutableListOf<String>()
        show(BrowserUiState(url = "https://example.com/docs"), opened::add)
        rule.onNodeWithTag(BrowserTags.ADDRESS).performClick()
        rule.onNodeWithTag(BrowserTags.ADDRESS_INPUT).assertIsDisplayed().performTextReplacement("kotlin flow")
        rule.onNodeWithTag(BrowserTags.ADDRESS_INPUT).performImeAction()
        assertThat(opened).containsExactly("https://www.google.com/search?q=kotlin+flow")
        rule.onAllNodesWithTag(BrowserTags.ADDRESS_INPUT).assertCountEquals(0)
    }

    @Test fun addressIsTypeableBeforeAnyPageOpens() {
        val opened = mutableListOf<String>()
        show(null, opened::add)
        rule.onNodeWithTag(BrowserTags.ADDRESS).performClick()
        rule.onNodeWithTag(BrowserTags.ADDRESS_INPUT).performTextReplacement("example.com")
        rule.onNodeWithTag(BrowserTags.ADDRESS_INPUT).performImeAction()
        assertThat(opened).containsExactly("https://example.com")
    }

    @Test fun addressStaysReadOnlyWithoutNavigation() {
        show(BrowserUiState(url = "https://example.com/"), null)
        rule.onNodeWithTag(BrowserTags.ADDRESS).performClick()
        rule.onAllNodesWithTag(BrowserTags.ADDRESS_INPUT).assertCountEquals(0)
    }
}
