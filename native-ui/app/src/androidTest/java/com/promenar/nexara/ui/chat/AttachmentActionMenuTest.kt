package com.promenar.nexara.ui.chat

import android.os.SystemClock
import android.view.WindowInsets as AndroidWindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test

class AttachmentActionMenuTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(
        id = "attachment-session",
        agentId = "attachment-agent",
        title = "Attachment chat",
        modelId = "provider/model",
    )

    @After
    fun closeImeAfterTest() {
        if (imeVisible()) {
            Espresso.pressBack()
            awaitImeClosed()
        }
    }

    @Test
    fun openMenu_isAnchoredAboveComposer_andDoesNotCoverComposerControls() {
        render()

        val add = rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT)
        add.assertContentDescriptionEquals(resource(R.string.chat_cd_add_attachment))
        add.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                resource(R.string.common_state_collapsed),
            )
        )
        add.performClick()

        val rootBounds = rule.onNodeWithTag(UiTags.CHAT_ROOT).fetchSemanticsNode().boundsInRoot
        val composerBounds = rule.onNodeWithTag(UiTags.CHAT_COMPOSER).fetchSemanticsNode().boundsInRoot
        val addBounds = add.fetchSemanticsNode().boundsInRoot
        val menuBounds = rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 2.dp.toPx() }
        val anchorTolerance = with(rule.density) { 24.dp.toPx() }

        assertThat(menuBounds.left).isAtLeast(rootBounds.left - tolerance)
        assertThat(menuBounds.top).isAtLeast(rootBounds.top - tolerance)
        assertThat(menuBounds.right).isAtMost(rootBounds.right + tolerance)
        assertThat(menuBounds.bottom).isAtMost(composerBounds.top + tolerance)
        assertThat(kotlin.math.abs(menuBounds.left - addBounds.left)).isAtMost(anchorTolerance)

        val modelBounds = rule.onNodeWithTag(UiTags.CHAT_MODEL_SELECTOR)
            .fetchSemanticsNode().boundsInRoot
        val tokenBounds = rule.onNodeWithTag(UiTags.CHAT_TOKEN_INDICATOR)
            .fetchSemanticsNode().boundsInRoot
        val sendBounds = rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION)
            .fetchSemanticsNode().boundsInRoot
        assertThat(menuBounds.overlaps(modelBounds)).isFalse()
        assertThat(menuBounds.overlaps(tokenBounds)).isFalse()
        assertThat(menuBounds.overlaps(sendBounds)).isFalse()

        add.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                resource(R.string.common_state_expanded),
            )
        )
        add.assertContentDescriptionEquals(resource(R.string.common_dismiss))

        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_IMAGE)
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT)
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun menuDismissesFromOutsideAndSystemBack() {
        render()

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_DISMISS_LAYER).performTouchInput { click() }
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertDoesNotExist()

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertIsDisplayed()
        Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertDoesNotExist()
    }

    @Test
    fun topBarTap_dismissesMenuWithoutInvokingUnderlyingAction() {
        var workspaceOpens = 0
        render(onOpenWorkspace = { workspaceOpens += 1 })

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        val workspace = rule.onNodeWithContentDescription(resource(R.string.chat_cd_workspace))
        workspace.performTouchInput { click() }
        rule.runOnIdle { assertThat(workspaceOpens).isEqualTo(0) }
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertDoesNotExist()

        workspace.performTouchInput { click() }
        rule.runOnIdle { assertThat(workspaceOpens).isEqualTo(1) }
    }

    @Test
    fun selectingEachActionClosesMenuAndInvokesOnlyThatAction() {
        var imagePicks = 0
        var documentPicks = 0
        render(
            onPickImage = { imagePicks += 1 },
            onPickDocument = { documentPicks += 1 },
        )

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_IMAGE)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertDoesNotExist()
        rule.runOnIdle {
            assertThat(imagePicks).isEqualTo(1)
            assertThat(documentPicks).isEqualTo(0)
        }

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertDoesNotExist()
        rule.runOnIdle {
            assertThat(imagePicks).isEqualTo(1)
            assertThat(documentPicks).isEqualTo(1)
        }
    }

    @Test
    fun generationAndImportDisableTrigger_andCloseAnOpenMenu() {
        var generating by mutableStateOf(false)
        var importing by mutableStateOf(false)
        render(
            isGenerating = { generating },
            isImporting = { importing },
        )

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertIsDisplayed()
        rule.runOnIdle { generating = true }
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).assertIsNotEnabled()

        rule.runOnIdle {
            generating = false
            importing = true
        }
        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).assertIsNotEnabled()
    }

    @Test
    fun disabledMenuActions_doNotInvokeCallbacks() {
        var imagePicks = 0
        var documentPicks = 0
        rule.setContent {
            NexaraTheme {
                val density = LocalDensity.current
                AttachmentActionMenu(
                    expanded = true,
                    anchorBounds = Rect(
                        left = with(density) { 16.dp.toPx() },
                        top = with(density) { 280.dp.toPx() },
                        right = with(density) { 64.dp.toPx() },
                        bottom = with(density) { 304.dp.toPx() },
                    ),
                    maximumBottom = with(density) { 272.dp.toPx() },
                    enabled = false,
                    onDismiss = {},
                    onPickImage = { imagePicks += 1 },
                    onPickDocument = { documentPicks += 1 },
                    modifier = Modifier
                        .width(320.dp)
                        .height(320.dp),
                )
            }
        }

        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_IMAGE)
            .assertIsNotEnabled()
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT)
            .assertIsNotEnabled()
            .performClick()
        rule.runOnIdle {
            assertThat(imagePicks).isEqualTo(0)
            assertThat(documentPicks).isEqualTo(0)
        }
    }

    @Test
    fun largeFontMenu_remainsFullyVisibleAndActionsKeep48DpTargets() {
        render(fontScale = 2f)

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        val root = rule.onNodeWithTag(UiTags.CHAT_ROOT).fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag(UiTags.CHAT_COMPOSER).fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 2.dp.toPx() }

        assertThat(menu.left).isAtLeast(root.left - tolerance)
        assertThat(menu.top).isAtLeast(root.top - tolerance)
        assertThat(menu.right).isAtMost(root.right + tolerance)
        assertThat(menu.bottom).isAtMost(composer.top + tolerance)
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_IMAGE).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun menuOpenedWithIme_staysInsideVisibleChatWindow() {
        render()

        rule.onNodeWithTag(UiTags.CHAT_INPUT).performClick().performTextInput("IME")
        assertThat(awaitImeOpened()).isGreaterThan(0)
        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        rule.waitForIdle()

        val root = rule.onNodeWithTag(UiTags.CHAT_ROOT).fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag(UiTags.CHAT_COMPOSER).fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 2.dp.toPx() }
        assertThat(menu.top).isAtLeast(root.top - tolerance)
        assertThat(menu.bottom).isAtMost(composer.top + tolerance)
    }

    @Test
    fun dismissAnimation_keepsInterceptingTouchesUntilMenuExitCompletes() {
        var underlyingClicks = 0
        var expanded by mutableStateOf(true)
        rule.setContent {
            NexaraTheme {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(320.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("attachment_underlying_action")
                            .clickable { underlyingClicks += 1 },
                    )
                    val density = LocalDensity.current
                    AttachmentActionMenu(
                        expanded = expanded,
                        anchorBounds = Rect(
                            left = with(density) { 16.dp.toPx() },
                            top = with(density) { 280.dp.toPx() },
                            right = with(density) { 64.dp.toPx() },
                            bottom = with(density) { 304.dp.toPx() },
                        ),
                        maximumBottom = with(density) { 272.dp.toPx() },
                        enabled = true,
                        onDismiss = { expanded = false },
                        onPickImage = {},
                        onPickDocument = {},
                    )
                }
            }
        }

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_DISMISS_LAYER).performTouchInput { click() }
        rule.onNodeWithTag("attachment_underlying_action").performTouchInput { click() }
        rule.runOnIdle { assertThat(underlyingClicks).isEqualTo(0) }

        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_DISMISS_LAYER).assertDoesNotExist()
        rule.onNodeWithTag("attachment_underlying_action").performTouchInput { click() }
        rule.runOnIdle { assertThat(underlyingClicks).isEqualTo(1) }
    }

    @Test
    fun shortWindowAtLargeFont_constrainsMenuAboveMaximumBottom() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NexaraTheme {
                    val anchorTop = with(LocalDensity.current) { 154.dp.toPx() }
                    AttachmentActionMenu(
                        expanded = true,
                        anchorBounds = Rect(
                            left = with(LocalDensity.current) { 16.dp.toPx() },
                            top = anchorTop,
                            right = with(LocalDensity.current) { 64.dp.toPx() },
                            bottom = with(LocalDensity.current) { 178.dp.toPx() },
                        ),
                        maximumBottom = anchorTop,
                        enabled = true,
                        onDismiss = {},
                        onPickImage = {},
                        onPickDocument = {},
                        modifier = androidx.compose.ui.Modifier
                            .width(320.dp)
                            .height(180.dp),
                    )
                }
            }
        }

        val menu = rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val maximumBottom = with(rule.density) { 154.dp.toPx() }
        assertThat(menu.top).isAtLeast(0f)
        assertThat(menu.bottom).isAtMost(maximumBottom)
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_IMAGE).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun shortChatWindowAtLargeFont_keepsIntegratedMenuVisible() {
        render(fontScale = 2f, viewportHeight = 320.dp)

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        val root = rule.onNodeWithTag(UiTags.CHAT_ROOT).fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag(UiTags.CHAT_COMPOSER).fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 2.dp.toPx() }

        assertThat(menu.top).isAtLeast(root.top - tolerance)
        assertThat(menu.bottom).isAtMost(composer.top + tolerance)
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_IMAGE).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun rtlMenu_anchorsToTriggerRightEdgeAndStaysAboveComposer() {
        render(layoutDirection = LayoutDirection.Rtl)

        val add = rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT)
        add.performClick()
        val menu = rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val addBounds = add.fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag(UiTags.CHAT_COMPOSER).fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 24.dp.toPx() }

        assertThat(kotlin.math.abs(menu.right - addBounds.right)).isAtMost(tolerance)
        assertThat(menu.bottom).isAtMost(composer.top + with(rule.density) { 2.dp.toPx() })
    }

    private fun render(
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        initialInput: String = "",
        isGenerating: () -> Boolean = { false },
        isImporting: () -> Boolean = { false },
        onPickImage: () -> Unit = {},
        onPickDocument: () -> Unit = {},
        onOpenWorkspace: () -> Unit = {},
        viewportHeight: Dp? = null,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            var input by remember { mutableStateOf(initialInput) }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                NexaraTheme {
                    val generating = isGenerating()
                    val content = @Composable {
                        ChatScreenContent(
                            state = ChatScreenState(
                                uiState = ChatUiState(
                                    session = session,
                                    isGenerating = generating,
                                    status = if (generating) {
                                        GenerationStatus.RECEIVING
                                    } else {
                                        GenerationStatus.IDLE
                                    },
                                ),
                                inputText = input,
                                isImportingDocument = isImporting(),
                            ),
                            actions = ChatScreenActions(
                                onTextChange = { input = it },
                                onPickImages = onPickImage,
                                onPickDocuments = onPickDocument,
                                onOpenWorkspace = onOpenWorkspace,
                            ),
                        )
                    }
                    if (viewportHeight == null) {
                        content()
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(viewportHeight),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    private fun awaitImeOpened(timeoutMs: Long = 8_000): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = imeBottomInset()
        var stableMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            val current = imeBottomInset()
            stableMs = if (current > 0 && current == last) stableMs + 100 else 0L
            last = current
            if (current > 0 && stableMs >= 200) return current
        }
        return last
    }

    private fun awaitImeClosed(timeoutMs: Long = 6_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var stableMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            stableMs = if (!imeVisible() && imeBottomInset() == 0) stableMs + 100 else 0L
            if (stableMs >= 200) return true
        }
        return !imeVisible() && imeBottomInset() == 0
    }

    private fun imeVisible(): Boolean = rule.runOnUiThread {
        val insets = rule.activity.window.decorView.rootWindowInsets
        insets != null && insets.isVisible(AndroidWindowInsets.Type.ime())
    }

    private fun imeBottomInset(): Int = rule.runOnUiThread {
        rule.activity.window.decorView.rootWindowInsets
            ?.getInsets(AndroidWindowInsets.Type.ime())?.bottom ?: 0
    }

    private fun resource(id: Int): String = rule.activity.getString(id)
}
