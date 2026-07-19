package com.promenar.nexara.ui

import android.content.res.Configuration
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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.data.model.PostProcessStatus
import com.promenar.nexara.data.model.PostProcessTask
import com.promenar.nexara.data.model.PostProcessType
import com.promenar.nexara.data.model.TaskState
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.domain.repository.PlanPatchOp
import com.promenar.nexara.ui.chat.ChatApprovalLiveRegion
import com.promenar.nexara.ui.chat.ChatInputBar
import com.promenar.nexara.ui.chat.ChatScreenActions
import com.promenar.nexara.ui.chat.ChatScreenContent
import com.promenar.nexara.ui.chat.ChatScreenState
import com.promenar.nexara.ui.chat.GenerationStatus
import com.promenar.nexara.ui.chat.PostProcessChip
import com.promenar.nexara.ui.chat.components.TaskFloatingPanel
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.rag.DocEditorFailureCode
import com.promenar.nexara.ui.rag.DocEditorPhase
import com.promenar.nexara.ui.rag.DocEditorScreenActions
import com.promenar.nexara.ui.rag.DocEditorScreenContent
import com.promenar.nexara.ui.rag.DocEditorScreenState
import com.promenar.nexara.ui.rag.DocEditorUiState
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilitySmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatInputControlsUseComplete48DpMaterialTargets() {
        composeRule.setContent {
            NexaraTheme {
                ChatScreenContent(
                    state = ChatScreenState(),
                    actions = ChatScreenActions(),
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.CHAT_MODEL_SELECTOR)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_TOKEN_INDICATOR)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_INPUT)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_COMPOSER)
            .assertIsDisplayed()
    }

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
                        isGenerating = true,
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
    fun chatInputBarExposesStablePlaceholderNameAfterTextEntered() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val placeholder = resources.getString(R.string.chat_input_placeholder_default)
        composeRule.setContent {
            NexaraTheme {
                ChatInputBar(
                    text = "hello",
                    placeholder = placeholder,
                    onTextChange = {},
                    onSend = {},
                    status = GenerationStatus.IDLE,
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.CHAT_INPUT).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(placeholder),
            )
        )
    }

    @Test
    fun promptEditorInputExposesStableTagAndPlaceholderName() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val placeholder = resources.getString(R.string.prompt_editor_default_placeholder)
        composeRule.setContent {
            NexaraTheme {
                UnifiedPromptEditor(
                    show = true,
                    onDismiss = {},
                    onSave = { Result.success(Unit) },
                    initialText = "draft prompt",
                    placeholder = placeholder,
                    title = "Session Prompt",
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(UiTags.PROMPT_EDITOR_INPUT).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(placeholder),
            )
        )
    }

    @Test
    fun docEditorSavingStateAnnouncesSavingLabelOnPoliteLiveRegion() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val savingLabel = resources.getString(R.string.doc_editor_saving)
        composeRule.setContent {
            NexaraTheme {
                DocEditorScreenContent(
                    state = DocEditorScreenState(
                        editorState = DocEditorUiState(
                            phase = DocEditorPhase.Saving,
                            workspaceRootUuid = "root",
                            documentId = "doc",
                            title = "release-notes.md",
                            content = "# Release",
                            hasLoadedDocument = true,
                        ),
                    ),
                    actions = DocEditorScreenActions(),
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_SAVING)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(savingLabel),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                )
            )
    }

    @Test
    fun docEditorSaveErrorNoticeUsesAssertiveLiveRegion() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val saveErrorTitle = resources.getString(R.string.doc_editor_save_failed_title)
        composeRule.setContent {
            NexaraTheme {
                DocEditorScreenContent(
                    state = DocEditorScreenState(
                        editorState = DocEditorUiState(
                            phase = DocEditorPhase.SaveError,
                            workspaceRootUuid = "root",
                            documentId = "doc",
                            title = "release-notes.md",
                            content = "# Release",
                            persistedContent = "# Release draft",
                            hasLoadedDocument = true,
                            contentDirty = true,
                            failureCode = DocEditorFailureCode.ContentSaveFailed,
                        ),
                    ),
                    actions = DocEditorScreenActions(),
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_SAVE_ERROR).assertExists()
        composeRule.onNodeWithText(saveErrorTitle).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
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

    @Test
    fun postProcessChipAndTaskFloatingPanelAccessibilityCoverage() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val postProcessLabel = resources.getString(R.string.chat_postprocess_summary)

        val fakeTask = PostProcessTask(
            id = "task-1",
            type = PostProcessType.AUTO_SUMMARY,
            status = PostProcessStatus.RUNNING,
        )

        val rootNode = TaskStep(
            id = "step-1",
            title = "分析文档结构",
            status = "doing",
            children = listOf(
                TaskStep(id = "step-1-1", title = "提取大纲", status = "done"),
                TaskStep(id = "step-1-2", title = "生成脑图", status = "pending"),
            ),
        )
        val fakeRepo = object : ITaskRepository {
            override fun observeActiveTree(sessionId: String): Flow<List<TaskStep>> =
                flowOf(listOf(rootNode))

            override suspend fun initializePlan(
                sessionId: String,
                goal: String,
                tree: List<TaskStep>,
            ): TaskState = TaskState()

            override suspend fun updatePlan(
                sessionId: String,
                operations: List<PlanPatchOp>,
            ): TaskState = TaskState()

            override suspend fun getPlan(sessionId: String): TaskState? = null
            override suspend fun dropPlan(sessionId: String, reason: String) {}
            override fun deriveParentStatus(children: List<TaskStep>): String = "doing"
            override fun countLeafProgress(steps: List<TaskStep>): Pair<Int, Int> = 1 to 2
        }

        composeRule.setContent {
            NexaraTheme {
                Column {
                    PostProcessChip(
                        task = fakeTask,
                        onRemove = {},
                    )
                    TaskFloatingPanel(
                        sessionId = "session-1",
                        taskRepo = fakeRepo,
                        goalTitle = "分析文档结构",
                    )
                }
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText(postProcessLabel).assertIsDisplayed()

        composeRule.onNodeWithText("🎯 分析文档结构", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("1/2 步骤 · 50%", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("提取大纲", substring = true).assertIsDisplayed()

        val headerRow = composeRule.onNodeWithTag(UiTags.CHAT_TASK_PANEL_HEADER)
        headerRow.assertExists()
        headerRow.assertHasClickAction()
        headerRow.assertHeightIsAtLeast(48.dp)

        headerRow.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提取大纲", substring = true).assertDoesNotExist()

        headerRow.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提取大纲", substring = true).assertIsDisplayed()
    }
}
