package com.promenar.nexara.ui.chat

import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.KgEdge
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.ui.chat.components.RagDetailsSheetContent
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test

class RagDetailsAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tabsExposeRoleSelectionAndMinimumTouchTarget() {
        showDetails()

        rule.onNodeWithTag("RAG_DETAILS_TAB_RETRIEVED")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("RAG_DETAILS_TAB_WEB")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithTag("RAG_DETAILS_TAB_KG")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun chatRetrievalEntryKeepsProgressActionAndDetailsCallback() {
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagProgressCard(
                    phases = listOf(
                        RagPhase(
                            id = "vector_search",
                            name = "Retrieving documents",
                            status = PhaseStatus.ACTIVE,
                            progress = 42,
                        ),
                    ),
                    references = previewReferences(),
                    kgPaths = emptyList(),
                    citations = emptyList(),
                    isComplete = false,
                )
            }
        }

        rule.onNodeWithTag("RAG_PROGRESS_CARD")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("retrieval entry has localized details action") { node ->
                    runCatching { node.config[SemanticsActions.OnClick].label }.getOrNull() ==
                        resources().getString(R.string.chat_rag_open_details)
                },
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.42f, 0f..1f),
                ),
            )
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag("RAG_DETAILS_ROOT").assertIsDisplayed()
    }

    @Test
    fun runningRetrievalEntryHasSingleProgressSemanticsSource() {
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagProgressCard(
                    phases = listOf(RagPhase("vector_search", "Retrieving", PhaseStatus.ACTIVE, 42)),
                    references = previewReferences(),
                    isComplete = false,
                )
            }
        }

        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.onAllNodes(hasText("42%"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun completedRetrievalEntryHasSingleStateSemanticsWithoutProgressOrDuplicateDoneText() {
        val done = resources().getString(R.string.chat_rag_done)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagProgressCard(
                    phases = emptyList(),
                    references = previewReferences(),
                    isComplete = true,
                )
            }
        }

        rule.onNodeWithTag("RAG_PROGRESS_CARD")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        rule.onAllNodes(hasText(done), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun realSheetLargeFontWebListScrollsToLastItemWithinModalWindow() {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    com.promenar.nexara.ui.chat.components.RagDetailsSheet(
                        references = emptyList(),
                        citations = longCitations(),
                        kgPaths = emptyList(),
                        onDismissRequest = {},
                        openLink = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("RAG_DETAILS_LIST").performScrollToIndex(11)
        rule.onNodeWithTag("RAG_DETAILS_WEB_ITEM:11").assertIsDisplayed()
    }

    @Test
    fun realSheetLandscapeKnowledgeGraphScrollsToLastRelationshipWithinModalWindow() {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            ) {
                NexaraTheme(dynamicColor = false) {
                    com.promenar.nexara.ui.chat.components.RagDetailsSheet(
                        references = emptyList(),
                        citations = emptyList(),
                        kgPaths = longKgPaths(),
                        onDismissRequest = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("RAG_DETAILS_LIST")
            .performScrollToNode(hasTestTag("RAG_DETAILS_KG_RELATION:9:0"))
        rule.onNodeWithTag("RAG_DETAILS_KG_RELATION:9:0").assertIsDisplayed()
    }

    @Test
    fun webLinkFailureIsVisibleAndRetryable() {
        val attempts = AtomicInteger(0)
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                if (attempts.incrementAndGet() == 1) error("preview handler rejected URI")
            }
        }
        showDetails(uriHandler = handler)

        rule.onNodeWithTag("RAG_DETAILS_TAB_WEB").performClick()
        rule.onNodeWithTag("RAG_DETAILS_WEB_ITEM:0")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher("web item uses localized click label") { node ->
                    val label = runCatching { node.config[SemanticsActions.OnClick].label }.getOrNull()
                    label == resources().getString(R.string.rag_details_open_link)
                },
            )
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag("RAG_DETAILS_LINK_ERROR").assertIsDisplayed()
        rule.onNodeWithTag("RAG_DETAILS_LINK_RETRY").assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(attempts.get()).isEqualTo(2) }
    }

    @Test
    fun largeFontWebListScrollsToLastLongCitation() {
        showDetails(
            configuration = DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                DeviceConfigurationOverride.FontScale(2f),
            citations = longCitations(),
        )

        rule.onNodeWithTag("RAG_DETAILS_TAB_WEB").performClick()
        rule.onNodeWithTag("RAG_DETAILS_LIST").performScrollToIndex(11)
        rule.onNodeWithTag("RAG_DETAILS_WEB_ITEM:11").assertIsDisplayed()
    }

    @Test
    fun landscapeKnowledgeGraphScrollsToLastLongRelationship() {
        showDetails(
            configuration = DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            kgPaths = longKgPaths(),
        )

        rule.onNodeWithTag("RAG_DETAILS_TAB_KG").performClick()
        rule.onNodeWithTag("RAG_DETAILS_LIST")
            .performScrollToNode(hasTestTag("RAG_DETAILS_KG_RELATION:9:0"))
        rule.onNodeWithTag("RAG_DETAILS_KG_RELATION:9:0").assertIsDisplayed()
        rule.onNodeWithText("Last destination with a deliberately long readable label").assertIsDisplayed()
    }

    private fun showDetails(
        configuration: DeviceConfigurationOverride? = null,
        uriHandler: UriHandler = object : UriHandler { override fun openUri(uri: String) = Unit },
        references: List<RagReference> = previewReferences(),
        citations: List<Citation> = longCitations().take(2),
        kgPaths: List<KgPath> = longKgPaths().take(2),
    ) {
        rule.setContent {
            val content = @androidx.compose.runtime.Composable {
                NexaraTheme(dynamicColor = false) {
                    RagDetailsSheetContent(
                        references = references,
                        citations = citations,
                        kgPaths = kgPaths,
                        openLink = uriHandler::openUri,
                    )
                }
            }
            if (configuration == null) content() else DeviceConfigurationOverride(configuration) { content() }
        }
        rule.waitForIdle()
    }

    private fun resources() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun previewReferences() = listOf(
        RagReference(
            id = "ref-1",
            source = "/release/very-long-accessibility-checklist.md",
            content = "Every release surface stays readable and reachable at large font scale.",
            score = 0.92f,
            rerankScore = 0.88f,
            rankChange = 2,
        ),
    )

    private fun longCitations() = (1..12).map { index ->
        Citation(
            title = if (index == 12) "Last web citation with a deliberately long readable title" else "Citation $index",
            url = "https://example.com/release/$index",
            source = "Release source $index",
            snippet = "A deterministic long snippet for accessibility and scrolling verification item $index.",
        )
    }

    private fun longKgPaths() = (1..10).map { index ->
        val source = KgNode("source-$index", "Source entity with a deliberately long readable label $index", "concept")
        val targetLabel = if (index == 10) {
            "Last destination with a deliberately long readable label"
        } else {
            "Destination entity with a deliberately long readable label $index"
        }
        val target = KgNode("target-$index", targetLabel, "concept")
        KgPath(
            queryKeywords = listOf("release", "accessibility", "item-$index"),
            nodes = listOf(source, target),
            edges = listOf(KgEdge(source.id, target.id, "is related through a long explanatory relationship", 1.0)),
            reasoning = "The relationship remains readable without forcing three narrow columns.",
        )
    }
}
