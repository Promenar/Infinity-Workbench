package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ChatScrollGeometryTest {
    @get:Rule val rule = createComposeRule()
    private var viewportHeight by mutableStateOf(700.dp)
    private var input by mutableStateOf("")
    private val tail = "geometry-tail-9142"

    @Test
    fun tailFollowsInitialComposerMeasurementAndViewportAndInputHeightChanges() {
        render()
        rule.onNodeWithText(tail).assertIsDisplayed()
        rule.runOnIdle { viewportHeight = 420.dp }
        rule.onNodeWithText(tail).assertIsDisplayed()
        rule.runOnIdle { input = (1..6).joinToString("\n") { "输入第 $it 行" } }
        rule.onNodeWithText(tail).assertIsDisplayed()
    }

    @Test
    fun geometryChangesRespectDeliberateUpScroll() {
        render()
        rule.onNodeWithText(tail).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_STATE_READY).performTouchInput { swipeDown() }
        rule.onNodeWithText(tail).assertIsNotDisplayed()
        rule.runOnIdle { viewportHeight = 420.dp; input = "第一行\n第二行\n第三行" }
        rule.onNodeWithText(tail).assertIsNotDisplayed()
    }

    private fun render() = rule.setContent {
        NexaraTheme {
            Box(Modifier.width(411.dp).height(viewportHeight)) {
                ChatScreenContent(
                    state = ChatScreenState(
                        uiState = ChatUiState(
                            session = Session(id = "geometry", agentId = "agent", title = "布局变化", modelId = "provider/model"),
                            messages = (0..17).map { index ->
                                Message("fill-$index", if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, "正文内容 ".repeat(40))
                            } + Message("tail", MessageRole.USER, tail),
                        ),
                        inputText = input,
                    ),
                    actions = ChatScreenActions(onTextChange = { input = it }),
                )
            }
        }
    }
}
