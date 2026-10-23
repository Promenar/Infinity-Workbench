package com.promenar.nexara.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.data.canvas.FileCanvasStore
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.ui.chat.CanvasInvocationCards
import com.promenar.nexara.ui.chat.ChatScreenActions
import com.promenar.nexara.ui.chat.ChatScreenContent
import com.promenar.nexara.ui.chat.ChatScreenState
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.R
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** 使用稳定的 Compose 占位验证真实宿主接线，WebView 隔离由独立 JVM 策略测试覆盖。 */
class CanvasChatEntryTest {
    @get:Rule val rule = createComposeRule()
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "canvas-test-${UUID.randomUUID()}")
    private val store = FileCanvasStore(root)

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun topBarMenu_opensSessionCanvasList() {
        rule.setContent {
            NexaraTheme {
                CanvasSessionHost("session", store, onInsertText = {}) {
                    ChatScreenContent(ChatScreenState(), ChatScreenActions())
                }
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_OPTIONS).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_ENTRY).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.CANVAS_LIST).assertIsDisplayed()
    }

    @Test fun successfulToolCard_opensLatestAndRefreshesWhileVisible() {
        val canvas = runBlocking { store.create("session", "图表", "<p>1</p>", "message") }
        val steps = listOf(
            ExecutionStep(id = "call", type = "tool_call", toolName = "canvas_create", toolCallId = "call"),
            ExecutionStep(id = "result", type = "tool_result", toolCallId = "call", content = """{"canvasId":"${canvas.metadata.id}","title":"图表","version":1}"""),
        )
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                NexaraTheme {
                    CanvasSessionHost("session", store, onInsertText = {}) {
                        Box(Modifier.fillMaxSize()) { CanvasInvocationCards(steps) }
                    }
                }
            }
        }
        rule.onNodeWithTag(UiTags.CANVAS_CARD).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_OPEN).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_FULLSCREEN).assertIsDisplayed()
        runBlocking { store.update("session", canvas.metadata.id, html = "<p>2</p>") }
        val latestLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.canvas_follow_latest, 2)
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText(latestLabel).fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag(UiTags.CANVAS_CLOSE).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_CARD).assertIsDisplayed()
    }

    @Test fun selectedHistory_remainsReadableAfterItsStoredVersionIsPruned() {
        val limited = FileCanvasStore(File(root, "limited"), maxVersions = 2)
        val canvas = runBlocking {
            limited.create("session", "历史图表", "<p>1</p>", "message").also {
                limited.update("session", it.metadata.id, html = "<p>2</p>")
            }
        }
        val observedRevision = AtomicInteger()
        val steps = listOf(
            ExecutionStep(id = "call", type = "tool_call", toolName = "canvas_create", toolCallId = "call"),
            ExecutionStep(id = "result", type = "tool_result", toolCallId = "call", content = """{"canvasId":"${canvas.metadata.id}","title":"历史图表","version":1}"""),
        )
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                NexaraTheme {
                    CanvasSessionHost("session", limited, onInsertText = {}) {
                        val revision = LocalCanvasSession.current?.canvases?.singleOrNull()?.currentVersion ?: 0
                        SideEffect { observedRevision.set(revision) }
                        Box(Modifier.fillMaxSize()) { CanvasInvocationCards(steps) }
                    }
                }
            }
        }
        rule.waitUntil(5_000) { observedRevision.get() == 2 }
        rule.onNodeWithTag(UiTags.CANVAS_OPEN).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_VERSION_SELECTOR).performClick()
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val historyLabel = resources.getString(R.string.canvas_history_version, 1)
        rule.onNodeWithText(historyLabel).performClick()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag(UiTags.CANVAS_COPY).assertIsEnabled() }.isSuccess
        }
        runBlocking { limited.update("session", canvas.metadata.id, html = "<p>3</p>") }
        rule.waitUntil(5_000) { observedRevision.get() == 3 }
        rule.onNodeWithText(historyLabel).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_COPY).assertIsEnabled()
        rule.onNodeWithText(resources.getString(R.string.canvas_load_error)).assertDoesNotExist()
    }
}
